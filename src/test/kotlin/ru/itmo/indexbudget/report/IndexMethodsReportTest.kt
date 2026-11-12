package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import tools.jackson.databind.json.JsonMapper

class IndexMethodsReportTest {
    @Test
    fun `BRIN is explicitly versioned across statuses and old reports stay v3`() {
        for (status in RunStatus.entries) {
            val source = ContractFixtures.report(status = status)
            for (methods in listOf(setOf(IndexMethod.BTREE), setOf(IndexMethod.BRIN), setOf(IndexMethod.HASH), IndexMethod.entries.toSet())) {
                val input = requireNotNull(source.input).copy(indexMethods = methods)
                val report = DefaultReportBuilder().build(input, source.candidates, source.result, RunContext())
                val json = JsonMapper().readTree(JsonReportWriter().render(report))
                assertEquals(if (methods.any { it != IndexMethod.BTREE }) 4 else 3, json["schema_version"].intValue())
                assertEquals(methods.sortedBy { it.ordinal }.map { it.wireName }, json["index_methods"].toList().map { it.stringValue() })
                val html = HtmlReportRenderer().render(report)
                assertTrue(html.contains("Методы индексов"))
                if (methods.any { it != IndexMethod.BTREE }) {
                    assertThrows(IllegalArgumentException::class.java) { JsonReportWriter().render(report.copy(schemaVersion = 3)) }
                }
            }
        }
    }

    @Test
    fun `BRIN keeps v4 for completed and partial results selecting only baseline`() {
        val source = ContractFixtures.report()
        val input = requireNotNull(source.input).copy(indexMethods = IndexMethod.entries.toSet())
        val baseline = ContractFixtures.baselineConfiguration()
        val completed = ContractFixtures.completedResult().copy(bestRobust = baseline, bestMean = baseline,
            diagnostics = SearchDiagnostics(stopReasons = listOf(StopReason.ZERO_STORAGE_BUDGET),
                statistics = RunStatistics(explainAttempts = 2, evaluationAttempts = 1, completedEvaluations = 1)))
        val failedEvaluation = ContractFixtures.partialResult()
        val partial = failedEvaluation.copy(bestRobust = baseline, bestMean = baseline,
            diagnostics = failedEvaluation.diagnostics.copy(
                statistics = RunStatistics(explainAttempts = 3, evaluationAttempts = 2, completedEvaluations = 1)))
        for (result in listOf(completed, partial)) {
            val effectiveInput = if (result is SearchResult.Completed)
                input.copy(constraints = input.constraints.copy(storageBudgetBytes = 0)) else input
            val report = DefaultReportBuilder().build(effectiveInput, source.candidates, result, RunContext())
            assertNotNull(report.result.selected)
            assertTrue(checkNotNull(report.result.selected) { "Expected selected to be available" }.evaluation.candidateIds.isEmpty())
            assertEquals(result.diagnostics.stopReasons, report.result.diagnostics.stopReasons)
            val json = JsonMapper().readTree(JsonReportWriter().render(report))
            assertEquals(4, json["schema_version"].intValue())
            assertFalse(json["selected"].isNull)
            assertTrue(json["selected_indexes"].isEmpty)
            assertEquals(result.diagnostics.stopReasons.map { it.name },
                json["diagnostics"]["stop_reasons"].toList().map { it.stringValue() })
            assertTrue(HtmlReportRenderer().render(report).contains("Выбран пустой набор"))
        }
        assertEquals(listOf(StopReason.EVALUATION_FAILED), partial.diagnostics.stopReasons)
    }

    @Test
    fun `manual BRIN alone also requires v4 and has no ordering semantics in JSON`() {
        val source = ContractFixtures.report()
        val candidate = source.candidates.candidates.first()
        for (method in listOf("brin", "hash")) {
            val definition = candidate.definition.copy(method = method, keys = candidate.definition.keys.take(1), includedColumns = emptyList())
            val input = requireNotNull(source.input).copy(manualIndexes = listOf(definition))
            val report = DefaultReportBuilder().build(input, source.candidates, source.result, RunContext())
            assertEquals(4, report.schemaVersion)
            val json = JsonMapper().readTree(JsonReportWriter().render(report))
            val requested = json["manual_requested_indexes"].first()
            assertEquals(method, requested["method"].stringValue())
            assertTrue(requested["keys"].first()["direction"].isNull)
            assertTrue(requested["keys"].first()["nulls"].isNull)
        }
    }
}
