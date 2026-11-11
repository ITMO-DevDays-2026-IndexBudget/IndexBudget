package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.fixtures.BudgetSeriesFixtures
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
        assertEquals(6, Regex("class=\"series-bar ").findAll(html).count())
        assertTrue(html.contains("Пустой набор"))
        assertTrue(html.contains("Одинаковый набор у точек №2, №3"))
        assertTrue(html.contains("Условия изменились"))
        assertTrue(html.contains("Сопоставимость ограничена"))
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
        assertTrue(html.contains("id=\"effects\""))
        assertTrue(html.contains("id=\"series-variant\""))
        assertTrue(html.contains("value=\"3\" selected"))
        for (point in summary["points"].toList().take(4)) {
            val ordinal = point["ordinal"].intValue()
            assertTrue(html.contains(point["run_id"].stringValue()))
            assertTrue(html.contains("id=\"variant-$ordinal-overview\""))
            assertTrue(html.contains("id=\"variant-$ordinal-run\""))
        }
        assertTrue(html.contains("id=\"variant-2-query-0\""))
        assertTrue(html.contains("href=\"#variant-2-query-0\""))
        assertTrue(html.contains("for=\"variant-2-query-sort\""))
        assertTrue(html.contains("SQL и сохранённые планы"))
        assertFalse(html.contains("id=\"variant-5\""))
        val ids = Regex("\\sid=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.toSet().size, "Embedded reports must never duplicate document IDs")
        assertFalse(Regex("<(iframe|link|img)\\b", RegexOption.IGNORE_CASE).containsMatchIn(html))
    }

    @Test
    fun `zero baseline disabled write and nonmonotonic outcomes stay distinct`() {
        val zero = renderer.render(generate("zero-baseline"))
        assertTrue(zero.contains("Неприменимо: нулевой baseline"))
        assertFalse(zero.contains("class=\"series-bar "))
        val disabled = renderer.render(generate("no-write"))
        assertTrue(disabled.contains("Модель записи отключена"))
        assertTrue(disabled.contains("Нет данных write units/ч"))
        assertTrue(renderer.render(generate("nonmonotonic")).contains("Обнаружен немонотонный эффект"))
        val write = renderer.render(generate("write"))
        assertTrue(write.contains("10000,5 write units/ч"))
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

    private fun generate(variant: String): JsonNode {
        BudgetSeriesFixtures.generate(directory.resolve(variant), variant)
        return mapper.readTree(Files.readString(directory.resolve("$variant/series.json")))
    }
}
