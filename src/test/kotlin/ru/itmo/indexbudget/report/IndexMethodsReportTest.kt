package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import tools.jackson.databind.json.JsonMapper

class IndexMethodsReportTest {
    @Test
    fun `BRIN is explicitly versioned even without selected candidates and old reports stay v3`() {
        for (status in RunStatus.entries) {
            val source = ContractFixtures.report(status = status)
            for (methods in listOf(setOf(IndexMethod.BTREE), setOf(IndexMethod.BRIN), IndexMethod.entries.toSet())) {
                val input = requireNotNull(source.input).copy(indexMethods = methods)
                val report = DefaultReportBuilder().build(input, source.candidates, source.result, RunContext())
                val json = JsonMapper().readTree(JsonReportWriter().render(report))
                assertEquals(if (IndexMethod.BRIN in methods) 4 else 3, json["schema_version"].intValue())
                assertEquals(methods.sortedBy { it.ordinal }.map { it.wireName }, json["index_methods"].toList().map { it.stringValue() })
                val html = HtmlReportRenderer().render(report)
                assertTrue(html.contains("Методы индексов"))
                if (IndexMethod.BRIN in methods) {
                    assertThrows(IllegalArgumentException::class.java) { JsonReportWriter().render(report.copy(schemaVersion = 3)) }
                }
            }
        }
    }

    @Test
    fun `manual BRIN alone also requires v4 and has no ordering semantics in JSON`() {
        val source = ContractFixtures.report()
        val candidate = source.candidates.candidates.first()
        val definition = candidate.definition.copy(method = "brin", keys = candidate.definition.keys.take(1), includedColumns = emptyList())
        val input = requireNotNull(source.input).copy(manualIndexes = listOf(definition))
        val report = DefaultReportBuilder().build(input, source.candidates, source.result, RunContext())
        assertEquals(4, report.schemaVersion)
        val json = JsonMapper().readTree(JsonReportWriter().render(report))
        val requested = json["manual_requested_indexes"].first()
        assertEquals("brin", requested["method"].stringValue())
        assertTrue(requested["keys"].first()["direction"].isNull)
        assertTrue(requested["keys"].first()["nulls"].isNull)
    }
}
