package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.fixtures.HtmlReportFixtures
import ru.itmo.indexbudget.fixtures.RecordedSeriesHtmlReportFixture
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class HtmlSeriesCatalogTest {
    @Test
    fun `series keeps each SQL DDL and raw plan once and links every variant`() {
        val reports = (0..2).associate { it + 1 to RecordedSeriesHtmlReportFixture.report(it) }
        val catalog = HtmlSeriesCatalog(reports)
        val queries = catalog.queries()
        val indexes = catalog.indexes()
        val source = reports.getValue(3)

        assertEquals(2, Regex("data-query-sql").findAll(queries).count())
        assertEquals(4, Regex("data-plan-key=").findAll(queries).count())
        assertEquals(2, Regex("data-index-ddl").findAll(indexes).count())
        for (query in requireNotNull(source.input).queries) assertEquals(1, count(queries, escape(query.sql)))
        for (candidate in source.candidates.candidates) assertEquals(1, count(indexes, escape(requireNotNull(candidate.ddl))))
        val plans = reports.values.flatMap { report ->
            listOfNotNull(report.result.baseline, report.result.selected?.evaluation,
                report.result.bestRobust?.evaluation, report.result.bestMean?.evaluation)
                .flatMap { it.plansByQueryId.values }.map { it.json }
        }.toSet()
        plans.forEach { assertEquals(1, count(queries, escape(it))) }
        for (ordinal in reports.keys) for (query in 0..1) {
            assertEquals(1, count(queries, "id=\"variant-$ordinal-query-$query\""))
        }
        assertTrue(indexes.contains("data-index-members=\"2,3\""))
        assertTrue(indexes.contains("data-index-members=\"3\""))
        assertTrue(queries.contains("99,09854423292273"), "Keep saved, unrounded percentages")
        assertTrue(indexes.contains("835584"), "Keep exact bytes")
        assertLocalReferences(queries + indexes)
    }

    @Test
    fun `same query ID with another SQL keeps both versions and separate anchors`() {
        val source = HtmlReportFixtures.completed()
        val input = requireNotNull(source.input)
        val changed = source.copy(input = input.copy(queries = input.queries.mapIndexed { index, query ->
            if (index == 0) query.copy(sql = query.sql + " /* another literal example */") else query
        }))
        val html = HtmlSeriesCatalog(mapOf(1 to source, 2 to changed)).queries()

        assertEquals(3, Regex("data-query-sql").findAll(html).count())
        assertEquals(1, count(html, "another literal example"))
        assertEquals(1, count(html, "id=\"variant-1-query-0\""))
        assertEquals(1, count(html, "id=\"variant-2-query-0\""))
        assertLocalReferences(html)
    }

    @Test
    fun `unknown candidate estimates remain distinct from zero and changed estimates`() {
        val source = HtmlReportFixtures.completed()
        val first = source.candidates.candidates.first()
        val zero = first.copy(estimatedBytes = 0, budgetedBytes = 0, writeUnitsPerHour = 0.0)
        val unknown = first.copy(estimatedBytes = null, budgetedBytes = null, writeUnitsPerHour = null)
        fun withCandidate(candidate: IndexCandidate) = source.copy(candidates = CandidateBatch(listOf(candidate)))
        val html = HtmlSeriesCatalog(mapOf(1 to withCandidate(zero), 2 to withCandidate(unknown))).indexes()

        assertEquals(1, Regex("data-index-ddl").findAll(html).count())
        assertTrue(html.contains("<th scope=\"row\">1</th><td>0</td><td>0</td><td>0</td>"))
        assertTrue(html.contains("<th scope=\"row\">2</th><td>Нет данных</td><td>Нет данных</td><td>Нет данных</td>"))
    }

    @Test
    fun `manual plans are preserved and shared with equal automatic plans`() {
        val source = HtmlReportFixtures.completed(sameIncumbents = true)
        val selected = requireNotNull(source.result.selected)
        val id = selected.evaluation.costByQueryId.keys.first()
        val unique = "[{\"Plan\":{\"Node Type\":\"Manual fixture\",\"Total Cost\":12.75}}]"
        val manual = ManualComparison(
            status = ManualComparisonStatus.COMPLETED,
            candidates = source.candidates.candidates.filter { it.id in selected.evaluation.candidateIds }.sortedBy { it.id },
            evaluation = selected.evaluation.copy(plansByQueryId = selected.evaluation.plansByQueryId + (id to ExplainPlan(unique))),
            metrics = selected.metrics,
        )
        val html = HtmlSeriesCatalog(mapOf(1 to source.copy(manualComparison = manual), 2 to source)).queries()

        assertEquals(1, count(html, escape(unique)))
        assertTrue(html.contains("Ручной набор"))
        assertLocalReferences(html)
    }

    @Test
    fun `failed and missing input never invent selected SQL costs or index membership`() {
        val source = HtmlReportFixtures.completed()
        val failed = source.copy(result = ContractFixtures.failedResult(), input = null)
        val catalog = HtmlSeriesCatalog(mapOf(1 to source, 2 to failed))
        val queries = catalog.queries()
        val indexes = catalog.indexes()

        assertTrue(queries.contains("Вариант 1"))
        assertFalse(indexes.contains("data-index-members=\"1,2\""))
        assertFalse(indexes.contains("data-index-members=\"2\""))
        assertLocalReferences(queries + indexes)
        assertTrue(HtmlSeriesCatalog(mapOf(2 to failed)).queries().contains("Исходные SQL не сохранены"))
    }

    @Test
    fun `all untrusted source text is escaped and identifier collisions stay separate`() {
        val source = HtmlReportFixtures.completed(sameIncumbents = true)
        val input = requireNotNull(source.input)
        val result = source.result as SearchResult.Completed
        val payload = "<script>alert(1)</script>"
        val query = input.queries.first()
        val first = source.candidates.candidates.first()
        val altered = source.copy(
            input = input.copy(queries = input.queries.map { if (it.id == query.id) it.copy(sql = payload) else it }),
            result = result.copy(baseline = result.baseline.copy(plansByQueryId =
                result.baseline.plansByQueryId + (query.id to ExplainPlan(payload)))),
            candidates = source.candidates.copy(candidates = source.candidates.candidates.map {
                if (it.id == first.id) it.copy(ddl = payload,
                    sourceBranches = setOf(CandidateSourceBranch(payload, payload))) else it
            }),
        )
        val catalog = HtmlSeriesCatalog(mapOf(1 to source, 2 to altered))
        val queries = catalog.queries()
        val indexes = catalog.indexes()
        assertFalse(queries.contains(payload))
        assertFalse(indexes.contains(payload))
        assertTrue(queries.contains(escape(payload)))
        assertTrue(indexes.contains(escape(payload)))
        assertEquals(source.candidates.candidates.size + 1, Regex("data-candidate-id=").findAll(indexes).count(),
            "Changed definition/DDL must not be silently merged by ID")
        assertLocalReferences(queries + indexes)
    }

    @Test
    fun `schema ambiguity and INCLUDE remain visible while unselected candidates are retained`() {
        val long = HtmlReportFixtures.longNames()
        val html = HtmlSeriesCatalog(mapOf(1 to long)).indexes()
        for (candidate in long.candidates.candidates) {
            val table = candidate.definition.table
            assertTrue(html.contains("${table.schema}.${table.name}(value)"))
        }
        val wide = HtmlSeriesCatalog(mapOf(1 to HtmlReportFixtures.wide())).indexes()
        assertTrue(wide.contains("wide_not_evaluated"))
        assertTrue(wide.contains("не выбран автоматически"))
        assertTrue(wide.contains("Нет данных"))
        val include = HtmlSeriesCatalog(mapOf(1 to HtmlReportFixtures.covering())).indexes()
        assertTrue(include.contains("INCLUDE (payload)"))
        assertTrue(include.contains("visibility map"))
    }

    @Test
    fun `manifest retains SQL and the common candidate pool without point reports`() {
        val source = HtmlReportFixtures.completed()
        val summary = manifest(source)
        val catalog = HtmlSeriesCatalog(emptyMap(), summary)
        val queries = catalog.queries()
        val indexes = catalog.indexes()
        for (query in requireNotNull(source.input).queries) assertEquals(1, count(queries, escape(query.sql)))
        for (candidate in source.candidates.candidates) assertEquals(1, count(indexes, escape(requireNotNull(candidate.ddl))))
        assertEquals(0, count(queries, "data-plan-key"), "Aggregate series metrics must not invent per-query plans")
        assertTrue(queries.contains("<th scope=\"row\">Вариант 1</th><td>Нет данных</td><td>Нет данных</td>"))
        assertTrue(indexes.contains("data-index-members=\"1\""))
        assertFalse(indexes.contains("data-index-members=\"1,2"), "Failed and skipped points cannot select an index")
        assertTrue(indexes.contains("Общий пул серии"))
        assertLocalReferences(queries + indexes)
    }

    @Test
    fun `manifest merges available definitions and restores missing input without duplicate SQL or DDL`() {
        val source = HtmlReportFixtures.completed()
        val input = requireNotNull(source.input)
        val summary = manifest(source)
        (summary["points"][1] as ObjectNode).put("status", "partial")
        val catalog = HtmlSeriesCatalog(mapOf(1 to source.copy(input = null)), summary)
        val queries = catalog.queries()
        val indexes = catalog.indexes()
        assertEquals(input.queries.size, count(queries, "data-query-sql"))
        assertEquals(source.candidates.candidates.size, count(indexes, "data-index-ddl"))
        assertEquals(source.candidates.candidates.size, count(indexes, "data-candidate-id="))
        assertTrue(indexes.contains("data-index-members=\"1,2\""))
        assertFalse(indexes.contains("Общий пул серии"), "Equal costs need no duplicate pool row")
        for (position in input.queries.indices) {
            assertEquals(1, count(queries, "id=\"variant-1-query-$position\""))
            assertEquals(1, count(queries, "id=\"variant-2-query-$position\""))
        }
        assertLocalReferences(queries + indexes)
    }

    @Test
    fun `manifest preserves changed candidate costs and definitions and escapes fallback source`() {
        val source = HtmlReportFixtures.completed()
        val summary = manifest(source)
        val attack = "</pre><script>alert('fallback')</script>"
        (summary["settings"]["queries"][0] as ObjectNode).put("sql", attack)
        val candidate = summary["preparation"]["candidates"][0] as ObjectNode
        candidate.put("estimated_bytes", 0)
        candidate.putNull("budgeted_bytes")
        val merged = HtmlSeriesCatalog(mapOf(1 to source), summary)
        assertTrue(merged.indexes().contains("<th scope=\"row\">Общий пул серии</th><td>0</td><td>Нет данных</td>"))
        assertEquals(source.candidates.candidates.size, count(merged.indexes(), "data-candidate-id="))
        assertTrue(merged.queries().contains(escape(attack)))
        assertFalse(merged.queries().contains(attack))

        candidate.put("ddl", attack)
        val changed = HtmlSeriesCatalog(mapOf(1 to source), summary).indexes()
        assertEquals(source.candidates.candidates.size + 1, count(changed, "data-candidate-id="))
        assertTrue(changed.contains(escape(attack)))
        assertFalse(changed.contains(attack))
        assertLocalReferences(changed)
    }

    private fun manifest(report: AnalysisReport): JsonNode {
        val input = requireNotNull(report.input)
        val root = mapOf(
            "settings" to mapOf("queries" to input.queries.map { mapOf("id" to it.id, "sql" to it.sql) }),
            "preparation" to mapOf("candidates" to report.candidates.candidates.map { candidate -> mapOf(
                "id" to candidate.id, "schema" to candidate.definition.table.schema, "table" to candidate.definition.table.name,
                "keys" to candidate.definition.keys.map { mapOf("column" to it.column.name,
                    "direction" to it.direction.name.lowercase(), "nulls" to it.nulls.name.lowercase()) },
                "included_columns" to candidate.definition.includedColumns.map { it.name }, "ddl" to candidate.ddl,
                "estimated_bytes" to candidate.estimatedBytes, "budgeted_bytes" to candidate.budgetedBytes,
                "write_units_per_hour" to candidate.writeUnitsPerHour,
            ) }),
            "points" to listOf("completed", "failed", "skipped").mapIndexed { index, status -> mapOf(
                "ordinal" to index + 1, "status" to status,
                "selected_index_ids" to report.result.selected?.evaluation?.candidateIds,
            ) },
        )
        val mapper = JsonMapper.builder().build()
        return mapper.readTree(mapper.writeValueAsString(root))
    }

    private fun assertLocalReferences(html: String) {
        val ids = Regex("\\sid=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.toSet().size, "Renderer-owned IDs must be unique")
        val links = Regex("href=\"#([^\"]+)\"").findAll(html).map { it.groupValues[1] }
        for (link in links) assertTrue(link in ids, "Missing anchor: $link")
    }

    private fun count(text: String, literal: String): Int = Regex(Regex.escape(literal)).findAll(text).count()
    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
