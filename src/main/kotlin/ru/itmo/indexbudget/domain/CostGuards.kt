package ru.itmo.indexbudget.domain

/**
 * Optional per-query planner-cost guards (#78): the user marks critical SQL and sets the maximum
 * allowed selected/baseline cost ratio (>= 1.0, equality allowed). This is the single implementation
 * shared by [MetricsCalculator.assessEvaluation] admission and report rendering. It constrains
 * planner cost only — never latency or an SLA.
 */
data class CostGuardOutcome(
    val queryId: String,
    val maxRatio: Double,
    val baselineCost: Double,
    val selectedCost: Double,
    /** Null when the baseline cost is zero: the ratio stays unknown instead of dividing by zero. */
    val observedRatio: Double?,
    val zeroBaseline: Boolean,
    val violated: Boolean,
)

object CostGuards {

    /**
     * Evaluates every guard over complete cost vectors in stable query ID order.
     * A zero-baseline guard is violated by any positive selected cost and never divides by zero;
     * an unknown regression is not replaced by zero. A non-finite ratio throws NUMERIC_ERROR.
     */
    fun evaluate(guards: Map<String, Double>, baseline: EvaluationResult, evaluation: EvaluationResult): List<CostGuardOutcome> =
        guards.keys.sorted().map { queryId ->
            val maxRatio = requireNotNull(guards[queryId])
            val baselineCost = baseline.costByQueryId.getValue(queryId)
            val selectedCost = evaluation.costByQueryId.getValue(queryId)
            val zeroBaseline = baselineCost == 0.0
            val observedRatio = if (zeroBaseline) null else selectedCost / baselineCost
            if (observedRatio != null && !observedRatio.isFinite()) {
                throw AnalysisException(AnalysisError(
                    ErrorCode.NUMERIC_ERROR, "Guarded query cost ratio is not finite.", queryId = queryId,
                ))
            }
            val violated = if (zeroBaseline) selectedCost > 0.0 else requireNotNull(observedRatio) > maxRatio
            CostGuardOutcome(queryId, maxRatio, baselineCost, selectedCost, observedRatio, zeroBaseline, violated)
        }

    /** Stable per-SQL rejection diagnostics for [EvaluationAssessment.Rejected]; the set itself keeps its metrics. */
    fun violationErrors(outcomes: List<CostGuardOutcome>): List<AnalysisError> = outcomes
        .filter { it.violated }
        .map { outcome ->
            if (outcome.zeroBaseline) {
                AnalysisError(
                    ErrorCode.CRITICAL_QUERY_REGRESSION,
                    "A zero-baseline critical query must retain zero cost.",
                    queryId = outcome.queryId,
                    details = mapOf(
                        "limit" to outcome.maxRatio.toString(),
                        "baseline_cost" to outcome.baselineCost.toString(),
                        "selected_cost" to outcome.selectedCost.toString(),
                        "observed_ratio" to "unknown",
                    ),
                )
            } else {
                AnalysisError(
                    ErrorCode.CRITICAL_QUERY_REGRESSION,
                    "Critical query planner cost exceeds the allowed ratio.",
                    queryId = outcome.queryId,
                    details = mapOf(
                        "limit" to outcome.maxRatio.toString(),
                        "observed_ratio" to requireNotNull(outcome.observedRatio).toString(),
                        "baseline_cost" to outcome.baselineCost.toString(),
                        "selected_cost" to outcome.selectedCost.toString(),
                    ),
                )
            }
        }
}
