package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.fixtures.ContractFixtures

class EvaluationContractTest {
    private val queryIds = ContractFixtures.workload().queries.map { it.id }.toSet()

    @Test
    fun `baseline and both incumbents cover shared SQL regardless of profile count`() {
        listOf(
            ContractFixtures.baselineEvaluation(),
            ContractFixtures.robustEvaluation(),
            ContractFixtures.meanEvaluation(),
        ).forEach { evaluation ->
            assertDoesNotThrow { evaluation.requireQueryCoverage(queryIds) }
        }
    }

    @Test
    fun `a failed query cannot silently produce a partial cost vector`() {
        val baseline = ContractFixtures.baselineEvaluation()
        val incomplete = baseline.copy(
            costByQueryId = baseline.costByQueryId - "records_by_value",
            plansByQueryId = baseline.plansByQueryId - "records_by_value",
        )

        assertThrows(IllegalArgumentException::class.java) { incomplete.requireQueryCoverage(queryIds) }
    }

    @Test
    fun `an evaluation for another workload is rejected even if its vector is complete`() {
        val baseline = ContractFixtures.baselineEvaluation()
        val wrongWorkload = baseline.copy(
            costByQueryId = baseline.costByQueryId + ("another_query" to 1.0),
            plansByQueryId = baseline.plansByQueryId + ("another_query" to ExplainPlan("{}")),
        )

        assertThrows(IllegalArgumentException::class.java) { wrongWorkload.requireQueryCoverage(queryIds) }
    }

    @Test
    fun `plans cannot omit a query whose cost is present`() {
        val baseline = ContractFixtures.baselineEvaluation()

        assertThrows(IllegalArgumentException::class.java) {
            baseline.copy(plansByQueryId = baseline.plansByQueryId - "records_by_value")
        }
    }

    @Test
    fun `invalid planner costs cannot reach metric calculations`() {
        val baseline = ContractFixtures.baselineEvaluation()

        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                baseline.copy(costByQueryId = baseline.costByQueryId + ("records_by_group" to invalid))
            }
        }
        assertDoesNotThrow {
            baseline.copy(costByQueryId = baseline.costByQueryId.mapValues { 0.0 })
        }
    }

    @Test
    fun `configuration identity cannot depend on duplicate or reordered IDs`() {
        val baseline = ContractFixtures.baselineEvaluation()

        listOf(listOf("b", "a"), listOf("a", "a")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { baseline.copy(candidateIds = invalid) }
        }
        assertDoesNotThrow { baseline.copy(candidateIds = listOf("a", "b")) }
    }
}
