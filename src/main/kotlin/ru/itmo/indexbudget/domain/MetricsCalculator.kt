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

    /** Computes every profile over the shared cost vectors. No database calls are permitted. */
    fun calculate(
        workload: WorkloadInput,
        baseline: EvaluationResult,
        evaluation: EvaluationResult,
        usage: BudgetUsage,
    ): SetMetrics
}

interface ObjectiveComparator {
    /** Full deterministic ordering: rounded ratios, budgeted bytes, optional write units, sorted IDs. */
    fun compare(left: EvaluatedConfiguration, right: EvaluatedConfiguration, objective: Objective): Int

    /** Greedy movement depends only on the first two ratio components, never just lower resource use. */
    fun improvesPrimaryMetrics(proposed: SetMetrics, current: SetMetrics, objective: Objective): Boolean
}
