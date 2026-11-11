package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.optimizer.SearchCheckpoint

class EvaluationAssessmentTest {

    private val calculator = MetricsCalculatorImpl()
    private val comparator = ObjectiveComparatorImpl()
    private val emptyUsage = BudgetUsage(0L, 0L, null, 0)
    private val candidateUsage = BudgetUsage(100L, 120L, null, 1)

    @Test
    fun `unchanged zero profile is admissible alongside an improved positive profile`() {
        val workload = workload(
            profile("zero", 1.0, 2.0, 0.0),
            profile("positive", 1.0, 0.0, 3.0),
        )
        val candidate = evaluation(0.0, 50.0, listOf("idx_a"))

        val assessment = calculator.assessEvaluation(workload, evaluation(0.0, 100.0), candidate, candidateUsage)
        val accepted = assertInstanceOf(EvaluationAssessment.Accepted::class.java, assessment)

        assertTrue(accepted.searchRequired)
        assertEquals(150.0, accepted.metrics.baselineMeanCost)
        assertEquals(75.0, accepted.metrics.meanCost)
        assertEquals(0.5, accepted.metrics.meanRatio)
        assertEquals(0.5, accepted.metrics.worstRatio)
        val zero = accepted.metrics.profiles.single { it.profileId == "zero" }
        assertEquals(0.0, zero.baselineCost)
        assertEquals(0.0, zero.cost)
        assertEquals(0.0, zero.gain)
        assertNull(zero.ratio)
        assertNull(zero.gainPercent)
        assertEquals(listOf("idx_a"), EvaluatedConfiguration(candidate, accepted.metrics).evaluation.candidateIds)
    }

    @Test
    fun `a low weight zero profile regression rejects a better mean and worst ratio`() {
        val workload = workload(
            profile("zero", 1.0, 1.0, 0.0),
            profile("positive", 999.0, 0.0, 1.0),
        )

        val assessment = calculator.assessEvaluation(
            workload,
            evaluation(0.0, 100.0),
            evaluation(1.0, 10.0, listOf("idx_a")),
            candidateUsage,
        )
        val rejected = assertInstanceOf(EvaluationAssessment.Rejected::class.java, assessment)

        assertEquals(listOf(ErrorCode.ZERO_BASELINE_REGRESSION), rejected.violations.map { it.code })
        assertEquals(listOf("zero"), rejected.violations.map { it.profileId })
        assertEquals(99.9, rejected.metrics.baselineMeanCost, 1e-12)
        assertEquals(9.991, rejected.metrics.meanCost, 1e-12)
        assertTrue(requireNotNull(rejected.metrics.meanRatio) < 1.0)
        assertEquals(0.1, rejected.metrics.worstRatio)
        val zero = rejected.metrics.profiles.single { it.profileId == "zero" }
        assertEquals(0.0, zero.baselineCost)
        assertEquals(1.0, zero.cost)
        assertEquals(-1.0, zero.gain)
        assertNull(zero.ratio)
        assertNull(zero.gainPercent)
    }

    @Test
    fun `the smallest positive zero-profile cost is still a regression`() {
        val assessment = calculator.assessEvaluation(
            workload(profile("zero", 1.0, 1.0, 0.0), profile("positive", 1.0, 0.0, 1.0)),
            evaluation(0.0, 100.0),
            evaluation(Double.MIN_VALUE, 50.0, listOf("idx_a")),
            candidateUsage,
        )
        val rejected = assertInstanceOf(EvaluationAssessment.Rejected::class.java, assessment)

        assertEquals(ErrorCode.ZERO_BASELINE_REGRESSION, rejected.violations.single().code)
        assertEquals("zero", rejected.violations.single().profileId)
        assertEquals(Double.MIN_VALUE, rejected.metrics.profiles.single { it.profileId == "zero" }.cost)
        assertEquals(0.5, rejected.metrics.meanRatio)
        assertEquals(0.5, rejected.metrics.worstRatio)
    }

    @Test
    fun `every regressing zero profile is diagnosed in stable id order`() {
        val profiles = listOf(
            profile("z_zero", 1.0, 3.0, 0.0),
            profile("positive", 1.0, 0.0, 1.0),
            profile("a_zero", 1.0, 1.0, 0.0),
        )

        for (orderedProfiles in listOf(profiles, profiles.reversed())) {
            val assessment = calculator.assessEvaluation(
                workload(*orderedProfiles.toTypedArray()),
                evaluation(0.0, 100.0),
                evaluation(2.0, 50.0, listOf("idx_a")),
                candidateUsage,
            )
            val rejected = assertInstanceOf(EvaluationAssessment.Rejected::class.java, assessment)

            assertEquals(listOf("a_zero", "z_zero"), rejected.violations.map { it.profileId })
            assertTrue(rejected.violations.all { it.code == ErrorCode.ZERO_BASELINE_REGRESSION })
            assertEquals(2.0, rejected.metrics.profiles.single { it.profileId == "a_zero" }.cost)
            assertEquals(6.0, rejected.metrics.profiles.single { it.profileId == "z_zero" }.cost)
        }
    }

    @Test
    fun `all zero baseline is accepted without search and can be retained in both incumbents`() {
        val workload = workload(
            profile("first", 1.0, 1.0, 0.0),
            profile("second", 1.0, 0.0, 2.0),
        )
        val baseline = evaluation(0.0, 0.0)
        val assessment = calculator.assessEvaluation(workload, baseline, baseline, emptyUsage)
        val accepted = assertInstanceOf(EvaluationAssessment.Accepted::class.java, assessment)

        assertFalse(accepted.searchRequired)
        assertEquals(0.0, accepted.metrics.baselineMeanCost)
        assertEquals(0.0, accepted.metrics.meanCost)
        assertNull(accepted.metrics.meanRatio)
        assertNull(accepted.metrics.worstRatio)
        for (profile in accepted.metrics.profiles) {
            assertEquals(0.0, profile.baselineCost)
            assertEquals(0.0, profile.cost)
            assertEquals(0.0, profile.gain)
            assertNull(profile.ratio)
            assertNull(profile.gainPercent)
        }
        val configuration = EvaluatedConfiguration(baseline, accepted.metrics)
        val checkpoint = SearchCheckpoint(configuration, configuration)
        for (objective in Objective.entries) {
            val result = SearchResult.Completed(objective, baseline, checkpoint.bestRobust, checkpoint.bestMean)
            assertSame(configuration, result.selected)
            assertTrue(result.selected.evaluation.candidateIds.isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                comparator.compare(configuration, configuration, objective)
            }
        }
    }

    @Test
    fun `all zero baseline does not accept positive cost as a no-search result`() {
        val assessment = calculator.assessEvaluation(
            workload(profile("first", 1.0, 1.0, 0.0), profile("second", 1.0, 0.0, 2.0)),
            evaluation(0.0, 0.0),
            evaluation(3.0, 0.0, listOf("idx_a")),
            candidateUsage,
        )
        val rejected = assertInstanceOf(EvaluationAssessment.Rejected::class.java, assessment)

        assertEquals(listOf("first"), rejected.violations.map { it.profileId })
        assertEquals(ErrorCode.ZERO_BASELINE_REGRESSION, rejected.violations.single().code)
        assertEquals(1.5, rejected.metrics.meanCost)
        assertNull(rejected.metrics.meanRatio)
        assertNull(rejected.metrics.worstRatio)
    }

    @Test
    fun `all zero metrics cannot turn a nonempty set into an incumbent`() {
        val candidate = evaluation(0.0, 0.0, listOf("idx_a"))
        val assessment = calculator.assessEvaluation(
            workload(profile("zero", 1.0, 1.0, 1.0)),
            evaluation(0.0, 0.0),
            candidate,
            candidateUsage,
        )
        val accepted = assertInstanceOf(EvaluationAssessment.Accepted::class.java, assessment)

        assertFalse(accepted.searchRequired)
        assertThrows(IllegalArgumentException::class.java) {
            EvaluatedConfiguration(candidate, accepted.metrics)
        }
    }

    @Test
    fun `rejected metrics cannot replace saved checkpoints or either incumbent`() {
        val workload = workload(profile("zero", 1.0, 1.0, 0.0), profile("positive", 1.0, 0.0, 1.0))
        val baseline = evaluation(0.0, 100.0)
        val candidate = evaluation(1.0, 10.0, listOf("idx_a"))
        val baselineAssessment = calculator.assessEvaluation(workload, baseline, baseline, emptyUsage)
        val baselineConfiguration = EvaluatedConfiguration(baseline, baselineAssessment.metrics)
        val assessment = calculator.assessEvaluation(workload, baseline, candidate, candidateUsage)
        val rejected = assertInstanceOf(EvaluationAssessment.Rejected::class.java, assessment)
        val savedCheckpoint = SearchCheckpoint(baselineConfiguration, baselineConfiguration)
        var checkpoint = savedCheckpoint

        assertThrows(IllegalArgumentException::class.java) {
            val invalidConfiguration = EvaluatedConfiguration(candidate, rejected.metrics)
            checkpoint = SearchCheckpoint(invalidConfiguration, invalidConfiguration)
        }

        assertSame(savedCheckpoint, checkpoint)
        assertSame(baselineConfiguration, checkpoint.bestRobust)
        assertSame(baselineConfiguration, checkpoint.bestMean)
        for (objective in Objective.entries) {
            val result = SearchResult.Completed(objective, baseline, checkpoint.bestRobust, checkpoint.bestMean)
            assertSame(baselineConfiguration, result.selected)
        }
    }

    @Test
    fun `greedy gate rejects a zero regression on either side before comparing ratios`() {
        val workload = workload(profile("zero", 1.0, 1.0, 0.0), profile("positive", 1.0, 0.0, 1.0))
        val baseline = evaluation(0.0, 100.0)
        val current = calculator.assessEvaluation(workload, baseline, baseline, emptyUsage).metrics
        val rejected = calculator.assessEvaluation(
            workload,
            baseline,
            evaluation(1.0, 10.0, listOf("idx_a")),
            candidateUsage,
        ).metrics

        for (objective in Objective.entries) {
            assertThrows(IllegalArgumentException::class.java) {
                comparator.improvesPrimaryMetrics(rejected, current, objective)
            }
            assertThrows(IllegalArgumentException::class.java) {
                comparator.improvesPrimaryMetrics(current, rejected, objective)
            }
        }
    }

    @Test
    fun `numeric overflow remains an error even when another zero profile regresses`() {
        val workload = workload(
            profile("zero", 1.0, 1.0, 0.0),
            profile("overflow", 1.0, 0.0, 2.0),
        )

        val exception = assertThrows(AnalysisException::class.java) {
            calculator.assessEvaluation(
                workload,
                evaluation(0.0, 1.0),
                evaluation(1.0, 1e308, listOf("idx_a")),
                candidateUsage,
            )
        }

        assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
        assertEquals("overflow", exception.error.profileId)
    }

    @Test
    fun `underflowed mean baseline is a numeric error rather than an all zero run`() {
        val workload = workload(
            profile("rare", 1.0, 1.0, 0.0),
            profile("usual", 3.0, 0.0, 1.0),
        )
        val baseline = evaluation(Double.MIN_VALUE, 0.0)

        val exception = assertThrows(AnalysisException::class.java) {
            calculator.assessEvaluation(workload, baseline, baseline, emptyUsage)
        }

        assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
    }

    private fun workload(vararg profiles: Profile): WorkloadInput = WorkloadInput(
        queries = listOf(QueryInput("q1", "SELECT 1"), QueryInput("q2", "SELECT 2")),
        profiles = profiles.toList(),
        constraints = Constraints(storageBudgetBytes = 1_000L, writeBudgetUnitsPerHour = null),
    )

    @Test
    fun `guard ratio equal to the limit passes and improvement passes any limit`() {
        val workload = workload(profile("only", 1.0, 1.0, 1.0))
            .let { it.copy(constraints = it.constraints.copy(queryCostGuards = mapOf("q1" to 2.0))) }
        // 100 -> 200 puts the observed ratio exactly at the inclusive 2.0 limit; 200 -> 200 and
        // 200 -> 100 stay strictly below it, so a strict-inequality regression fails the first pair only.
        for ((first, second) in listOf(100.0 to 200.0, 200.0 to 200.0, 200.0 to 100.0)) {
            val assessment = calculator.assessEvaluation(
                workload, evaluation(first, 1.0), evaluation(second, 1.0, listOf("idx_a")), candidateUsage,
            )
            assertInstanceOf(EvaluationAssessment.Accepted::class.java, assessment)
        }
    }

    @Test
    fun `guard violation rejects the set with the limit and the observed ratio`() {
        val workload = workload(profile("only", 1.0, 1.0, 1.0))
            .let { it.copy(constraints = it.constraints.copy(queryCostGuards = mapOf("q1" to 1.1))) }

        val assessment = calculator.assessEvaluation(
            workload, evaluation(100.0, 1.0), evaluation(150.0, 1.0, listOf("idx_a")), candidateUsage,
        )
        val rejected = assertInstanceOf(EvaluationAssessment.Rejected::class.java, assessment)

        val violation = rejected.violations.single()
        assertEquals(ErrorCode.CRITICAL_QUERY_REGRESSION, violation.code)
        assertEquals("q1", violation.queryId)
        assertEquals("1.1", violation.details["limit"])
        assertEquals("1.5", violation.details["observed_ratio"])
        assertEquals("100.0", violation.details["baseline_cost"])
        assertEquals("150.0", violation.details["selected_cost"])
        // Raw metrics survive the rejection: both query costs are still visible in the profile.
        assertEquals(151.0 / 101.0, rejected.metrics.meanRatio)
    }

    @Test
    fun `an improving mean does not bypass a critical query guard`() {
        val workload = workload(profile("only", 1.0, 1.0, 1.0))
            .let { it.copy(constraints = it.constraints.copy(queryCostGuards = mapOf("q1" to 1.05))) }

        val assessment = calculator.assessEvaluation(
            workload, evaluation(100.0, 100.0), evaluation(110.0, 1.0, listOf("idx_a")), candidateUsage,
        )
        val rejected = assertInstanceOf(EvaluationAssessment.Rejected::class.java, assessment)

        assertTrue(requireNotNull(rejected.metrics.meanRatio) < 1.0)
        assertEquals(ErrorCode.CRITICAL_QUERY_REGRESSION, rejected.violations.single().code)
        assertEquals("q1", rejected.violations.single().queryId)
    }

    @Test
    fun `a zero-baseline guard keeps zero cost but never divides by zero`() {
        val workload = workload(profile("only", 1.0, 1.0, 1.0))
            .let { it.copy(constraints = it.constraints.copy(queryCostGuards = mapOf("q1" to 1.1))) }
        val baseline = evaluation(0.0, 50.0)

        val retained = calculator.assessEvaluation(
            workload, baseline, evaluation(0.0, 10.0, listOf("idx_a")), candidateUsage,
        )
        assertInstanceOf(EvaluationAssessment.Accepted::class.java, retained)

        val regression = calculator.assessEvaluation(
            workload, baseline, evaluation(Double.MIN_VALUE, 10.0, listOf("idx_a")), candidateUsage,
        )
        val rejected = assertInstanceOf(EvaluationAssessment.Rejected::class.java, regression)
        val violation = rejected.violations.single()
        assertEquals(ErrorCode.CRITICAL_QUERY_REGRESSION, violation.code)
        assertEquals("q1", violation.queryId)
        assertEquals("unknown", violation.details["observed_ratio"])
        // The ratio stays unknown instead of becoming zero or infinity.
        assertNull(CostGuards.evaluate(workload.constraints.queryCostGuards, baseline,
            evaluation(Double.MIN_VALUE, 10.0)).single().observedRatio)
    }

    @Test
    fun `guard and zero-baseline profile violations are reported together in stable order`() {
        val workload = workload(profile("zero", 1.0, 1.0, 0.0), profile("positive", 1.0, 0.0, 1.0))
            .let { it.copy(constraints = it.constraints.copy(queryCostGuards = mapOf("q2" to 1.0))) }

        val assessment = calculator.assessEvaluation(
            workload, evaluation(0.0, 100.0), evaluation(5.0, 150.0, listOf("idx_a")), candidateUsage,
        )
        val rejected = assertInstanceOf(EvaluationAssessment.Rejected::class.java, assessment)

        assertEquals(
            listOf(ErrorCode.ZERO_BASELINE_REGRESSION, ErrorCode.CRITICAL_QUERY_REGRESSION),
            rejected.violations.map { it.code },
        )
        assertEquals(listOf("zero", "q2"), rejected.violations.map { it.profileId ?: it.queryId })
    }

    @Test
    fun `a non-finite guard ratio remains a numeric error`() {
        val workload = workload(profile("only", 1.0, 0.0, 1.0))
            .let { it.copy(constraints = it.constraints.copy(queryCostGuards = mapOf("q1" to 1.1))) }

        val exception = assertThrows(AnalysisException::class.java) {
            calculator.assessEvaluation(
                workload, evaluation(Double.MIN_VALUE, 1.0),
                evaluation(1e308, 1.0, listOf("idx_a")), candidateUsage,
            )
        }
        assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
        assertEquals("q1", exception.error.queryId)
    }

    @Test
    fun `guard outcomes preserve full vectors for reports`() {
        val guards = mapOf("q1" to 1.2, "q2" to 1.0)
        val baseline = evaluation(100.0, 0.0)
        val selected = evaluation(110.0, 0.0)

        val outcomes = CostGuards.evaluate(guards, baseline, selected)

        assertEquals(listOf("q1", "q2"), outcomes.map { it.queryId })
        val first = outcomes.first()
        assertEquals(1.2, first.maxRatio)
        assertEquals(1.1, first.observedRatio)
        assertFalse(first.zeroBaseline)
        assertFalse(first.violated)
        val second = outcomes.last()
        assertTrue(second.zeroBaseline)
        assertNull(second.observedRatio)
        assertFalse(second.violated)
        assertTrue(CostGuards.violationErrors(outcomes).isEmpty())
    }

    private fun profile(id: String, weight: Double, q1: Double, q2: Double): Profile =
        Profile(id, weight, mapOf("q1" to q1, "q2" to q2))

    private fun evaluation(q1: Double, q2: Double, candidateIds: List<String> = emptyList()): EvaluationResult =
        EvaluationResult(
            candidateIds = candidateIds,
            costByQueryId = mapOf("q1" to q1, "q2" to q2),
            plansByQueryId = mapOf("q1" to ExplainPlan("{}"), "q2" to ExplainPlan("{}")),
        )
}
