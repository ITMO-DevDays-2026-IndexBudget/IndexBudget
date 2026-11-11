package ru.itmo.indexbudget.statistics

import tools.jackson.databind.json.JsonMapper

class FrequencyJson {
    fun render(value: FrequencyProfiles): ByteArray = JsonMapper.builder().build().writeValueAsBytes(linkedMapOf(
        "schema_version" to 1, "kind" to "pg_stat_statements_profiles",
        "source" to mapOf("source_id" to value.source.sourceId.toString(), "database_oid" to value.source.databaseOid,
            "reader_userid" to value.source.readerUserid, "all_query_texts_visible" to value.source.allQueryTextsVisible,
            "postgres_version" to value.source.postgresVersion, "extension_version" to value.source.extensionVersion,
            "track" to value.source.track),
        "time_basis" to "capture_midpoints", "incomplete_policy" to "matched_stable_counters_only",
        "limitations" to listOf("non_atomic_captures", "pg16_targeted_reset_may_be_undetectable", "historical_time_is_not_predicted_latency"),
        "common_usable_keys" to value.commonUsableKeys.map(::key),
        "profiles" to value.profiles.map { profile -> mapOf(
            "id" to profile.id, "weight" to profile.weight, "normalized_weight" to profile.normalizedWeight,
            "before_snapshot_id" to profile.beforeSnapshotId.toString(), "after_snapshot_id" to profile.afterSnapshotId.toString(),
            "status" to if (profile.complete) "completed" else "partial",
            "period" to profile.period.let { mapOf("before_started_at" to it.beforeStartedAt.toString(),
                "before_finished_at" to it.beforeFinishedAt.toString(), "after_started_at" to it.afterStartedAt.toString(),
                "after_finished_at" to it.afterFinishedAt.toString(), "start" to it.start.toString(), "end" to it.end.toString(),
                "duration_seconds" to it.durationSeconds, "minimum_duration_seconds" to it.minimumDurationSeconds,
                "maximum_duration_seconds" to it.maximumDurationSeconds) },
            "reasons" to profile.reasons.map { it.name }, "before_reasons" to profile.beforeReasons.map { it.name },
            "after_reasons" to profile.afterReasons.map { it.name }, "unidentified_before" to profile.unidentifiedBefore,
            "unidentified_after" to profile.unidentifiedAfter,
            "observed_calls_per_hour" to profile.observedCallsPerHour,
            "observed_total_exec_time_ms" to profile.observedTotalExecTimeMillis,
            "observations" to profile.observations.map { row -> mapOf(
                "key" to key(row.key), "state" to row.state.name, "query" to row.query, "text_status" to row.textStatus.name,
                "delta_calls" to row.deltaCalls?.toString(), "calls_per_hour" to row.callsPerHour,
                "delta_rows" to row.deltaRows?.toString(), "total_exec_time_ms" to row.totalExecTimeMillis,
                "mean_exec_time_ms" to row.meanExecTimeMillis, "reasons" to row.reasons.map { it.name },
            ) },
        ) },
    ))

    private fun key(value: StatisticsKey) = mapOf("source_id" to value.sourceId.toString(), "dbid" to value.dbid,
        "userid" to value.userid, "queryid" to value.queryid.toString(), "toplevel" to value.toplevel)
}
