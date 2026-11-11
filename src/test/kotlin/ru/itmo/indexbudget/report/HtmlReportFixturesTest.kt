package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.fixtures.HtmlReportFixtures
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class HtmlReportFixturesTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `every preview has consistent saved costs query comparisons and JSON effects`() {
        for ((name, report) in HtmlReportFixtures.all()) {
            val json = mapper.readTree(JsonReportWriter().render(report))
            val input = report.input
            if (input == null) {
                assertTrue(json["queries"].isNull, name)
                assertTrue(json["query_effects"].isEmpty, name)
                continue
            }
            val ids = input.queries.map { it.id }
            assertEquals(ids, report.queryComparisons.map { it.queryId }, name)
            assertEquals(ids, json["queries"].toList().map { it["id"].stringValue() }, name)
            assertEquals(ids, json["query_effects"].toList().map { it["id"].stringValue() }, name)
            for ((index, id) in ids.withIndex()) {
                val baseline = report.result.baseline?.costByQueryId?.get(id)
                val selected = report.result.selected?.evaluation?.costByQueryId?.get(id)
                val regression = if (baseline != null && selected != null) selected > baseline else null
                val query = json["queries"][index]
                val effects = json["query_effects"][index]
                val comparison = report.queryComparisons[index]
                assertEquals(baseline, comparison.baselineCost, "$name/$id")
                assertEquals(selected, comparison.selectedCost, "$name/$id")
                assertEquals(regression, comparison.regression, "$name/$id")
                assertCost(baseline, query["baseline_cost"], "$name/$id")
                assertCost(selected, query["selected_cost"], "$name/$id")
                assertEquals(regression, query["regression"].let { if (it.isNull) null else it.booleanValue() }, "$name/$id")
                for ((key, evaluation) in mapOf(
                    "baseline" to report.result.baseline,
                    "selected" to report.result.selected?.evaluation,
                    "best_robust" to report.result.bestRobust?.evaluation,
                    "best_mean" to report.result.bestMean?.evaluation,
                )) {
                    assertCost(baseline, effects[key]["baseline_cost"], "$name/$id/$key")
                    assertCost(evaluation?.costByQueryId?.get(id), effects[key]["cost"], "$name/$id/$key")
                }
            }
        }
    }

    private fun assertCost(expected: Double?, actual: JsonNode, message: String) {
        if (expected == null) assertTrue(actual.isNull, message) else {
            assertFalse(actual.isNull, message)
            assertEquals(expected, actual.doubleValue(), message)
        }
    }
}
