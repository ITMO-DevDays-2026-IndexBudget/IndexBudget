package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.fixtures.ContractFixtures

class SearchResultContractTest {
    @Test
    fun `changing the objective selects from the saved common-pool incumbents`() {
        val robust = ContractFixtures.completedResult(Objective.ROBUST)
        val mean = robust.copy(selectedObjective = Objective.MEAN)

        assertSame(robust.bestRobust, robust.selected)
        assertSame(mean.bestMean, mean.selected)
        assertSame(robust.bestRobust, mean.bestRobust)
        assertSame(robust.bestMean, mean.bestMean)
    }

    @Test
    fun `partial analysis retains usable selection and reports why it stopped`() {
        val partial = ContractFixtures.partialResult(Objective.MEAN)

        assertSame(partial.bestMean, partial.selected)
        assertEquals(RunStatus.PARTIAL, partial.status)
        assertEquals(3, partial.status.exitCode)
        assertThrows(IllegalArgumentException::class.java) {
            partial.copy(diagnostics = SearchDiagnostics())
        }
    }

    @Test
    fun `failed analysis cannot expose a successful index selection`() {
        val failed = ContractFixtures.failedResult()

        assertNull(failed.baseline)
        assertNull(failed.selected)
        assertNull(failed.bestRobust)
        assertNull(failed.bestMean)
    }

    @Test
    fun `an indexed evaluation cannot be passed off as the baseline`() {
        val result = ContractFixtures.completedResult()

        assertThrows(IllegalArgumentException::class.java) {
            result.copy(baseline = ContractFixtures.robustEvaluation())
        }
    }

    @Test
    fun `an incumbent missing a baseline query cannot enter a usable result`() {
        val result = ContractFixtures.completedResult()
        val meanEvaluation = result.bestMean.evaluation
        val incomplete = meanEvaluation.copy(
            costByQueryId = meanEvaluation.costByQueryId - "records_by_value",
            plansByQueryId = meanEvaluation.plansByQueryId - "records_by_value",
        )

        assertThrows(IllegalArgumentException::class.java) {
            result.copy(bestMean = result.bestMean.copy(evaluation = incomplete))
        }
    }

    @Test
    fun `report fixtures preserve absent write data instead of claiming zero cost`() {
        val report = ContractFixtures.report(withWriteProfile = false)
        val input = requireNotNull(report.input)

        assertNull(input.tableWriteRates)
        assertNull(input.constraints.writeBudgetUnitsPerHour)
        report.candidates.candidates.forEach { assertNull(it.writeUnitsPerHour) }
        assertNull(report.result.bestRobust!!.metrics.budgetUsage.writeUnitsPerHour)
        assertNull(report.result.bestMean!!.metrics.budgetUsage.writeUnitsPerHour)
        val unestimated = ContractFixtures.unestimatedCandidate()
        assertNull(unestimated.estimatedBytes)
        assertNull(unestimated.budgetedBytes)
    }

    @Test
    fun `same-incumbent fixture does not invent a robustness tradeoff`() {
        val report = ContractFixtures.report(sameIncumbents = true)

        assertSame(report.result.bestRobust, report.result.bestMean)
        assertEquals(0.0, requireNotNull(report.priceOfRobustness))
    }
}
