package ru.itmo.indexbudget.report

import ru.itmo.indexbudget.domain.*
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/** Explicit report v2 wire format; domain implementation details never define public JSON fields. */
class JsonReportWriter {
    private val mapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun render(report: AnalysisReport): String {
        val result = report.result
        val input = report.input
        val comparisons = report.queryComparisons.associateBy { it.queryId }
        val selectedIds = result.selected?.evaluation?.candidateIds?.toSet().orEmpty()
        val root = linkedMapOf(
            "schema_version" to report.schemaVersion,
            "spec_version" to report.specVersion,
            "run_id" to report.runId,
            "input_hash" to report.inputHash,
            "duration_millis" to report.durationMillis,
            "environment" to environment(report.environment),
            "status" to result.status.name.lowercase(),
            "selected_objective" to result.selectedObjective.name.lowercase(),
            "comparison_scope" to report.comparisonScope.name.lowercase(),
            "incumbents_coincide" to report.incumbentsCoincide,
            "price_of_robustness" to report.priceOfRobustness,
            "profiles" to input?.profiles?.map { profile -> linkedMapOf(
                "id" to profile.id,
                "weight" to profile.weight,
                "normalized_weight" to report.normalizedProfileWeights[profile.id],
                "calls_per_hour" to profile.callsPerHour.toSortedMap(),
            ) },
            "budgets" to input?.constraints?.let(::budgets),
            "table_write_rates" to input?.tableWriteRates?.toSortedMap()?.mapValues { (_, rates) -> linkedMapOf(
                "inserts_per_hour" to rates.insertsPerHour,
                "updates_per_hour" to rates.updatesPerHour,
                "deletes_per_hour" to rates.deletesPerHour,
                "source" to rates.source.name.lowercase(),
            ) },
            "queries" to input?.queries?.map { query -> linkedMapOf(
                "id" to query.id,
                "sql" to query.sql,
                "baseline_cost" to comparisons[query.id]?.baselineCost,
                "selected_cost" to comparisons[query.id]?.selectedCost,
                "regression" to comparisons[query.id]?.regression,
            ) },
            "baseline" to result.baseline?.let { configuration(it, report.baselineMetrics) },
            "selected" to result.selected?.let { configuration(it.evaluation, it.metrics) },
            "best_robust" to result.bestRobust?.let { configuration(it.evaluation, it.metrics) },
            "best_mean" to result.bestMean?.let { configuration(it.evaluation, it.metrics) },
            "selected_indexes" to report.candidates.candidates.filter { it.id in selectedIds }.sortedBy { it.id }
                .map(::candidate),
            "candidates" to report.candidates.candidates.sortedBy { it.id }.map(::candidate),
            "candidate_diagnostics" to candidateDiagnostics(report.candidates.diagnostics),
            "diagnostics" to diagnostics(result.diagnostics),
            "error" to (result as? SearchResult.Failed)?.error?.let(::error),
        )
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n"
    }

    private fun environment(value: EnvironmentInfo) = linkedMapOf(
        "application_version" to value.applicationVersion,
        "java_version" to value.javaVersion,
        "kotlin_version" to value.kotlinVersion,
        "postgres_version" to value.postgresVersion,
        "hypopg_version" to value.hypopgVersion,
    )

    private fun budgets(value: Constraints) = linkedMapOf(
        "storage_budget_bytes" to value.storageBudgetBytes,
        "write_budget_units_per_hour" to value.writeBudgetUnitsPerHour,
        "max_indexes" to value.maxIndexes,
    )

    private fun configuration(value: EvaluationResult, metrics: SetMetrics?) = linkedMapOf(
        "candidate_ids" to value.candidateIds,
        "cost_by_query_id" to value.costByQueryId.toSortedMap(),
        "plans_by_query_id" to value.plansByQueryId.toSortedMap().mapValues { (_, plan) ->
            requireNotNull(mapper.readTree(plan.json)) { "Saved EXPLAIN plan must contain JSON" }
        },
        "metrics" to metrics?.let(::metrics),
    )

    private fun metrics(value: SetMetrics) = linkedMapOf(
        "profiles" to value.profiles.sortedBy { it.profileId }.map { profile -> linkedMapOf(
            "profile_id" to profile.profileId,
            "normalized_weight" to profile.normalizedWeight,
            "baseline_cost" to profile.baselineCost,
            "cost" to profile.cost,
            "gain" to profile.gain,
            "ratio" to profile.ratio,
            "gain_percent" to profile.gainPercent,
        ) },
        "baseline_mean_cost" to value.baselineMeanCost,
        "mean_cost" to value.meanCost,
        "mean_ratio" to value.meanRatio,
        "worst_ratio" to value.worstRatio,
        "budget_usage" to linkedMapOf(
            "estimated_bytes" to value.budgetUsage.estimatedBytes,
            "budgeted_bytes" to value.budgetUsage.budgetedBytes,
            "write_units_per_hour" to value.budgetUsage.writeUnitsPerHour,
            "index_count" to value.budgetUsage.indexCount,
            "size_safety_factor" to value.budgetUsage.sizeSafetyFactor,
        ),
    )

    private fun candidate(value: IndexCandidate) = linkedMapOf(
        "id" to value.id,
        "definition" to linkedMapOf(
            "table" to table(value.definition.table),
            "method" to value.definition.method,
            "unique" to value.definition.unique,
            "keys" to value.definition.keys.map { key -> linkedMapOf(
                "column" to column(key.column),
                "direction" to key.direction.name.lowercase(),
                "nulls" to key.nulls.name.lowercase(),
                "collation" to key.collation,
                "operator_class" to key.operatorClass,
            ) },
        ),
        "source_query_ids" to value.sourceQueryIds.sorted(),
        "generation_reasons" to value.generationReasons.map { it.name }.sorted(),
        "ddl" to value.ddl,
        "estimated_bytes" to value.estimatedBytes,
        "budgeted_bytes" to value.budgetedBytes,
        "write_units_per_hour" to value.writeUnitsPerHour,
    )

    private fun candidateDiagnostics(value: CandidateDiagnostics) = linkedMapOf(
        "column_limit_per_query_table" to value.columnLimitPerQueryTable,
        "local_candidate_limit" to value.localCandidateLimit,
        "global_candidate_limit" to value.globalCandidateLimit,
        "candidates_before_global_deduplication" to value.candidatesBeforeGlobalDeduplication,
        "candidates_after_global_deduplication" to value.candidatesAfterGlobalDeduplication,
        "shortlisted_candidates" to value.shortlistedCandidates,
        "ungenerated_definitions" to value.ungeneratedDefinitions,
        "local_scopes" to value.localScopes.map { scope -> linkedMapOf(
            "query_id" to scope.queryId,
            "table" to table(scope.table),
            "discovered_columns" to scope.discoveredColumns,
            "retained_columns" to scope.retainedColumns,
            "enumerated_definitions" to scope.enumeratedDefinitions,
            "deduplicated_definitions" to scope.deduplicatedDefinitions,
            "retained_definitions" to scope.retainedDefinitions,
            "excluded_columns" to scope.excludedColumns.map(::column),
        ) },
        "decisions" to value.decisions.map(::decision),
        "warnings" to value.warnings.map(::warning),
    )

    private fun diagnostics(value: SearchDiagnostics) = linkedMapOf(
        "completed_phases" to value.completedPhases.map { it.name.lowercase() },
        "stop_reasons" to value.stopReasons.map { it.name },
        "statistics" to linkedMapOf(
            "explain_attempts" to value.statistics.explainAttempts,
            "evaluation_attempts" to value.statistics.evaluationAttempts,
            "completed_evaluations" to value.statistics.completedEvaluations,
            "cache_hits" to value.statistics.cacheHits,
            "repair_attempts" to value.statistics.repairAttempts,
        ),
        "limits" to linkedMapOf(
            "command_timeout_millis" to value.limits.commandTimeout.toMillis(),
            "search_timeout_millis" to value.limits.searchTimeout.toMillis(),
            "explain_timeout_millis" to value.limits.explainTimeout.toMillis(),
            "connect_timeout_millis" to value.limits.connectTimeout.toMillis(),
            "max_explain_attempts" to value.limits.maxExplainAttempts,
            "max_repair_attempts" to value.limits.maxRepairAttempts,
        ),
        "warnings" to value.warnings.map(::warning),
        "errors" to value.errors.map(::error),
        "candidate_decisions" to value.candidateDecisions.map(::decision),
        "unexamined_configurations" to value.unexaminedConfigurations,
    )

    private fun table(value: TableRef) = linkedMapOf("schema" to value.schema, "name" to value.name)
    private fun column(value: ColumnRef) = linkedMapOf("table" to table(value.table), "name" to value.name)
    private fun decision(value: CandidateDecision) = linkedMapOf(
        "reason" to value.reason.name,
        "candidate_id" to value.candidateId,
        "query_id" to value.queryId,
        "table" to value.table?.let(::table),
        "phase" to value.phase?.name?.lowercase(),
        "details" to value.details.toSortedMap(),
    )
    private fun warning(value: AnalysisWarning) = linkedMapOf(
        "code" to value.code.name, "message" to value.message,
        "query_id" to value.queryId, "profile_id" to value.profileId, "details" to value.details.toSortedMap(),
    )
    private fun error(value: AnalysisError) = linkedMapOf(
        "code" to value.code.name, "message" to value.message,
        "query_id" to value.queryId, "profile_id" to value.profileId, "details" to value.details.toSortedMap(),
    )
}
