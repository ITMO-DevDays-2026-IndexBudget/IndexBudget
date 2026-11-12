package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.HtmlReportFixtures
import ru.itmo.indexbudget.fixtures.BudgetSeriesFixtures
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

class WriteProfileReportTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()

    @Test fun `observed rates origin and DML meanings survive JSON and HTML without evaluation`() {
        val report = HtmlReportFixtures.observedWrite()
        val html = HtmlReportRenderer().render(report)
        val json = mapper.readTree(JsonReportWriter().render(report))
        assertEquals(0.000125, json.path("table_write_rates").path("public.records").path("inserts_per_hour").doubleValue())
        assertEquals(7200.0, json.path("table_write_rates_origin").path("window").path("period_seconds").doubleValue())
        assertTrue(html.contains("0,000125"))
        assertTrue(html.contains("2026-11-12T10:00:00Z"))
        assertTrue(html.contains("Наблюдение полное"))
        assertTrue(html.contains("&lt;observed&gt;"))
        assertFalse(html.contains("<observed>"))
        assertTrue(html.contains("Фактическая модификация таблицы не включена"))
        val query = json.path("queries").last()
        assertEquals("update", query.path("statement_kind").stringValue())
        assertEquals("row_search_and_preparation", query.path("cost_interpretation").stringValue())
        assertTrue(html.substringAfter("id=\"query-comparison\"").substringBefore("</table>").contains("UPDATE"))
        assertEquals(report.result.selected?.evaluation?.candidateIds, json.path("selected").path("candidate_ids").toList().map { it.stringValue() })
    }

    @Test fun `incomplete absent manual and old observed inputs remain distinguishable`() {
        val incomplete = HtmlReportFixtures.observedWrite(false)
        val html = HtmlReportRenderer().render(incomplete)
        assertTrue(html.contains("Наблюдение неполное"))
        assertTrue(html.contains("INCOMPLETE_SNAPSHOT"))
        val oldObserved = incomplete.copy(input = requireNotNull(incomplete.input).copy(tableWriteRatesOrigin = null))
        assertTrue(HtmlReportRenderer().render(oldObserved).contains("Полнота неизвестна"))
        assertTrue(WriteProfileReport.render(null).contains("Профиль записи не задан"))
        assertTrue(WriteProfileReport.render(HtmlReportFixtures.completed().input).contains("Ручной профиль"))
        val escaped = incomplete.copy(input = incomplete.input.copy(tableWriteRatesOrigin = incomplete.input.tableWriteRatesOrigin?.copy(
            reasons = listOf("<script>alert(1)</script>"), sourceId = "<source>")))
        val safe = HtmlReportRenderer().render(escaped)
        assertTrue(safe.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(safe.contains("&lt;source&gt;"))
    }

    @Test fun `partial DELETE report retains its selected set and observed profile`() {
        val source = HtmlReportFixtures.observedWrite()
        val complete = source.result as SearchResult.Completed
        val input = requireNotNull(source.input)
        val id = input.queries.last().id
        val report = source.copy(input = input.copy(queries = input.queries.map {
            if (it.id == id) it.copy(sql = "DELETE FROM public.records WHERE value = 7") else it
        }), statementKinds = source.statementKinds + (id to StatementKind.DELETE),
            result = SearchResult.Partial(complete.selectedObjective, complete.baseline, complete.bestRobust,
                complete.bestMean, complete.diagnostics.copy(stopReasons = listOf(StopReason.CONNECTION_LOST))))
        val json = mapper.readTree(JsonReportWriter().render(report))
        assertEquals("partial", json.path("status").stringValue())
        assertEquals("delete", json.path("queries").last().path("statement_kind").stringValue())
        assertTrue(HtmlReportRenderer().render(report).contains("DELETE"))
        assertEquals(mapper.readTree(JsonReportWriter().render(source)).path("selected"), json.path("selected"))
        assertEquals(mapper.readTree(JsonReportWriter().render(source)).path("table_write_rates_origin"), json.path("table_write_rates_origin"))
    }

    @Test fun `legacy summary without SQL kinds does not duplicate the shared query catalog`() {
        val report = HtmlReportFixtures.observedWrite()
        val summary: tools.jackson.databind.JsonNode = mapper.valueToTree(mapOf("settings" to mapOf("queries" to requireNotNull(report.input).queries.map {
            mapOf("id" to it.id, "sql" to it.sql)
        }), "points" to listOf(mapOf("ordinal" to 1))))
        val html = HtmlSeriesCatalog(mapOf(1 to report), summary).queries()
        assertEquals(report.input.queries.size, Regex("class=\"query-detail\"").findAll(html).count())
        assertTrue(html.contains("UPDATE"))
    }

    @Test fun `series preserves shared observed origin and SQL kinds even without point models`() {
        val output = directory.resolve("series")
        BudgetSeriesFixtures.generate(output, "observed-write")
        val json = mapper.readTree(Files.readString(output.resolve("series.json")))
        assertTrue(json.path("settings").path("table_write_rates_origin").path("complete").booleanValue())
        assertEquals("update", json.path("settings").path("queries").last().path("statement_kind").stringValue())
        for (name in listOf("report.html", "series.html")) {
            val html = Files.readString(output.resolve(name))
            assertTrue(html.contains("2026-11-12T10:00:00Z"), name)
            assertTrue(html.contains("0,000125"), name)
            assertTrue(html.contains("UPDATE"), name)
            assertTrue(html.contains("Фактическая модификация таблицы не включена"), name)
        }
        val legacy = mapper.readTree(Files.readString(output.resolve("series.json")))
        (legacy.path("settings") as tools.jackson.databind.node.ObjectNode).remove("table_write_rates_origin")
        legacy.path("settings").path("queries").forEach {
            (it as tools.jackson.databind.node.ObjectNode).remove("statement_kind")
        }
        assertTrue(HtmlBudgetSeriesRenderer().render(legacy).contains("Тип не сохранён"))
    }
}
