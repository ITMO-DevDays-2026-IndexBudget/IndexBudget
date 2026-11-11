package ru.itmo.indexbudget.statistics

import tools.jackson.databind.json.JsonMapper

/** Versioned snapshot format, independent of workload/report v2. Bigint query IDs are decimal strings. */
class SnapshotJson {
    private val mapper = JsonMapper.builder().build()

    fun entry(value: StatisticsEntry): Map<String, Any?> = linkedMapOf(
        "dbid" to value.dbid, "userid" to value.userid, "queryid" to value.queryid?.toString(),
        "toplevel" to value.toplevel, "query" to value.query, "text_status" to value.textStatus,
        "calls" to value.calls, "total_exec_time" to value.totalExecTime, "rows" to value.rows,
    )

    fun entryBytes(value: StatisticsEntry): Int = mapper.writeValueAsBytes(entry(value)).size

    fun render(value: StatisticsSnapshot): ByteArray {
        fun epoch(epoch: StatisticsEpoch?) = epoch?.let {
            mapOf("stats_reset" to it.statsReset, "dealloc" to it.dealloc)
        }
        val source = value.source
        return mapper.writeValueAsBytes(linkedMapOf(
            "schema_version" to 1, "kind" to "pg_stat_statements_snapshot",
            "snapshot_id" to value.snapshotId.toString(), "source_id" to source.sourceId.toString(),
            "scope" to mapOf("database_oid" to source.databaseOid, "reader_userid" to source.readerUserid,
                "rows" to "current_database_all_users", "all_query_texts_visible" to source.allQueryTextsVisible,
                "ranking" to "none", "track" to source.track),
            "postgres_version" to source.postgresVersion, "extension_version" to source.extensionVersion,
            "started_at" to value.startedAt, "finished_at" to value.finishedAt,
            "before" to epoch(value.before), "after" to epoch(value.after),
            "status" to if (value.complete) "completed" else "partial",
            "reasons" to value.reasons, "row_count" to value.entries.size,
            "unavailable_text_count" to value.entries.count { it.query == null },
            "limits" to mapOf("max_rows" to value.limits.maxRows, "max_bytes" to value.limits.maxBytes,
                "max_query_bytes" to value.limits.maxQueryBytes, "timeout_millis" to value.limits.timeout.toMillis()),
            "entries" to value.entries.map(::entry),
        )).also { require(it.size <= value.limits.maxBytes) { "Snapshot exceeds its output budget" } }
    }
}
