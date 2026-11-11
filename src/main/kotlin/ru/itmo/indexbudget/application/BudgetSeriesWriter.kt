package ru.itmo.indexbudget.application

import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.report.HtmlBudgetSeriesRenderer
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Separate v1 manifest; ordinary workload v2 and per-run report v3 retain their contracts. */
internal class BudgetSeriesWriter(
    private val owner: SeriesDirectoryOwner,
    private val input: WorkloadInput,
    private val request: BudgetSeriesRequest,
    private val context: RunContext,
) {
    private val directory: Path get() = owner.directory
    private val mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
    private val renderer = HtmlBudgetSeriesRenderer()

    fun publish(result: BudgetSeriesResult, preparation: SeriesPreparation?, preparationContext: RunContext?, finished: Boolean) {
        owner.verify()
        val root = linkedMapOf<String, Any?>(
            "schema_version" to 1, "spec_version" to "0.2", "series_id" to context.runId,
            "status" to if (finished) result.status.name.lowercase() else "running",
            "duration_millis" to context.elapsedTime().toMillis(), "timeout_seconds" to request.timeoutSeconds,
            "budget" to request.budget.wireName, "values" to request.values,
            "comparison_scope" to "fixed_candidate_pool",
            "source" to mapOf("workload_input_hash" to context.inputHash,
                "workload_source" to if (input.provenance == null) "manual_json" else "pg_stat_statements",
                "workload_source_id" to input.provenance?.sourceId?.toString(),
                "connection_configuration" to "INDEXBUDGET_DATABASE_URL",
                "observation_scope" to "catalog_baseline_plans_versions; no database snapshot isolation"),
            "settings" to mapOf("selected_objective" to input.objective.name.lowercase(),
                "analysis_mode" to input.analysisMode.name.lowercase(), "candidate_ranking" to input.candidateRanking.wireName,
                "constraints" to budgets(input.constraints),
                "queries" to input.queries.map { mapOf("id" to it.id, "sql" to it.sql) },
                "profiles" to input.profiles.map { mapOf("id" to it.id, "weight" to it.weight, "calls_per_hour" to it.callsPerHour) },
                "table_write_rates" to input.tableWriteRates?.mapValues { (_, rates) -> mapOf(
                    "inserts_per_hour" to rates.insertsPerHour, "updates_per_hour" to rates.updatesPerHour,
                    "deletes_per_hour" to rates.deletesPerHour, "source" to rates.source.name.lowercase()) }),
            "preparation" to preparationContext?.let { preparedContext -> mapOf(
                "run_id" to preparedContext.runId, "statistics" to statistics(preparedContext.statistics()),
                "limits" to limits(preparedContext.limits),
                "maximum_budgets" to preparation?.pool?.input?.constraints?.let(::budgets),
                "conditions" to preparation?.conditions?.let(::conditions),
                "candidate_ids" to preparation?.pool?.candidates?.map { it.id },
                "candidates" to preparation?.pool?.candidates?.map(::candidate),
                "error" to result.preparationError?.let(::error)) },
            "points" to result.points.map { point -> mapOf(
                "ordinal" to point.ordinal, "budget_value" to point.value, "status" to point.status.name.lowercase(),
                "run_id" to point.runId, "conditions_match" to point.conditionsMatch,
                "condition_changes" to point.changes, "conditions" to point.conditions?.let(::conditions),
                "selected_index_ids" to point.result?.selected?.evaluation?.candidateIds,
                "metrics" to point.result?.selected?.metrics?.let(::metrics),
                "statistics" to (point.diagnostics ?: point.result?.diagnostics)?.statistics?.let(::statistics),
                "limits" to (point.diagnostics ?: point.result?.diagnostics)?.limits?.let(::limits),
                "stop_reasons" to (point.diagnostics ?: point.result?.diagnostics)?.stopReasons?.map { it.name },
                "errors" to (point.diagnostics ?: point.result?.diagnostics)?.errors?.map(::error),
                "artifacts" to point.artifacts, "error" to point.error?.let(::error)) },
        )
        val temporary = directory.resolve(".series-${context.runId}.tmp")
        val temporaryHtml = directory.resolve(".series-${context.runId}.html.tmp")
        val temporaryReport = directory.resolve(".series-${context.runId}.report.tmp")
        try {
            val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n"
            val summary = mapper.readTree(json)
            val reports = result.points.mapNotNull { point ->
                point.report?.takeIf { point.artifacts.containsKey("report.html") }?.let { point.ordinal to it }
            }.toMap()
            Files.writeString(temporary, json)
            Files.writeString(temporaryHtml, renderer.render(summary))
            Files.writeString(temporaryReport, renderer.render(summary, reports))
            owner.verify()
            Files.move(temporary, directory.resolve("series.json"), ATOMIC_MOVE, REPLACE_EXISTING)
            Files.move(temporaryHtml, directory.resolve("series.html"), ATOMIC_MOVE, REPLACE_EXISTING)
            Files.move(temporaryReport, directory.resolve("report.html"), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: Exception) {
            // Never leave an older HTML snapshot describing a different set of published points.
            try { owner.verify(); Files.deleteIfExists(directory.resolve("series.html")) } catch (_: Exception) { }
            try { owner.verify(); Files.deleteIfExists(directory.resolve("report.html")) } catch (_: Exception) { }
            throw AnalysisException(AnalysisError(ErrorCode.EXPORT_FAILED, "Cannot publish budget series summary."))
        } finally {
            try { Files.deleteIfExists(temporary) } catch (_: Exception) { }
            try { Files.deleteIfExists(temporaryHtml) } catch (_: Exception) { }
            try { Files.deleteIfExists(temporaryReport) } catch (_: Exception) { }
        }
    }

    private fun budgets(value: Constraints) = mapOf("storage_budget_bytes" to value.storageBudgetBytes,
        "write_budget_units_per_hour" to value.writeBudgetUnitsPerHour, "max_indexes" to value.maxIndexes,
        "query_cost_guards" to value.queryCostGuards.toSortedMap())

    private fun limits(value: RunLimits) = mapOf("command_timeout_millis" to value.commandTimeout.toMillis(),
        "search_timeout_millis" to value.searchTimeout.toMillis(), "explain_timeout_millis" to value.explainTimeout.toMillis(),
        "connect_timeout_millis" to value.connectTimeout.toMillis(), "max_explain_attempts" to value.maxExplainAttempts,
        "max_repair_attempts" to value.maxRepairAttempts)

    private fun conditions(value: SeriesConditions) = mapOf("catalog_hash" to value.catalogHash,
        "baseline_costs" to value.baselineCosts, "baseline_plans_hash" to value.baselinePlansHash,
        "source_fingerprint" to value.sourceFingerprint,
        "postgres_version" to value.databaseVersions.postgresVersion, "hypopg_version" to value.databaseVersions.hypopgVersion)

    private fun statistics(value: RunStatistics) = mapOf("explain_attempts" to value.explainAttempts,
        "evaluation_attempts" to value.evaluationAttempts, "completed_evaluations" to value.completedEvaluations,
        "cache_hits" to value.cacheHits, "repair_attempts" to value.repairAttempts)

    private fun candidate(value: IndexCandidate) = mapOf("id" to value.id, "schema" to value.definition.table.schema,
        "table" to value.definition.table.name, "keys" to value.definition.keys.map { mapOf("column" to it.column.name,
            "direction" to it.direction.name.lowercase(), "nulls" to it.nulls.name.lowercase()) },
        "included_columns" to value.definition.includedColumns.map { it.name }, "ddl" to value.ddl,
        "estimated_bytes" to value.estimatedBytes, "budgeted_bytes" to value.budgetedBytes,
        "write_units_per_hour" to value.writeUnitsPerHour)

    private fun metrics(value: SetMetrics) = mapOf("baseline_mean_cost" to value.baselineMeanCost,
        "mean_cost" to value.meanCost, "mean_ratio" to value.meanRatio, "worst_ratio" to value.worstRatio,
        "budget_usage" to mapOf("estimated_bytes" to value.budgetUsage.estimatedBytes,
            "budgeted_bytes" to value.budgetUsage.budgetedBytes, "write_units_per_hour" to value.budgetUsage.writeUnitsPerHour,
            "index_count" to value.budgetUsage.indexCount, "size_safety_factor" to value.budgetUsage.sizeSafetyFactor),
        "profiles" to value.profiles.map { mapOf("profile_id" to it.profileId, "normalized_weight" to it.normalizedWeight,
            "baseline_cost" to it.baselineCost, "cost" to it.cost, "gain" to it.gain,
            "ratio" to it.ratio, "gain_percent" to it.gainPercent) })

    private fun error(value: AnalysisError) = mapOf("code" to value.code.name, "message" to value.message,
        "query_id" to value.queryId, "profile_id" to value.profileId, "details" to value.details)
}
