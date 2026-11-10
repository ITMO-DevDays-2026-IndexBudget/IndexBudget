package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.fixtures.ContractFixtures

class PriceOfRobustnessTest {

    @Test
    fun `different incumbents use absolute mean costs rather than ratios or percentages`() {
        val result = ContractFixtures.completedResult()

        // C_mean values are 155000 and 66100; M/R and profile percentages have other units.
        assertEquals(88_900.0, PriceOfRobustness.calculate(result))
    }

    @Test
    fun `changing the selected objective keeps the comparison within the same saved pool`() {
        val robust = ContractFixtures.completedResult()
        val mean = robust.copy(selectedObjective = Objective.MEAN)

        assertSame(robust.bestRobust, mean.bestRobust)
        assertSame(robust.bestMean, mean.bestMean)
        assertEquals(88_900.0, PriceOfRobustness.calculate(robust))
        assertEquals(88_900.0, PriceOfRobustness.calculate(mean))
    }

    @Test
    fun `same configuration gives zero for separate saved instances`() {
        val result = ContractFixtures.completedResult(sameIncumbents = true)
        val separateMean = result.bestMean.copy()

        assertNotSame(result.bestRobust, separateMean)
        assertEquals(0.0, PriceOfRobustness.calculate(result.copy(bestMean = separateMean)))
    }

    @Test
    fun `a baseline-only pool has zero price`() {
        val baseline = ContractFixtures.baselineConfiguration()
        val result = SearchResult.Completed(Objective.ROBUST, baseline.evaluation, baseline, baseline.copy())

        assertEquals(0.0, PriceOfRobustness.calculate(result))
    }

    @Test
    fun `all-zero baseline compares absolute costs even though ratios are unknown`() {
        val workload = ContractFixtures.workload()
        val evaluation = evaluation(emptyList(), 0.0, 0.0)
        val metrics = MetricsCalculatorImpl().calculate(
            workload, evaluation, evaluation, BudgetUsage(0L, 0L, 0.0, 0),
        )
        val baseline = EvaluatedConfiguration(evaluation, metrics)
        val result = SearchResult.Completed(Objective.MEAN, evaluation, baseline, baseline.copy())

        assertNull(metrics.meanRatio)
        assertNull(metrics.worstRatio)
        assertEquals(0.0, PriceOfRobustness.calculate(result))
    }

    @Test
    fun `baseline can be the robust incumbent while a regressing rare profile improves the mean`() {
        val workload = ContractFixtures.workload()
        val baseline = ContractFixtures.baselineConfiguration()
        val candidateEvaluation = evaluation(listOf("candidate"), 10.0, 110.0)
        val candidateMetrics = MetricsCalculatorImpl().calculate(
            workload, baseline.evaluation, candidateEvaluation, BudgetUsage(100L, 120L, 4100.0, 1),
        )
        val candidate = EvaluatedConfiguration(candidateEvaluation, candidateMetrics)
        val result = SearchResult.Completed(Objective.ROBUST, baseline.evaluation, baseline, candidate)

        // The common two-set pool chooses baseline for R and the candidate for M.
        assertEquals(310_000.0, baseline.metrics.meanCost)
        assertEquals(70_000.0, candidate.metrics.meanCost)
        assertEquals(240_000.0, PriceOfRobustness.calculate(result))
    }

    @Test
    fun `partial results compare both saved incumbents without requiring completed search`() {
        val completed = ContractFixtures.completedResult()
        val partial = SearchResult.Partial(
            selectedObjective = Objective.MEAN,
            baseline = completed.baseline,
            bestRobust = completed.bestRobust,
            bestMean = completed.bestMean,
            diagnostics = SearchDiagnostics(stopReasons = listOf(StopReason.CONNECTION_LOST)),
        )

        assertEquals(88_900.0, PriceOfRobustness.calculate(partial))
        assertEquals(0.0, PriceOfRobustness.calculate(ContractFixtures.partialResult()))
    }

    @Test
    fun `failed results have unknown price with or without a saved baseline`() {
        val failed = ContractFixtures.failedResult()

        assertNull(PriceOfRobustness.calculate(failed))
        assertNull(PriceOfRobustness.calculate(failed.copy(baseline = ContractFixtures.baselineEvaluation())))
    }

    @Test
    fun `raw finite negative differences are preserved without rounding or clamping`() {
        val result = ContractFixtures.completedResult()
        val robust = result.bestRobust.withMeanCost(1.0)
        val mean = result.bestMean.withMeanCost(Math.nextUp(1.0))

        // Check arithmetic over supplied metrics, independently of how search selected the pair.
        assertEquals(-2.220446049250313E-16, PriceOfRobustness.calculate(result.copy(
            bestRobust = robust,
            bestMean = mean,
        )))
    }

    @Test
    fun `invalid absolute costs in either incumbent produce a numeric error`() {
        val result = ContractFixtures.completedResult()
        val invalidCosts = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)

        for (cost in invalidCosts) {
            val invalidRobust = result.copy(bestRobust = result.bestRobust.withMeanCost(cost))
            val invalidMean = result.copy(bestMean = result.bestMean.withMeanCost(cost))
            for (invalid in listOf(invalidRobust, invalidMean)) {
                val exception = assertThrows(AnalysisException::class.java) {
                    PriceOfRobustness.calculate(invalid)
                }
                assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
            }
        }
    }

    @Test
    fun `matching configuration ids cannot hide a numeric error behind zero`() {
        val result = ContractFixtures.completedResult(sameIncumbents = true)
        val invalid = result.bestRobust.withMeanCost(Double.NaN)

        val exception = assertThrows(AnalysisException::class.java) {
            PriceOfRobustness.calculate(result.copy(bestRobust = invalid, bestMean = invalid.copy()))
        }
        assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
    }

    @Test
    fun `report fixtures pass the calculated value together with the same result and comparison scope`() {
        for (objective in Objective.entries) {
            val report = ContractFixtures.report(objective = objective)

            assertEquals(88_900.0, report.priceOfRobustness)
            assertEquals(PriceOfRobustness.calculate(report.result), report.priceOfRobustness)
            assertEquals(ComparisonScope.EVALUATED_SETS, report.comparisonScope)
            assertEquals(objective, report.result.selectedObjective)
            assertSame(
                if (objective == Objective.ROBUST) report.result.bestRobust else report.result.bestMean,
                report.result.selected,
            )
        }
        assertEquals(0.0, ContractFixtures.report(sameIncumbents = true).priceOfRobustness)
        assertEquals(0.0, ContractFixtures.report(status = RunStatus.PARTIAL).priceOfRobustness)
        assertNull(ContractFixtures.report(status = RunStatus.FAILED).priceOfRobustness)
    }

    private fun EvaluatedConfiguration.withMeanCost(cost: Double): EvaluatedConfiguration =
        copy(metrics = metrics.copy(meanCost = cost))

    private fun evaluation(candidateIds: List<String>, groupCost: Double, valueCost: Double): EvaluationResult {
        val costs = mapOf("records_by_group" to groupCost, "records_by_value" to valueCost)
        return EvaluationResult(candidateIds, costs, costs.mapValues { ExplainPlan("{}") })
    }
}
