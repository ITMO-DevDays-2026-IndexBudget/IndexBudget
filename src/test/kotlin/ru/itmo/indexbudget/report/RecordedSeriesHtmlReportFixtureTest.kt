package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.domain.CandidatePreparationScope
import ru.itmo.indexbudget.fixtures.RecordedSeriesHtmlReportFixture
import java.nio.file.Files
import java.nio.file.Path

class RecordedSeriesHtmlReportFixtureTest {
    @Test
    fun `updated series uses one model for summary and embedded point reports`(@TempDir directory: Path) {
        val result = RecordedSeriesHtmlReportFixture.result()
        val summary = RecordedSeriesHtmlReportFixture.summary(result)
        assertEquals("fixed_candidate_pool", summary["comparison_scope"].stringValue())
        assertEquals("manual_json", summary["source"]["workload_source"].stringValue())
        assertTrue(summary["source"]["workload_input_hash"].isNull)
        assertTrue(summary["preparation"]["conditions"].isNull)
        result.points.forEachIndexed { index, point ->
            assertEquals(point.result?.selected?.metrics?.meanCost, summary["points"][index]["metrics"]["mean_cost"].doubleValue())
            assertEquals(point.runId, summary["points"][index]["run_id"].stringValue())
        }
        RecordedSeriesHtmlReportFixture.generate(directory.resolve("preview"))
        val html = Files.readString(directory.resolve("preview/recorded-series.html"))
        assertTrue(html.contains("variant-1-overview"))
        assertTrue(html.contains("variant-2-overview"))
        assertTrue(html.contains("variant-3-overview"))
        assertTrue(Files.exists(directory.resolve("preview/series.json")))
        assertTrue(Files.exists(directory.resolve("preview/point-03/report.json")))
        assertFalse(Files.readString(directory.resolve("preview/point-01/indexes.sql")).contains("CREATE INDEX"))
    }

    @Test
    fun `updated example preserves three budget points without importing old observation windows`() {
        val reports = (0..2).map { RecordedSeriesHtmlReportFixture.report(it) }
        assertEquals(listOf(0, 1, 2), reports.map { it.summary.budgetUsage?.indexCount })
        assertEquals(listOf(0.0, 1.0, 2.0), reports.map { it.summary.budgetUsage?.writeUnitsPerHour })
        assertEquals(listOf(0L, 835_584L, 1_671_168L), reports.map { it.summary.budgetUsage?.budgetedBytes })
        assertEquals(listOf(1786.0, 901.05, 16.1), reports.map { it.result.selected?.metrics?.meanCost })
        assertEquals(listOf(2, 6, 8), reports.map { it.result.diagnostics.statistics.explainAttempts })
        assertEquals(listOf(0, 1, 0), reports.map { it.result.diagnostics.statistics.cacheHits })
        for (report in reports) {
            assertEquals(listOf("default"), report.input?.profiles?.map { it.id })
            assertEquals(listOf(893.0, 893.0), report.queryComparisons.map { it.baselineCost })
            assertNull(report.workloadProvenance)
            assertNull(report.inputHash)
            assertTrue(report.runId.endsWith("-html-preview"))
            assertEquals(CandidatePreparationScope.COMMON_POOL, report.candidates.diagnostics.preparationScope)
            assertEquals(0, report.candidates.diagnostics.fixedPoolSelection?.sizeAttempts)
            val html = HtmlReportRenderer().render(report)
            assertFalse(html.contains("95,1"))
            assertFalse(html.contains("Оба критерия выбрали один набор"))
            assertFalse(html.contains("Источник, период и охват SQL-нагрузки не указаны"))
            assertFalse(html.contains("Неизвестно, насколько она отражает рабочую БД"))
            assertEquals(true, report.incumbentsCoincide)
        }
    }

    @Test
    fun `updated example retains plans for empty one-index and two-index selections`() {
        val reports = (0..2).map { RecordedSeriesHtmlReportFixture.report(it) }
        val shapes = reports.map { report -> requireNotNull(report.result.selected).evaluation.plansByQueryId.values.map {
            when {
                it.json.contains("Index Only Scan") -> "index"
                it.json.contains("Seq Scan") -> "seq"
                else -> "unknown"
            }
        } }
        assertEquals(listOf(listOf("seq", "seq"), listOf("index", "seq"), listOf("index", "index")), shapes)
        assertEquals(listOf(0.0, 49.54927211646137, 99.09854423292273), reports.map { it.summary.meanEffect.gainPercent })
    }
}
