package ru.itmo.indexbudget.domain

enum class RunStatus(val exitCode: Int) { COMPLETED(0), PARTIAL(3), FAILED(1) }

enum class StopReason {
    SEARCH_COMPLETE,
    NO_CANDIDATES,
    NO_IMPROVEMENT,
    MAX_INDEXES,
    ZERO_STORAGE_BUDGET,
    ZERO_MAX_INDEXES,
    ZERO_BASELINE,
    DEADLINE_EXCEEDED,
    CANCELLED,
    EXPLAIN_LIMIT,
    REPAIR_LIMIT,
    EVALUATION_FAILED,
    CONNECTION_LOST,
}

data class SearchDiagnostics(
    val completedPhases: List<AnalysisPhase> = emptyList(),
    val stopReasons: List<StopReason> = emptyList(),
    val statistics: RunStatistics = RunStatistics(),
    val limits: RunLimits = RunLimits(),
    val warnings: List<AnalysisWarning> = emptyList(),
    val errors: List<AnalysisError> = emptyList(),
    val candidateDecisions: List<CandidateDecision> = emptyList(),
    val unexaminedConfigurations: Long? = null,
)

/** Completed and partial states always preserve both fully evaluated incumbents from the same pool. */
sealed interface SearchResult {
    val comparisonScope: ComparisonScope get() = ComparisonScope.EVALUATED_SETS
    val status: RunStatus
    val selectedObjective: Objective
    val baseline: EvaluationResult?
    val bestRobust: EvaluatedConfiguration?
    val bestMean: EvaluatedConfiguration?
    val selected: EvaluatedConfiguration?
    val diagnostics: SearchDiagnostics

    data class Completed(
        override val selectedObjective: Objective,
        override val baseline: EvaluationResult,
        override val bestRobust: EvaluatedConfiguration,
        override val bestMean: EvaluatedConfiguration,
        override val diagnostics: SearchDiagnostics = SearchDiagnostics(),
    ) : SearchResult {
        init {
            requireUsableResults(baseline, bestRobust, bestMean)
        }

        override val status: RunStatus get() = RunStatus.COMPLETED
        override val selected: EvaluatedConfiguration
            get() = if (selectedObjective == Objective.ROBUST) bestRobust else bestMean
    }

    data class Partial(
        override val selectedObjective: Objective,
        override val baseline: EvaluationResult,
        override val bestRobust: EvaluatedConfiguration,
        override val bestMean: EvaluatedConfiguration,
        override val diagnostics: SearchDiagnostics,
    ) : SearchResult {
        init {
            requireUsableResults(baseline, bestRobust, bestMean)
            require(diagnostics.stopReasons.isNotEmpty()) { "A partial result must explain why analysis stopped" }
        }

        override val status: RunStatus get() = RunStatus.PARTIAL
        override val selected: EvaluatedConfiguration
            get() = if (selectedObjective == Objective.ROBUST) bestRobust else bestMean
    }

    data class Failed(
        override val selectedObjective: Objective,
        val error: AnalysisError,
        override val baseline: EvaluationResult? = null,
        override val diagnostics: SearchDiagnostics = SearchDiagnostics(),
    ) : SearchResult {
        override val status: RunStatus get() = RunStatus.FAILED
        override val bestRobust: EvaluatedConfiguration? get() = null
        override val bestMean: EvaluatedConfiguration? get() = null
        override val selected: EvaluatedConfiguration? get() = null
    }
}

private fun requireUsableResults(
    baseline: EvaluationResult,
    robust: EvaluatedConfiguration,
    mean: EvaluatedConfiguration,
) {
    require(baseline.candidateIds.isEmpty()) { "Baseline must evaluate the empty hypothetical index set" }
    robust.evaluation.requireQueryCoverage(baseline.costByQueryId.keys)
    mean.evaluation.requireQueryCoverage(baseline.costByQueryId.keys)
}

data class EnvironmentInfo(
    val applicationVersion: String,
    val javaVersion: String,
    val kotlinVersion: String? = null,
    val postgresVersion: String? = null,
    val hypopgVersion: String? = null,
)

enum class ComparisonScope { EVALUATED_SETS }

/** Regression describes planner cost only; unknown comparisons stay null. */
data class QueryCostComparison(
    val queryId: String,
    val baselineCost: Double?,
    val selectedCost: Double?,
    val regression: Boolean?,
)

/** JSON and HTML use the same immutable report; SQL is rendered only for [SearchResult.selected]. */
data class AnalysisReport(
    val runId: String,
    val inputHash: String?,
    val durationMillis: Long,
    val environment: EnvironmentInfo,
    val result: SearchResult,
    val input: WorkloadInput? = null,
    val candidates: CandidateBatch = CandidateBatch(emptyList()),
    val priceOfRobustness: Double? = null,
    val schemaVersion: Int = 2,
    val specVersion: String = "0.2",
    val comparisonScope: ComparisonScope = ComparisonScope.EVALUATED_SETS,
    val baselineMetrics: SetMetrics? = null,
    val normalizedProfileWeights: Map<String, Double> = emptyMap(),
    val queryComparisons: List<QueryCostComparison> = emptyList(),
    val incumbentsCoincide: Boolean? = null,
)
