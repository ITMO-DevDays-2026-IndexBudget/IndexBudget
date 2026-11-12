package ru.itmo.indexbudget.experiments

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ColumnRef
import ru.itmo.indexbudget.domain.Constraints
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.EvaluationResult
import ru.itmo.indexbudget.domain.ExplainPlan
import ru.itmo.indexbudget.domain.GenerationReason
import ru.itmo.indexbudget.domain.IndexCandidate
import ru.itmo.indexbudget.domain.IndexDefinition
import ru.itmo.indexbudget.domain.IndexKey
import ru.itmo.indexbudget.domain.MetricsCalculatorImpl
import ru.itmo.indexbudget.domain.NanoClock
import ru.itmo.indexbudget.domain.ObjectiveComparatorImpl
import ru.itmo.indexbudget.domain.Profile
import ru.itmo.indexbudget.domain.QueryInput
import ru.itmo.indexbudget.domain.ResolvedQuery
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.RunStatistics
import ru.itmo.indexbudget.domain.TableRef
import ru.itmo.indexbudget.domain.WorkloadInput
import ru.itmo.indexbudget.fixtures.SearchScenarios
import ru.itmo.indexbudget.optimizer.ConfigurationEvaluator
import java.time.Duration

class ExhaustiveSearchOptimizerTest {
    private val metrics = MetricsCalculatorImpl()
    private val comparator = ObjectiveComparatorImpl()

    @Test
    fun `a complete enumeration verifies the independently known optimum on the mean vs robust fixture`() {
        val scenario = SearchScenarios.meanVsRobust()
        val workload = scenario.resolvedWorkload()
        val context = searchContext()
        val baseline = scenario.evaluator().evaluate(emptyList(), workload, context)

        val result = ExhaustiveSearchOptimizer().search(
            workload, scenario.candidates, baseline, scenario.evaluator(), metrics, comparator, context,
        )

        assertTrue(result.complete)
        assertTrue(result.verifiedOptimum)
        assertNull(result.stopReason)
        // Independent expectations from the scripted fixture: value wins the worst case,
        // group_id wins the expected cost.
        assertEquals(listOf("records_value"), result.bestRobust.evaluation.candidateIds)
        assertEquals(listOf("records_group_id"), result.bestMean.evaluation.candidateIds)
        assertEquals(2, result.searchSpaceSize)
        assertEquals(2, result.evaluatedSets)
        assertTrue(result.budgetExcludedSets.isEmpty())
        assertEquals(setOf(emptyList(), listOf("records_group_id"), listOf("records_value")),
            result.evaluations.keys)
        // Every subset is evaluated exactly once; the reference has no cache and no repair.
        // The scripted fixture reserves evaluation attempts only, without EXPLAIN slots.
        assertEquals(RunStatistics(evaluationAttempts = 3, completedEvaluations = 3), context.statistics())
    }

    @Test
    fun `budget inadmissible subsets are excluded before any explain and never consume attempts`() {
        val fixture = fixture(constraints = Constraints(60, null, 3))
        val workload = resolved(fixture.input)
        val context = searchContext()
        val baseline = fixture.evaluator.evaluate(emptyList(), workload, context)

        val result = ExhaustiveSearchOptimizer().search(
            workload, fixture.candidates, baseline, fixture.evaluator, metrics, comparator, context,
        )

        // i costs 48 budgeted, j and k cost 30 each: {i,j}, {i,k} and {i,j,k} exceed 60.
        assertEquals(listOf(listOf("i", "j"), listOf("i", "k"), listOf("i", "j", "k")),
            result.budgetExcludedSets)
        assertTrue(result.complete)
        assertEquals(7, result.searchSpaceSize)
        assertEquals(4, result.evaluatedSets)
        assertEquals(4, fixture.evaluator.calls.filter { it.isNotEmpty() }.size)
        // The pair {j,k} is the known admissible optimum on this fixture.
        assertEquals(listOf("j", "k"), result.bestMean.evaluation.candidateIds)
        assertEquals(listOf("j", "k"), result.bestRobust.evaluation.candidateIds)
    }

    @Test
    fun `a guard violation rejects the full vector without updating either incumbent`() {
        val input = baseInput().copy(constraints = Constraints(10_000, null, 3,
            queryCostGuards = mapOf("q1" to 1.0)))
        // Every nonempty set improves q2 but raises the guarded q1 above its baseline cost.
        val guardedVectors = mapOf(
            emptyList<String>() to mapOf("q1" to 100.0, "q2" to 100.0),
            listOf("i") to mapOf("q1" to 110.0, "q2" to 20.0),
            listOf("j") to mapOf("q1" to 120.0, "q2" to 10.0),
            listOf("k") to mapOf("q1" to 105.0, "q2" to 50.0),
            listOf("i", "j") to mapOf("q1" to 130.0, "q2" to 5.0),
            listOf("i", "k") to mapOf("q1" to 115.0, "q2" to 30.0),
            listOf("j", "k") to mapOf("q1" to 125.0, "q2" to 15.0),
            listOf("i", "j", "k") to mapOf("q1" to 140.0, "q2" to 8.0),
        )
        val fixture = fixture(input = input, vectors = guardedVectors)
        val workload = resolved(input)
        val context = searchContext()
        val baseline = fixture.evaluator.evaluate(emptyList(), workload, context)

        val result = ExhaustiveSearchOptimizer().search(
            workload, fixture.candidates, baseline, fixture.evaluator, metrics, comparator, context,
        )

        // Every set is still evaluated and the reference stays complete, but none is admissible:
        // the verified optimum is the empty baseline set.
        assertTrue(result.complete)
        assertEquals(7, result.evaluatedSets)
        assertEquals(setOf(listOf("i"), listOf("j"), listOf("k"), listOf("i", "j"), listOf("i", "k"),
            listOf("j", "k"), listOf("i", "j", "k")), result.rejectedEvaluations.keys)
        assertTrue(result.rejectedEvaluations.values.all { rejection ->
            rejection.violations.any { it.code == ErrorCode.CRITICAL_QUERY_REGRESSION && it.queryId == "q1" }
        })
        assertEquals(emptyList<String>(), result.bestRobust.evaluation.candidateIds)
        assertEquals(emptyList<String>(), result.bestMean.evaluation.candidateIds)
    }

    @Test
    fun `the own verification deadline stops enumeration without claiming an optimum`() {
        val fixture = fixture()
        val workload = resolved(fixture.input)
        val context = searchContext()
        val baseline = fixture.evaluator.evaluate(emptyList(), workload, context)
        var ticks = 0L
        val advancing = NanoClock { ticks += 1_000_000_000L; ticks } // one second per call
        val optimizer = ExhaustiveSearchOptimizer(verificationTimeout = Duration.ofSeconds(2), clock = advancing)

        val result = optimizer.search(
            workload, fixture.candidates, baseline, fixture.evaluator, metrics, comparator, context,
        )

        assertFalse(result.complete)
        assertFalse(result.verifiedOptimum)
        assertEquals(ExhaustiveSearchOptimizer.VERIFICATION_DEADLINE, result.stopReason)
        assertEquals(7, result.searchSpaceSize)
        assertTrue(result.evaluatedSets in 1..6)
        assertTrue(result.evaluatedSets < result.searchSpaceSize)
        // Saved incumbents come from the enumerated prefix only and stay usable.
        assertTrue(result.evaluations.containsKey(emptyList()))
        assertEquals(result.evaluatedSets, fixture.evaluator.calls.count { it.isNotEmpty() })
    }

    @Test
    fun `a terminal explain limit keeps saved incumbents and marks the reference incomplete`() {
        val fixture = fixture()
        val workload = resolved(fixture.input)
        val context = searchContext()
        val baseline = fixture.evaluator.evaluate(emptyList(), workload, context)
        val evaluator = object : ConfigurationEvaluator by fixture.evaluator {
            override fun evaluate(
                candidates: List<IndexCandidate>,
                workload: ResolvedWorkload,
                context: RunContext,
            ): EvaluationResult {
                if (candidates.size == 2) {
                    throw AnalysisException(AnalysisError(ErrorCode.EXPLAIN_LIMIT, "Fixture limit reached"))
                }
                return fixture.evaluator.evaluate(candidates, workload, context)
            }
        }

        val result = ExhaustiveSearchOptimizer().search(
            workload, fixture.candidates, baseline, evaluator, metrics, comparator, context,
        )

        assertFalse(result.complete)
        assertEquals("explain_limit", result.stopReason)
        // Singletons were evaluated before the terminal failure and their winner is retained.
        assertEquals(3, result.evaluatedSets)
        assertTrue(result.evaluations.keys.containsAll(listOf(listOf("i"), listOf("j"), listOf("k"))))
    }

    @Test
    fun `pool and max index caps are enforced before any evaluation`() {
        val fixture = fixture()
        val workload = resolved(fixture.input)
        val context = searchContext()
        val baseline = fixture.evaluator.evaluate(emptyList(), workload, context)
        val oversized = fixture.candidates + (1..6).map { candidate("m$it", estimatedBytes = 25L) }
        val deepInput = fixture.input.copy(constraints = Constraints(10_000, null, 6))

        assertThrows(IllegalArgumentException::class.java) {
            ExhaustiveSearchOptimizer().search(workload, oversized, baseline,
                fixture.evaluator, metrics, comparator, context)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExhaustiveSearchOptimizer().search(resolved(deepInput), fixture.candidates,
                fixture.evaluator.evaluate(emptyList(), resolved(deepInput), context),
                fixture.evaluator, metrics, comparator, context)
        }
    }

    private fun baseInput(): WorkloadInput = WorkloadInput(
        listOf(QueryInput("q1", "SELECT 1"), QueryInput("q2", "SELECT 2")),
        listOf(Profile("only", 1.0, mapOf("q1" to 1.0, "q2" to 1.0))),
        Constraints(10_000, null, 3),
    )

    private fun fixture(
        input: WorkloadInput = baseInput(),
        constraints: Constraints = input.constraints,
        vectors: Map<List<String>, Map<String, Double>> = completeVectors(),
    ): Fixture {
        val effective = input.copy(constraints = constraints)
        // Estimated sizes reproduce the budget trap: ceil(1.2*40)=48 and ceil(1.2*25)=30,
        // so any set pairing i with another candidate exceeds the 60-byte budget.
        val candidates = listOf(
            candidate("i", estimatedBytes = 40L),
            candidate("j", estimatedBytes = 25L),
            candidate("k", estimatedBytes = 25L),
        )
        return Fixture(effective, candidates, ScriptedVectorEvaluator(vectors))
    }

    /** Complete vector table for the full space of three candidates; {j,k} is the optimum and
     * no combination is the sum of individual wins. */
    private fun completeVectors(): Map<List<String>, Map<String, Double>> = mapOf(
        emptyList<String>() to mapOf("q1" to 100.0, "q2" to 100.0),
        listOf("i") to mapOf("q1" to 90.0, "q2" to 80.0),
        listOf("j") to mapOf("q1" to 95.0, "q2" to 95.0),
        listOf("k") to mapOf("q1" to 97.0, "q2" to 93.0),
        listOf("i", "j") to mapOf("q1" to 70.0, "q2" to 70.0),
        listOf("i", "k") to mapOf("q1" to 75.0, "q2" to 65.0),
        listOf("j", "k") to mapOf("q1" to 50.0, "q2" to 40.0),
        listOf("i", "j", "k") to mapOf("q1" to 60.0, "q2" to 60.0),
    )

    private fun resolved(input: WorkloadInput): ResolvedWorkload = ResolvedWorkload(input, input.queries.map {
        ResolvedQuery(it.id, it.sql, emptySet(), emptySet(), emptyList(), emptyList())
    })

    private fun searchContext(): RunContext = RunContext(clock = NanoClock { 0L })
        .also { it.startSearch() }

    private fun candidate(id: String, estimatedBytes: Long): IndexCandidate {
        val table = TableRef("public", "records")
        // assessBudget budgets ceil(1.2 * estimated); the candidate field mirrors it for reporting.
        val reserve = estimatedBytes / 5 + if (estimatedBytes % 5 == 0L) 0L else 1L
        return IndexCandidate(id,
            IndexDefinition(table, listOf(IndexKey(ColumnRef(table, "column")))),
            emptySet(), setOf(GenerationReason.SINGLE_COLUMN),
            estimatedBytes = estimatedBytes, budgetedBytes = estimatedBytes + reserve)
    }

    private data class Fixture(
        val input: WorkloadInput,
        val candidates: List<IndexCandidate>,
        val evaluator: ScriptedVectorEvaluator,
    )

    private class ScriptedVectorEvaluator(
        private val vectors: Map<List<String>, Map<String, Double>>,
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
            val costs = vectors.getValue(ids)
            costs.forEach { (query, _) -> context.reserveExplainAttempt() }
            context.recordCompletedEvaluation()
            return EvaluationResult(ids, costs, costs.mapValues { (query, cost) ->
                ExplainPlan("""{"fixture_ids":"${ids.joinToString(",")}","query":"$query","cost":$cost}""")
            })
        }
    }
}
