package ru.itmo.indexbudget.optimizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.SearchScenario
import ru.itmo.indexbudget.fixtures.SearchScenarios

class GreedyOptimizerTest {
    private val optimizer = GreedyOptimizer()
    private val metrics = MetricsCalculatorImpl()
    private val comparator = ObjectiveComparatorImpl()

    @Test
    fun `both objectives retain different incumbents from the same three evaluations`() {
        for (objective in Objective.entries) {
            val fixture = SearchScenarios.meanVsRobust()
            val search = search(fixture.copy(workload = fixture.workload.copy(objective = objective)))

            assertEquals(listOf("records_value"), search.result.bestRobust.evaluation.candidateIds)
            assertEquals(listOf("records_group_id"), search.result.bestMean.evaluation.candidateIds)
            assertEquals(282700.0, search.result.bestRobust.metrics.meanCost, 1e-8)
            assertEquals(147400.0, search.result.bestMean.metrics.meanCost, 1e-8)
            assertEquals(303000.0 / 310000.0, checkNotNull(search.result.bestRobust.metrics.worstRatio) { "Expected worstRatio to be available" }, 1e-12)
            assertEquals(304000.0 / 310000.0, checkNotNull(search.result.bestMean.metrics.worstRatio) { "Expected worstRatio to be available" }, 1e-12)
            assertEquals(objective, search.result.selectedObjective)
            assertEquals(ComparisonScope.EVALUATED_SETS, search.result.comparisonScope)
            assertSame(if (objective == Objective.ROBUST) search.result.bestRobust else search.result.bestMean,
                search.result.selected)
            assertEquals(fixture.costs.keys, search.evaluations.keys)
            assertSame(search.result.selected, search.repairStart)
        }
    }

    @Test
    fun `robust moves along a worst-ratio plateau when the mean improves`() {
        val fixture = scenario(
            candidates = listOf(candidate("A"), candidate("B")),
            costs = mapOf(
                emptyList<String>() to vector(100.0, 100.0),
                listOf("A") to vector(100.0, 80.0),
                listOf("B") to vector(90.0, 100.0),
                listOf("A", "B") to vector(100.0, 60.0),
            ),
            profiles = separateProfiles(),
        )

        val search = search(fixture)

        assertEquals(listOf("A", "B"), search.current.evaluation.candidateIds)
        assertEquals(1.0, search.current.metrics.worstRatio)
        assertEquals(0.8, search.current.metrics.meanRatio)
        assertEquals(fixture.costs.keys, search.evaluations.keys)
    }

    @Test
    fun `overlapping single benefits are not added to invent an improving pair`() {
        val fixture = SearchScenarios.overlappingBenefit()

        val search = search(fixture)

        assertEquals(listOf("records_overlap_a"), search.current.evaluation.candidateIds)
        assertEquals(search.result.bestRobust, search.result.bestMean)
        assertEquals(60.0, search.result.selected.metrics.meanCost)
        assertEquals(fixture.costs.keys, search.evaluations.keys)
        assertTrue(StopReason.NO_IMPROVEMENT in search.result.diagnostics.stopReasons)
    }

    @Test
    fun `zero-benefit singles remain available for repair without evaluating the pair greedily`() {
        val fixture = SearchScenarios.zeroToTwo()

        val search = search(fixture)

        assertTrue(search.current.evaluation.candidateIds.isEmpty())
        assertEquals(fixture.candidates.map { it.id }.toSet(), search.candidates.map { it.id }.toSet())
        assertEquals(setOf(emptyList(), listOf("records_group_id"), listOf("records_value")),
            search.evaluations.keys)
        assertTrue(search.result.diagnostics.candidateDecisions.any {
            it.reason == CandidateReason.NO_MARGINAL_IMPROVEMENT && it.candidateId == "records_group_id"
        })
    }

    @Test
    fun `negative single benefit does not hide later synergy along the selected chain`() {
        val fixture = scenario(
            candidates = listOf(candidate("A"), candidate("B")),
            costs = mapOf(
                emptyList<String>() to vector(100.0),
                listOf("A") to vector(120.0),
                listOf("B") to vector(80.0),
                listOf("A", "B") to vector(50.0),
            ),
        )

        val search = search(fixture)

        assertEquals(listOf("A", "B"), search.result.selected.evaluation.candidateIds)
        assertEquals(50.0, search.result.selected.metrics.meanCost)
        assertEquals(fixture.costs.keys, search.evaluations.keys)
    }

    @Test
    fun `repair starts from the full-key incumbent even when the last pair did not advance the chain`() {
        val fixture = scenario(
            candidates = listOf(candidate("A", bytes = 0), candidate("B", bytes = 1)),
            costs = mapOf(
                emptyList<String>() to vector(100.0),
                listOf("A") to vector(100.0),
                listOf("B") to vector(80.0),
                listOf("A", "B") to vector(80.0),
            ),
        )

        val search = search(fixture)

        assertEquals(listOf("B"), search.current.evaluation.candidateIds)
        assertEquals(listOf("A", "B"), search.repairStart.evaluation.candidateIds)
        assertEquals(search.result.bestRobust, search.result.bestMean)
        assertEquals(2L, search.repairStart.metrics.budgetUsage.budgetedBytes)
        assertSame(search.evaluations.getValue(listOf("A", "B")), search.repairStart.evaluation)
    }

    @Test
    fun `rounded primary ties choose fewer bytes even if raw cost is slightly worse`() {
        val fixture = scenario(
            candidates = listOf(candidate("A", bytes = 1), candidate("B", bytes = 2)),
            costs = mapOf(
                emptyList<String>() to vector(1.0),
                listOf("A") to vector(0.8000000005),
                listOf("B") to vector(0.8000000004),
            ),
            constraints = Constraints(100, null, 1),
        )

        val search = search(fixture)

        assertEquals(listOf("A"), search.result.selected.evaluation.candidateIds)
        assertEquals(0.8000000005, search.result.selected.metrics.meanRatio)
    }

    @Test
    fun `an improvement lost by rounding cannot advance the chain`() {
        val fixture = scenario(
            candidates = listOf(candidate("A")),
            costs = mapOf(emptyList<String>() to vector(1.0), listOf("A") to vector(0.9999999996)),
        )

        val search = search(fixture)

        assertTrue(search.current.evaluation.candidateIds.isEmpty())
        assertTrue(search.result.selected.evaluation.candidateIds.isEmpty())
        assertTrue(listOf("A") in search.evaluations)
    }

    @Test
    fun `equal cost and size prefer lower known writes before IDs`() {
        val frequent = TableRef("public", "frequent")
        val quiet = TableRef("public", "quiet")
        val fixture = scenario(
            candidates = listOf(candidate("A", table = frequent), candidate("Z", table = quiet)),
            costs = mapOf(emptyList<String>() to vector(100.0), listOf("A") to vector(80.0), listOf("Z") to vector(80.0)),
            constraints = Constraints(100, null, 1),
            rates = mapOf("public.frequent" to TableWriteRates(5.0, 0.0, 0.0),
                "public.quiet" to TableWriteRates(1.0, 0.0, 0.0)),
        )

        val search = search(fixture)

        assertEquals(listOf("Z"), search.result.selected.evaluation.candidateIds)
        assertEquals(1.0, search.result.selected.metrics.budgetUsage.writeUnitsPerHour)
    }

    @Test
    fun `equal metrics and expenses choose stable IDs independent of candidate order`() {
        val fixture = scenario(
            candidates = listOf(candidate("Z"), candidate("A")),
            costs = mapOf(emptyList<String>() to vector(100.0), listOf("A") to vector(80.0), listOf("Z") to vector(80.0)),
            constraints = Constraints(100, null, 1),
        )

        val forward = search(fixture)
        val reversed = search(fixture.copy(candidates = fixture.candidates.reversed()))

        assertEquals(listOf("A"), forward.result.selected.evaluation.candidateIds)
        assertEquals(forward.result.selected, reversed.result.selected)
        assertEquals(forward.evaluations.keys.toList(), reversed.evaluations.keys.toList())
        assertNull(forward.result.selected.metrics.budgetUsage.writeUnitsPerHour)
    }

    @Test
    fun `storage rejects singles and combinations before evaluation while retaining repair candidates`() {
        val fixture = SearchScenarios.oneToTwoNoSynergy()
        val oversized = candidate("oversized", bytes = 9)

        val search = search(fixture.copy(candidates = fixture.candidates + oversized))

        assertEquals(listOf("records_trap_a"), search.current.evaluation.candidateIds)
        assertEquals(setOf(emptyList(), listOf("records_trap_a"), listOf("records_trap_b"), listOf("records_trap_c")),
            search.evaluations.keys)
        assertEquals(fixture.candidates.map { it.id }.toSet(), search.candidates.map { it.id }.toSet())
        assertTrue(search.result.diagnostics.candidateDecisions.any {
            it.reason == CandidateReason.STORAGE_BUDGET && it.candidateId == "oversized"
        })
    }

    @Test
    fun `write budget rejects the whole pair although both singles fit`() {
        val firstTable = TableRef("public", "first")
        val secondTable = TableRef("public", "second")
        val fixture = scenario(
            candidates = listOf(candidate("A", table = firstTable), candidate("B", table = secondTable)),
            costs = mapOf(emptyList<String>() to vector(100.0), listOf("A") to vector(70.0), listOf("B") to vector(80.0)),
            constraints = Constraints(100, 6.0, 2),
            rates = mapOf("public.first" to TableWriteRates(3.0, 0.0, 0.0),
                "public.second" to TableWriteRates(4.0, 0.0, 0.0)),
        )

        val search = search(fixture)

        assertEquals(listOf("A"), search.result.selected.evaluation.candidateIds)
        assertEquals(fixture.costs.keys, search.evaluations.keys)
        assertEquals(2, search.candidates.size)
    }

    @Test
    fun `zero write budget still evaluates candidates on a table with zero writes`() {
        val quiet = TableRef("public", "quiet")
        val busy = TableRef("public", "busy")
        val fixture = scenario(
            candidates = listOf(candidate("A", table = quiet), candidate("B", table = busy)),
            costs = mapOf(emptyList<String>() to vector(100.0), listOf("A") to vector(70.0)),
            constraints = Constraints(100, 0.0, 2),
            rates = mapOf("public.quiet" to TableWriteRates(0.0, 0.0, 0.0),
                "public.busy" to TableWriteRates(0.0, 1.0, 0.0)),
        )

        val search = search(fixture)

        assertEquals(listOf("A"), search.result.selected.evaluation.candidateIds)
        assertEquals(listOf("A"), search.candidates.map { it.id })
        assertTrue(search.result.diagnostics.candidateDecisions.any {
            it.reason == CandidateReason.WRITE_BUDGET && it.candidateId == "B"
        })
    }

    @Test
    fun `zero-baseline regression is retained as rejected history but never becomes an incumbent`() {
        val fixture = scenario(
            candidates = listOf(candidate("A"), candidate("B")),
            costs = mapOf(
                emptyList<String>() to vector(0.0, 100.0),
                listOf("A") to vector(1.0, 20.0),
                listOf("B") to vector(0.0, 50.0),
                listOf("A", "B") to vector(0.0, 40.0),
            ),
            profiles = separateProfiles(),
        )

        val search = search(fixture)

        assertEquals(listOf("A", "B"), search.result.selected.evaluation.candidateIds)
        assertEquals(setOf(listOf("A")), search.rejectedEvaluations.keys)
        val violation = search.rejectedEvaluations.getValue(listOf("A")).violations.single()
        assertEquals(ErrorCode.ZERO_BASELINE_REGRESSION, violation.code)
        assertEquals("first", violation.profileId)
        assertEquals(fixture.costs.keys, search.evaluations.keys)
        assertEquals(2, search.candidates.size)
        assertNull(search.result.selected.metrics.profiles.single { it.profileId == "first" }.ratio)
    }

    @Test
    fun `baseline-only conditions do not inspect candidate sizes or compare null ratios`() {
        val cases = listOf(
            Triple(Constraints(0, null, 2), 100.0, StopReason.ZERO_STORAGE_BUDGET),
            Triple(Constraints(100, null, 0), 100.0, StopReason.ZERO_MAX_INDEXES),
            Triple(Constraints(100, null, 2), 0.0, StopReason.ZERO_BASELINE),
        )
        for ((constraints, cost, reason) in cases) {
            val fixture = scenario(
                candidates = listOf(candidate("A").copy(estimatedBytes = null)),
                costs = mapOf(emptyList<String>() to vector(cost)),
                constraints = constraints,
            )

            val search = search(fixture)

            assertTrue(search.result.selected.evaluation.candidateIds.isEmpty())
            assertEquals(setOf(emptyList<String>()), search.evaluations.keys)
            assertTrue(reason in search.result.diagnostics.stopReasons)
            if (cost == 0.0) {
                assertNull(search.result.selected.metrics.meanRatio)
                assertNull(search.result.selected.metrics.worstRatio)
            }
        }
    }

    @Test
    fun `empty shortlist returns the supplied baseline`() {
        val search = search(scenario(emptyList(), mapOf(emptyList<String>() to vector(100.0))))

        assertEquals(setOf(emptyList<String>()), search.evaluations.keys)
        assertTrue(search.candidates.isEmpty())
        assertTrue(StopReason.NO_CANDIDATES in search.result.diagnostics.stopReasons)
    }

    @Test
    fun `query candidate and index limits reject excessive inputs instead of truncating`() {
        val excessiveQueries = scenario(
            candidates = listOf(candidate("A")),
            costs = mapOf(emptyList<String>() to (1..21).associate { "q$it" to 100.0 }),
        )
        val excessiveCandidates = scenario(
            candidates = (1..17).map { candidate("i$it") },
            costs = mapOf(emptyList<String>() to vector(100.0)),
        )
        val excessiveIndexes = scenario(
            candidates = listOf(candidate("A")),
            costs = mapOf(emptyList<String>() to vector(100.0)),
            constraints = Constraints(100, null, 6),
        )
        for (fixture in listOf(excessiveQueries, excessiveCandidates, excessiveIndexes)) {
            val error = assertThrows(AnalysisException::class.java) { search(fixture) }
            assertEquals(ErrorCode.INPUT_LIMIT_EXCEEDED, error.error.code)
        }
    }

    @Test
    fun `optimizer adapter exposes the greedy result and preserves both baseline plans`() {
        val fixture = SearchScenarios.meanVsRobust()
        val context = RunContext()
        context.startSearch()
        val evaluator = fixture.evaluator()
        val resolved = fixture.resolvedWorkload()
        val baseline = evaluator.evaluate(emptyList(), resolved, context)

        val result = optimizer.optimize(resolved, CandidateBatch(fixture.candidates), baseline, evaluator,
            metrics, comparator, context)

        assertTrue(result is SearchResult.Completed)
        assertSame(baseline, result.baseline)
        assertFalse(checkNotNull(result.bestRobust) { "Expected bestRobust to be available" }.evaluation.plansByQueryId.isEmpty())
        assertFalse(checkNotNull(result.bestMean) { "Expected bestMean to be available" }.evaluation.plansByQueryId.isEmpty())
        assertEquals(ComparisonScope.EVALUATED_SETS, result.comparisonScope)
    }

    @Test
    fun `a guard rejection keeps both incumbents on the baseline with a completed baseline-only result`() {
        val fixture = scenario(
            candidates = listOf(candidate("A")),
            costs = mapOf(
                emptyList<String>() to vector(100.0, 100.0),
                listOf("A") to vector(200.0, 1.0),
            ),
            profiles = listOf(Profile("heavy", 1.0, mapOf("q1" to 1.0, "q2" to 1000.0))),
            constraints = Constraints(1000, null, 2, queryCostGuards = mapOf("q1" to 1.0)),
        )

        val search = search(fixture)

        // The mean improves ~80x, but the guarded q1 doubles: the set is excluded from both incumbents.
        val rejected = search.rejectedEvaluations.values.single()
        assertEquals(ErrorCode.CRITICAL_QUERY_REGRESSION, rejected.violations.single().code)
        assertEquals("q1", rejected.violations.single().queryId)
        assertTrue(requireNotNull(rejected.metrics.meanRatio) < 1.0)
        assertTrue(search.result is SearchResult.Completed)
        assertEquals(emptyList<String>(), search.result.bestRobust.evaluation.candidateIds)
        assertEquals(emptyList<String>(), search.result.bestMean.evaluation.candidateIds)
        assertEquals(emptyList<String>(), search.result.selected.evaluation.candidateIds)
        assertTrue(StopReason.NO_IMPROVEMENT in search.result.diagnostics.stopReasons)
        assertTrue(search.result.diagnostics.warnings.any {
            it.code == WarningCode.CANDIDATE_REJECTED && it.queryId == "q1"
        })
    }

    @Test
    fun `a guard limit at the observed ratio accepts the set and updates incumbents`() {
        val fixture = scenario(
            candidates = listOf(candidate("A")),
            costs = mapOf(
                emptyList<String>() to vector(100.0, 100.0),
                listOf("A") to vector(110.0, 1.0),
            ),
            profiles = listOf(Profile("heavy", 1.0, mapOf("q1" to 1.0, "q2" to 100.0))),
            constraints = Constraints(1000, null, 2, queryCostGuards = mapOf("q1" to 1.1)),
        )

        val search = search(fixture)

        assertTrue(search.rejectedEvaluations.isEmpty())
        assertEquals(listOf("A"), search.result.bestRobust.evaluation.candidateIds)
        assertEquals(listOf("A"), search.result.bestMean.evaluation.candidateIds)
    }

    private fun search(fixture: SearchScenario): GreedySearchResult {
        val context = RunContext()
        context.startSearch()
        val evaluator = fixture.evaluator()
        val resolved = fixture.resolvedWorkload()
        val baseline = evaluator.evaluate(emptyList(), resolved, context)
        return optimizer.search(resolved, CandidateBatch(fixture.candidates), baseline, evaluator,
            metrics, comparator, context)
    }

    private fun scenario(
        candidates: List<IndexCandidate>,
        costs: Map<List<String>, Map<String, Double>>,
        profiles: List<Profile>? = null,
        constraints: Constraints = Constraints(1000, null, 2),
        rates: Map<String, TableWriteRates>? = null,
    ): SearchScenario {
        val queryIds = costs.getValue(emptyList()).keys.sorted()
        val input = WorkloadInput(
            queries = queryIds.map { QueryInput(it, "SELECT 1") },
            profiles = profiles ?: listOf(Profile("single", 1.0, queryIds.associateWith { 1.0 })),
            constraints = constraints,
            tableWriteRates = rates,
        )
        return SearchScenario("greedy_contract", input, candidates, costs)
    }

    private fun vector(vararg costs: Double): Map<String, Double> =
        costs.mapIndexed { index, cost -> "q${index + 1}" to cost }.toMap()

    private fun separateProfiles(): List<Profile> = listOf(
        Profile("first", 1.0, mapOf("q1" to 1.0, "q2" to 0.0)),
        Profile("second", 1.0, mapOf("q1" to 0.0, "q2" to 1.0)),
    )

    private fun candidate(id: String, bytes: Long = 1, table: TableRef = TableRef("public", "records")) =
        IndexCandidate(
            id = id,
            definition = IndexDefinition(table, listOf(IndexKey(ColumnRef(table, "key_$id")))),
            sourceQueryIds = emptySet(),
            generationReasons = setOf(GenerationReason.SINGLE_COLUMN),
            estimatedBytes = bytes,
        )
}
