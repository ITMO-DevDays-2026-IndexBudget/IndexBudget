package ru.itmo.indexbudget.statistics

import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.postgres.JdbcAnalysisSessionFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor

/** No extension, DDL, ANALYZE, statistics reset or privilege changes. */
class JdbcTableStatisticsCollector(
    private val connect: (RunContext) -> Connection = JdbcAnalysisSessionFactory()::openConnection,
    private val now: () -> Instant = Instant::now,
) : TableStatisticsCollector {
    private val reader = JdbcSnapshotReader()
    override fun collect(sourceId: UUID, limits: SnapshotLimits, context: RunContext): TableSnapshot {
        val started = now().toString()
        val connection = connect(context)
        try {
            connection.isReadOnly = true
            // Each bounded SELECT gets a fresh transaction/statistics cache. No SET/clear_snapshot required.
            // LIMIT bounds the result even when pgJDBC cannot stream an autocommit ResultSet.
            connection.autoCommit = true
            val metadata = metadata(connection, context)
            val entries = mutableListOf<TableCounters>()
            val reasons = linkedSetOf<TableSnapshotReason>()
            if (!metadata.track) reasons += TableSnapshotReason.TRACK_COUNTS_OFF
            var after: Metadata? = null
            var bytes = 16384
            val json = TableSnapshotJson()
            try {
                reader.query(connection, context, """
                    SELECT c.oid AS relation_oid, n.nspname AS schema_name, c.relname AS table_name,
                        pg_catalog.has_schema_privilege(n.oid, 'USAGE') AND
                        pg_catalog.has_any_column_privilege(c.oid, 'SELECT') AS select_access,
                        s.n_tup_ins, s.n_tup_upd, s.n_tup_del, s.n_tup_hot_upd
                    FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    LEFT JOIN pg_catalog.pg_stat_user_tables s ON s.relid = c.oid
                    WHERE c.relkind = 'r' AND n.nspname <> 'information_schema'
                        AND n.nspname !~ '^pg_'
                    ORDER BY c.oid LIMIT ${limits.maxRows + 1}
                """.trimIndent()) { rows ->
                    while (reader.next(connection, context, rows)) {
                        if (entries.size >= limits.maxRows) { reasons += TableSnapshotReason.ROW_LIMIT; break }
                        fun counter(name: String): Long? {
                            val value = rows.getLong(name)
                            return if (rows.wasNull() || !metadata.track) null else value.also {
                                if (it < 0) fail(ErrorCode.STATISTICS_READ_FAILED, "Table statistics contain invalid counters.")
                            }
                        }
                        val entry = TableCounters(rows.getLong("relation_oid"), rows.getString("schema_name"),
                            rows.getString("table_name"), rows.getBoolean("select_access"), counter("n_tup_ins"),
                            counter("n_tup_upd"), counter("n_tup_del"), counter("n_tup_hot_upd"))
                        val size = json.entryBytes(entry) + 1
                        if (bytes.toLong() + size > limits.maxBytes) { reasons += TableSnapshotReason.BYTE_LIMIT; break }
                        entries += entry
                        bytes += size
                        if (listOf(entry.nTupIns, entry.nTupUpd, entry.nTupDel, entry.nTupHotUpd).any { it == null })
                            reasons += TableSnapshotReason.COUNTERS_UNAVAILABLE
                    }
                }
                after = metadata(connection, context)
                if (metadata.reset != after.reset || metadata.started != after.started) reasons += TableSnapshotReason.RESET_DETECTED
                if (!after.track) reasons += TableSnapshotReason.TRACK_COUNTS_OFF
            } catch (error: AnalysisException) {
                when (error.error.code) {
                    ErrorCode.DEADLINE_EXCEEDED -> reasons += TableSnapshotReason.DEADLINE_EXCEEDED
                    ErrorCode.CANCELLED -> reasons += TableSnapshotReason.CANCELLED
                    else -> throw error
                }
            } catch (_: SQLException) {
                reasons += when {
                    context.isCancelled -> TableSnapshotReason.CANCELLED
                    context.remainingTime().isZero -> TableSnapshotReason.DEADLINE_EXCEEDED
                    else -> TableSnapshotReason.READ_FAILED
                }
            }
            return TableSnapshot(UUID.randomUUID(), TableStatisticsSource(sourceId, metadata.dbid, metadata.userid,
                metadata.version, metadata.track), started, now().toString(), metadata.reset, after?.reset,
                metadata.started, entries.toList(), reasons.toList(), limits)
        } catch (error: SQLException) {
            context.checkDeadline()
            fail(if (error.sqlState == "42501") ErrorCode.STATISTICS_PERMISSION_DENIED else ErrorCode.STATISTICS_READ_FAILED,
                "Cannot read PostgreSQL table statistics metadata.")
        } finally {
            try { connection.abort(Executor { it.run() }) } catch (_: Exception) { /* Close below remains mandatory. */ }
            try { connection.close() } catch (_: Exception) { fail(ErrorCode.STATISTICS_READ_FAILED, "Cannot close table statistics connection.") }
        }
    }

    private data class Metadata(val dbid: Long, val userid: Long, val version: String, val track: Boolean,
        val reset: String?, val started: String)

    private fun metadata(connection: Connection, context: RunContext): Metadata = reader.query(connection, context, """
        SELECT d.oid AS dbid, r.oid AS userid, pg_catalog.current_setting('server_version') AS version,
            pg_catalog.current_setting('server_version_num')::int AS version_number,
            pg_catalog.current_setting('track_counts')::boolean AS track_counts,
            s.stats_reset, pg_catalog.pg_postmaster_start_time() AS started
        FROM pg_catalog.pg_database d CROSS JOIN pg_catalog.pg_roles r
        LEFT JOIN pg_catalog.pg_stat_database s ON s.datid = d.oid
        WHERE d.datname = pg_catalog.current_database() AND r.rolname = current_user
    """.trimIndent()) { rows ->
        if (!reader.next(connection, context, rows)) fail(ErrorCode.STATISTICS_READ_FAILED, "Database identity is unavailable.")
        if (rows.getInt("version_number") !in 160000..169999)
            fail(ErrorCode.STATISTICS_UNAVAILABLE, "Table statistics snapshots currently support PostgreSQL 16.")
        Metadata(rows.getLong("dbid"), rows.getLong("userid"), rows.getString("version"), rows.getBoolean("track_counts"),
            rows.getTimestamp("stats_reset")?.toInstant()?.toString(), rows.getTimestamp("started").toInstant().toString())
    }

    private fun fail(code: ErrorCode, message: String): Nothing = throw AnalysisException(AnalysisError(code, message))
}
