package ru.itmo.indexbudget.optimizer

import ru.itmo.indexbudget.domain.CandidateBatch
import ru.itmo.indexbudget.domain.EvaluationResult
import ru.itmo.indexbudget.domain.EvaluatedConfiguration
import ru.itmo.indexbudget.domain.IndexCandidate
import ru.itmo.indexbudget.domain.MetricsCalculator
import ru.itmo.indexbudget.domain.ObjectiveComparator
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.SearchResult
import ru.itmo.indexbudget.domain.SearchDiagnostics

/** A saved pair from one evaluated pool, available even if the next external call fails. */
data class SearchCheckpoint(
    val bestRobust: EvaluatedConfiguration,
    val bestMean: EvaluatedConfiguration,
    val diagnostics: SearchDiagnostics = SearchDiagnostics(),
)

/** The only database-facing capability needed by search; implementations may also be test fixtures. */
interface ConfigurationEvaluator {
    /**
     * Evaluate the entire configuration over all shared queries. Return only a complete vector/plans,
     * with exact workload coverage, or throw a typed AnalysisException. Never calculate profile metrics.
     * Reserve one evaluation attempt at entry and record completion only after all queries succeed.
     * The database adapter also reserves each EXPLAIN attempt before sending SQL. Search owns cache
     * hits and repair attempts; a cached result does not invoke this method.
     */
    fun evaluate(
        candidates: List<IndexCandidate>,
        workload: ResolvedWorkload,
        context: RunContext,
    ): EvaluationResult
}

interface Optimizer {
    /**
     * The application supplies the complete baseline and final shortlist with known sizes.
     * Keep the run-local cache and both incumbents here; all formulas come from [metrics].
     * No Spring, JDBC, or parser types belong in optimizer implementations.
     * Before evaluating a nonempty set, use assessBudget: exclude inadmissible singletons with
     * budget CandidateDecisions, and never evaluate a configuration that violates any budget.
     * Publish both incumbents through onCheckpoint after every accepted full evaluation, before
     * starting the next operation. Enter/complete GREEDY and REPAIR phases on the shared context.
     */
    fun optimize(
        workload: ResolvedWorkload,
        candidates: CandidateBatch,
        baseline: EvaluationResult,
        evaluator: ConfigurationEvaluator,
        metrics: MetricsCalculator,
        comparator: ObjectiveComparator,
        context: RunContext,
        onCheckpoint: (SearchCheckpoint) -> Unit = {},
    ): SearchResult
}
