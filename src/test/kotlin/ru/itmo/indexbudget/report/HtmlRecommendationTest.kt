package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.fixtures.HtmlReportFixtures
import tools.jackson.module.kotlin.jacksonObjectMapper

class HtmlRecommendationTest {
    private val renderer = HtmlReportRenderer()

    @Test
    fun `overview leads with recommendation and warns about regressions before search statistics`() {
        val report = HtmlReportFixtures.regression()
        val html = renderer.render(report)
        val overview = section(html, "overview")
        val selected = report.candidates.candidates.single { it.id in report.summary.selectedIndexIds.orEmpty() }
        assertTrue(overview.contains(selected.definition.table.name + "("))
        assertTrue(overview.contains("Есть регрессии SQL: 1"))
        assertTrue(overview.contains("href=\"#queries\""))
        assertFalse(overview.contains("Полностью оценённые наборы"))
        assertTrue(section(html, "search").contains("Полностью оценённые наборы"))
        val queries = section(html, "queries")
        assertTrue(queries.indexOf(">records_by_value</a>") < queries.indexOf(">records_by_group</a>"))
        assertTrue(queries.contains("href=\"#query-1\">records_by_value"))
        assertTrue(queries.contains("id=\"query-1\""))
        assertTrue(section(html, "indexes").contains("не измеренную индивидуальную пользу"))
    }

    @Test
    fun `chart values match JSON and supplied ratios instead of recalculating costs`() {
        val source = HtmlReportFixtures.completed()
        val result = source.result as SearchResult.Completed
        val selected = result.bestRobust.copy(metrics = result.bestRobust.metrics.copy(
            meanRatio = 0.3, worstRatio = 2.0,
            profiles = result.bestRobust.metrics.profiles.mapIndexed { index, profile ->
                profile.copy(ratio = if (index == 0) 0.25 else 2.0)
            }))
        val report = source.copy(result = result.copy(bestRobust = selected))
        val html = renderer.render(report)
        val json = jacksonObjectMapper().readTree(JsonReportWriter().render(report))
        val profiles = section(html, "profiles")
        assertTrue(profiles.contains("style=\"width:12.5%\"")) // ratio 0.25 on a common 0..2 scale
        assertTrue(profiles.contains("class=\"chart-fill regressed\" style=\"width:100%\""))
        assertTrue(profiles.contains("class=\"chart-fill baseline\" style=\"width:50%\""))
        val labels = Regex("<strong>([0-9]+)</strong>").findAll(profiles).map { it.groupValues[1].toDouble() }.toList()
        assertEquals(listOf(100.0, 25.0, 100.0, 200.0), labels)
        json["profile_comparisons"].forEachIndexed { index, series ->
            assertEquals(series["selected"]["ratio"].doubleValue() * 100, labels[index * 2 + 1])
        }
        val comparison = section(html, "comparison")
        assertTrue(comparison.contains("style=\"width:15%\"")) // saved M=0.3, not cost/baseline
        assertTrue(comparison.contains("<strong>30</strong>"))
        assertTrue(comparison.contains("Общая шкала для обоих наборов"))
    }

    @Test
    fun `coincident sets render once while zero baseline and failure have no invented bars`() {
        val same = section(renderer.render(HtmlReportFixtures.completed(sameIncumbents = true)), "comparison")
        assertEquals(1, Regex("data-configuration=").findAll(same).count())
        assertTrue(same.contains("Robust = Mean"))
        for (report in listOf(HtmlReportFixtures.zeroBaseline(), ContractFixtures.report(status = RunStatus.FAILED))) {
            val html = renderer.render(report)
            val graphs = section(html, "profiles") + section(html, "comparison")
            assertFalse(graphs.contains("class=\"chart-fill"))
            assertFalse(graphs.contains("<strong>100</strong>"))
            assertTrue(graphs.contains(if (report.result.status == RunStatus.FAILED) "Нет данных" else "Неприменимо: нулевой baseline"))
        }
    }

    @Test
    fun `budget bars keep estimates reserve disabled missing and zero limits distinct`() {
        val source = HtmlReportFixtures.completed()
        val result = source.result as SearchResult.Completed
        val input = requireNotNull(source.input)
        val selected = result.bestRobust.copy(metrics = result.bestRobust.metrics.copy(
            budgetUsage = BudgetUsage(25, 30, 5.0, 1)))
        val report = source.copy(input = input.copy(constraints = Constraints(100, 10.0)),
            result = result.copy(bestRobust = selected))
        val budgets = section(renderer.render(report), "indexes").substringBefore("<article")
        for (width in listOf(25, 30, 50)) assertTrue(budgets.contains("style=\"width:$width%\""))
        val disabled = section(renderer.render(report.copy(input = input.copy(constraints = Constraints(100, null)))), "indexes")
        assertTrue(disabled.contains("Лимит отключён · заполнение неприменимо"))
        val zero = section(renderer.render(HtmlReportFixtures.zeroBaseline().let { it.copy(
            input = requireNotNull(it.input).copy(constraints = Constraints(0, 0.0))) }), "indexes")
        assertTrue(zero.contains("Нулевой лимит · расход 0"))
        assertFalse(zero.contains("style=\"width:"))
        val failed = section(renderer.render(ContractFixtures.report(status = RunStatus.FAILED)), "indexes")
        assertTrue(failed.contains("Нет данных · заполнение неизвестно"))
        assertFalse(failed.contains("style=\"width:"))
    }

    @Test
    fun `extreme finite ratios do not produce infinite labels or invalid CSS`() {
        val source = HtmlReportFixtures.completed()
        val result = source.result as SearchResult.Completed
        val report = source.copy(result = result.copy(bestRobust = result.bestRobust.copy(
            metrics = result.bestRobust.metrics.copy(meanRatio = Double.MAX_VALUE, worstRatio = Double.MAX_VALUE))))
        val html = renderer.render(report)
        assertFalse(html.contains("Infinity"))
        assertFalse(html.contains("NaN"))
        Regex("style=\"width:([^%]+)%\"").findAll(html).forEach {
            assertTrue(it.groupValues[1].toDouble() in 0.0..100.0)
        }
    }

    private fun section(html: String, id: String): String =
        html.substringAfter("<section id=\"$id\"").substringBefore("</section>")
}
