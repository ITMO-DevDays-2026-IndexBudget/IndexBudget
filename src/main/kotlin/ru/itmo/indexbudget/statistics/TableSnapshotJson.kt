package ru.itmo.indexbudget.statistics

import tools.jackson.databind.json.JsonMapper

class TableSnapshotJson {
    private val mapper = JsonMapper.builder().build()
    fun entry(value: TableCounters): Map<String, Any?> = linkedMapOf(
        "relation_oid" to value.relationOid, "schema" to value.schema, "name" to value.name,
        "select_access" to value.selectAccess, "n_tup_ins" to value.nTupIns, "n_tup_upd" to value.nTupUpd,
        "n_tup_del" to value.nTupDel, "n_tup_hot_upd" to value.nTupHotUpd)
    fun entryBytes(value: TableCounters): Int = mapper.writeValueAsBytes(entry(value)).size
    fun render(value: TableSnapshot): ByteArray = mapper.writeValueAsBytes(linkedMapOf(
        "schema_version" to 1, "kind" to "pg_table_statistics_snapshot",
        "snapshot_id" to value.snapshotId.toString(), "source_id" to value.source.sourceId.toString(),
        "database_oid" to value.source.databaseOid, "reader_userid" to value.source.readerUserid,
        "postgres_version" to value.source.postgresVersion, "track_counts" to value.source.trackCounts,
        "scope" to value.scope, "started_at" to value.startedAt, "finished_at" to value.finishedAt,
        "database_stats_reset_before" to value.resetBefore, "database_stats_reset_after" to value.resetAfter,
        "postmaster_started_at" to value.postmasterStartedAt, "reset_detection" to "best_effort_pg16",
        "status" to if (value.complete) "completed" else "partial", "reasons" to value.reasons.map { it.name },
        "limits" to mapOf("max_rows" to value.limits.maxRows, "max_bytes" to value.limits.maxBytes,
            "timeout_millis" to value.limits.timeout.toMillis()),
        "row_count" to value.entries.size, "entries" to value.entries.map(::entry),
    )).also { require(it.size <= value.limits.maxBytes) }
}
