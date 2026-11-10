package ru.itmo.indexbudget.fixtures

import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.EvaluationResult
import ru.itmo.indexbudget.domain.ExplainPlan
import ru.itmo.indexbudget.domain.IndexCandidate
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.optimizer.ConfigurationEvaluator

/**
 * Deterministic fake [ConfigurationEvaluator] for search tests before the HypoPG adapter is connected.
 *
 * A [script] maps a sorted candidate-ID list to a full cost-by-query vector; the empty list is the
 * baseline. Every vector must cover exactly the same query IDs. An unscripted set throws a typed
 * [AnalysisException] so a test can never silently guess a cost.
 *
 * Bookkeeping follows the [ConfigurationEvaluator] contract: one evaluation attempt is reserved on
 * entry and completion is recorded only after the whole vector succeeds. EXPLAIN attempts belong to
 * the PostgreSQL adapter, so this fake does not reserve them.
 */
class ScriptedEvaluator(
    private val script: Map<List<String>, Map<String, Double>>,
) : ConfigurationEvaluator {

    private val queryIds: Set<String> = requireNotNull(script.values.firstOrNull()?.keys) {
        "A script must define at least one cost vector"
    }

    init {
        require(script.values.all { it.keys == queryIds }) {
            "Every scripted vector must cover exactly the same query IDs"
        }
        require(script.keys.all { it == it.distinct().sorted() }) {
            "Script keys must be sorted, distinct candidate-ID lists"
        }
        require(script.containsKey(emptyList())) {
            "A script must define the baseline cost vector for the empty set"
        }
    }

    override fun evaluate(
        candidates: List<IndexCandidate>,
        workload: ResolvedWorkload,
        context: RunContext,
    ): EvaluationResult {
        context.reserveEvaluationAttempt()
        val ids = candidates.map { it.id }.distinct().sorted()
        val costs = script[ids] ?: throw AnalysisException(
            AnalysisError(
                ErrorCode.INTERNAL_ERROR,
                "No scripted costs for candidate set $ids",
            ),
        )
        val plans = costs.mapValues { (_, cost) ->
            ExplainPlan("""[{"Plan":{"Node Type":"Fixture Scan","Total Cost":$cost}}]""")
        }
        val result = EvaluationResult(ids, costs, plans)
            .requireQueryCoverage(workload.queries.map { it.id }.toSet())
        context.recordCompletedEvaluation()
        return result
    }
}