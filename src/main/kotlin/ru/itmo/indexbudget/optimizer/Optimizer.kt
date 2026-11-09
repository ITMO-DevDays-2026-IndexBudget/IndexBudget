package ru.itmo.indexbudget.optimizer

import ru.itmo.indexbudget.domain.CandidateBatch
import ru.itmo.indexbudget.domain.EvaluationResult
import ru.itmo.indexbudget.domain.IndexCandidate
import ru.itmo.indexbudget.domain.MetricsCalculator
import ru.itmo.indexbudget.domain.ObjectiveComparator
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.SearchResult

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
     */
    fun optimize(
        workload: ResolvedWorkload,
        candidates: CandidateBatch,
        baseline: EvaluationResult,
        evaluator: ConfigurationEvaluator,
        metrics: MetricsCalculator,
        comparator: ObjectiveComparator,
        context: RunContext,
    ): SearchResult
}
