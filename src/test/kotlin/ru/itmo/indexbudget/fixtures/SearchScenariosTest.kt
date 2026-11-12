package ru.itmo.indexbudget.fixtures

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.IndexCandidate
import ru.itmo.indexbudget.domain.ResolvedQuery
import ru.itmo.indexbudget.domain.RunContext
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Validates the scripted search scenarios: each one encodes the intended objective behavior, and the
 * fake evaluator is deterministic and coverage-safe. The reference metric computation below exists
 * only to check fixture construction and is not the product implementation of the formulas (that
 * belongs to MetricsCalculator/ObjectiveComparator).
 */
class SearchScenariosTest {

    @Test
    fun `every scenario candidate carries consistent estimated and budgeted sizes`() {
        for (scenario in allScenarios()) {
            for (candidate in scenario.candidates) {
                val estimated = candidate.estimatedBytes
                assertTrue(estimated != null, "${scenario.name}: ${candidate.id} missing estimated size")
                assertEquals(
                    expectedBudgeted(checkNotNull(estimated) { "Expected estimated to be available" }),
                    candidate.budgetedBytes,
                    "${scenario.name}: ${candidate.id} budgeted must be ceil(1.2 * estimated)",
                )
            }
        }
    }

    @Test
    fun `mean vs robust pick different single sets and expose a positive price of robustness`() {
        val scenario = SearchScenarios.meanVsRobust()
        val group = scenario.metricsFor(listOf("records_group_id"))
        val value = scenario.metricsFor(listOf("records_value"))

        assertTrue(round9(value.worstRatio) < round9(group.worstRatio), "robust must prefer the lower worst case")
        assertTrue(round9(group.meanRatio) < round9(value.meanRatio), "mean must prefer the lower expected cost")
        assertTrue(round9(value.meanRatio) > round9(group.meanRatio), "the robust pick must cost something on the mean")
    }

    @Test
    fun `plateau keeps the worst ratio identical while the mean improves`() {
        val scenario = SearchScenarios.plateau()
        val worse = scenario.metricsFor(listOf("records_plateau_worse"))
        val better = scenario.metricsFor(listOf("records_plateau_better"))

        assertEquals(0.0, kotlin.math.abs(better.worstRatio - worse.worstRatio), 1e-15)
        assertEquals(0, round9(better.worstRatio).compareTo(round9(worse.worstRatio)))
        assertTrue(round9(better.meanRatio) < round9(worse.meanRatio))
    }

    @Test
    fun `zero to two no single helps but the pair does`() {
        val scenario = SearchScenarios.zeroToTwo()
        val group = scenario.metricsFor(listOf("records_group_id"))
        val value = scenario.metricsFor(listOf("records_value"))
        val pair = scenario.metricsFor(listOf("records_group_id", "records_value"))

        for (single in listOf(group, value)) {
            assertEquals(0, round9(single.worstRatio).compareTo(BigDecimal.ONE))
            assertEquals(0, round9(single.meanRatio).compareTo(BigDecimal.ONE))
        }
        assertTrue(round9(pair.worstRatio) < round9(group.worstRatio))
        assertTrue(round9(pair.meanRatio) < round9(group.meanRatio))
    }

    @Test
    fun `one to two replacing the best single with an admissible pair is the only improving move`() {
        val scenario = SearchScenarios.oneToTwo()
        val storageBudget = scenario.workload.constraints.storageBudgetBytes
        val maxIndexes = scenario.workload.constraints.maxIndexes
        val i = scenario.metricsFor(listOf("records_budget_i"))
        val j = scenario.metricsFor(listOf("records_budget_j"))
        val k = scenario.metricsFor(listOf("records_budget_k"))
        val pair = scenario.metricsFor(listOf("records_budget_j", "records_budget_k"))
        fun budgetedSize(ids: List<String>) =
            ids.sumOf { id -> checkNotNull(scenario.candidates.first { it.id == id }.budgetedBytes) { "Expected budgetedBytes to be available" } }

        // i is the best single
        assertTrue(round9(i.worstRatio) < round9(j.worstRatio))
        assertTrue(round9(j.worstRatio) < round9(k.worstRatio))

        // the greedy single i and the pair are admissible under the scenario constraints
        assertTrue(budgetedSize(listOf("records_budget_i")) <= storageBudget)
        assertTrue(budgetedSize(listOf("records_budget_j", "records_budget_k")) <= storageBudget)
        assertTrue(listOf("records_budget_i").size <= maxIndexes)
        assertTrue(listOf("records_budget_j", "records_budget_k").size <= maxIndexes)

        // extending i with any single is inadmissible
        assertTrue(budgetedSize(listOf("records_budget_i", "records_budget_j")) > storageBudget)
        assertTrue(budgetedSize(listOf("records_budget_i", "records_budget_k")) > storageBudget)
        assertTrue(budgetedSize(listOf("records_budget_i", "records_budget_j", "records_budget_k")) > storageBudget)

        // the admissible pair strictly beats the greedy single
        assertTrue(round9(pair.worstRatio) < round9(i.worstRatio))
        assertTrue(round9(pair.meanRatio) < round9(i.meanRatio))

        // every single swap is worse than keeping i
        assertTrue(round9(i.worstRatio) < round9(j.worstRatio))
        assertTrue(round9(i.worstRatio) < round9(k.worstRatio))
    }

    @Test
    fun `one to two without synergy reproduces the required budget example`() {
        val scenario = SearchScenarios.oneToTwoNoSynergy()
        val budget = scenario.workload.constraints.storageBudgetBytes
        val maxIndexes = scenario.workload.constraints.maxIndexes
        fun size(ids: List<String>) =
            ids.sumOf { id -> checkNotNull(scenario.candidates.first { it.id == id }.budgetedBytes) { "Expected budgetedBytes to be available" } }

        // budgeted sizes A=6, B=5, C=5 against budget 10
        assertEquals(6L, size(listOf("records_trap_a")))
        assertEquals(5L, size(listOf("records_trap_b")))
        assertEquals(5L, size(listOf("records_trap_c")))
        assertTrue(size(listOf("records_trap_a")) <= budget)
        assertTrue(size(listOf("records_trap_b", "records_trap_c")) <= budget)
        assertTrue(listOf("records_trap_a").size <= maxIndexes)
        assertTrue(listOf("records_trap_b", "records_trap_c").size <= maxIndexes)
        assertTrue(size(listOf("records_trap_a", "records_trap_b")) > budget)
        assertTrue(size(listOf("records_trap_a", "records_trap_c")) > budget)

        // gains A=12, B=9.5, C=9.5 and no synergy in the pair {B,C}=19
        assertEquals(12.0, scenario.gainOf(listOf("records_trap_a")), 1e-9)
        assertEquals(9.5, scenario.gainOf(listOf("records_trap_b")), 1e-9)
        assertEquals(9.5, scenario.gainOf(listOf("records_trap_c")), 1e-9)
        assertEquals(19.0, scenario.gainOf(listOf("records_trap_b", "records_trap_c")), 1e-9)
        assertEquals(
            scenario.gainOf(listOf("records_trap_b")) + scenario.gainOf(listOf("records_trap_c")),
            scenario.gainOf(listOf("records_trap_b", "records_trap_c")),
            1e-9,
        )

        // A is the best single, yet the admissible pair without it is strictly better
        assertTrue(scenario.gainOf(listOf("records_trap_a")) > scenario.gainOf(listOf("records_trap_b")))
        assertTrue(scenario.gainOf(listOf("records_trap_b", "records_trap_c")) > scenario.gainOf(listOf("records_trap_a")))
    }

    @Test
    fun `overlapping benefit adds no marginal improvement after the best single`() {
        val scenario = SearchScenarios.overlappingBenefit()
        val a = scenario.metricsFor(listOf("records_overlap_a"))
        val b = scenario.metricsFor(listOf("records_overlap_b"))
        val pair = scenario.metricsFor(listOf("records_overlap_a", "records_overlap_b"))

        // both singles improve the baseline
        assertTrue(round9(a.meanRatio) < BigDecimal.ONE)
        assertTrue(round9(b.meanRatio) < BigDecimal.ONE)
        // a is the better single, but the pair is no better than a: no marginal improvement
        assertTrue(round9(a.meanRatio) < round9(b.meanRatio))
        assertEquals(0, round9(pair.meanRatio).compareTo(round9(a.meanRatio)))
        assertEquals(0, round9(pair.worstRatio).compareTo(round9(a.worstRatio)))
    }

    @Test
    fun `scripted evaluator returns the baseline for the empty set`() {
        val scenario = SearchScenarios.meanVsRobust()
        val context = RunContext()
        val result = scenario.evaluator().evaluate(emptyList(), scenario.resolvedWorkload(), context)

        assertTrue(result.candidateIds.isEmpty())
        assertEquals(scenario.costs.getValue(emptyList()), result.costByQueryId)
    }

    @Test
    fun `scripted evaluator returns a scripted vector and records completion`() {
        val scenario = SearchScenarios.meanVsRobust()
        val context = RunContext()
        val candidates = scenario.byIds("records_group_id")

        val result = scenario.evaluator().evaluate(candidates, scenario.resolvedWorkload(), context)

        assertEquals(listOf("records_group_id"), result.candidateIds)
        assertEquals(mapOf("records_by_group" to 40.0, "records_by_value" to 100.0), result.costByQueryId)
        assertEquals(1, context.statistics().evaluationAttempts)
        assertEquals(1, context.statistics().completedEvaluations)
    }

    @Test
    fun `scripted evaluator is deterministic regardless of candidate order`() {
        val scenario = SearchScenarios.zeroToTwo()
        val context = RunContext()
        val evaluator = scenario.evaluator()
        val group = scenario.byIds("records_group_id").single()
        val value = scenario.byIds("records_value").single()

        val forward = evaluator.evaluate(listOf(group, value), scenario.resolvedWorkload(), context)
        val backward = evaluator.evaluate(listOf(value, group), scenario.resolvedWorkload(), context)

        assertEquals(forward.candidateIds, backward.candidateIds)
        assertEquals(forward.costByQueryId, backward.costByQueryId)
        assertEquals(2, context.statistics().evaluationAttempts)
        assertEquals(2, context.statistics().completedEvaluations)
    }

    @Test
    fun `scripted evaluator is reusable with the same context`() {
        val scenario = SearchScenarios.meanVsRobust()
        val context = RunContext()
        val evaluator = scenario.evaluator()
        val candidates = scenario.byIds("records_group_id")

        val first = evaluator.evaluate(candidates, scenario.resolvedWorkload(), context)
        val second = evaluator.evaluate(candidates, scenario.resolvedWorkload(), context)

        assertEquals(first.costByQueryId, second.costByQueryId)
        assertEquals(2, context.statistics().evaluationAttempts)
        assertEquals(2, context.statistics().completedEvaluations)
    }

    @Test
    fun `scripted evaluator rejects a workload with mismatched query IDs without recording completion`() {
        val scenario = SearchScenarios.meanVsRobust()
        val context = RunContext()
        val evaluator = scenario.evaluator()
        val mismatched = scenario.resolvedWorkload().copy(
            queries = scenario.resolvedWorkload().queries +
                ResolvedQuery("extra", "SELECT 1", emptySet(), emptySet(), emptyList(), emptyList()),
        )

        assertThrows(IllegalArgumentException::class.java) {
            evaluator.evaluate(scenario.byIds("records_group_id"), mismatched, context)
        }

        // the failed coverage check consumes an attempt but records no completion
        assertEquals(1, context.statistics().evaluationAttempts)
        assertEquals(0, context.statistics().completedEvaluations)
        assertEquals(0, context.statistics().explainAttempts)
        assertEquals(0, context.statistics().repairAttempts)
        assertEquals(0, context.statistics().cacheHits)
    }

    @Test
    fun `scripted evaluator rejects an unscripted set with a typed error`() {
        val scenario = SearchScenarios.meanVsRobust()
        val context = RunContext()
        val candidates = scenario.byIds("records_group_id", "records_value")

        val exception = assertThrows(AnalysisException::class.java) {
            scenario.evaluator().evaluate(candidates, scenario.resolvedWorkload(), context)
        }

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.error.code)
        assertEquals(1, context.statistics().evaluationAttempts)
        assertEquals(0, context.statistics().completedEvaluations)
    }

    private fun allScenarios(): List<SearchScenario> = listOf(
        SearchScenarios.meanVsRobust(),
        SearchScenarios.plateau(),
        SearchScenarios.zeroToTwo(),
        SearchScenarios.oneToTwo(),
        SearchScenarios.oneToTwoNoSynergy(),
        SearchScenarios.overlappingBenefit(),
    )

    private fun SearchScenario.metricsFor(ids: List<String>): ReferenceMetrics =
        referenceMetrics(this, costs.getValue(ids.sorted()))

    private fun SearchScenario.byIds(vararg ids: String): List<IndexCandidate> =
        ids.map { id -> candidates.first { it.id == id } }

    private fun SearchScenario.gainOf(ids: List<String>): Double {
        val baselineCost = costs.getValue(emptyList()).values.single()
        val cost = costs.getValue(ids.sorted()).values.single()
        return baselineCost - cost
    }

    private fun referenceMetrics(scenario: SearchScenario, costs: Map<String, Double>): ReferenceMetrics {
        val workload = scenario.workload
        val weightSum = workload.profiles.sumOf { it.weight }
        val baseline = scenario.costs.getValue(emptyList())
        val ratios = LinkedHashMap<String, Double>()
        var cMean = 0.0
        var bMean = 0.0
        for (profile in workload.profiles) {
            val frequency = profile.callsPerHour
            val cost = workload.queries.sumOf { q -> frequency.getValue(q.id) * costs.getValue(q.id) }
            val base = workload.queries.sumOf { q -> frequency.getValue(q.id) * baseline.getValue(q.id) }
            ratios[profile.id] = cost / base
            val p = profile.weight / weightSum
            cMean += p * cost
            bMean += p * base
        }
        return ReferenceMetrics(ratios, cMean / bMean, checkNotNull(ratios.values.maxOrNull()) { "Expected maxOrNull to be available" })
    }

    private fun expectedBudgeted(estimated: Long): Long =
        BigDecimal.valueOf(estimated).multiply(BigDecimal("1.2")).setScale(0, RoundingMode.CEILING).toLong()

    private fun round9(value: Double): BigDecimal =
        BigDecimal.valueOf(value).setScale(9, RoundingMode.HALF_EVEN)

    private data class ReferenceMetrics(
        val ratios: Map<String, Double>,
        val meanRatio: Double,
        val worstRatio: Double,
    )
}
