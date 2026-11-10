package ru.itmo.indexbudget.postgres

import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.postgresql.ds.PGSimpleDataSource
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.RunContext

/** Opens a dedicated, unpooled connection only when the pipeline starts introspection. */
class JdbcAnalysisSessionFactory(
    private val databaseUrl: () -> String? = { System.getenv("INDEXBUDGET_DATABASE_URL") },
    private val connect: (PGSimpleDataSource) -> Connection = { it.connection },
) : AnalysisSessionFactory {
    override fun open(context: RunContext): AnalysisSession {
        context.checkDeadline()
        val url = databaseUrl()?.takeIf { it.startsWith("jdbc:postgresql:") }
            ?: fail("INDEXBUDGET_DATABASE_URL must contain a PostgreSQL JDBC URL")
        val source = try {
            PGSimpleDataSource().apply {
                setURL(url)
                val limit = context.connectionTimeLimit()
                // Set after parsing the URL so URL parameters cannot disable the run's limits.
                connectTimeout = ((limit.toMillis() + 999) / 1000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                // pgJDBC's login timeout covers the entire connection attempt, including multi-host URLs.
                setProperty("loginTimeout", (limit.toMillis().coerceAtLeast(1) / 1000.0).toString())
                socketTimeout = ((context.remainingTime().toMillis() + 999) / 1000)
                    .coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                cancelSignalTimeout = connectTimeout
                logServerErrorDetail = false
            }
        } catch (_: IllegalArgumentException) {
            fail("Invalid PostgreSQL JDBC connection settings")
        } catch (error: SQLException) {
            fail("Invalid PostgreSQL JDBC connection settings", error.sqlState)
        }
        context.checkDeadline()
        val connection = awaitConnection(source, context)
        try {
            context.checkDeadline()
            return JdbcAnalysisSession(connection)
        } catch (error: Throwable) {
            // A connection obtained after the budget expired still belongs to this invocation.
            releaseConnection(connection)
            throw error
        }
    }

    /** Driver timeouts cannot observe RunContext cancellation; bound the caller's wait as well. */
    private fun awaitConnection(source: PGSimpleDataSource, context: RunContext): Connection {
        val limit = context.connectionTimeLimit().toNanos()
        val started = System.nanoTime()
        val abandoned = AtomicBoolean()
        val pending = AtomicReference<Connection?>()
        val task = FutureTask {
            val connection = connect(source)
            pending.set(connection)
            // A driver may ignore interruption and return after the command has already stopped.
            if (abandoned.get()) {
                pending.getAndSet(null)?.let(::releaseConnection)
                throw SQLException("Analysis connection attempt was stopped")
            }
            connection
        }
        Thread.ofVirtual().name("indexbudget-connect").start(task)
        try {
            while (true) {
                context.checkDeadline()
                val remaining = limit - (System.nanoTime() - started)
                if (remaining <= 0) fail("PostgreSQL connection attempt timed out")
                try {
                    val connection = task.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS)
                    context.checkDeadline()
                    if (System.nanoTime() - started >= limit) fail("PostgreSQL connection attempt timed out")
                    pending.compareAndSet(connection, null)
                    return connection
                } catch (_: TimeoutException) {
                    // Check the monotonic command deadline and cancellation between bounded waits.
                } catch (error: ExecutionException) {
                    context.checkDeadline()
                    fail("Unable to open the PostgreSQL analysis session", (error.cause as? SQLException)?.sqlState)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    context.cancel()
                    context.checkDeadline()
                }
            }
        } finally {
            abandoned.set(true)
            task.cancel(true)
            pending.getAndSet(null)?.let(::releaseConnection)
        }
    }

    private fun releaseConnection(connection: Connection) {
        // Abort first: a graceful close can otherwise wait for a stalled server or network.
        try { connection.abort(Executor { it.run() }) } catch (_: Exception) {
            // Try physical close below without retaining driver errors or connection settings.
        }
        try { connection.close() } catch (_: Exception) {
            // Preserve the original safe error, including for late connections on the worker thread.
        }
    }

    private fun fail(message: String, sqlState: String? = null): Nothing = throw AnalysisException(
        AnalysisError(ErrorCode.DB_CONNECTION_ERROR, message, details =
            sqlState?.takeIf { it.matches(Regex("[A-Z0-9]{5}")) }?.let { mapOf("sql_state" to it) } ?: emptyMap()),
    )
}
