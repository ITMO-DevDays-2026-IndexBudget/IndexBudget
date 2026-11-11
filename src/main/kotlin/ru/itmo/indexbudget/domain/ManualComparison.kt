package ru.itmo.indexbudget.domain

/** A manual evaluation is deliberately not an EvaluatedConfiguration/search incumbent. */
data class ManualComparison(
    val status: String,
    val candidates: List<IndexCandidate> = emptyList(),
    val decisions: List<CandidateDecision> = emptyList(),
    val evaluation: EvaluationResult? = null,
    val metrics: SetMetrics? = null,
    val budgetViolations: Set<BudgetViolation> = emptySet(),
    val evaluationViolations: List<AnalysisError> = emptyList(),
    val queryComparisons: List<QueryCostComparison> = emptyList(),
    val error: AnalysisError? = null,
) {
    val admissible: Boolean get() = status == "completed" && budgetViolations.isEmpty() && evaluationViolations.isEmpty()
}
