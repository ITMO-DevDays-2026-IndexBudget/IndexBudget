package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.fixtures.RecordedHtmlReportFixture
import java.time.Duration

class RecordedHtmlReportFixtureTest {
    @Test
    fun `recorded example retains exact observation windows counts and saved SQL costs`() {
        val report = RecordedHtmlReportFixture.report()
        val windows = requireNotNull(report.workloadProvenance).windows
        assertEquals(listOf(95_126_000L, 71_260_000L), windows.map { Duration.between(it.startedAt, it.finishedAt).toNanos() })
        assertEquals(listOf(false, true), windows.map { it.complete })
        assertEquals(listOf(10L, 11L), windows.map { it.coverage.selectedCalls })
        assertEquals(listOf(20L, 21L), windows.map { it.coverage.availableCalls })
        assertEquals(listOf(3.157004, 3.701207), windows.map { it.coverage.selectedExecTimeMillis })
        assertEquals(listOf(7.082334, 7.47633), windows.map { it.coverage.availableExecTimeMillis })
        assertEquals(listOf(85.5, 85.5), report.queryComparisons.map { it.baselineCost })
        assertEquals(listOf(28.05, 8.05), report.queryComparisons.map { it.selectedCost })
        assertEquals(275_252L, report.summary.budgetUsage?.budgetedBytes)
        assertNull(report.summary.budgetUsage?.writeUnitsPerHour)
        assertEquals(true, report.incumbentsCoincide)
        assertEquals(79.583983315, checkNotNull(report.summary.meanEffect.gainPercent) { "Expected gainPercent to be available" }, 0.000000001)
        assertNull(report.inputHash, "A reconstruction must not claim the original workload hash")
        assertTrue(report.runId.endsWith("-html-preview"))
    }

    @Test
    fun `recorded example uses real saved plan shapes and exports the same model`() {
        val report = RecordedHtmlReportFixture.report()
        val plans = requireNotNull(report.result.selected).evaluation.plansByQueryId.values.map { it.json }
        assertTrue(plans[0].contains("Bitmap Heap Scan"))
        assertTrue(plans[1].contains("Index Only Scan"))
        assertTrue(requireNotNull(report.result.baseline).plansByQueryId.values.all { it.json.contains("Seq Scan") })
        val html = HtmlReportRenderer().render(report)
        ImportedHtmlAssertions.assertReport(report, html)
        val json = JsonReportWriter().render(report)
        for (window in requireNotNull(report.workloadProvenance).windows) {
            assertTrue(html.contains(window.startedAt.toString()))
            assertTrue(json.contains(window.startedAt.toString()))
        }
    }
}
