package ru.itmo.indexbudget.domain

/** Shared calculations owned by the metrics module; callers must not duplicate these formulas. */
interface MetricsCalculator {
    /** Requires known candidate sizes and a complete write profile when one is supplied. */
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
