package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.fixtures.HtmlReportFixtures

class HtmlReportAnalysisModeTest {
    private val renderer = HtmlReportRenderer()

    @Test
    fun `every mode displays all effective limits and saved counters even for failed reports`() {
        for (mode in AnalysisMode.entries) {
            for (status in RunStatus.entries) {
                val source = if (status == RunStatus.COMPLETED) HtmlReportFixtures.completed()
                    else ContractFixtures.report(status = status)
                val diagnostics = source.result.diagnostics.copy(
                    limits = mode.limits,
                    statistics = RunStatistics(673, 29, 17, 7, 11),
                )
                val result = when (val saved = source.result) {
                    is SearchResult.Completed -> saved.copy(diagnostics = diagnostics)
                    is SearchResult.Partial -> saved.copy(diagnostics = diagnostics)
                    is SearchResult.Failed -> saved.copy(diagnostics = diagnostics)
                }
                val report = source.copy(analysisMode = mode, result = result,
                    input = if (status == RunStatus.FAILED) null else source.input)
                val html = renderer.render(report)
                val compact = html.replace("\u202f", "").replace("\u00a0", "")

                assertTrue(html.contains("Режим анализа"))
                val modeName = mode.name.lowercase() + " · " + when (mode) {
                    AnalysisMode.FAST -> "Быстрый"
                    AnalysisMode.NORMAL -> "Обычный"
                    AnalysisMode.DEEP -> "Глубокий"
                }
                assertTrue(html.contains(modeName))
                assertTrue(html.contains("<dt>Режим анализа</dt><dd>$modeName</dd>"))
                for ((label, value) in mapOf(
                    "Попытки EXPLAIN, включая ошибки" to 673,
                    "Попытки оценки наборов" to 29,
                    "Полностью оценённые наборы" to 17,
                    "Cache hits" to 7,
                    "Новые попытки repair" to 11,
                )) {
                    val rows = Regex("(?s)<tr>.*?</tr>").findAll(compact)
                        .filter { it.value.contains(label) }.toList()
                    assertEquals(1, rows.size, "Expected one counter for $mode/$status/$label")
                    assertTrue(rows.single().value.contains(">$value</td>"),
                        "Missing saved counter for $mode/$status/$label")
                }
                for ((label, value) in mapOf(
                    "Общий бюджет времени, мс" to mode.limits.commandTimeout.toMillis(),
                    "Бюджет поиска с baseline, мс" to mode.limits.searchTimeout.toMillis(),
                    "Один EXPLAIN, не более, мс" to mode.limits.explainTimeout.toMillis(),
                    "Подключение, не более, мс" to mode.limits.connectTimeout.toMillis(),
                )) {
                    assertTrue(compact.contains("<dt>$label</dt><dd>$value</dd>"),
                        "Missing effective timeout for $mode/$status/$label")
                }
                assertTrue(compact.contains("<td>${mode.limits.maxExplainAttempts}</td>"))
                assertTrue(compact.contains("<td>${mode.limits.maxRepairAttempts}</td>"))
                diagnostics.stopReasons.forEach { assertTrue(html.contains(it.name)) }
                assertSame(source.result.baseline, report.result.baseline)
                assertSame(source.result.selected, report.result.selected)
            }
        }
    }
}
