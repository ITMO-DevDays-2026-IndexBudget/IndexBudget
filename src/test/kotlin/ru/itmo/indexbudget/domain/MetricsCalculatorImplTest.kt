package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MetricsCalculatorImplTest {

    private val impl = MetricsCalculatorImpl()
    private val usage = BudgetUsage(0L, 0L, null, 1)

    @Test
    fun `computes per-profile costs gains ratios and percentages`() {
        val workload = workload(
            profile("normal", 0.9, 3000.0, 100.0),
            profile("campaign", 0.1, 100.0, 3000.0),
        )
        val metrics = impl.calculate(workload, evaluation(100.0, 100.0), evaluation(40.0, 100.0), usage)

        val normal = metrics.profiles.first { it.profileId == "normal" }
        assertEquals(310_000.0, normal.baselineCost, 1e-9)
        assertEquals(130_000.0, normal.cost, 1e-9)
        assertEquals(180_000.0, normal.gain, 1e-9)
        assertEquals(130_000.0 / 310_000.0, normal.ratio!!, 1e-12)
        assertEquals(100.0 * 180_000.0 / 310_000.0, normal.gainPercent!!, 1e-9)

        val campaign = metrics.profiles.first { it.profileId == "campaign" }
        assertEquals(310_000.0, campaign.baselineCost, 1e-9)
        assertEquals(304_000.0, campaign.cost, 1e-9)
        assertEquals(304_000.0 / 310_000.0, campaign.ratio!!, 1e-12)
    }

    @Test
    fun `computes mean and worst ratios over the shared cost vector`() {
        val workload = workload(
            profile("normal", 0.9, 3000.0, 100.0),
            profile("campaign", 0.1, 100.0, 3000.0),
        )
        val metrics = impl.calculate(workload, evaluation(100.0, 100.0), evaluation(40.0, 100.0), usage)

        assertEquals(310_000.0, metrics.baselineMeanCost, 1e-9)
        assertEquals(147_400.0, metrics.meanCost, 1e-9)
        assertEquals(147_400.0 / 310_000.0, metrics.meanRatio!!, 1e-12)
        assertEquals(304_000.0 / 310_000.0, metrics.worstRatio!!, 1e-12)
    }

    @Test
    fun `mean ratio is not the weighted average of profile ratios for unequal baselines`() {
        val workload = workload(
            profile("p1", 0.5, 100.0, 10.0),
            profile("p2", 0.5, 10.0, 100.0),
        )
        val metrics = impl.calculate(workload, evaluation(100.0, 50.0), evaluation(40.0, 100.0), usage)

        val averageOfRatios = metrics.profiles.sumOf { it.normalizedWeight * it.ratio!! }
        assertNotEquals(averageOfRatios, metrics.meanRatio!!, 1e-9)
    }

    @Test
    fun `single profile makes mean and worst ratios equal`() {
        val workload = workload(profile("only", 1.0, 3000.0, 100.0))
        val metrics = impl.calculate(workload, evaluation(100.0, 100.0), evaluation(40.0, 100.0), usage)

        assertEquals(130_000.0 / 310_000.0, metrics.meanRatio!!, 1e-12)
        assertEquals(130_000.0 / 310_000.0, metrics.worstRatio!!, 1e-12)
    }

    @Test
    fun `zero-baseline profile has null ratios and is excluded from the worst case`() {
        val workload = workload(
            profile("zero", 0.5, 100.0, 0.0),
            profile("positive", 0.5, 0.0, 100.0),
        )
        // q1 has zero baseline cost, so the zero profile has B_s = 0
        val metrics = impl.calculate(workload, evaluation(0.0, 100.0), evaluation(0.0, 50.0), usage)

        val zero = metrics.profiles.first { it.profileId == "zero" }
        assertEquals(0.0, zero.baselineCost, 1e-9)
        assertNull(zero.ratio)
        assertNull(zero.gainPercent)
        // worst case comes only from the positive-baseline profile
        assertEquals(0.5, metrics.worstRatio!!, 1e-12)
        assertEquals(0.5, metrics.meanRatio!!, 1e-12)
    }

    @Test
    fun `all-zero baselines make mean and worst ratios null`() {
        val workload = workload(
            profile("p1", 0.5, 100.0, 0.0),
            profile("p2", 0.5, 0.0, 100.0),
        )
        val metrics = impl.calculate(workload, evaluation(0.0, 0.0), evaluation(10.0, 20.0), usage)

        assertNull(metrics.meanRatio)
        assertNull(metrics.worstRatio)
    }

    @Test
    fun `overflowing cost term is a numeric error`() {
        val workload = workload(profile("big", 1.0, Double.MAX_VALUE, 0.0))
        val exception = assertThrows(AnalysisException::class.java) {
            impl.calculate(workload, evaluation(100.0, 100.0), evaluation(100.0, 100.0), usage)
        }
        assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
        assertEquals("big", exception.error.profileId)
    }

    @Test
    fun `assessBudget reports not implemented until the budget subtask`() {
        val exception = assertThrows(AnalysisException::class.java) {
            impl.assessBudget(emptyList(), workload(profile("p", 1.0, 1.0, 1.0)))
        }
        assertEquals(ErrorCode.NOT_IMPLEMENTED, exception.error.code)
    }

    private fun workload(vararg profiles: Profile): WorkloadInput = WorkloadInput(
        schemaVersion = 2,
        queries = listOf(QueryInput("q1", "SELECT 1"), QueryInput("q2", "SELECT 2")),
        profiles = profiles.toList(),
        constraints = Constraints(storageBudgetBytes = 1000L, writeBudgetUnitsPerHour = null, maxIndexes = 5),
    )

    private fun profile(id: String, weight: Double, q1: Double, q2: Double): Profile =
        Profile(id = id, weight = weight, callsPerHour = mapOf("q1" to q1, "q2" to q2))

    private fun evaluation(q1: Double, q2: Double): EvaluationResult = EvaluationResult(
        candidateIds = emptyList(),
        costByQueryId = mapOf("q1" to q1, "q2" to q2),
        plansByQueryId = mapOf("q1" to ExplainPlan("{}"), "q2" to ExplainPlan("{}")),
    )
}