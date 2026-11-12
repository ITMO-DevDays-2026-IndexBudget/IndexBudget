package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

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
        assertEquals(130_000.0 / 310_000.0, checkNotNull(normal.ratio) { "Expected ratio to be available" }, 1e-12)
        assertEquals(100.0 * 180_000.0 / 310_000.0, checkNotNull(normal.gainPercent) { "Expected gainPercent to be available" }, 1e-9)

        val campaign = metrics.profiles.first { it.profileId == "campaign" }
        assertEquals(310_000.0, campaign.baselineCost, 1e-9)
        assertEquals(304_000.0, campaign.cost, 1e-9)
        assertEquals(304_000.0 / 310_000.0, checkNotNull(campaign.ratio) { "Expected ratio to be available" }, 1e-12)
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
        assertEquals(147_400.0 / 310_000.0, checkNotNull(metrics.meanRatio) { "Expected meanRatio to be available" }, 1e-12)
        assertEquals(304_000.0 / 310_000.0, checkNotNull(metrics.worstRatio) { "Expected worstRatio to be available" }, 1e-12)
    }

    @Test
    fun `mean ratio is not the weighted average of profile ratios for unequal baselines`() {
        val workload = workload(
            profile("p1", 0.5, 100.0, 10.0),
            profile("p2", 0.5, 10.0, 100.0),
        )
        val metrics = impl.calculate(workload, evaluation(100.0, 50.0), evaluation(40.0, 100.0), usage)

        val averageOfRatios = metrics.profiles.sumOf { it.normalizedWeight * checkNotNull(it.ratio) { "Expected ratio to be available" } }
        assertNotEquals(averageOfRatios, checkNotNull(metrics.meanRatio) { "Expected meanRatio to be available" }, 1e-9)
        assertEquals(8250.0, metrics.baselineMeanCost, 1e-9)
        assertEquals(7700.0, metrics.meanCost, 1e-9)
        assertEquals(14.0 / 15.0, metrics.meanRatio, 1e-12)
        assertEquals(26.0 / 15.0, checkNotNull(metrics.worstRatio) { "Expected worstRatio to be available" }, 1e-12)
    }

    @Test
    fun `single profile makes mean and worst ratios equal`() {
        val workload = workload(profile("only", 1.0, 3000.0, 100.0))
        val metrics = impl.calculate(workload, evaluation(100.0, 100.0), evaluation(40.0, 100.0), usage)

        assertEquals(130_000.0 / 310_000.0, checkNotNull(metrics.meanRatio) { "Expected meanRatio to be available" }, 1e-12)
        assertEquals(130_000.0 / 310_000.0, checkNotNull(metrics.worstRatio) { "Expected worstRatio to be available" }, 1e-12)
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
        assertEquals(0.5, checkNotNull(metrics.worstRatio) { "Expected worstRatio to be available" }, 1e-12)
        assertEquals(0.5, checkNotNull(metrics.meanRatio) { "Expected meanRatio to be available" }, 1e-12)
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
        assertEquals(0.0, metrics.baselineMeanCost)
        assertEquals(1500.0, metrics.meanCost)
        val first = metrics.profiles.first { it.profileId == "p1" }
        assertEquals(0.0, first.baselineCost)
        assertEquals(1000.0, first.cost)
        assertEquals(-1000.0, first.gain)
        assertNull(first.ratio)
        assertNull(first.gainPercent)
        val second = metrics.profiles.first { it.profileId == "p2" }
        assertEquals(0.0, second.baselineCost)
        assertEquals(2000.0, second.cost)
        assertEquals(-2000.0, second.gain)
        assertNull(second.ratio)
        assertNull(second.gainPercent)
    }

    @Test
    fun `baseline must cover exactly the workload query IDs`() {
        val workload = workload(profile("p", 1.0, 1.0, 1.0))
        val invalidCosts = listOf(
            mapOf("q1" to 100.0),
            mapOf("q1" to 100.0, "q2" to 100.0, "extra" to 100.0),
        )

        for (costs in invalidCosts) {
            assertThrows(IllegalArgumentException::class.java) {
                impl.calculate(workload, evaluation(costs), evaluation(50.0, 50.0), usage)
            }
        }
    }

    @Test
    fun `evaluation must cover exactly the workload query IDs`() {
        val workload = workload(profile("p", 1.0, 1.0, 1.0))
        val invalidCosts = listOf(
            mapOf("q1" to 50.0),
            mapOf("q1" to 50.0, "q2" to 50.0, "extra" to 50.0),
        )

        for (costs in invalidCosts) {
            assertThrows(IllegalArgumentException::class.java) {
                impl.calculate(workload, evaluation(100.0, 100.0), evaluation(costs), usage)
            }
        }
    }

    @Test
    fun `equally incomplete vectors cannot produce successful metrics`() {
        val workload = workload(profile("p", 1.0, 1.0, 1.0))

        assertThrows(IllegalArgumentException::class.java) {
            impl.calculate(
                workload,
                evaluation(mapOf("q1" to 100.0)),
                evaluation(mapOf("q1" to 50.0)),
                usage,
            )
        }
    }

    @ParameterizedTest(name = "query order [{0}, {1}, {2}]")
    @CsvSource("q1,q2,q3", "q1,q3,q2", "q2,q1,q3", "q2,q3,q1", "q3,q1,q2", "q3,q2,q1")
    fun `query permutations preserve baseline and evaluation sums`(first: String, second: String, third: String) {
        val order = listOf(first, second, third)
        val baseline = mapOf("q1" to 2e16, "q2" to 2.0, "q3" to 2.0)
        val costs = mapOf("q1" to 1e16, "q2" to 1.0, "q3" to 1.0)
        val workload = workload(
            Profile("p", 1.0, order.associateWith { 1.0 }),
        ).copy(queries = order.map { QueryInput(it, "SELECT 1") })

        // Малые слагаемые теряются при сложении после q1, но сохраняются при сложении до него.
        val metrics = impl.calculate(
            workload,
            evaluation(order.associateWith { baseline.getValue(it) }),
            evaluation(order.reversed().associateWith { costs.getValue(it) }),
            usage,
        )

        assertEquals(2e16, metrics.baselineMeanCost)
        assertEquals(1e16, metrics.meanCost)
        assertEquals(0.5, metrics.meanRatio)
        assertEquals(0.5, metrics.worstRatio)
        assertEquals(2e16, metrics.profiles.single().baselineCost)
        assertEquals(1e16, metrics.profiles.single().cost)
    }

    @ParameterizedTest(name = "profile order [{0}, {1}, {2}]")
    @CsvSource("0,1,2", "0,2,1", "1,0,2", "1,2,0", "2,0,1", "2,1,0")
    fun `profile permutations preserve mean sums`(first: Int, second: Int, third: Int) {
        val profiles = listOf(
            profile("a", 1.0, 3e16, 0.0),
            profile("b", 1.0, 3.0, 1.0),
            profile("c", 1.0, 3.0, 1.0),
        )
        val workload = workload(profiles[first], profiles[second], profiles[third])

        val metrics = impl.calculate(workload, evaluation(1.0, 0.0), evaluation(2.0, 0.0), usage)

        assertEquals(listOf("a", "b", "c"), metrics.profiles.map { it.profileId })
        assertEquals(1e16, metrics.baselineMeanCost)
        assertEquals(2e16, metrics.meanCost)
        assertEquals(2.0, metrics.meanRatio)
        assertEquals(2.0, metrics.worstRatio)
    }

    @Test
    fun `relative weights are normalized without weakening the worst profile`() {
        val workload = workload(
            profile("usual", 999.0, 1.0, 0.0),
            profile("rare", 1.0, 0.0, 1.0),
        )
        val metrics = impl.calculate(workload, evaluation(100.0, 100.0), evaluation(50.0, 200.0), usage)

        assertEquals(0.999, metrics.profiles.first { it.profileId == "usual" }.normalizedWeight)
        assertEquals(0.001, metrics.profiles.first { it.profileId == "rare" }.normalizedWeight)
        assertEquals(100.0, metrics.baselineMeanCost, 1e-12)
        assertEquals(50.15, metrics.meanCost, 1e-12)
        assertEquals(0.5015, checkNotNull(metrics.meanRatio) { "Expected meanRatio to be available" }, 1e-12)
        assertEquals(2.0, metrics.worstRatio)
        assertEquals(listOf(999.0, 1.0), workload.profiles.map { it.weight })
    }

    @Test
    fun `weight underflow preserves the common normalizer diagnostic`() {
        val workload = workload(
            profile("rare", Double.MIN_VALUE, 1.0, 0.0),
            profile("usual", 2.0, 0.0, 1.0),
        )
        val exception = assertThrows(AnalysisException::class.java) {
            impl.calculate(workload, evaluation(100.0, 100.0), evaluation(50.0, 50.0), usage)
        }

        assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
        assertEquals("rare", exception.error.profileId)
        assertEquals("profiles.weight", exception.error.details["field"])
    }

    @Test
    fun `overflowing profile ratio is rejected even when the mean ratio is finite`() {
        val workload = workload(
            profile("tiny", 1.0, 1.0, 0.0),
            profile("usual", 1.0, 0.0, 1.0),
        )
        val exception = assertThrows(AnalysisException::class.java) {
            impl.calculate(workload, evaluation(1e-200, 1.0), evaluation(1e200, 1.0), usage)
        }

        assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
        assertEquals("tiny", exception.error.profileId)
    }

    @Test
    fun `positive profile ratio underflow is a numeric error instead of a zero metric`() {
        val workload = workload(profile("tiny_ratio", 1.0, 1.0, 0.0))
        val exception = assertThrows(AnalysisException::class.java) {
            impl.calculate(workload, evaluation(1e200, 0.0), evaluation(1e-200, 0.0), usage)
        }
        assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
        assertEquals("tiny_ratio", exception.error.profileId)
    }

    @ParameterizedTest(name = "baseline={0}, cost={1}")
    @CsvSource("1e308,0", "1e-308,1")
    fun `overflowing percentage is rejected even when ratios are finite`(baseline: Double, cost: Double) {
        val workload = workload(profile("extreme", 1.0, 1.0, 1.0))
        val exception = assertThrows(AnalysisException::class.java) {
            impl.calculate(workload, evaluation(baseline, 0.0), evaluation(cost, 0.0), usage)
        }

        assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
        assertEquals("extreme", exception.error.profileId)
    }

    @Test
    fun `mean ratio underflow is rejected even when every profile ratio is representable`() {
        val profiles = listOf(profile("tiny", 1.0, 1e-308, 0.0), profile("large", 1.0, 0.0, 1e306))
        val baseline = evaluation(1.0, 1.0)
        val selected = evaluation(1.0, 0.0)
        assertEquals(listOf(1.0, 0.0), profiles.map {
            impl.calculate(workload(it), baseline, selected, usage).meanRatio
        })

        val exception = assertThrows(AnalysisException::class.java) {
            impl.calculate(workload(*profiles.toTypedArray()), baseline, selected, usage)
        }
        assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
        assertNull(exception.error.profileId)

        val zero = impl.calculate(workload(*profiles.toTypedArray()), baseline, evaluation(0.0, 0.0), usage)
        assertEquals(0.0, zero.meanCost)
        assertEquals(0.0, zero.meanRatio)
    }

    @Test
    fun `representable subnormal mean ratio remains available`() {
        val workload = workload(profile("tiny", 1.0, 1e-17, 0.0), profile("large", 1.0, 0.0, 1e306))
        val metrics = impl.calculate(workload, evaluation(1.0, 1.0), evaluation(1.0, 0.0), usage)
        assertEquals(1e-323, metrics.meanRatio)
        assertTrue(requireNotNull(metrics.meanRatio) > 0.0)
    }

    @Test
    fun `overflowing profile sum is a numeric error for baseline and evaluation`() {
        val workload = workload(profile("big", 1.0, 1.0, 1.0))
        val finite = evaluation(1.0, 1.0)
        val overflowing = evaluation(1e308, 1e308)

        for ((baseline, evaluation) in listOf(overflowing to finite, finite to overflowing)) {
            val exception = assertThrows(AnalysisException::class.java) {
                impl.calculate(workload, baseline, evaluation, usage)
            }
            assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
            assertEquals("big", exception.error.profileId)
        }
    }

    @Test
    fun `zero mean baseline with a positive profile baseline is a numeric error`() {
        val workload = workload(
            profile("rare", 1.0, 1.0, 0.0),
            profile("usual", 3.0, 0.0, 1.0),
        )
        val baseline = evaluation(Double.MIN_VALUE, 0.0)

        val exception = assertThrows(AnalysisException::class.java) {
            impl.calculate(workload, baseline, baseline, usage)
        }
        assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
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

    private fun workload(vararg profiles: Profile): WorkloadInput = WorkloadInput(
        schemaVersion = 2,
        queries = listOf(QueryInput("q1", "SELECT 1"), QueryInput("q2", "SELECT 2")),
        profiles = profiles.toList(),
        constraints = Constraints(storageBudgetBytes = 1000L, writeBudgetUnitsPerHour = null, maxIndexes = 5),
    )

    private fun profile(id: String, weight: Double, q1: Double, q2: Double): Profile =
        Profile(id = id, weight = weight, callsPerHour = mapOf("q1" to q1, "q2" to q2))

    private fun evaluation(q1: Double, q2: Double): EvaluationResult = evaluation(mapOf("q1" to q1, "q2" to q2))

    private fun evaluation(costs: Map<String, Double>): EvaluationResult = EvaluationResult(
        candidateIds = emptyList(),
        costByQueryId = costs,
        plansByQueryId = costs.mapValues { ExplainPlan("{}") },
    )
}
