package ru.itmo.indexbudget.optimizer

import ru.itmo.indexbudget.domain.*

/**
 * Saved greedy pool for inspecting the greedy phase. Evaluations include the supplied baseline and
 * rejected full vectors; only admissible configurations can become incumbents. Singleton vectors
 * remain available even when they did not improve the chain. All plans belong to this run.
 */
data class GreedySearchResult(
    val result: SearchResult.Completed,
    val current: EvaluatedConfiguration,
    val candidates: List<IndexCandidate>,
    val evaluations: Map<List<String>, EvaluationResult>,
    val rejectedEvaluations: Map<List<String>, EvaluationAssessment.Rejected>,
) {
    val repairStart: EvaluatedConfiguration get() = result.selected
}

/** Greedy-only entry point retained for PoC and isolated algorithm checks. */
class GreedyOptimizer : Optimizer {
    override fun optimize(
        workload: ResolvedWorkload,
        candidates: CandidateBatch,
        baseline: EvaluationResult,
        evaluator: ConfigurationEvaluator,
        metrics: MetricsCalculator,
        comparator: ObjectiveComparator,
        context: RunContext,
        onCheckpoint: (SearchCheckpoint) -> Unit,
    ): SearchResult = search(workload, candidates, baseline, evaluator, metrics, comparator, context, onCheckpoint).result

    fun search(
        workload: ResolvedWorkload,
        candidates: CandidateBatch,
        baseline: EvaluationResult,
        evaluator: ConfigurationEvaluator,
        metrics: MetricsCalculator,
        comparator: ObjectiveComparator,
        context: RunContext,
        onCheckpoint: (SearchCheckpoint) -> Unit = {},
    ): GreedySearchResult = SearchRun(workload, candidates, baseline, evaluator, metrics, comparator,
        context, onCheckpoint, recoverLocalFailures = false).greedyOnly()
}
