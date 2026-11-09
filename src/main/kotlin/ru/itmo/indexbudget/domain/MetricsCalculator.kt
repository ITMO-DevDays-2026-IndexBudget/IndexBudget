package ru.itmo.indexbudget.domain

/** Shared calculations owned by the metrics module; callers must not duplicate these formulas. */
interface MetricsCalculator {
    /** Requires known candidate sizes and a complete write profile when one is supplied. */
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
