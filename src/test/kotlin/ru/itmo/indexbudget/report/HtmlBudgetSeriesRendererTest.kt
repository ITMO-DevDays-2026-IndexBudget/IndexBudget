package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.fixtures.BudgetSeriesFixtures
import ru.itmo.indexbudget.fixtures.HtmlReportFixtures
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

class HtmlBudgetSeriesRendererTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val renderer = HtmlBudgetSeriesRenderer()

    @Test
    fun `published mixed summary is exactly the JSON view with partial failed and skipped points`() {
        val summary = generate("mixed")
        val html = Files.readString(directory.resolve("mixed/series.html"))
        assertEquals(renderer.render(summary), html)
        assertEquals(listOf("completed", "completed", "partial", "failed", "skipped"),
            summary["points"].toList().map { it["status"].stringValue() })
        assertFalse(html.contains("class=\"series-bar "))
        assertTrue(html.contains("class=\"series-table\""))
        assertTrue(html.contains("Пустой набор"))
        assertTrue(html.contains("Одинаковый набор у точек №2, №3"))
        assertTrue(html.contains("Условия изменились"))
        assertTrue(html.contains("Условия изменились или не проверены"))
        assertTrue(html.contains("EXPLAIN_FAILED"))
        assertTrue(html.contains("CANCELLED"))
        assertFalse(html.contains("point-04/indexes.sql"))
        assertFalse(html.contains("point-05/report.html"))
        // Every saved profile percentage appears literally; the renderer must not derive it from rounded costs.
        for (point in summary["points"]) for (p in point.path("metrics").path("profiles")) {
            val number = p["gain_percent"].decimalValue().stripTrailingZeros().toPlainString().replace('.', ',')
            assertTrue(html.contains("$number %"))
        }
    }

    @Test
    fun `root report contains full saved variants with isolated anchors and largest usable budget open`() {
        val summary = generate("mixed")
        val html = Files.readString(directory.resolve("mixed/report.html"))
        assertTrue(html.contains("id=\"series-query-catalog\""))
        assertFalse(html.contains("id=\"effects\""))
        assertTrue(html.contains("id=\"series-variant\""))
        assertTrue(Regex("<option value=\"3\"[^>]* selected").containsMatchIn(html))
        for (point in summary["points"].toList().take(4)) {
            val ordinal = point["ordinal"].intValue()
            assertTrue(html.contains(point["run_id"].stringValue()))
            assertTrue(html.contains("id=\"variant-$ordinal-overview\""))
            assertTrue(html.contains("id=\"variant-$ordinal-run\""))
        }
        assertTrue(html.contains("id=\"variant-2-query-0\""))
        assertTrue(html.contains("href=\"#variant-2-query-0\""))
        assertTrue(html.contains("for=\"variant-2-query-sort\""))
        assertTrue(html.contains("data-query-sql"))
        assertTrue(html.contains("data-plan-key"))
        assertTrue(html.contains("id=\"series-resource-model\""))
        assertTrue(html.contains("число INCLUDE-колонок"))
        val anchors = Regex("href=\"#([^\"]+)\"").findAll(html).map { it.groupValues[1] }
        anchors.forEach { assertTrue(it in Regex("id=\"([^\"]+)\"").findAll(html).map { match -> match.groupValues[1] }.toSet(), "Missing anchor $it") }
        assertFalse(html.contains("id=\"variant-5\""))
        val ids = Regex("\\sid=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.toSet().size, "Embedded reports must never duplicate document IDs")
        assertFalse(Regex("<(iframe|link|img)\\b", RegexOption.IGNORE_CASE).containsMatchIn(html))
    }

    @Test
    fun `overview retains current variant facts with partial empty failed and unknown regressions`() {
        val summary = generate("mixed")
        val html = Files.readString(directory.resolve("mixed/report.html"))
        assertTrue(html.contains("data-overview-variant=\"3\">"))
        assertTrue(html.contains("data-overview-variant=\"2\">"), "Without JavaScript all saved results remain readable")
        assertFalse(overview(html, 3).contains("Поиск остановлен досрочно"))
        assertTrue(overview(html, 1).contains("Выбран пустой набор"))
        assertTrue(html.contains("0\u00a0байт"))
        assertTrue(overview(html, 1).contains("Регрессий SQL не обнаружено"))
        assertTrue(overview(html, 4).contains("Пригодная рекомендация не сохранена"))
        assertTrue(overview(html, 4).contains("Эффект не определён"))
        assertTrue(overview(renderer.render(summary), 1).contains("Рост стоимости: нет данных"))
    }

    @Test
    fun `zero baseline disabled write and nonmonotonic outcomes stay distinct`() {
        val zero = renderer.render(generate("zero-baseline"))
        assertTrue(zero.contains("Неприменимо: нулевой baseline"))
        assertFalse(zero.contains("class=\"series-bar "))
        val disabled = renderer.render(generate("no-write"))
        assertTrue(disabled.contains("Модель записи отключена"))
        assertTrue(disabled.contains("<dt>Запись, write units/ч</dt><dd>Нет данных</dd>"))
        assertTrue(renderer.render(generate("nonmonotonic")).contains("При большем бюджете оценка хотя бы одного профиля стала хуже"))
        val write = renderer.render(generate("write"))
        assertTrue(write.contains("10000,5\u00a0write units/ч"))
        assertFalse(write.contains("Лимит отключён"))
    }

    @Test
    fun `links survive relocation and never target missing artifacts or external dependencies`() {
        generate("mixed")
        val moved = Files.move(directory.resolve("mixed"), directory.resolve("перенесённая серия"))
        val html = Files.readString(moved.resolve("series.html"))
        val links = Regex("href=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        for (link in links.filterNot { it.startsWith("#") }) {
            assertFalse(Path.of(link).isAbsolute)
            assertTrue(Files.isRegularFile(moved.resolve(link)), link)
        }
        for (link in links.filter { it.startsWith("#") }) assertTrue(html.contains("id=\"${link.drop(1)}\""))
        assertFalse(Regex("<(script|link|img|iframe)\\b|url\\(", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertTrue(html.contains("default-src 'none'"))
    }

    @Test
    fun `untrusted text is escaped and artifact URLs are limited to the point directory`() {
        val summary = generate("same")
        val attack = "</style><script>alert('x')</script>"
        (summary as ObjectNode).put("series_id", attack)
        (summary["preparation"]["candidates"][0] as ObjectNode).put("ddl", attack)
        (summary["settings"]["profiles"][0] as ObjectNode).put("id", attack)
        val artifacts = summary["points"][0]["artifacts"] as ObjectNode
        artifacts.put("report.html", "https://example.org/steal")
        artifacts.put("report.json", "../secret.json")
        artifacts.put("indexes.sql", "javascript:alert(1)")
        val html = renderer.render(summary)
        assertFalse(html.contains(attack))
        assertTrue(html.contains("&lt;script&gt;"))
        assertFalse(html.contains("https://example.org"))
        assertFalse(html.contains("../secret.json"))
        assertFalse(html.contains("javascript:"))
    }

    @Test
    fun `renderer preserves saved percentages and exact large budget values`() {
        val summary = generate("same")
        val point = summary["points"][0] as ObjectNode
        point.put("budget_value", Long.MAX_VALUE)
        (point["metrics"]["profiles"][0] as ObjectNode).put("gain_percent", 12.3456789)
        point.putNull("conditions_match")
        val html = renderer.render(summary)
        assertTrue(html.contains("9223372036854775807"))
        assertTrue(html.contains("12,3456789 %"))
        assertTrue(html.contains("Сопоставимость не установлена"))
        assertFalse(Regex("NaN|Infinity").containsMatchIn(html))
    }

    @Test
    fun `series uses compact values short index labels and exact disclosures`() {
        val summary = generate("same")
        val point = summary["points"][0] as ObjectNode
        (point["metrics"]["profiles"][0] as ObjectNode).put("gain_percent", 79.583983315)
        val html = renderer.render(summary)
        assertTrue(html.contains("<h1>Индексы при разных бюджетах"))
        assertFalse(html.contains("class=\"eyebrow\"") || html.contains("class=\"brand-icon\""))
        assertTrue(html.contains("Снижение на 79,6%"))
        assertTrue(html.contains("79,583983315 %"))
        assertTrue(html.contains("3\u00a0MiB"))
        assertTrue(html.contains("records · btree ("))
        assertFalse(Regex("<summary>ib_[^<]+").containsMatchIn(html))
    }

    @Test
    fun `single profile does not duplicate M and R and tiny changes keep direction`() {
        val summary = generate("same")
        val profiles = summary["settings"]["profiles"] as tools.jackson.databind.node.ArrayNode
        val first = profiles[0]
        profiles.removeAll()
        profiles.add(first)
        for (point in summary["points"]) {
            val metrics = point["metrics"] as ObjectNode
            val rows = metrics["profiles"] as tools.jackson.databind.node.ArrayNode
            val row = rows[0] as ObjectNode
            row.put("gain_percent", -0.01)
            rows.removeAll()
            rows.add(row)
        }
        val html = renderer.render(summary)
        assertTrue(html.contains("Рост менее 0,1%"))
        assertTrue(html.contains("Отношение к baseline"))
        assertFalse(html.contains("Среднее отношение M") || html.contains("Худшее отношение R"))
        assertEquals(1, Regex("class=\"effect-sentence\"").findAll(overview(html, 1)).count())
    }

    @Test
    fun `overview follows saved profile rows and keeps missing effects and definitions explicit`() {
        val summary = generate("same")
        val point = summary["points"][0]
        val rows = point["metrics"]["profiles"] as tools.jackson.databind.node.ArrayNode
        val third = (rows[0].deepCopy() as ObjectNode).put("profile_id", "user_profile_3")
        rows.add(third)
        val three = overview(renderer.render(summary), 1)
        assertEquals(1, Regex("class=\"effect-sentence\"").findAll(three).count())
        assertTrue(three.contains("user_profile_3"))
        rows.removeAll()
        (summary["preparation"]["candidates"] as tools.jackson.databind.node.ArrayNode).removeAll()
        val unavailable = overview(renderer.render(summary), 1)
        assertTrue(unavailable.contains("Эффект не определён"))
        assertTrue(unavailable.contains("Определение неизвестно"))
        assertFalse(unavailable.contains(point["selected_index_ids"][0].stringValue()))
    }

    @Test
    fun `missing input with one saved profile has no nonexistent comparison link without JavaScript`() {
        val summary = generate("same")
        val point = summary["points"][0]
        val ordinal = point["ordinal"].intValue()
        // An incomplete retained report can preserve evaluated metrics without input or the coincidence flag.
        val report = HtmlReportFixtures.oneProfile().copy(
            runId = point["run_id"].stringValue(),
            inputHash = summary["source"]["workload_input_hash"].stringValue(),
            input = null,
            incumbentsCoincide = null,
        )
        val html = renderer.render(summary, mapOf(ordinal to report))
        assertTrue(html.contains("id=\"variant-$ordinal-queries\""))
        assertFalse(html.contains("id=\"variant-$ordinal-comparison\""))
        assertFalse(html.contains("data-section=\"comparison\""), "Static navigation must not rely on JavaScript to remove a dead link")
        assertFalse(html.contains("href=\"#variant-$ordinal-comparison\""))
    }

    @Test
    fun `same table names from different schemas remain distinct`() {
        val summary = generate("same")
        val candidates = summary["preparation"]["candidates"].toList()
        val selectedIds = summary["points"][0]["selected_index_ids"] as tools.jackson.databind.node.ArrayNode
        selectedIds.removeAll()
        candidates.take(2).forEachIndexed { index, candidate ->
            (candidate as ObjectNode).put("schema", if (index == 0) "public" else "archive")
            candidate.put("table", "records")
            selectedIds.add(candidate["id"].stringValue())
        }
        val html = renderer.render(summary)
        assertTrue(html.contains("public.records · btree ("))
        assertTrue(html.contains("archive.records · btree ("))
    }

    private fun generate(variant: String): JsonNode {
        BudgetSeriesFixtures.generate(directory.resolve(variant), variant)
        return mapper.readTree(Files.readString(directory.resolve("$variant/series.json")))
    }

    private fun overview(html: String, ordinal: Int): String =
        html.substringAfter("data-overview-variant=\"$ordinal\">").substringBefore("</article>")
}
