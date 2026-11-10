package ru.itmo.indexbudget.domain

/** Shared calculations owned by the metrics module; callers must not duplicate these formulas. */
interface MetricsCalculator {
    /**
     * Only new candidates consume budgets; existing indexes belong to the baseline.
     * Uses estimatedBytes as the source of truth, rounding the 20% reserve per index before summing.
     * Unknown sizes throw HYPOTHETICAL_INDEX_ERROR; negative sizes and overflow throw NUMERIC_ERROR.
     * A supplied write profile must be complete (validated against all resolved tables by the pipeline).
     * Missing candidate rates or a missing profile with an enabled limit throw INVALID_WRITE_PROFILE.
     * Without a profile, write units are null even for the empty set. Cached candidate expenses are ignored.
     * Write expenses and the limit are compared exactly over the supplied Double values; only the returned
     * usage is rounded to Double. Never infer admissibility by comparing that rounded usage to the limit.
     * Zero storage/maxIndexes are baseline-only signals handled by the pipeline before generation.
     */
    fun assessBudget(candidates: List<IndexCandidate>, workload: WorkloadInput): BudgetAssessment

    /**
     * Computes raw metrics over the complete shared cost vectors, including rejected configurations.
     * Search uses [assessEvaluation] before comparison or updating incumbents. No database calls.
     */
    fun calculate(
        workload: WorkloadInput,
        baseline: EvaluationResult,
        evaluation: EvaluationResult,
        usage: BudgetUsage,
    ): SetMetrics

    /**
     * Checks post-evaluation admissibility separately from pre-EXPLAIN budgets.
     * Rejected sets retain their metrics and profile diagnostics; they do not throw AnalysisException.
     * Numerical failures from [calculate] still propagate. Assess the baseline before generating
     * candidates: Accepted.searchRequired=false requires returning that empty baseline without search.
     */
    fun assessEvaluation(
        workload: WorkloadInput,
        baseline: EvaluationResult,
        evaluation: EvaluationResult,
        usage: BudgetUsage,
    ): EvaluationAssessment = EvaluationAssessment.assess(calculate(workload, baseline, evaluation, usage))
}

interface ObjectiveComparator {
    /** Full deterministic ordering: rounded ratios, budgeted bytes, optional write units, sorted IDs. */
    fun compare(left: EvaluatedConfiguration, right: EvaluatedConfiguration, objective: Objective): Int

    /**
     * Requires admissible metrics with known ratios. Movement depends only on the first two ratio
     * components, never just lower resource use; zero-baseline runs must skip search entirely.
     */
    fun improvesPrimaryMetrics(proposed: SetMetrics, current: SetMetrics, objective: Objective): Boolean
}
