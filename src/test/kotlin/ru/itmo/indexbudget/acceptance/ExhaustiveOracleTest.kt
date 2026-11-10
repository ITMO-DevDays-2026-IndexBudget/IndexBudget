package ru.itmo.indexbudget.acceptance

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.ConfigurationEvaluator
import java.time.Duration

class ExhaustiveOracleTest {
    @Test
    fun `eight candidates and five indexes cover exactly 219 whole sets and 657 explains`() {
        val workload = workload(queryCount = 3, maxIndexes = 5)
        val evaluator = CountingEvaluator { ids -> vector(workload, 100.0 - ids.size) }
        val context = context()
        val result = ExhaustiveOracle().run(workload, candidates(8).reversed(), evaluator, context)

        assertEquals(RunStatus.COMPLETED, result.status)
        assertTrue(result.optimumCertifiedInFixedSpace)
        assertEquals(219, result.structuralConfigurations)
        assertEquals(219, result.outcomes.size)
        assertEquals(219, evaluator.calls.distinct().size)
        assertEquals(RunStatistics(explainAttempts = 657, evaluationAttempts = 219, completedEvaluations = 219), result.statistics)
        assertEquals(listOf("a", "b", "c", "d", "e"), result.bestRobust?.evaluation?.candidateIds)
        assertEquals(result.bestRobust, result.bestMean)
    }

    @Test
    fun `both objectives use the same exhaustive pool with independently known optima`() {
        val base = workload(queryCount = 2, maxIndexes = 1)
        val workload = base.copy(input = base.input.copy(profiles = listOf(
            Profile("normal", 9.0, mapOf("q1" to 10.0, "q2" to 0.0)),
            Profile("rare", 1.0, mapOf("q1" to 0.0, "q2" to 10.0)),
        )))
        val evaluator = CountingEvaluator { ids -> when (ids) {
            emptyList<String>() -> mapOf("q1" to 100.0, "q2" to 100.0)
            listOf("a") -> mapOf("q1" to 10.0, "q2" to 100.0)
            listOf("b") -> mapOf("q1" to 50.0, "q2" to 50.0)
            else -> error("Unexpected set")
        } }
        val result = ExhaustiveOracle().run(workload, candidates(2), evaluator, context())

        assertEquals(listOf("b"), result.bestRobust?.evaluation?.candidateIds)
        assertEquals(listOf("a"), result.bestMean?.evaluation?.candidateIds)
        assertEquals(0.5, result.bestRobust?.metrics?.worstRatio)
        assertEquals(0.19, requireNotNull(result.bestMean?.metrics?.meanRatio), 1e-12)
        assertEquals(3, evaluator.calls.size)
        assertEquals(6, result.statistics.explainAttempts)
    }

    @Test
    fun `a pair with no individual benefit is still evaluated as a whole`() {
        val workload = workload(queryCount = 1, maxIndexes = 2)
        val evaluator = CountingEvaluator { ids -> vector(workload, if (ids.size == 2) 10.0 else 100.0) }
        val result = ExhaustiveOracle().run(workload, candidates(2), evaluator, context())

        assertEquals(listOf("a", "b"), result.bestRobust?.evaluation?.candidateIds)
        assertEquals(0.1, result.bestRobust?.metrics?.worstRatio)
        assertEquals(listOf(emptyList<String>(), listOf("a"), listOf("b"), listOf("a", "b")), evaluator.calls)
    }

    @Test
    fun `budget rejection uses exact shared safety rounding and issues no explain`() {
        val original = workload(queryCount = 1, maxIndexes = 2)
        val workload = original.copy(input = original.input.copy(constraints = Constraints(12, null, 2)))
        val pool = candidates(2).map { it.copy(estimatedBytes = if (it.id == "a") 10 else 11) }
        val evaluator = CountingEvaluator { ids -> vector(workload, 100.0 - ids.size) }
        val result = ExhaustiveOracle().run(workload, pool, evaluator, context())

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf(emptyList<String>(), listOf("a")), evaluator.calls)
        assertEquals(2, result.outcomes.count { it.disposition == "budget_rejected" })
        assertEquals(12L, result.bestRobust?.metrics?.budgetUsage?.budgetedBytes)
    }

    @Test
    fun `a failed set is never an optimum and makes certification partial`() {
        val workload = workload(queryCount = 2, maxIndexes = 2)
        val evaluator = CountingEvaluator { ids ->
            if (ids == listOf("a")) throw AnalysisException(AnalysisError(ErrorCode.EXPLAIN_FAILED, "Synthetic failure"))
            vector(workload, 100.0 - 10 * ids.size)
        }
        val result = ExhaustiveOracle().run(workload, candidates(2), evaluator, context())

        assertEquals(RunStatus.PARTIAL, result.status)
        assertFalse(result.optimumCertifiedInFixedSpace)
        assertEquals(4, result.outcomes.size)
        assertEquals(1, result.outcomes.count { it.disposition == "evaluation_failed" })
        assertNull(result.outcomes.single { it.disposition == "evaluation_failed" }.evaluation)
        assertEquals(listOf("a", "b"), result.bestRobust?.evaluation?.candidateIds)
        assertEquals(4, result.statistics.evaluationAttempts)
        assertEquals(3, result.statistics.completedEvaluations)
        assertEquals(8, result.statistics.explainAttempts)
    }

    @Test
    fun `deadline preserves the last complete set without evaluating the next`() {
        var time = 0L
        val workload = workload(queryCount = 1, maxIndexes = 2)
        val evaluator = CountingEvaluator { ids ->
            if (ids.isNotEmpty()) time = Duration.ofSeconds(2).toNanos()
            vector(workload, if (ids.isEmpty()) 100.0 else 40.0)
        }
        val context = context(clock = NanoClock { time }, seconds = 1)
        val result = ExhaustiveOracle().run(workload, candidates(2), evaluator, context)

        assertEquals(RunStatus.PARTIAL, result.status)
        assertFalse(result.optimumCertifiedInFixedSpace)
        assertEquals(listOf("a"), result.bestRobust?.evaluation?.candidateIds)
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, result.errors.single().code)
        assertEquals(2, evaluator.calls.size)
        assertEquals(2, result.statistics.completedEvaluations)
    }

    @Test
    fun `explain limit preserves baseline and never starts an unreserved explain`() {
        val workload = workload(queryCount = 2, maxIndexes = 1)
        val evaluator = CountingEvaluator { ids -> vector(workload, 100.0 - ids.size) }
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofSeconds(600),
            searchTimeout = Duration.ofSeconds(600), maxExplainAttempts = 3))
        val result = ExhaustiveOracle().run(workload, candidates(1), evaluator, context)

        assertEquals(RunStatus.PARTIAL, result.status)
        assertEquals(emptyList<String>(), result.bestRobust?.evaluation?.candidateIds)
        assertEquals(3, result.statistics.explainAttempts)
        assertEquals(1, result.statistics.completedEvaluations)
        assertEquals(ErrorCode.EXPLAIN_LIMIT, result.errors.single().code)
    }

    @Test
    fun `missing full baseline cannot produce a usable partial result`() {
        val workload = workload(queryCount = 2, maxIndexes = 1)
        val evaluator = CountingEvaluator { mapOf("q1" to 100.0) }
        val result = ExhaustiveOracle().run(workload, candidates(1), evaluator, context())

        assertEquals(RunStatus.FAILED, result.status)
        assertFalse(result.optimumCertifiedInFixedSpace)
        assertNull(result.bestRobust)
        assertNull(result.bestMean)
        assertEquals(ErrorCode.EXPLAIN_FAILED, result.errors.single().code)
        assertEquals(1, evaluator.calls.size)
    }

    @Test
    fun `the dedicated oracle context can use a deadline longer than production 90 seconds`() {
        var time = 0L
        val workload = workload(queryCount = 1, maxIndexes = 1)
        val evaluator = CountingEvaluator { ids ->
            time += Duration.ofSeconds(120).toNanos()
            vector(workload, 100.0 - ids.size)
        }
        val result = ExhaustiveOracle().run(workload, candidates(1), evaluator,
            context(clock = NanoClock { time }, seconds = 600))

        assertEquals(RunStatus.COMPLETED, result.status)
        assertTrue(result.optimumCertifiedInFixedSpace)
        assertEquals(2, result.statistics.completedEvaluations)
    }

    @Test
    fun `numerical failures cannot be published as a successful partial optimum`() {
        val workload = workload(queryCount = 1, maxIndexes = 1)
        val evaluator = CountingEvaluator { ids ->
            if (ids.isNotEmpty()) throw AnalysisException(AnalysisError(ErrorCode.NUMERIC_ERROR, "Synthetic numerical failure"))
            vector(workload, 100.0)
        }
        val result = ExhaustiveOracle().run(workload, candidates(1), evaluator, context())
        assertEquals(RunStatus.FAILED, result.status)
        assertFalse(result.optimumCertifiedInFixedSpace)
        assertEquals(ErrorCode.NUMERIC_ERROR, result.errors.single().code)
    }

    @Test
    fun `final connection cleanup cannot upgrade a numerically failed run with a saved baseline`() {
        val workload = workload(queryCount = 1, maxIndexes = 1)
        val context = context()
        val evaluator = CountingEvaluator { ids ->
            if (ids.isNotEmpty()) throw AnalysisException(AnalysisError(ErrorCode.NUMERIC_ERROR, "Synthetic numerical failure"))
            vector(workload, 100.0)
        }
        val failed = ExhaustiveOracle().run(workload, candidates(1), evaluator, context)
        assertEquals(RunStatus.FAILED, failed.status)
        assertNotNull(failed.bestRobust)

        val finalized = failed.withFailure(AnalysisError(ErrorCode.DB_CONNECTION_ERROR, "Synthetic close failure"), context)
        assertEquals(RunStatus.FAILED, finalized.status)
        assertFalse(finalized.optimumCertifiedInFixedSpace)
        assertEquals(listOf(ErrorCode.NUMERIC_ERROR, ErrorCode.DB_CONNECTION_ERROR), finalized.errors.map { it.code })
    }

    @Test
    fun `suppressed connection loss stops local failure recovery and preserves only the baseline`() {
        for (primaryCode in listOf(ErrorCode.EXPLAIN_FAILED, ErrorCode.HYPOTHETICAL_INDEX_ERROR)) {
            val workload = workload(queryCount = 1, maxIndexes = 2)
            val evaluator = CountingEvaluator { ids ->
                if (ids.isNotEmpty()) throw AnalysisException(AnalysisError(primaryCode, "Synthetic local failure")).apply {
                    addSuppressed(AnalysisException(AnalysisError(ErrorCode.DB_CONNECTION_ERROR, "Synthetic cleanup connection loss")))
                }
                vector(workload, 100.0)
            }
            val result = ExhaustiveOracle().run(workload, candidates(2), evaluator, context())

            assertEquals(RunStatus.PARTIAL, result.status)
            assertFalse(result.optimumCertifiedInFixedSpace)
            assertEquals(emptyList<String>(), result.bestRobust?.evaluation?.candidateIds)
            assertEquals(2, evaluator.calls.size)
            assertEquals(listOf(primaryCode, ErrorCode.DB_CONNECTION_ERROR), result.errors.map { it.code })
            assertEquals(result.errors, result.outcomes.single { it.disposition == "evaluation_failed" }.errors)
        }
    }

    @Test
    fun `suppressed fatal cleanup failure makes the oracle failed and stops later evaluations`() {
        for (primaryCode in listOf(ErrorCode.EXPLAIN_FAILED, ErrorCode.HYPOTHETICAL_INDEX_ERROR)) {
            val workload = workload(queryCount = 1, maxIndexes = 2)
            val evaluator = CountingEvaluator { ids ->
                if (ids.isNotEmpty()) throw AnalysisException(AnalysisError(primaryCode, "Synthetic local failure")).apply {
                    addSuppressed(AnalysisException(AnalysisError(ErrorCode.NUMERIC_ERROR, "Synthetic fatal cleanup failure")))
                }
                vector(workload, 100.0)
            }
            val result = ExhaustiveOracle().run(workload, candidates(2), evaluator, context())

            assertEquals(RunStatus.FAILED, result.status)
            assertFalse(result.optimumCertifiedInFixedSpace)
            assertEquals(2, evaluator.calls.size)
            assertEquals(listOf(primaryCode, ErrorCode.NUMERIC_ERROR), result.errors.map { it.code })
            assertEquals(result.errors, result.outcomes.single { it.disposition == "evaluation_failed" }.errors)
        }
    }

    @Test
    fun `connection cleanup after a completed oracle produces partial without losing saved results`() {
        val workload = workload(queryCount = 1, maxIndexes = 1)
        val context = context()
        val evaluator = CountingEvaluator { ids -> vector(workload, 100.0 - ids.size) }
        val completed = ExhaustiveOracle().run(workload, candidates(1), evaluator, context)
        val partial = completed.withFailure(AnalysisError(ErrorCode.DB_CONNECTION_ERROR, "Synthetic close failure"), context)

        assertEquals(RunStatus.PARTIAL, partial.status)
        assertFalse(partial.optimumCertifiedInFixedSpace)
        assertEquals(completed.bestRobust, partial.bestRobust)
        assertEquals(completed.bestMean, partial.bestMean)
        assertEquals(ErrorCode.DB_CONNECTION_ERROR, partial.errors.single().code)
    }

    @Test
    fun `zero baseline regression is recorded but never compared as an optimum`() {
        val source = workload(queryCount = 2, maxIndexes = 1)
        val workload = source.copy(input = source.input.copy(profiles = listOf(
            Profile("zero", 1.0, mapOf("q1" to 1.0, "q2" to 0.0)),
            Profile("positive", 1.0, mapOf("q1" to 0.0, "q2" to 1.0)),
        )))
        val evaluator = CountingEvaluator { ids -> when (ids) {
            emptyList<String>() -> mapOf("q1" to 0.0, "q2" to 100.0)
            listOf("a") -> mapOf("q1" to 1.0, "q2" to 1.0)
            else -> mapOf("q1" to 0.0, "q2" to 50.0)
        } }
        val result = ExhaustiveOracle().run(workload, candidates(2), evaluator, context())

        assertEquals(RunStatus.COMPLETED, result.status)
        assertTrue(result.optimumCertifiedInFixedSpace)
        assertEquals(listOf("b"), result.bestRobust?.evaluation?.candidateIds)
        val rejected = result.outcomes.single { it.disposition == "evaluation_rejected" }
        assertEquals(listOf("a"), rejected.candidateIds)
        assertEquals(ErrorCode.ZERO_BASELINE_REGRESSION, rejected.errors.single().code)
        assertEquals("zero", rejected.errors.single().profileId)
    }

    @Test
    fun `all zero baselines keep the empty set and do not perform candidate explains`() {
        val workload = workload(queryCount = 1, maxIndexes = 2)
        val evaluator = CountingEvaluator { vector(workload, 0.0) }
        val result = ExhaustiveOracle().run(workload, candidates(2), evaluator, context())

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(emptyList<String>(), result.bestRobust?.evaluation?.candidateIds)
        assertEquals(1, evaluator.calls.size)
        assertNull(result.bestRobust?.metrics?.worstRatio)
        assertEquals(3, result.outcomes.count { it.disposition == "baseline_only_zero_baseline" })
    }

    @Test
    fun `invalid candidate space is rejected before evaluator work`() {
        val workload = workload(queryCount = 1, maxIndexes = 5)
        val evaluator = CountingEvaluator { vector(workload, 100.0) }
        assertThrows(IllegalArgumentException::class.java) {
            ExhaustiveOracle().run(workload, candidates(9), evaluator, context())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExhaustiveOracle().run(workload, listOf(candidates(1).single(), candidates(1).single()), evaluator, context())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExhaustiveOracle().run(workload.copy(input = workload.input.copy(constraints = Constraints(Long.MAX_VALUE, null, 6))),
                candidates(1), evaluator, context())
        }
        assertTrue(evaluator.calls.isEmpty())
    }

    @Test
    fun `the standalone program requires an explicit positive deadline`() {
        assertThrows(IllegalArgumentException::class.java) {
            OracleOptions.parse(arrayOf("--workload", "x", "--output", "y"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OracleOptions.parse(arrayOf("--workload", "x", "--output", "y", "--deadline-seconds", "0"))
        }
        assertEquals(600L, OracleOptions.parse(arrayOf("--workload", "x", "--output", "y", "--deadline-seconds", "600")).deadlineSeconds)
    }

    private fun workload(queryCount: Int, maxIndexes: Int): ResolvedWorkload {
        val queries = (1..queryCount).map { QueryInput("q$it", "SELECT a FROM public.oracle_records WHERE a = $it") }
        val input = WorkloadInput(queries, listOf(Profile("all", 1.0, queries.associate { it.id to 1.0 })),
            Constraints(Long.MAX_VALUE, null, maxIndexes))
        return ResolvedWorkload(input, queries.map { query -> ResolvedQuery(query.id, query.sql, setOf(table),
            referencedColumns = emptySet(), predicates = emptyList(), orderBy = emptyList()) })
    }

    private fun candidates(count: Int): List<IndexCandidate> = (0 until count).map { index ->
        val id = ('a'.code + index).toChar().toString()
        IndexCandidate(id, IndexDefinition(table, listOf(IndexKey(ColumnRef(table, id)))), setOf("q1"),
            setOf(GenerationReason.SINGLE_COLUMN), estimatedBytes = 10)
    }

    private fun vector(workload: ResolvedWorkload, cost: Double) = workload.queries.associate { it.id to cost }

    private fun context(clock: NanoClock = NanoClock(System::nanoTime), seconds: Long = 600) = RunContext(
        limits = RunLimits(commandTimeout = Duration.ofSeconds(seconds), searchTimeout = Duration.ofSeconds(seconds)), clock = clock,
    )

    private class CountingEvaluator(private val costs: (List<String>) -> Map<String, Double>) : ConfigurationEvaluator {
        val calls = mutableListOf<List<String>>()
        override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload, context: RunContext): EvaluationResult {
            context.reserveEvaluationAttempt()
            val ids = candidates.map { it.id }.sorted()
            calls += ids
            workload.queries.forEach { context.reserveExplainAttempt() }
            val vector = costs(ids)
            return EvaluationResult(ids, vector, vector.mapValues { (_, cost) ->
                ExplainPlan("""[{"Plan":{"Total Cost":$cost}}]""")
            }).also { context.recordCompletedEvaluation() }
        }
    }

    private companion object {
        val table = TableRef("public", "oracle_records")
    }
}
