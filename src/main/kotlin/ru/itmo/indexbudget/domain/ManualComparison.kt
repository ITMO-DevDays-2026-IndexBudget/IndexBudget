package ru.itmo.indexbudget.domain

enum class ManualComparisonStatus(val wireValue: String) {
    NOT_EVALUATED("not_evaluated"), COMPLETED("completed"), FAILED("failed"),
}

/** A manual evaluation is deliberately not an EvaluatedConfiguration/search incumbent. */
data class ManualComparison(
    val status: ManualComparisonStatus,
    val candidates: List<IndexCandidate> = emptyList(),
    val decisions: List<CandidateDecision> = emptyList(),
    val evaluation: EvaluationResult? = null,
    val metrics: SetMetrics? = null,
    val budgetViolations: Set<BudgetViolation> = emptySet(),
    val evaluationViolations: List<AnalysisError> = emptyList(),
    val queryComparisons: List<QueryCostComparison> = emptyList(),
    val error: AnalysisError? = null,
) {
    init {
        if (status == ManualComparisonStatus.COMPLETED) {
            require(evaluation != null && metrics != null) { "Completed manual comparison requires evaluation and metrics" }
            require(evaluation.candidateIds == candidates.map { it.id }) { "Manual evaluation must describe the full set" }
            require(error == null) { "Completed manual comparison cannot have an error" }
        } else {
            require(evaluation == null && metrics == null && queryComparisons.isEmpty()) { "Incomplete comparison cannot publish costs" }
        }
        if (status == ManualComparisonStatus.FAILED) require(error != null) { "Failed comparison requires an error" }
    }

    val admissible: Boolean get() = status == ManualComparisonStatus.COMPLETED && budgetViolations.isEmpty() && evaluationViolations.isEmpty()
}
