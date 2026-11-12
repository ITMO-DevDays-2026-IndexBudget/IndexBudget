package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.HtmlReportFixtures
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

class HtmlReportReadabilityTest {
    private val renderer = HtmlReportRenderer()

    @Test
    fun `one profile has one effect and no duplicate objective comparison`() {
        val html = renderer.render(HtmlReportFixtures.oneProfile())
        val overview = section(html, "overview")
        assertTrue(overview.contains("Результат анализа"))
        assertTrue(overview.contains("Снижение на"))
        assertFalse(overview.contains("Средний эффект"))
        assertFalse(overview.contains("Худший профиль"))
        assertFalse(html.contains("id=\"comparison\""))
        assertFalse(html.contains("id=\"profiles\""))
        assertTrue(section(html, "method").contains("Точные стоимости профиля и вес"))
        assertTrue(overview.contains("по сравнению с существующими индексами"))
    }

    @Test
    fun `profile rows follow actual user profiles for one two and three scenarios`() {
        for (report in listOf(HtmlReportFixtures.oneProfile(), HtmlReportFixtures.completed(), HtmlReportFixtures.threeProfiles())) {
            val profiles = requireNotNull(report.input).profiles
            val html = renderer.render(report)
            if (profiles.size > 1) {
                val section = section(html, "profiles")
                assertEquals(profiles.size, Regex("data-profile-id=").findAll(section).count())
                profiles.forEach { assertTrue(section.contains(it.id), it.id) }
                assertTrue(html.indexOf("id=\"queries\"") < html.indexOf("id=\"profiles\""))
                assertTrue(html.indexOf("id=\"profiles\"") < html.indexOf("id=\"indexes\""))
            } else assertFalse(html.contains("id=\"profiles\""))
            assertTrue(html.indexOf("id=\"queries\"") < html.indexOf("id=\"indexes\""))
            assertTrue(html.indexOf("id=\"queries\"") < html.indexOf("id=\"search\""))
        }
    }

    @Test
    fun `coincident multi-profile results explain the equality in a disclosure`() {
        val html = renderer.render(HtmlReportFixtures.completed(sameIncumbents = true))
        assertTrue(html.contains("<summary>Оба критерия выбрали один набор</summary>"))
        assertEquals(1, Regex("data-configuration=").findAll(html).count())
        assertTrue(html.contains("best_robust"))
        assertTrue(html.contains("best_mean"))
    }

    @Test
    fun `short labels preserve full identifiers and distinguish same tables in different schemas`() {
        val report = HtmlReportFixtures.longNames()
        val html = renderer.render(report)
        val overview = section(html, "overview")
        val visibleOverview = overview.substringBefore("<details")
        for (candidate in report.candidates.candidates) {
            val table = candidate.definition.table
            assertTrue(overview.contains("${table.schema}.${table.name} · btree (value)"))
            assertFalse(visibleOverview.contains(candidate.id))
            assertTrue(html.contains(candidate.id))
        }
        val queries = section(html, "queries")
        assertTrue(queries.contains("href=\"#query-0\">SQL 1</a>"))
        assertTrue(queries.contains("href=\"#query-1\">SQL 2</a>"))
        for (query in requireNotNull(report.input).queries) {
            assertTrue(queries.contains(query.id))
            assertTrue(queries.contains(query.sql.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")))
        }
    }

    @Test
    fun `tiny nonzero changes retain their direction instead of becoming no change`() {
        for ((increase, direction) in listOf(false to "Снижение", true to "Рост")) {
            val report = HtmlReportFixtures.tinyEffect(increase)
            val html = renderer.render(report)
            val overview = section(html, "overview")
            assertTrue(overview.contains("$direction менее 0,1%"), overview)
            val effect = Regex("class=\"effect-sentence[^\"]*\"[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
                .find(overview)?.groupValues?.get(1).orEmpty()
            assertFalse(effect.contains("Без изменения"))
            val json = JsonMapper().readTree(JsonReportWriter().render(report))
            assertNotEquals(0.0, json["summary"]["mean_effect"]["gain_percent"].doubleValue())
        }
        assertTrue(section(renderer.render(HtmlReportFixtures.empty()), "overview").contains("Без изменения"))
        assertTrue(section(renderer.render(HtmlReportFixtures.zeroBaseline()), "overview").contains("нулевой baseline"))
    }

    @Test
    fun `profile details preserve the full saved percentage without recomputing it`() {
        val source = HtmlReportFixtures.completed()
        val result = source.result as SearchResult.Completed
        val exactPercent = 79.58398331512345
        val configuration = result.bestRobust.copy(metrics = result.bestRobust.metrics.copy(
            profiles = result.bestRobust.metrics.profiles.map { it.copy(gainPercent = exactPercent) },
        ))
        val report = source.copy(result = result.copy(bestRobust = configuration))
        val html = renderer.render(report)
        val profiles = section(html, "profiles")
        assertTrue(profiles.contains("79,6%"))
        assertTrue(profiles.contains(exactPercent.toString().replace('.', ',')), "Exact saved percent must remain inspectable")
        val json = JsonMapper().readTree(JsonReportWriter().render(report))
        assertEquals(exactPercent, json["profile_comparisons"][0]["selected"]["gain_percent"].doubleValue())
    }

    @Test
    fun `short incomplete observation windows are prominent and distinct from hourly frequencies`() {
        val report = HtmlReportFixtures.imported()
        val input = requireNotNull(report.input)
        val provenance = requireNotNull(input.provenance)
        val start = Instant.parse("2026-11-10T08:00:00Z")
        val windows = provenance.windows.mapIndexed { index, window -> window.copy(
            startedAt = start,
            finishedAt = start.plusMillis(if (index == 0) 95 else 71),
            complete = index == 0,
            coverage = ObservationCoverage(if (index == 0) 10 else null, null, null, null),
        ) }
        val html = renderer.render(report.copy(input = input.copy(provenance = provenance.copy(windows = windows))))
        val overview = section(html, "overview")
        assertTrue(overview.contains("pg_stat_statements"))
        assertTrue(overview.contains("95"))
        assertTrue(overview.contains("71"))
        assertTrue(overview.contains("мс"))
        assertTrue(overview.contains("неполн", ignoreCase = true))
        assertTrue(overview.contains("SQL"))
        assertFalse(overview.contains("недостаточн", ignoreCase = true), "No new observation-duration threshold")
        val history = section(html, "provenance")
        assertTrue(history.contains("Историческое время выполнения"))
        assertTrue(history.contains("Нет данных"))
        assertTrue(history.contains("пересчитаны на час"))
    }

    @Test
    fun `valid long observation intervals do not overflow presentation conversion`() {
        val source = HtmlReportFixtures.imported()
        val input = requireNotNull(source.input)
        val provenance = requireNotNull(input.provenance)
        val windows = provenance.windows.map { it.copy(
            startedAt = Instant.parse("1600-01-01T00:00:00Z"), finishedAt = Instant.parse("2026-11-11T00:00:00Z"),
        ) }
        val html = assertDoesNotThrow<String> {
            renderer.render(source.copy(input = input.copy(provenance = provenance.copy(windows = windows))))
        }
        assertTrue(section(html, "overview").contains("pg_stat_statements"))
        assertTrue(section(html, "provenance").contains("1600-01-01T00:00:00Z"))
    }

    @Test
    fun `embedded report isolates every control label copy target and SQL anchor`() {
        val prefix = "variant-3-"
        val html = renderer.renderContent(HtmlReportFixtures.completed(), prefix)
        val ids = Regex("\\sid=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it.startsWith(prefix) })
        for (target in Regex("\\s(?:for|data-copy)=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }) {
            assertTrue(target in ids, "Missing or cross-report control target $target")
        }
        assertTrue(html.contains("data-copy=\"variant-3-copy-sql-0\""))
        assertTrue(html.contains("data-copy=\"variant-3-copy-ddl-0\""))
        assertTrue(html.contains("href=\"#variant-3-query-0\""))
        assertFalse(html.contains("<!doctype") || html.contains("<script>"))
        assertThrows(IllegalArgumentException::class.java) { renderer.renderContent(HtmlReportFixtures.completed(), "user-controlled-") }
    }

    @Test
    fun `saved costs still expose regressions when workload and comparison list are missing`() {
        val report = HtmlReportFixtures.regression().copy(input = null, queryComparisons = emptyList())
        val html = renderer.render(report)
        val queries = section(html, "queries")
        val regressedRow = Regex("<tr[^>]*data-order=\"1\"[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).find(queries)
        assertNotNull(regressedRow)
        assertTrue(requireNotNull(regressedRow).value.contains("class=\"regression\""))
        assertTrue(regressedRow.value.contains("Рост cost · процент неизвестен"))
        assertTrue(section(html, "overview").contains("<dt>Регрессии SQL</dt><dd>1 из 2</dd>"))
    }

    @Test
    fun `reusable summaries omit catalogs and keep isolated query links`() {
        val report = HtmlReportFixtures.completed()
        val prefix = "variant-2-"
        val queries = renderer.renderSection(report, HtmlReportSection.QUERIES, prefix)
        assertTrue(queries.contains("id=\"variant-2-query-comparison\""))
        assertTrue(queries.contains("href=\"#variant-2-query-0\""))
        assertFalse(queries.contains("data-copy="))
        val resources = renderer.renderSection(report, HtmlReportSection.INDEXES, prefix)
        assertTrue(resources.contains("id=\"variant-2-resource-metrics\""))
        assertFalse(resources.contains("data-selected-index="))
        val catalog = renderer.renderSection(report, HtmlReportSection.INDEX_DETAILS, prefix)
        assertTrue(catalog.contains("data-copy=\"variant-2-copy-ddl-0\""))
        val diagnostics = renderer.renderSection(report, HtmlReportSection.SEARCH, prefix, includeCandidateCatalog = false)
        assertFalse(diagnostics.contains("Все сохранённые кандидаты"))
        assertTrue(diagnostics.contains("Полностью оценённые наборы"))
    }

    @Test
    fun `identical saved plans have one body per SQL and retain every variant label`() {
        val source = HtmlReportFixtures.completed()
        val result = source.result as SearchResult.Completed
        val queryId = requireNotNull(source.input).queries.first().id
        val json = "[{\"Plan\":{\"Node Type\":\"SharedPlanBody\",\"Total Cost\":42}}]"
        val plans = result.baseline.plansByQueryId + (queryId to ExplainPlan(json))
        val report = source.copy(result = result.copy(
            baseline = result.baseline.copy(plansByQueryId = plans),
            bestRobust = result.bestRobust.copy(evaluation = result.bestRobust.evaluation.copy(plansByQueryId = plans)),
            bestMean = result.bestMean.copy(evaluation = result.bestMean.evaluation.copy(plansByQueryId = plans)),
        ))
        val details = renderer.renderSection(report, HtmlReportSection.QUERY_DETAILS)
        assertEquals(1, Regex("SharedPlanBody").findAll(details).count())
        assertTrue(details.contains("Существующие индексы · Выбранный набор · best_robust · best_mean · EXPLAIN JSON"))
        assertEquals(requireNotNull(report.input).queries.size, Regex("data-copy=\"copy-sql-").findAll(details).count())
    }

    private fun section(html: String, id: String): String {
        val marker = "<section id=\"$id\""
        assertTrue(html.contains(marker), "Missing section $id")
        return html.substringAfter(marker).substringBefore("</section>")
    }
}
