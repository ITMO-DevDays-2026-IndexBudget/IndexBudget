package ru.itmo.indexbudget.optimizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import java.time.Duration

class GreedyOptimizerLimitsTest {
    @Test
    fun `sixteen candidates and five steps use at most 71 complete shared vectors including baseline`() {
        val workload = workload(queryCount = 20, profileCount = 3)
        val context = context()
        val evaluator = CountingEvaluator()
        val baseline = baseline(evaluator, workload, context)
        val checkpoints = mutableListOf<SearchCheckpoint>()

        val search = GreedyOptimizer().search(
            workload, CandidateBatch(candidates(16).reversed()), baseline, evaluator,
            MetricsCalculatorImpl(), ObjectiveComparatorImpl(), context, checkpoints::add,
        )

        // The five rounds have 16, 15, 14, 13 and 12 additions, regardless of profile count.
        assertEquals(71, evaluator.calls.size)
        assertEquals(71, evaluator.calls.distinct().size)
        assertEquals(mapOf(0 to 1, 1 to 16, 2 to 15, 3 to 14, 4 to 13, 5 to 12),
            evaluator.calls.groupingBy { it.size }.eachCount())
        assertEquals(RunStatistics(explainAttempts = 1420, evaluationAttempts = 71,
            completedEvaluations = 71), context.statistics())
        assertEquals(listOf("i00", "i01", "i02", "i03", "i04"), search.result.selected.evaluation.candidateIds)
        assertEquals(50.0, search.result.selected.evaluation.costByQueryId.getValue("q00"))
        assertEquals(71, search.evaluations.size)
        assertTrue(checkpoints.size >= 71)
        assertTrue(search.result.diagnostics.completedPhases.contains(AnalysisPhase.GREEDY))
        assertFalse(search.result.diagnostics.completedPhases.contains(AnalysisPhase.REPAIR))
        assertEquals(context.statistics(), search.result.diagnostics.statistics)
        assertEquals(0, context.statistics().repairAttempts)
    }

    @Test
    fun `EXPLAIN limit consumes failed attempt but preserves both last complete incumbents`() {
        val workload = workload(queryCount = 2)
        val context = context(RunLimits(maxExplainAttempts = 5))
        val evaluator = CountingEvaluator()
        val baseline = baseline(evaluator, workload, context)
        val checkpoints = mutableListOf<SearchCheckpoint>()

        val error = failure {
            optimize(workload, baseline, evaluator, context, checkpoints)
        }

        assertEquals(ErrorCode.EXPLAIN_LIMIT, error.code)
        assertEquals(RunStatistics(explainAttempts = 5, evaluationAttempts = 3,
            completedEvaluations = 2), context.statistics())
        assertEquals(listOf(emptyList(), listOf("i00"), listOf("i01")), evaluator.calls)
        assertEquals(listOf(emptyList(), listOf("i00")),
            checkpoints.map { it.bestRobust.evaluation.candidateIds }.distinct())
        assertSavedFirstCandidate(checkpoints.last())
        assertFalse(context.completedPhases().contains(AnalysisPhase.GREEDY))
    }

    @Test
    fun `full result returning after deadline is checkpointed before stopping further work`() {
        var now = 0L
        val workload = workload(queryCount = 2)
        val context = context(clock = NanoClock { now })
        val evaluator = CountingEvaluator(afterComplete = { ids, _, evaluation ->
            if (ids == listOf("i00")) now = Duration.ofSeconds(60).toNanos()
            evaluation
        })
        val baseline = baseline(evaluator, workload, context)
        val checkpoints = mutableListOf<SearchCheckpoint>()

        val error = failure {
            optimize(workload, baseline, evaluator, context, checkpoints)
        }

        assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.code)
        assertEquals(listOf(emptyList(), listOf("i00")), evaluator.calls)
        assertEquals(RunStatistics(explainAttempts = 4, evaluationAttempts = 2,
            completedEvaluations = 2), context.statistics())
        assertEquals(listOf(emptyList(), listOf("i00")),
            checkpoints.map { it.bestRobust.evaluation.candidateIds }.distinct())
        assertSavedFirstCandidate(checkpoints.last())
    }

    @Test
    fun `cancellation after a full evaluation saves it and prevents the next evaluator call`() {
        val workload = workload(queryCount = 2)
        val context = context()
        val evaluator = CountingEvaluator(afterComplete = { ids, run, evaluation ->
            if (ids == listOf("i00")) run.cancel()
            evaluation
        })
        val baseline = baseline(evaluator, workload, context)
        val checkpoints = mutableListOf<SearchCheckpoint>()

        val error = failure {
            optimize(workload, baseline, evaluator, context, checkpoints)
        }

        assertEquals(ErrorCode.CANCELLED, error.code)
        assertEquals(listOf(emptyList(), listOf("i00")), evaluator.calls)
        assertEquals(listOf(emptyList(), listOf("i00")),
            checkpoints.map { it.bestRobust.evaluation.candidateIds }.distinct())
        assertSavedFirstCandidate(checkpoints.last())
    }

    @Test
    fun `typed evaluator failure is propagated without replacing the checkpoint with an incomplete set`() {
        for (code in listOf(ErrorCode.EXPLAIN_FAILED, ErrorCode.DB_CONNECTION_ERROR)) {
            val workload = workload(queryCount = 2)
            val context = context()
            val evaluator = CountingEvaluator(onQuery = { ids, _, _ ->
                if (ids == listOf("i01")) throw AnalysisException(AnalysisError(code, "Fixture failure"))
            })
            val baseline = baseline(evaluator, workload, context)
            val checkpoints = mutableListOf<SearchCheckpoint>()

            val error = failure {
                optimize(workload, baseline, evaluator, context, checkpoints)
            }

            assertEquals(code, error.code)
            assertEquals(RunStatistics(explainAttempts = 5, evaluationAttempts = 3,
                completedEvaluations = 2), context.statistics())
            assertEquals(listOf(emptyList(), listOf("i00")),
                checkpoints.map { it.bestRobust.evaluation.candidateIds }.distinct())
            assertSavedFirstCandidate(checkpoints.last())
        }
    }

    @Test
    fun `a returned partial vector is rejected before metrics and incumbent updates`() {
        val workload = workload(queryCount = 2)
        val context = context()
        val evaluator = CountingEvaluator(afterComplete = { ids, _, evaluation ->
            if (ids == listOf("i01")) evaluation.copy(
                costByQueryId = mapOf("q00" to 0.0),
                plansByQueryId = mapOf("q00" to ExplainPlan("{}")),
            ) else evaluation
        })
        val baseline = baseline(evaluator, workload, context)
        val checkpoints = mutableListOf<SearchCheckpoint>()

        val error = failure {
            optimize(workload, baseline, evaluator, context, checkpoints)
        }

        assertEquals(ErrorCode.EXPLAIN_FAILED, error.code)
        assertEquals(listOf(emptyList(), listOf("i00")),
            checkpoints.map { it.bestRobust.evaluation.candidateIds }.distinct())
        assertSavedFirstCandidate(checkpoints.last())
    }

    @Test
    fun `a complete vector for another configuration is rejected before incumbent updates`() {
        val workload = workload(queryCount = 2)
        val context = context()
        val evaluator = CountingEvaluator(afterComplete = { ids, _, evaluation ->
            if (ids == listOf("i01")) evaluation.copy(candidateIds = listOf("i99"),
                costByQueryId = evaluation.costByQueryId.mapValues { 0.0 }) else evaluation
        })
        val baseline = baseline(evaluator, workload, context)
        val checkpoints = mutableListOf<SearchCheckpoint>()

        val error = failure {
            optimize(workload, baseline, evaluator, context, checkpoints)
        }

        assertEquals(ErrorCode.INTERNAL_ERROR, error.code)
        assertEquals(listOf(emptyList(), listOf("i00")),
            checkpoints.map { it.bestRobust.evaluation.candidateIds }.distinct())
        assertSavedFirstCandidate(checkpoints.last())
    }

    @Test
    fun `numeric failure after a good set is propagated instead of becoming a search result`() {
        val workload = workload(queryCount = 2)
        val context = context()
        val evaluator = CountingEvaluator(afterComplete = { ids, _, evaluation ->
            if (ids == listOf("i01")) evaluation.copy(
                costByQueryId = evaluation.costByQueryId.mapValues { Double.MAX_VALUE },
            ) else evaluation
        })
        val baseline = baseline(evaluator, workload, context)
        val checkpoints = mutableListOf<SearchCheckpoint>()

        val error = failure {
            optimize(workload, baseline, evaluator, context, checkpoints)
        }

        assertEquals(ErrorCode.NUMERIC_ERROR, error.code)
        assertEquals(listOf(emptyList(), listOf("i00")),
            checkpoints.map { it.bestRobust.evaluation.candidateIds }.distinct())
        assertSavedFirstCandidate(checkpoints.last())
    }

    private fun optimize(
        workload: ResolvedWorkload,
        baseline: EvaluationResult,
        evaluator: ConfigurationEvaluator,
        context: RunContext,
        checkpoints: MutableList<SearchCheckpoint>,
    ): SearchResult = GreedyOptimizer().optimize(
        workload, CandidateBatch(candidates(2)), baseline, evaluator,
        MetricsCalculatorImpl(), ObjectiveComparatorImpl(), context, checkpoints::add,
    )

    private fun assertSavedFirstCandidate(checkpoint: SearchCheckpoint) {
        assertEquals(listOf("i00"), checkpoint.bestRobust.evaluation.candidateIds)
        assertEquals(listOf("i00"), checkpoint.bestMean.evaluation.candidateIds)
        assertEquals(setOf("q00", "q01"), checkpoint.bestRobust.evaluation.costByQueryId.keys)
        assertEquals(checkpoint.bestRobust.evaluation.costByQueryId.keys,
            checkpoint.bestRobust.evaluation.plansByQueryId.keys)
        assertEquals(90.0, checkpoint.bestRobust.evaluation.costByQueryId.getValue("q00"))
    }

    private fun context(
        limits: RunLimits = RunLimits(),
        clock: NanoClock = NanoClock { 0L },
    ): RunContext = RunContext(limits = limits, clock = clock).also {
        it.startSearch()
        it.enterPhase(AnalysisPhase.BASELINE)
    }

    private fun baseline(
        evaluator: ConfigurationEvaluator,
        workload: ResolvedWorkload,
        context: RunContext,
    ): EvaluationResult = evaluator.evaluate(emptyList(), workload, context).also { context.completePhase() }

    private fun workload(queryCount: Int, profileCount: Int = 1): ResolvedWorkload {
        val queries = (0 until queryCount).map { QueryInput("q%02d".format(it), "SELECT 1") }
        val input = WorkloadInput(
            queries = queries,
            profiles = (0 until profileCount).map { profile ->
                Profile("p$profile", 1.0, queries.associate { it.id to 1.0 })
            },
            constraints = Constraints(1_000_000, null, maxIndexes = 5),
        )
        return ResolvedWorkload(input, queries.map {
            ResolvedQuery(it.id, it.sql, emptySet(), emptySet(), emptyList(), emptyList())
        })
    }

    private fun candidates(count: Int): List<IndexCandidate> {
        val table = TableRef("public", "records")
        return (0 until count).map {
            val id = "i%02d".format(it)
            IndexCandidate(id, IndexDefinition(table, listOf(IndexKey(ColumnRef(table, "column_$it")))),
                emptySet(), setOf(GenerationReason.SINGLE_COLUMN), estimatedBytes = 1)
        }
    }

    private fun failure(action: () -> Unit): AnalysisError =
        assertThrows(AnalysisException::class.java) { action() }.error

    /** Simulates one EXPLAIN per shared query; the optimizer must not reserve these counters again. */
    private class CountingEvaluator(
        private val onQuery: (List<String>, String, RunContext) -> Unit = { _, _, _ -> },
        private val afterComplete: (List<String>, RunContext, EvaluationResult) -> EvaluationResult =
            { _, _, evaluation -> evaluation },
    ) : ConfigurationEvaluator {
        val calls = mutableListOf<List<String>>()

        override fun evaluate(
            candidates: List<IndexCandidate>,
            workload: ResolvedWorkload,
            context: RunContext,
        ): EvaluationResult {
            context.reserveEvaluationAttempt()
            val ids = candidates.map { it.id }.sorted()
            calls += ids
            val costs = workload.queries.sortedBy { it.id }.associate { query ->
                context.reserveExplainAttempt()
                onQuery(ids, query.id, context)
                query.id to (100.0 - 10.0 * ids.size)
            }
            val result = EvaluationResult(ids, costs, costs.mapValues { ExplainPlan("{\"fixture\":true}") })
            context.recordCompletedEvaluation()
            return afterComplete(ids, context, result)
        }
    }
}
