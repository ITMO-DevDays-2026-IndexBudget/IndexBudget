package ru.itmo.indexbudget.statistics

import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.postgres.JdbcAnalysisSessionFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Read-only PostgreSQL 16 collector. No reset, DDL, ANALYZE, ranking or index session initialization. */
class JdbcStatisticsCollector(
    private val connect: (RunContext) -> Connection = JdbcAnalysisSessionFactory()::openConnection,
    private val now: () -> Instant = Instant::now,
) : StatisticsCollector {
    override fun collect(sourceId: UUID, limits: SnapshotLimits, context: RunContext): StatisticsSnapshot {
        val started = now().toString()
        val connection = connect(context)
        try {
            context.checkDeadline()
            connection.isReadOnly = true
            connection.autoCommit = false
            val metadata = query(connection, context, METADATA) { rows ->
                if (!next(connection, context, rows)) fail(ErrorCode.STATISTICS_UNAVAILABLE, "Install pg_stat_statements in the current database first.")
                val versionNumber = rows.getInt("version_number")
                if (versionNumber !in 160000..169999) fail(ErrorCode.STATISTICS_UNAVAILABLE, "Statistics snapshots currently support PostgreSQL 16.")
                rows.getString("extension_schema") to StatisticsSource(sourceId, rows.getLong("dbid"),
                    rows.getLong("userid"), rows.getBoolean("visible"), rows.getString("version"),
                    rows.getString("extversion"), rows.getString("track"))
            }
            val schema = "\"" + metadata.first.replace("\"", "\"\"") + "\""
            val before = epoch(connection, context, schema)
            val entries = mutableListOf<StatisticsEntry>()
            val reasons = linkedSetOf<SnapshotReason>()
            var after: StatisticsEpoch? = null
            // Reserve a fixed upper bound for bounded metadata; account for escaped UTF-8, not characters.
            var bytes = 16_384
            val json = SnapshotJson()
            try {
                val sql = """
                    SELECT dbid, userid, queryid, toplevel, calls, total_exec_time, rows,
                        CASE WHEN pg_catalog.octet_length(query) <= ${limits.maxQueryBytes} THEN query END AS query,
                        queryid IS NOT NULL AND pg_catalog.octet_length(query) > ${limits.maxQueryBytes} AS text_too_large
                    FROM $schema.pg_stat_statements
                    WHERE dbid = (SELECT oid FROM pg_catalog.pg_database WHERE datname = pg_catalog.current_database())
                    LIMIT ${limits.maxRows + 1}
                """.trimIndent()
                query(connection, context, sql) { rows ->
                    while (next(connection, context, rows)) {
                        context.checkDeadline()
                        if (entries.size == limits.maxRows) { reasons += SnapshotReason.ROW_LIMIT; break }
                        val queryId = rows.getLong("queryid").let { if (rows.wasNull()) null else it }
                        val tooLarge = queryId != null && rows.getBoolean("text_too_large")
                        val query = if (queryId == null) null else rows.getString("query")
                        val status = when { queryId == null -> QueryTextStatus.RESTRICTED; tooLarge -> QueryTextStatus.SIZE_LIMIT;
                            query == null -> QueryTextStatus.UNAVAILABLE; else -> QueryTextStatus.AVAILABLE }
                        val entry = StatisticsEntry(rows.getLong("dbid"), rows.getLong("userid"), queryId,
                            rows.getBoolean("toplevel"), query, status, rows.getLong("calls"),
                            rows.getDouble("total_exec_time"), rows.getLong("rows"))
                        if (entry.calls < 0 || entry.rows < 0 || !entry.totalExecTime.isFinite() || entry.totalExecTime < 0)
                            fail(ErrorCode.STATISTICS_READ_FAILED, "Statistics contain invalid counters.")
                        val size = json.entryBytes(entry) + 1
                        if (bytes.toLong() + size > limits.maxBytes) { reasons += SnapshotReason.BYTE_LIMIT; break }
                        bytes += size
                        entries += entry
                        if (tooLarge) reasons += SnapshotReason.QUERY_TEXT_LIMIT
                    }
                }
                after = epoch(connection, context, schema)
                if (before.statsReset != after.statsReset) reasons += SnapshotReason.STATS_RESET_DURING_READ
                if (before.dealloc != after.dealloc) reasons += SnapshotReason.DEALLOC_DURING_READ
                context.checkDeadline()
            } catch (error: AnalysisException) {
                if (error.error.code !in setOf(ErrorCode.DEADLINE_EXCEEDED, ErrorCode.CANCELLED)) throw error
                reasons += if (error.error.code == ErrorCode.CANCELLED) SnapshotReason.CANCELLED else SnapshotReason.DEADLINE_EXCEEDED
            } catch (error: SQLException) {
                // Preserve the already read prefix, but never label a failed cursor as a complete snapshot.
                reasons += if (context.isCancelled) SnapshotReason.CANCELLED else if (context.remainingTime().isZero)
                    SnapshotReason.DEADLINE_EXCEEDED else if (error.sqlState == "42501") SnapshotReason.STATISTICS_PERMISSION_DENIED else SnapshotReason.READ_FAILED
            }
            return StatisticsSnapshot(UUID.randomUUID(), metadata.second, started, now().toString(), before,
                after, entries.toList(), reasons.toList(), limits)
        } catch (error: SQLException) {
            // An aborted metadata read must preserve cancellation/deadline as the primary cause.
            context.checkDeadline()
            val code = when (error.sqlState) {
                "42501" -> ErrorCode.STATISTICS_PERMISSION_DENIED
                "55000", "42P01", "42883" -> ErrorCode.STATISTICS_UNAVAILABLE
                else -> ErrorCode.STATISTICS_READ_FAILED
            }
            fail(code, when (code) {
                ErrorCode.STATISTICS_PERMISSION_DENIED -> "Statistics views are not readable by the current role."
                ErrorCode.STATISTICS_UNAVAILABLE -> "pg_stat_statements is unavailable; check installation and shared_preload_libraries."
                else -> "Cannot read PostgreSQL statistics."
            })
        } finally {
            // No rollback/reset SQL is needed: this is a dedicated read-only connection.
            try { connection.abort(Executor { it.run() }) } catch (_: Exception) { /* Close is still mandatory. */ }
            try { connection.close() } catch (_: Exception) {
                fail(ErrorCode.STATISTICS_READ_FAILED, "Cannot close the statistics connection.")
            }
        }
    }

    private fun epoch(connection: Connection, context: RunContext, schema: String): StatisticsEpoch =
        query(connection, context, "SELECT stats_reset, dealloc FROM $schema.pg_stat_statements_info") {
            if (!next(connection, context, it)) fail(ErrorCode.STATISTICS_UNAVAILABLE, "pg_stat_statements metadata is unavailable.")
            StatisticsEpoch(it.getTimestamp("stats_reset").toInstant().toString(), it.getLong("dealloc"))
        }

    private fun <T> query(connection: Connection, context: RunContext, sql: String, read: (ResultSet) -> T): T {
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

    private fun next(connection: Connection, context: RunContext, rows: ResultSet): Boolean {
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

    private fun fail(code: ErrorCode, message: String): Nothing = throw AnalysisException(AnalysisError(code, message))

    private companion object {
        val METADATA = """
            SELECT e.extversion, n.nspname AS extension_schema,
                pg_catalog.current_setting('server_version') AS version,
                pg_catalog.current_setting('server_version_num')::int AS version_number,
                pg_catalog.current_setting('pg_stat_statements.track', true) AS track,
                d.oid AS dbid, r.oid AS userid,
                (r.rolsuper OR pg_catalog.pg_has_role(current_user, 'pg_read_all_stats', 'USAGE')) AS visible
            FROM pg_catalog.pg_extension e JOIN pg_catalog.pg_namespace n ON n.oid = e.extnamespace
            CROSS JOIN pg_catalog.pg_database d CROSS JOIN pg_catalog.pg_roles r
            WHERE e.extname = 'pg_stat_statements' AND d.datname = pg_catalog.current_database()
                AND r.rolname = current_user
        """.trimIndent()
    }
}
