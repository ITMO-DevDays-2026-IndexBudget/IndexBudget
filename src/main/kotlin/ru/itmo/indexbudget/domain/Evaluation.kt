package ru.itmo.indexbudget.domain

/** Opaque saved EXPLAIN JSON, avoiding a JSON-library dependency in the domain. */
data class ExplainPlan(val json: String)

/**
 * One complete evaluation of a configuration for all shared queries, never one evaluation per profile.
 * The adapter must check [requireQueryCoverage] before returning and map database/numeric errors to
 * [AnalysisError]. Constructor checks detect programming errors in implementations and fixtures.
 */
data class EvaluationResult(
    val candidateIds: List<String>,
    val costByQueryId: Map<String, Double>,
    val plansByQueryId: Map<String, ExplainPlan>,
) {
    init {
        require(candidateIds == candidateIds.distinct().sorted()) { "Candidate IDs must be unique and sorted" }
        require(costByQueryId.isNotEmpty()) { "An evaluation must contain at least one query" }
        require(costByQueryId.keys == plansByQueryId.keys) { "Costs and plans must cover the same queries" }
        require(costByQueryId.values.all { it.isFinite() && it >= 0.0 }) { "Costs must be finite and nonnegative" }
    }

    fun requireQueryCoverage(expectedQueryIds: Set<String>): EvaluationResult {
        require(costByQueryId.keys == expectedQueryIds) { "An evaluation must cover every workload query exactly" }
        return this
    }
}

data class BudgetUsage(
    val estimatedBytes: Long,
    val budgetedBytes: Long,
    val writeUnitsPerHour: Double?,
    val indexCount: Int,
    val sizeSafetyFactor: Double = 1.2,
)

enum class BudgetViolation { STORAGE_BUDGET, WRITE_BUDGET, MAX_INDEXES }

data class BudgetAssessment(
    val usage: BudgetUsage,
    val violations: Set<BudgetViolation> = emptySet(),
) {
    val admissible: Boolean get() = violations.isEmpty()
}

data class ProfileMetrics(
    val profileId: String,
    val normalizedWeight: Double,
    val baselineCost: Double,
    val cost: Double,
    val gain: Double,
    val ratio: Double?,
    val gainPercent: Double?,
)

/** Ratios are null for zero baselines. Raw ratios are retained; comparison rounds to 9 places HALF_EVEN. */
data class SetMetrics(
    val profiles: List<ProfileMetrics>,
    val baselineMeanCost: Double,
    val meanCost: Double,
    val meanRatio: Double?,
    val worstRatio: Double?,
    val budgetUsage: BudgetUsage,
)

data class EvaluatedConfiguration(
    val evaluation: EvaluationResult,
    val metrics: SetMetrics,
) {
    init {
        val assessment = metrics.requireAdmissible()
        require(assessment.searchRequired || evaluation.candidateIds.isEmpty()) {
            "All-zero baselines may retain only the empty index set"
        }
    }
}
