package ru.itmo.indexbudget.optimizer

import ru.itmo.indexbudget.domain.*

/** One greedy chain followed by a bounded pass around its fixed best incumbent. */
class LimitedRepairOptimizer : Optimizer {
    override fun optimize(
        workload: ResolvedWorkload,
        candidates: CandidateBatch,
        baseline: EvaluationResult,
        evaluator: ConfigurationEvaluator,
        metrics: MetricsCalculator,
        comparator: ObjectiveComparator,
        context: RunContext,
        onCheckpoint: (SearchCheckpoint) -> Unit,
    ): SearchResult = SearchRun(workload, candidates, baseline, evaluator, metrics, comparator,
        context, onCheckpoint, recoverLocalFailures = true).withRepair()
}
