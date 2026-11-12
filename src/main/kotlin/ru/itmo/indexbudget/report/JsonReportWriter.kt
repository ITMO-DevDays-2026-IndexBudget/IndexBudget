package ru.itmo.indexbudget.report

import ru.itmo.indexbudget.domain.*
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/** Explicit report v3 wire format; domain implementation details never define public JSON fields. */
class JsonReportWriter {
    private val mapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun render(report: AnalysisReport): String {
        report.validateMethodFormat()
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
            "analysis_mode" to report.analysisMode.name.lowercase(),
            "candidate_ranking" to report.candidateRanking.wireName,
            "index_methods" to report.indexMethods.sortedBy { it.ordinal }.map { it.wireName },
            "selected_objective" to result.selectedObjective.name.lowercase(),
            "comparison_scope" to report.comparisonScope.name.lowercase(),
            "incumbents_coincide" to report.incumbentsCoincide,
            "price_of_robustness" to report.priceOfRobustness,
            "summary" to summary(report.summary),
            "profile_comparisons" to report.profileComparisons.map(::series),
            "query_effects" to report.queryEffects.map(::series),
            "workload_provenance" to report.workloadProvenance?.let(::provenance),
            "profiles" to input?.profiles?.map { profile -> linkedMapOf(
                "id" to profile.id,
                "weight" to profile.weight,
                "normalized_weight" to report.normalizedProfileWeights[profile.id],
                "calls_per_hour" to profile.callsPerHour.toSortedMap(),
            ) },
            "budgets" to input?.constraints?.let(::budgets),
            "cost_guards" to linkedMapOf(
                "basis" to "planner_cost_ratio",
                "note" to "Optional per-query constraints on planner cost ratios; not latency or SLA.",
                "max_ratio_by_query_id" to input?.constraints?.queryCostGuards?.toSortedMap(),
                "outcomes" to report.costGuards.map(::costGuard),
            ),
            "write_proxy_model" to "row_changes_times_key_and_include_columns",
            "write_proxy_note" to "Equal column counts do not imply equal real maintenance costs across index methods.",
            "table_write_rates" to WriteProfileReport.rates(input),
            "table_write_rates_origin" to WriteProfileReport.origin(input?.tableWriteRatesOrigin),
            "queries" to input?.queries?.map { query -> linkedMapOf(
                "id" to query.id,
                "sql" to query.sql,
                "statement_kind" to report.statementKinds[query.id]?.name?.lowercase(),
                "cost_interpretation" to report.statementKinds[query.id]?.let {
                    if (it == StatementKind.SELECT) "planner_cost" else "row_search_and_preparation"
                },
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
            "manual_requested_indexes" to input?.manualIndexes?.map { definition -> linkedMapOf(
                "schema" to definition.table.schema, "table" to definition.table.name, "method" to definition.method,
                "keys" to definition.keys.map { key -> linkedMapOf("column" to key.column.name,
                    "direction" to key.direction.name.lowercase().takeIf { definition.method == "btree" },
                    "nulls" to key.nulls.name.lowercase().takeIf { definition.method == "btree" }) },
            ) },
            "manual_comparison" to report.manualComparison?.let(::manualComparison),
            "candidate_diagnostics" to candidateDiagnostics(report.candidates.diagnostics),
            "diagnostics" to diagnostics(result.diagnostics),
            "error" to (result as? SearchResult.Failed)?.error?.let(::error),
        )
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n"
    }

    private fun manualComparison(value: ManualComparison) = linkedMapOf(
        "origin" to "manual_outside_automatic_shortlist",
        "comparison_scope" to "separate_manual_evaluation",
        "status" to value.status.wireValue, "admissible" to value.admissible,
        "candidates" to value.candidates.map(::candidate),
        "decisions" to value.decisions.map(::decision),
        "configuration" to value.evaluation?.let { configuration(it, value.metrics) },
        "budget_violations" to value.budgetViolations.map { it.name }.sorted(),
        "evaluation_violations" to value.evaluationViolations.map(::error),
        "queries" to value.queryComparisons.map { linkedMapOf(
            "query_id" to it.queryId, "baseline_cost" to it.baselineCost,
            "manual_cost" to it.selectedCost, "regression" to it.regression,
        ) },
        "error" to value.error?.let(::error),
    )

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
        "query_cost_guards" to value.queryCostGuards.toSortedMap(),
    )

    private fun costGuard(value: CostGuardOutcome) = linkedMapOf(
        "query_id" to value.queryId,
        "max_ratio" to value.maxRatio,
        "baseline_cost" to value.baselineCost,
        "selected_cost" to value.selectedCost,
        "observed_ratio" to value.observedRatio,
        "zero_baseline" to value.zeroBaseline,
        "violated" to value.violated,
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
        "budget_usage" to budgetUsage(value.budgetUsage),
    )

    private fun budgetUsage(value: BudgetUsage) = linkedMapOf(
        "estimated_bytes" to value.estimatedBytes,
        "budgeted_bytes" to value.budgetedBytes,
        "write_units_per_hour" to value.writeUnitsPerHour,
        "index_count" to value.indexCount,
        "size_safety_factor" to value.sizeSafetyFactor,
    )

    private fun effect(value: CostEffect) = linkedMapOf(
        "baseline_cost" to value.baselineCost,
        "cost" to value.cost,
        "gain" to value.gain,
        "ratio" to value.ratio,
        "gain_percent" to value.gainPercent,
        "relative_status" to value.relativeStatus.name.lowercase(),
    )

    private fun series(value: CostComparisonSeries) = linkedMapOf(
        "id" to value.id,
        "baseline" to effect(value.baseline),
        "selected" to effect(value.selected),
        "best_robust" to effect(value.bestRobust),
        "best_mean" to effect(value.bestMean),
    )

    private fun summary(value: RecommendationSummary) = linkedMapOf(
        "status" to value.status.name.lowercase(),
        "selected_index_ids" to value.selectedIndexIds,
        "mean_effect" to effect(value.meanEffect),
        "worst_ratio" to value.worstRatio,
        "budget_usage" to value.budgetUsage?.let(::budgetUsage),
        "budget_limits" to value.budgetLimits?.let(::budgets),
        "write_limit_enabled" to value.writeLimitEnabled,
        "regressed_query_count" to value.regressedQueryCount,
        "comparison_scope" to value.comparisonScope.name.lowercase(),
        "incumbents_coincide" to value.incumbentsCoincide,
        "price_of_robustness" to value.priceOfRobustness,
        "stop_reasons" to value.stopReasons.map { it.name },
        "errors" to value.errors.map(::error),
    )

    private fun provenance(value: WorkloadProvenance) = linkedMapOf(
        "schema_version" to value.schemaVersion,
        "source" to "pg_stat_statements",
        "source_id" to value.sourceId.toString(),
        "selection_rule" to value.selectionRule,
        "windows" to value.windows.map { window -> linkedMapOf(
            "profile_id" to window.profileId,
            "started_at" to window.startedAt.toString(),
            "finished_at" to window.finishedAt.toString(),
            "complete" to window.complete,
            "coverage" to linkedMapOf(
                "denominator" to "available_complete_entries",
                "selected_calls" to window.coverage.selectedCalls,
                "available_calls" to window.coverage.availableCalls,
                "selected_exec_time_millis" to window.coverage.selectedExecTimeMillis,
                "available_exec_time_millis" to window.coverage.availableExecTimeMillis,
            ),
        ) },
        "query_examples" to value.queryExamples.map { example -> linkedMapOf(
            "query_id" to example.queryId,
            "source" to example.source.name.lowercase(),
        ) },
    )

    private fun candidate(value: IndexCandidate) = linkedMapOf(
        "id" to value.id,
        "definition" to linkedMapOf(
            "table" to table(value.definition.table),
            "method" to value.definition.method,
            "included_columns" to value.definition.includedColumns.map(::column),
            "unique" to value.definition.unique,
            "keys" to value.definition.keys.map { key -> linkedMapOf(
                "column" to column(key.column),
                "direction" to key.direction.name.lowercase().takeIf { value.definition.method == "btree" },
                "nulls" to key.nulls.name.lowercase().takeIf { value.definition.method == "btree" },
                "collation" to key.collation,
                "operator_class" to key.operatorClass,
            ) },
        ),
        "source_query_ids" to value.sourceQueryIds.sorted(),
        "source_branches" to value.sourceBranches.sortedWith(compareBy({ it.queryId }, { it.conditionPath })).map {
            linkedMapOf("query_id" to it.queryId, "condition_path" to it.conditionPath)
        },
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
        "initial_shortlisted_candidates" to value.initialShortlistedCandidates,
        "reserve_candidates" to value.reserveCandidates,
        "reserve_candidate_limit" to value.reserveCandidateLimit,
        "max_size_attempts" to value.maxSizeAttempts,
        "preparation_scope" to value.preparationScope.name.lowercase(),
        "fixed_pool_selection" to value.fixedPoolSelection?.let { point -> linkedMapOf(
            "pool_candidates" to point.poolCandidates,
            "admissible_candidates" to point.admissibleCandidates,
            "budget_rejected_candidates" to point.budgetRejectedCandidates,
            "size_attempts" to point.sizeAttempts,
        ) },
        "preparation" to value.preparation?.let { preparation -> linkedMapOf(
            "examined_candidates" to preparation.examinedCandidates,
            "size_attempts" to preparation.sizeAttempts,
            "budget_rejected_candidates" to preparation.budgetRejectedCandidates,
            "candidate_errors" to preparation.candidateErrors,
            "refilled_candidates" to preparation.refilledCandidates,
            "final_shortlisted_candidates" to preparation.finalShortlistedCandidates,
            "stop_reason" to preparation.stopReason.name,
        ) },
        "ungenerated_definitions" to value.ungeneratedDefinitions,
        "local_scopes" to value.localScopes.map { scope -> linkedMapOf(
            "query_id" to scope.queryId,
            "table" to table(scope.table),
            "discovered_columns" to scope.discoveredColumns,
            "retained_columns" to scope.retainedColumns,
            "enumerated_definitions" to scope.enumeratedDefinitions,
            "deduplicated_definitions" to scope.deduplicatedDefinitions,
            "unindexable_variants" to scope.unindexableVariants,
            "retained_definitions" to scope.retainedDefinitions,
            "excluded_columns" to scope.excludedColumns.map(::column),
            "enumerated_include_definitions" to scope.enumeratedIncludeDefinitions,
            "retained_include_definitions" to scope.retainedIncludeDefinitions,
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
