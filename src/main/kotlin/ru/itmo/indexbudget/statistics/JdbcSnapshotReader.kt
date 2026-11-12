package ru.itmo.indexbudget.statistics

import ru.itmo.indexbudget.domain.RunContext
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shared deadline-aware JDBC reads for query and table statistics. Does not own the connection. */
internal class JdbcSnapshotReader {
    fun <T> query(connection: Connection, context: RunContext, sql: String, read: (ResultSet) -> T): T {
        context.checkDeadline()
        connection.prepareStatement(sql).use { statement ->
            context.checkDeadline()
            val millis = context.remainingTime().toMillis().coerceAtLeast(1)
            connection.setNetworkTimeout(Executor { it.run() }, millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            statement.queryTimeout = ((millis + 999) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            statement.fetchSize = 128
            context.checkDeadline()
            // queryTimeout covers execute, not later cursor FETCH operations in pgJDBC.
            // Keep an absolute deadline guard alive until the entire ResultSet has closed.
            CursorDeadlineGuard(connection, context).use {
                return statement.executeQuery().use(read)
            }
        }
    }

    fun next(connection: Connection, context: RunContext, rows: ResultSet): Boolean {
        context.checkDeadline()
        // Refresh before every next(): the driver can fetch a new page here, not just in execute.
        val millis = context.remainingTime().toMillis().coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        connection.setNetworkTimeout(Executor { it.run() }, millis)
        context.checkDeadline()
        val hasNext = rows.next()
        context.checkDeadline()
        return hasNext
    }

    /** Absolute bound across execute, all FETCHes and row processing, including a stalled read. */
    private class CursorDeadlineGuard(connection: Connection, context: RunContext) : AutoCloseable {
        private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "indexbudget-statistics-deadline").apply { isDaemon = true }
        }
        private val pending = scheduler.scheduleAtFixedRate({
            if (context.isCancelled || context.remainingTime().isZero) {
                try { connection.abort(Executor { it.run() }) } catch (_: Exception) {
                    try { connection.close() } catch (_: Exception) { /* Owner closes again in finally. */ }
                }
            }
        }, 0, 10, TimeUnit.MILLISECONDS)

        override fun close() {
            pending.cancel(false)
            scheduler.shutdownNow()
        }
    }

}
