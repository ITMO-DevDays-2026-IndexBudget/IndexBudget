package ru.itmo.indexbudget.statistics

import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.RunContext
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.util.UUID

/**
 * Versioned per-table write snapshot format v1 (#102 collector output, consumed by #107).
 * Counters are cumulative changed rows from pg_stat_user_tables, not SQL calls. The shared
 * reset epoch comes from pg_stat_database.stats_reset and may be null when never reset.
 */
object TableWriteSnapshotJson {
    const val KIND = "pg_stat_user_tables_snapshot"
    private val mapper = JsonMapper.builder().build()

    fun render(value: TableWriteSnapshot): ByteArray = mapper.writeValueAsBytes(linkedMapOf(
        "schema_version" to 1,
        "kind" to KIND,
        "snapshot_id" to value.snapshotId.toString(),
        "source_id" to value.source.sourceId.toString(),
        "scope" to linkedMapOf(
            "database_oid" to value.source.databaseOid,
            "reader_userid" to value.source.readerUserid,
            "rows" to "current_database_all_schemas",
        ),
        "postgres_version" to value.source.postgresVersion,
        "track_counts" to value.source.trackCounts,
        "stats_reset" to value.source.statsReset,
        "started_at" to value.startedAt,
        "finished_at" to value.finishedAt,
        "status" to if (value.complete) "completed" else "partial",
        "reasons" to value.reasons.map { it.name },
        "row_count" to value.entries.size,
        "entries" to value.entries.map { entry ->
            linkedMapOf(
                "table_oid" to entry.tableOid,
                "schema" to entry.schemaName,
                "table" to entry.tableName,
                "n_tup_ins" to entry.inserts,
                "n_tup_upd" to entry.updates,
                "n_tup_del" to entry.deletes,
                "n_tup_hot_upd" to entry.hotUpdates,
            )
        },
    ))

    fun read(path: Path, maxBytes: Int = 64 * 1024 * 1024, context: RunContext = RunContext()): TableWriteSnapshot {
        val root = ImportJson.read(path, maxBytes, context)
        ImportJson.fields(root, setOf("schema_version", "kind", "snapshot_id", "source_id", "scope",
            "postgres_version", "track_counts", "stats_reset", "started_at", "finished_at",
            "status", "reasons", "row_count", "entries"))
        if (ImportJson.long(root, "schema_version") != 1L || ImportJson.text(root, "kind") != KIND) {
            ImportJson.fail("Expected $KIND v1.")
        }
        val scope = root.get("scope") ?: ImportJson.fail("Snapshot scope is required.")
        ImportJson.fields(scope, setOf("database_oid", "reader_userid", "rows"))
        if (ImportJson.text(scope, "rows") != "current_database_all_schemas") {
            ImportJson.fail("Table snapshot must cover the whole current database.")
        }
        val databaseOid = ImportJson.long(scope, "database_oid")
        val readerUserid = ImportJson.long(scope, "reader_userid")
        val postgresVersion = ImportJson.text(root, "postgres_version")
        if (databaseOid <= 0 || readerUserid <= 0 || !postgresVersion.startsWith("16.")) {
            ImportJson.fail("A PostgreSQL 16 source with a known database and reader is required.")
        }
        val trackCounts = root.get("track_counts")?.takeUnless { it.isNull }?.let { ImportJson.bool(root, "track_counts") }
        if (trackCounts == false) {
            ImportJson.fail("track_counts must be enabled to observe table write counters.")
        }
        val statsReset = root.get("stats_reset")?.takeUnless { it.isNull }
            ?.let { ImportJson.text(root, "stats_reset") }
        val entries = ImportJson.array(root, "entries").mapIndexed { index, row ->
            context.checkDeadline()
            ImportJson.fields(row, setOf("table_oid", "schema", "table",
                "n_tup_ins", "n_tup_upd", "n_tup_del", "n_tup_hot_upd"))
            val entry = TableWriteEntry(
                ImportJson.long(row, "table_oid"),
                ImportJson.text(row, "schema"),
                ImportJson.text(row, "table"),
                ImportJson.long(row, "n_tup_ins"),
                ImportJson.long(row, "n_tup_upd"),
                ImportJson.long(row, "n_tup_del"),
                row.get("n_tup_hot_upd")?.takeUnless { it.isNull }?.let { ImportJson.long(row, "n_tup_hot_upd") },
            )
            if (entry.tableOid <= 0 || entry.schemaName.isBlank() || entry.tableName.isBlank()) {
                ImportJson.fail("Table snapshot entry ${if (entry.tableName.isBlank()) index else entry.tableName} needs a positive OID and nonblank names.")
            }
            if (entry.inserts < 0 || entry.updates < 0 || entry.deletes < 0 ||
                (entry.hotUpdates ?: 0L) < 0L
            ) {
                throw AnalysisException(AnalysisError(ErrorCode.NUMERIC_ERROR,
                    "Table write counters must be nonnegative.", details = mapOf("table" to "${entry.schemaName}.${entry.tableName}")))
            }
            entry
        }
        val byOid = entries.associateBy { it.tableOid }
        if (byOid.size != entries.size) ImportJson.fail("Table snapshot entries must have unique OIDs.")
        if (entries.map { it.schemaName to it.tableName }.distinct().size != entries.size) {
            ImportJson.fail("Table snapshot entries must have unique schema-qualified names.")
        }
        val snapshot = TableWriteSnapshot(
            snapshotId = UUID.fromString(ImportJson.text(root, "snapshot_id")),
            source = TableWriteSource(UUID.fromString(ImportJson.text(root, "source_id")),
                databaseOid, readerUserid, trackCounts, postgresVersion, statsReset),
            startedAt = ImportJson.text(root, "started_at"),
            finishedAt = ImportJson.text(root, "finished_at"),
            entries = entries,
            complete = ImportJson.text(root, "status") == "completed",
            reasons = ImportJson.array(root, "reasons").map { TableWriteSnapshotReason.valueOf(it.asString()) },
        )
        if (ImportJson.long(root, "row_count") != entries.size.toLong() ||
            ImportJson.text(root, "status") != if (snapshot.complete) "completed" else "partial"
        ) {
            ImportJson.fail("Inconsistent table snapshot status or row count.")
        }
        return snapshot
    }
}
