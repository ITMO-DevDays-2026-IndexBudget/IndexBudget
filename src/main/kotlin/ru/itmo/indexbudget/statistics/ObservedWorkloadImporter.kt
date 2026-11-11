package ru.itmo.indexbudget.statistics

import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import java.security.MessageDigest
import java.util.HexFormat

data class SqlExample(val key: StatisticsKey, val sql: String, val searchPath: List<String>)
data class ImportedWorkload(val workload: Map<String, Any?>, val manifest: Map<String, Any?>,
    val resolvedQueries: List<ResolvedQuery>, val error: AnalysisError? = null)

/** The catalog port must use the explicitly declared path, never the connection's accidental default. */
fun interface ImportCatalog {
    fun read(tables: Set<TableName>, searchPath: List<String>, context: RunContext): CatalogSnapshot
}

class ObservedWorkloadImporter(private val catalog: ImportCatalog) {
    fun build(frequencies: FrequencyProfiles, examples: List<SqlExample>, options: Map<String, Any?>,
        context: RunContext = RunContext()): ImportedWorkload {
        context.checkDeadline()
        if (options.keys.any { it !in setOf("constraints", "objective", "table_write_rates", "analysis_mode", "candidate_ranking") })
            ImportJson.fail("Import options may only specify budgets, objective, write rates and analysis presets.")
        val profiles = frequencies.profiles
        if (profiles.size !in 1..3 || profiles.map { it.id }.distinct().size != profiles.size)
            ImportJson.fail("Provide one to three unique profiles.")
        if (examples.size > 1000 || examples.map { it.key }.distinct().size != examples.size)
            ImportJson.fail("Examples must contain at most 1000 unique full identities.")
        val observations = profiles.map { profile -> profile.observations.associateBy { it.key }.also {
            if (it.size != profile.observations.size) ImportJson.fail("Duplicate observation identities.")
        } }
        val keys = observations.flatMap { it.keys }.distinct().sortedWith(KEY_ORDER)
        if (keys.size > 30_000) ImportJson.fail("Import supports at most 30000 distinct full identities; input was not truncated.", ErrorCode.INPUT_LIMIT_EXCEEDED)
        if (examples.any { it.key !in keys }) ImportJson.fail("An example identity does not belong to the imported windows.")
        val byKey = examples.associateBy { it.key }
        val sql = JSqlParserSqlAnalyzer()
        val matcher = SqlTemplateMatcher(context)
        val eligible = linkedMapOf<StatisticsKey, ResolvedQuery>()
        val decisions = linkedMapOf<StatisticsKey, Map<String, Any?>>()
        for (key in keys) {
            context.checkDeadline()
            val rows = observations.map { it[key] }
            val example = byKey[key]
            fun exclude(reason: String, details: Any? = null) {
                decisions[key] = linkedMapOf("key" to ImportJson.key(key), "status" to "excluded", "reason" to reason, "details" to details)
            }
            if (!key.toplevel) { exclude("NESTED_STATEMENT"); continue }
            if (rows.any { it == null || it.state == ObservationState.UNKNOWN || it.callsPerHour == null }) {
                exclude("INCOMPLETE_OBSERVATION", profiles.mapIndexed { i, p -> mapOf("profile_id" to p.id,
                    "state" to rows[i]?.state?.name, "reasons" to rows[i]?.reasons?.map { it.name }) }); continue
            }
            if (rows.any { it?.textStatus != QueryTextStatus.AVAILABLE || it.query == null }) { exclude("UNAVAILABLE_TEXT"); continue }
            if (rows.map { it?.query }.distinct().size != 1) { exclude("TEMPLATE_CHANGED_BETWEEN_WINDOWS"); continue }
            val template = requireNotNull(rows.first()?.query)
            if (template.toByteArray(Charsets.UTF_8).size > 64 * 1024) { exclude("SQL_SIZE_LIMIT"); continue }
            val statements = try { net.sf.jsqlparser.parser.CCJSqlParserUtil.parseStatements(template) }
                catch (_: net.sf.jsqlparser.JSQLParserException) { exclude("UNSUPPORTED_STATEMENT"); continue }
                finally { context.checkDeadline() }
            if (statements.size != 1 || statements[0] !is net.sf.jsqlparser.statement.select.PlainSelect) {
                exclude("UNSUPPORTED_STATEMENT"); continue
            }
            if (rows.all { it?.callsPerHour == 0.0 }) { exclude("NO_CALLS"); continue }
            if (example == null) { exclude("MISSING_EXAMPLE"); continue }
            if (example.searchPath.isEmpty() || example.searchPath.first() != "pg_catalog" ||
                example.searchPath.distinct().size != example.searchPath.size || example.searchPath.size > 16 ||
                example.searchPath.any { it.isBlank() || it == "\$user" || it.startsWith("pg_temp") || '\u0000' in it }) {
                exclude("EXPLICIT_CONTEXT_REQUIRED"); continue
            }
            val id = id(key)
            try {
                val one = WorkloadInput(listOf(QueryInput(id, example.sql)), listOf(Profile("validation", 1.0, mapOf(id to 1.0))),
                    Constraints(0, null))
                val parsed = sql.parse(one, context)
                val snapshot = catalog.read(parsed.referencedTables + matcher.referencedTables(template), example.searchPath, context)
                val resolved = sql.resolve(parsed, snapshot, context).queries.single()
                if (!matcher.match(requireNotNull(rows.first()?.query), example.sql, snapshot)) {
                    exclude("TEMPLATE_MISMATCH"); continue
                }
                val frozen = matcher.qualifiedExample(example.sql, snapshot)
                // Validate the exact exported SQL too, after explicit table qualification.
                val frozenInput = one.copy(queries = listOf(QueryInput(id, frozen)))
                val frozenQuery = sql.resolve(sql.parse(frozenInput, context), snapshot, context).queries.single()
                if (frozenQuery.copy(sql = resolved.sql) != resolved) ImportJson.fail("Qualified SQL changed resolved semantics.")
                eligible[key] = frozenQuery
            } catch (failure: AnalysisException) {
                if (failure.error.code !in setOf(ErrorCode.UNSUPPORTED_SQL, ErrorCode.UNKNOWN_COLUMN, ErrorCode.UNKNOWN_TABLE,
                        ErrorCode.AMBIGUOUS_COLUMN, ErrorCode.INVALID_INPUT, ErrorCode.INPUT_LIMIT_EXCEEDED)) throw failure
                exclude("SQL_REJECTED", mapOf("code" to failure.error.code.name))
            }
        }
        // Equal opportunity per profile, not weight-proportional allocation; no profile's traffic is merged into another.
        val queues = observations.map { rows -> eligible.keys.sortedWith(compareByDescending<StatisticsKey> {
            requireNotNull(rows.getValue(it).callsPerHour)
        }.then(KEY_ORDER)).iterator() }
        val selected = linkedSetOf<StatisticsKey>()
        while (selected.size < 20) {
            context.checkDeadline()
            var added = false
            for (queue in queues) {
                while (queue.hasNext()) {
                    val key = queue.next()
                    if (selected.add(key)) { added = true; break }
                }
                if (selected.size == 20) break
            }
            if (!added) break
        }
        var selectionError = if (selected.isEmpty()) AnalysisError(ErrorCode.INVALID_INPUT,
            "No supported queries with complete frequencies and explicit examples remain; see import-manifest.json.") else null
        val ordered = selected.sortedWith(KEY_ORDER)
        val queryIds = ordered.associateWith(::id)
        val importedProfiles = profiles.mapIndexed { index, profile ->
            val calls = ordered.associate { queryIds.getValue(it) to requireNotNull(observations[index].getValue(it).callsPerHour) }
            if (calls.values.none { it > 0 } && selectionError == null) selectionError = AnalysisError(ErrorCode.INVALID_INPUT,
                "Profile has no positive frequency after selection; see import-manifest.json.", profileId = profile.id)
            mapOf("id" to profile.id, "weight" to profile.weight, "calls_per_hour" to calls)
        }
        for ((key, query) in eligible) {
            val example = byKey.getValue(key)
            decisions[key] = linkedMapOf("key" to ImportJson.key(key), "status" to if (key in selected) "selected" else "excluded",
                "reason" to if (key in selected) null else "QUERY_LIMIT", "query_id" to query.id,
                "representative_example" to true, "sql_sha256" to hash(query.sql.toByteArray(Charsets.UTF_8)),
                "template_sha256" to hash(requireNotNull(observations.first().getValue(key).query).toByteArray(Charsets.UTF_8)),
                "declared_search_path" to example.searchPath,
                "parameter_type_scope" to "representative_example_only_original_bind_types_not_observed")
        }
        val periods = profiles.mapIndexed { i, profile ->
            val available = profile.observations.filter { it.key.toplevel && it.state != ObservationState.UNKNOWN }
            fun total(rows: List<FrequencyObservation>, value: (FrequencyObservation) -> Double?): Double {
                val sum = rows.fold(java.math.BigDecimal.ZERO) { acc, row ->
                    context.checkDeadline()
                    acc + java.math.BigDecimal.valueOf(requireNotNull(value(row)))
                }.toDouble()
                if (!sum.isFinite()) ImportJson.fail("Coverage arithmetic overflow.", ErrorCode.NUMERIC_ERROR)
                return sum
            }
            val chosen = ordered.map { observations[i].getValue(it) }
            val denominatorCalls = total(available) { it.callsPerHour }
            val denominatorTime = total(available) { it.totalExecTimeMillis }
            val selectedCalls = total(chosen) { it.callsPerHour }
            val selectedTime = total(chosen) { it.totalExecTimeMillis }
            fun calls(rows: List<FrequencyObservation>): Long = try {
                rows.fold(0L) { sum, row -> context.checkDeadline(); Math.addExact(sum, requireNotNull(row.deltaCalls)) }
            } catch (_: ArithmeticException) { ImportJson.fail("Coverage call counts exceed signed bigint.", ErrorCode.NUMERIC_ERROR) }
            mapOf("profile_id" to profile.id, "before_snapshot_id" to profile.beforeSnapshotId.toString(),
                "after_snapshot_id" to profile.afterSnapshotId.toString(), "start" to profile.period.start.toString(),
                "end" to profile.period.end.toString(), "duration_seconds" to profile.period.durationSeconds,
                "before_started_at" to profile.period.beforeStartedAt.toString(), "before_finished_at" to profile.period.beforeFinishedAt.toString(),
                "after_started_at" to profile.period.afterStartedAt.toString(), "after_finished_at" to profile.period.afterFinishedAt.toString(),
                "minimum_duration_seconds" to profile.period.minimumDurationSeconds, "maximum_duration_seconds" to profile.period.maximumDurationSeconds,
                "complete" to profile.complete, "reasons" to profile.reasons.map { it.name },
                "before_reasons" to profile.beforeReasons.map { it.name }, "after_reasons" to profile.afterReasons.map { it.name },
                "unidentified_before" to profile.unidentifiedBefore, "unidentified_after" to profile.unidentifiedAfter,
                "coverage_denominator" to "available_matched_stable_top_level_observations_in_this_window",
                "available_calls_per_hour" to denominatorCalls, "selected_calls_per_hour" to selectedCalls,
                "available_calls" to calls(available), "selected_calls" to calls(chosen),
                "calls_coverage" to if (denominatorCalls > 0) selectedCalls / denominatorCalls else null,
                "available_historical_exec_time_ms" to denominatorTime, "selected_historical_exec_time_ms" to selectedTime,
                "historical_time_coverage" to if (denominatorTime > 0) selectedTime / denominatorTime else null)
        }
        val manifest = linkedMapOf<String, Any?>("schema_version" to 1, "kind" to "pg_stat_statements_import",
            "status" to if (selectionError == null) "completed" else "failed",
            "error" to selectionError?.let { mapOf("code" to it.code.name, "message" to it.message, "profile_id" to it.profileId) },
            "source_id" to frequencies.source.sourceId.toString(), "database_oid" to frequencies.source.databaseOid,
            "source" to mapOf("postgres_version" to frequencies.source.postgresVersion, "extension_version" to frequencies.source.extensionVersion,
                "reader_userid" to frequencies.source.readerUserid, "all_query_texts_visible" to frequencies.source.allQueryTextsVisible,
                "track" to frequencies.source.track, "source_identity" to "operator_assigned_uuid_plus_database_oid"),
            "selection_rule" to SELECTION_RULE, "periods" to periods, "entries" to keys.map { key -> decisions.getValue(key) +
                ("observations" to profiles.mapIndexed { i, profile -> observations[i][key].let { row ->
                    mapOf("profile_id" to profile.id, "state" to row?.state?.name, "calls_per_hour" to row?.callsPerHour,
                        "historical_exec_time_ms" to row?.totalExecTimeMillis, "reasons" to row?.reasons?.map { it.name }) } }) },
            "limitations" to listOf("representative_literals_not_generic_plan", "historical_time_is_not_predicted_latency",
                "pg16_targeted_reset_may_be_undetectable", "top_level_only_to_avoid_nested_double_counting"))
        // Use #68's existing report-facing contract unchanged. Rich per-key evidence stays in the
        // separate versioned manifest, referenced by name + exact byte hash in the selection rule.
        val provenance = linkedMapOf<String, Any?>("schema_version" to 1, "source" to "pg_stat_statements",
            "source_id" to frequencies.source.sourceId.toString(),
            "selection_rule" to "$SELECTION_RULE; import_manifest=import-manifest.json; sha256=${hash(ImportJson.mapper.writeValueAsBytes(manifest))}",
            "windows" to periods.map { period -> mapOf("profile_id" to period.getValue("profile_id"),
                "started_at" to period.getValue("start"), "finished_at" to period.getValue("end"),
                "complete" to period.getValue("complete"), "coverage" to mapOf("denominator" to "available_complete_entries",
                    "selected_calls" to period.getValue("selected_calls"), "available_calls" to period.getValue("available_calls"),
                    "selected_exec_time_millis" to period.getValue("selected_historical_exec_time_ms"),
                    "available_exec_time_millis" to period.getValue("available_historical_exec_time_ms"))) },
            "query_examples" to ordered.map { mapOf("query_id" to queryIds.getValue(it), "source" to "user_supplied_literals") })
        val workload = linkedMapOf<String, Any?>("schema_version" to 2,
            "queries" to ordered.map { mapOf("id" to queryIds.getValue(it), "sql" to eligible.getValue(it).sql) },
            "profiles" to importedProfiles)
        workload.putAll(options)
        workload["provenance"] = provenance
        return ImportedWorkload(workload, manifest, ordered.map { eligible.getValue(it) }, selectionError)
    }

    companion object {
        const val SELECTION_RULE = "profile_round_robin_desc_calls_per_hour_full_identity_tiebreak_max_20"
        val KEY_ORDER: Comparator<StatisticsKey> = compareBy<StatisticsKey>({ it.sourceId.toString() }, { it.dbid },
            { it.userid }, { it.queryid }, { it.toplevel })
        internal fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun id(key: StatisticsKey): String = "obs_" + hash(ImportJson.mapper.writeValueAsBytes(ImportJson.key(key)))
    }
}
