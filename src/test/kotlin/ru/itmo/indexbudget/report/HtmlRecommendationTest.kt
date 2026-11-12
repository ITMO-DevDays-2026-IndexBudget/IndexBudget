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
    fun `recommendation names every method including B-tree on the same column`() {
        val source = ContractFixtures.report(sameIncumbents = true)
        val original = source.candidates.candidates.single()
        for (method in listOf("btree", "brin", "hash")) {
            val expected = "records · $method (created_at)"
            val candidate = if (method == "btree") original else original.copy(definition = original.definition.copy(
                method = method, keys = listOf(IndexKey(original.definition.keys.single().column,
                    operatorClass = if (method == "brin") "pg_catalog.timestamp_minmax_ops" else "pg_catalog.timestamp_ops"))))
            val input = requireNotNull(source.input).copy(indexMethods = IndexMethod.entries.toSet())
            val report = DefaultReportBuilder().build(input, source.candidates.copy(candidates = listOf(candidate)),
                source.result, RunContext())
            val overview = section(renderer.render(report), "overview")
            val recommendation = overview.substringAfter("<ul class=\"recommended-indexes\">", "").substringBefore("</ul>")
            assertTrue(recommendation.contains("<code>$expected</code>"), recommendation)
        }
    }

    @Test
    fun `overview leads with recommendation and warns about regressions before search statistics`() {
        val report = HtmlReportFixtures.regression()
        val html = renderer.render(report)
        val overview = section(html, "overview")
        val selected = report.candidates.candidates.single { it.id in report.summary.selectedIndexIds.orEmpty() }
        assertTrue(overview.contains(selected.definition.table.name + " · btree ("))
        assertTrue(overview.contains("<dt>Регрессии SQL</dt><dd>1 из 2</dd>"))
        assertTrue(overview.contains("href=\"#queries\""))
        assertFalse(overview.contains("Полностью оценённые наборы"))
        assertTrue(section(html, "search").contains("Полностью оценённые наборы"))
        val queries = section(html, "queries")
        assertTrue(queries.indexOf("href=\"#query-1\"") < queries.indexOf("href=\"#query-0\""))
        assertTrue(queries.contains("href=\"#query-1\">SQL 2"))
        assertTrue(queries.contains("id=\"query-1\""))
        assertTrue(section(html, "indexes").contains("Польза индекса отдельно от набора не измерена"))
    }

    @Test
    fun `profile table preserves supplied values without reconstructing domain metrics`() {
        val source = HtmlReportFixtures.completed()
        val result = source.result as SearchResult.Completed
        val selected = result.bestRobust.copy(metrics = result.bestRobust.metrics.copy(
            meanRatio = 0.3, worstRatio = 2.0,
            profiles = result.bestRobust.metrics.profiles.mapIndexed { index, profile ->
                profile.copy(ratio = if (index == 0) 0.25 else 2.0, gainPercent = if (index == 0) 75.0 else -100.0)
            }))
        val report = source.copy(result = result.copy(bestRobust = selected))
        val html = renderer.render(report)
        val json = jacksonObjectMapper().readTree(JsonReportWriter().render(report))
        val profiles = section(html, "profiles")
        assertFalse(profiles.contains("chart-fill"))
        assertTrue(profiles.contains("Снижение на 75%"))
        assertTrue(profiles.contains("Рост на 100%"))
        json["profile_comparisons"].forEach { profile ->
            val row = Regex("<tr data-profile-id=\"${profile["id"].asString()}\"[^>]*>").find(profiles)?.value
            assertNotNull(row)
            assertTrue(requireNotNull(row).contains("data-baseline=\"${profile["selected"]["baseline_cost"].doubleValue()}\""))
            assertTrue(row.contains("data-cost=\"${profile["selected"]["cost"].doubleValue()}\""))
            assertTrue(row.contains("data-gain=\"${profile["selected"]["gain_percent"].doubleValue()}\""))
        }
        assertTrue(section(html, "comparison").contains("<dt>Среднее отношение M</dt><dd>0,3</dd>"))
    }

    @Test
    fun `coincident sets render once and unavailable effects remain explicit`() {
        val same = renderer.render(HtmlReportFixtures.completed(sameIncumbents = true))
        assertEquals(1, Regex("data-configuration=").findAll(same).count())
        assertTrue(same.contains("<summary>Оба критерия выбрали один набор</summary>"))
        for (report in listOf(HtmlReportFixtures.zeroBaseline(), ContractFixtures.report(status = RunStatus.FAILED))) {
            val html = renderer.render(report)
            assertFalse(html.contains("class=\"chart-fill"))
            assertTrue(html.contains(if (report.result.status == RunStatus.FAILED) "Нет данных" else "нулевой baseline"))
        }
    }

    @Test
    fun `resource table distinguishes estimates reserve disabled missing and zero limits`() {
        val source = HtmlReportFixtures.completed()
        val result = source.result as SearchResult.Completed
        val input = requireNotNull(source.input)
        val selected = result.bestRobust.copy(metrics = result.bestRobust.metrics.copy(
            budgetUsage = BudgetUsage(25, 30, 5.0, 1)))
        val report = source.copy(input = input.copy(constraints = Constraints(100, 10.0)),
            result = result.copy(bestRobust = selected))
        val budgets = section(renderer.render(report), "indexes")
        assertTrue(budgets.contains("30\u00a0байт"))
        assertTrue(budgets.contains("HypoPG: 25\u00a0байт"))
        assertTrue(budgets.contains("100\u00a0байт"))
        assertTrue(budgets.contains("5 условных ед./ч"))
        assertTrue(budgets.contains("10 условных ед./ч"))
        val disabled = section(renderer.render(report.copy(input = input.copy(constraints = Constraints(100, null)))), "indexes")
        assertTrue(disabled.contains("Бюджет записи отключён"))
        val zero = section(renderer.render(HtmlReportFixtures.zeroBaseline().let { it.copy(
            input = requireNotNull(it.input).copy(constraints = Constraints(0, 0.0))) }), "indexes")
        assertTrue(zero.contains("Нулевой лимит · расход 0"))
        val failed = section(renderer.render(ContractFixtures.report(status = RunStatus.FAILED)), "indexes")
        assertTrue(failed.contains("Нет данных"))
        assertFalse(failed.contains("style=\"width:"))
    }

    @Test
    fun `extreme finite ratios retain finite exact labels`() {
        val source = HtmlReportFixtures.completed()
        val result = source.result as SearchResult.Completed
        val report = source.copy(result = result.copy(bestRobust = result.bestRobust.copy(
            metrics = result.bestRobust.metrics.copy(meanRatio = Double.MAX_VALUE, worstRatio = Double.MAX_VALUE))))
        val html = renderer.render(report)
        assertFalse(html.contains("Infinity"))
        assertFalse(html.contains("NaN"))
    }

    @Test
    fun `partial reason appears beside status before recommendation and effect`() {
        val partial = ContractFixtures.partialResult()
        val report = ContractFixtures.report().copy(result = partial.copy(diagnostics = partial.diagnostics.copy(
            stopReasons = listOf(StopReason.REPAIR_LIMIT),
            errors = listOf(AnalysisError(ErrorCode.REPAIR_LIMIT, "Repair attempt limit reached.")),
        )))
        val overview = section(renderer.render(report), "overview")
        val reason = overview.indexOf("REPAIR_LIMIT")
        assertTrue(reason >= 0)
        assertTrue(reason < overview.indexOf("<h2>Предлагаемые индексы</h2>"))
        assertTrue(reason < overview.indexOf("class=\"effect-sentence\""))
        assertEquals(1, Regex("REPAIR_LIMIT").findAll(overview).count())
        assertTrue(overview.contains("Сохранён лучший полный допустимый результат"))
    }

    private fun section(html: String, id: String): String =
        html.substringAfter("<section id=\"$id\"").substringBefore("</section>")
}
