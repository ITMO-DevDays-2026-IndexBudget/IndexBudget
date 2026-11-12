package ru.itmo.indexbudget.report

import ru.itmo.indexbudget.domain.*
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.Locale

/** Shared, escaped source catalog. It displays retained values and never evaluates a configuration. */
internal class HtmlSeriesCatalog(reports: Map<Int, AnalysisReport>, summary: JsonNode? = null) {
    private val reports = reports.toSortedMap()
    private data class QueryKey(val id: String, val sql: String?)
    private data class QueryOccurrence(val ordinal: Int, val position: Int, val report: AnalysisReport?)
    private data class CandidateKey(val id: String, val definition: IndexDefinition, val ddl: String?)
    private data class CandidateOccurrence(val ordinal: Int, val candidate: IndexCandidate, val manual: Boolean)

    private val queryGroups = linkedMapOf<QueryKey, MutableList<QueryOccurrence>>()
    private val plans = linkedMapOf<String, Int>()
    private val candidateGroups = linkedMapOf<CandidateKey, MutableList<CandidateOccurrence>>()
    private val summaryQueries = summary?.path("settings")?.path("queries")?.mapNotNull { query ->
        string(query.path("id"))?.let { QueryKey(it, string(query.path("sql"))) }
    }.orEmpty()
    private val summaryKinds = summary?.path("settings")?.path("queries")?.toList().orEmpty().associate {
        string(it.path("id")) to string(it.path("statement_kind"))?.uppercase(Locale.ROOT)
    }
    private val summaryCandidates = summary?.path("preparation")?.path("candidates")?.toList().orEmpty().distinct()
    private val summaryPoints = summary?.path("points")?.toList().orEmpty()

    init {
        for ((ordinal, report) in this.reports) {
            queryIds(report).forEachIndexed { position, id ->
                val key = QueryKey(id, report.input?.queries?.firstOrNull { it.id == id }?.sql
                    ?: summaryQueries.firstOrNull { it.id == id }?.sql)
                queryGroups.getOrPut(key) { mutableListOf() }.add(QueryOccurrence(ordinal, position, report))
            }
            evaluations(report).forEach { (_, evaluation) ->
                evaluation?.plansByQueryId?.values?.forEach { plan ->
                    plans.getOrPut(plan.json) { plans.size }
                }
            }
            for (candidate in report.candidates.candidates) {
                val key = CandidateKey(candidate.id, candidate.definition, candidate.ddl)
                candidateGroups.getOrPut(key) { mutableListOf() }.add(CandidateOccurrence(ordinal, candidate, false))
            }
            for (candidate in report.manualComparison?.candidates.orEmpty()) {
                val key = CandidateKey(candidate.id, candidate.definition, candidate.ddl)
                candidateGroups.getOrPut(key) { mutableListOf() }.add(CandidateOccurrence(ordinal, candidate, true))
            }
        }
        // The series manifest retains the common input even when no point report was exported.
        // Per-query costs and plans cannot be reconstructed from aggregate series metrics.
        for ((position, query) in summaryQueries.withIndex()) {
            val occurrences = queryGroups.getOrPut(query) { mutableListOf() }
            for (point in summaryPoints) {
                val ordinal = point.path("ordinal").intValue()
                if (ordinal !in this.reports) occurrences.add(QueryOccurrence(ordinal, position, null))
            }
        }
    }

    fun queries(): String = buildString {
        append("<section id=\"series-query-catalog\"><h2>SQL и планы</h2>")
        if (queryGroups.isEmpty()) append("<p>Исходные SQL не сохранены.</p>")
        queryGroups.entries.forEachIndexed { index, (query, occurrences) ->
            val kind = occurrences.firstNotNullOfOrNull { it.report?.statementKinds?.get(query.id)?.name } ?: summaryKinds[query.id]
            append("<details class=\"query-detail\" id=\"series-query-$index\"><summary><code>")
                .append(h(query.id)).append("</code> · ").append(h(kind ?: "Тип не сохранён")).append(" · SQL, оценки и планы</summary>")
            for (occurrence in occurrences) {
                append("<span class=\"catalog-anchor\" id=\"variant-${occurrence.ordinal}-query-${occurrence.position}\"></span>")
            }
            if (kind in setOf("UPDATE", "DELETE")) append("<p>Cost оценивает поиск и подготовку строк. Фактическая модификация таблицы и сопровождение индексов не включены.</p>")
            if (query.sql == null) append("<p>Исходный SQL отсутствует в модели.</p>")
            else copyable(query.sql, "series-sql-$index", "Скопировать SQL", "data-query-sql")
            if (occurrences.isEmpty()) append("<p>SQL сохранён в исходных данных серии. Оценки выполнения отсутствуют.</p>")
            else table(listOf("Вариант", "До", "С набором", "Снижение, %", "Исходный план", "План с набором"),
                "Точные оценки одного выполнения, planner cost") {
                for (occurrence in occurrences) {
                    val report = occurrence.report
                    val baseline = report?.result?.baseline
                    val selected = report?.result?.selected?.evaluation
                    val effect = report?.queryEffects?.firstOrNull { it.id == query.id }?.selected
                    append("<tr>")
                    cell("Вариант ${occurrence.ordinal}", heading = true)
                    cell(number(baseline?.costByQueryId?.get(query.id)))
                    cell(number(selected?.costByQueryId?.get(query.id)))
                    cell(effect?.gainPercent?.let(::number) ?: if (effect?.baselineCost == 0.0) "Неприменимо" else "Нет данных")
                    planCell(baseline?.plansByQueryId?.get(query.id))
                    planCell(selected?.plansByQueryId?.get(query.id))
                    append("</tr>")
                }
            }
            val alternatives = occurrences.flatMap { occurrence ->
                occurrence.report?.let(::evaluations).orEmpty().filter { (label, evaluation) ->
                    label !in setOf("Исходные индексы", "Выбранный набор") && evaluation != null
                }.map { Triple(occurrence.ordinal, it.first, requireNotNull(it.second)) }
            }
            if (alternatives.isNotEmpty()) {
                append("<details><summary>Другие сохранённые наборы</summary>")
                table(listOf("Вариант", "Набор", "Стоимость", "План"), "Сохранённые оценки, planner cost") {
                    for ((ordinal, label, evaluation) in alternatives) {
                        append("<tr>")
                        cell("Вариант $ordinal", heading = true)
                        cell(label)
                        cell(number(evaluation.costByQueryId[query.id]))
                        planCell(evaluation.plansByQueryId[query.id])
                        append("</tr>")
                    }
                }
                append("</details>")
            }
            append("</details>")
        }
        if (plans.isNotEmpty()) {
            append("<details class=\"saved-plans\"><summary>Сохранённые планы EXPLAIN JSON</summary>")
            for ((json, index) in plans) {
                append("<details id=\"series-plan-$index\"><summary>План ${index + 1} · EXPLAIN JSON</summary>")
                copyable(json, "series-plan-code-$index", "Скопировать план", "data-plan-key=\"$index\"")
                append("</details>")
            }
            append("</details>")
        }
        append("</section>")
    }

    fun indexes(): String = buildString {
        append("<section id=\"series-index-catalog\"><div class=\"catalog-toolbar\"><h2>Каталог индексов и DDL</h2>")
        append("<label class=\"catalog-toggle\" hidden><input id=\"series-all-indexes\" type=\"checkbox\"> Показать все рассмотренные индексы</label></div>")
        append("<p id=\"series-empty-indexes\" hidden>В выбранном варианте новые индексы не предлагаются.</p>")
        if (candidateGroups.isEmpty() && summaryCandidates.isEmpty()) append("<p>Кандидаты не сохранены.</p>")
        val renderedDdl = mutableMapOf<String, String>()
        candidateGroups.entries.forEachIndexed { index, (key, occurrences) ->
            val manifest = summaryCandidates.filter { sameCandidate(it, key) }
            val members = (occurrences.filter { occurrence ->
                !occurrence.manual && key.id in reports.getValue(occurrence.ordinal).result.selected?.evaluation?.candidateIds.orEmpty()
            }.map { it.ordinal } + manifest.flatMap(::summaryMembers)).distinct().sorted()
            val label = shortDefinition(key.definition)
            append("<article class=\"index-card\" id=\"series-index-$index\" data-candidate-id=\"")
                .append(h(key.id)).append("\" data-index-members=\"").append(members.joinToString(",")).append("\">")
            if (members.isNotEmpty()) {
                append("<h3><code>").append(h(label)).append("</code></h3><p class=\"muted\">Выбран: ")
                    .append(h(members.joinToString { "вариант $it" })).append("</p>")
                append("<details><summary>DDL и полное определение</summary>")
            } else {
                append("<details><summary><code>").append(h(label)).append("</code> · не выбран автоматически</summary>")
            }
            ddl(key.ddl, index, renderedDdl)
            append("<p>Полный ID: <code>").append(h(key.id)).append("</code></p><p>")
                .append(h(definition(key.definition))).append("</p>")
            table(listOf("Варианты", "Оценка, байт", "С запасом, байт", "Запись, усл. ед./ч"), "Сохранённые расходы индекса") {
                occurrences.groupBy { Triple(it.candidate.estimatedBytes, it.candidate.budgetedBytes, it.candidate.writeUnitsPerHour) }
                    .forEach { (usage, same) ->
                        row(same.map { it.ordinal }.distinct().joinToString(), number(usage.first), number(usage.second), number(usage.third))
                    }
                for (candidate in manifest.filter { saved -> occurrences.none { sameUsage(saved, it.candidate) } }) {
                    row("Общий пул серии", number(candidate.path("estimated_bytes")), number(candidate.path("budgeted_bytes")),
                        number(candidate.path("write_units_per_hour")))
                }
            }
            val sources = occurrences.groupBy { Triple(it.candidate.sourceQueryIds, it.candidate.generationReasons, it.candidate.sourceBranches) }
            for ((source, same) in sources) {
                append("<dl class=\"values\">")
                if (sources.size > 1) entry("Варианты", same.map { it.ordinal }.distinct().joinToString())
                entry("Источники SQL", source.first.sorted().joinToString().ifEmpty { "Не указаны" })
                entry("Происхождение", source.second.sortedBy { it.name }.joinToString { it.name }.ifEmpty { "Не указано" })
                entry("Ветви условий", source.third.sortedWith(compareBy({ it.queryId }, { it.conditionPath }))
                    .joinToString { "${it.queryId}: ${it.conditionPath}" }.ifEmpty { "Нет OR-происхождения" })
                append("</dl>")
            }
            val manual = occurrences.filter { it.manual }.map { it.ordinal }.distinct()
            if (manual.isNotEmpty()) append("<p>Есть в ручном наборе: ")
                .append(h(manual.joinToString { "вариант $it" })).append(".</p>")
            append("<p class=\"muted\">SQL-источники объясняют происхождение кандидата, а не индивидуальную пользу индекса.</p>")
            if (key.definition.includedColumns.isNotEmpty()) append("<p class=\"muted\">INCLUDE не гарантирует index-only scan: обращения к таблице зависят от visibility map.</p>")
            append("</details></article>")
        }
        summaryCandidates.filter { candidate -> candidateGroups.keys.none { sameCandidate(candidate, it) } }
            .forEachIndexed { fallbackIndex, candidate ->
                val index = candidateGroups.size + fallbackIndex
                val members = summaryMembers(candidate)
                val id = string(candidate.path("id")) ?: "Нет данных"
                append("<article class=\"index-card\" id=\"series-index-$index\" data-candidate-id=\"")
                    .append(h(id)).append("\" data-index-members=\"").append(members.joinToString(",")).append("\">")
                val label = summaryDefinition(candidate, short = true)
                if (members.isNotEmpty()) {
                    append("<h3><code>").append(h(label)).append("</code></h3><p class=\"muted\">Выбран: ")
                        .append(h(members.joinToString { "вариант $it" })).append("</p><details><summary>DDL и полное определение</summary>")
                } else append("<details><summary><code>").append(h(label)).append("</code> · кандидат общего пула</summary>")
                ddl(string(candidate.path("ddl")), index, renderedDdl)
                append("<p>Полный ID: <code>").append(h(id)).append("</code></p><p>")
                    .append(h(summaryDefinition(candidate, short = false))).append("</p>")
                table(listOf("Источник", "Оценка, байт", "С запасом, байт", "Запись, усл. ед./ч"), "Сохранённые расходы индекса") {
                    row("Общий пул серии", number(candidate.path("estimated_bytes")), number(candidate.path("budgeted_bytes")),
                        number(candidate.path("write_units_per_hour")))
                }
                append("<p class=\"muted\">Определение и расходы сохранены в сводке серии; подробности происхождения кандидата отсутствуют.</p>")
                if (candidate.path("included_columns").isArray && !candidate.path("included_columns").isEmpty)
                    append("<p class=\"muted\">INCLUDE не гарантирует index-only scan: обращения к таблице зависят от visibility map.</p>")
                append("</details></article>")
            }
        append("</section>")
    }

    private fun StringBuilder.ddl(value: String?, index: Int, rendered: MutableMap<String, String>) {
        if (value == null) append("<p>DDL отсутствует в модели.</p>")
        else {
            val existing = rendered[value]
            if (existing != null) append("<p><a href=\"#").append(existing).append("\">Общее определение DDL</a></p>")
            else {
                val id = "series-ddl-$index"
                rendered[value] = id
                copyable(value, id, "Скопировать DDL", "data-index-ddl")
            }
        }
    }

    private fun summaryMembers(candidate: JsonNode): List<Int> = summaryPoints.filter { point ->
        val id = string(candidate.path("id"))
        val report = reports[point.path("ordinal").intValue()]
        id != null && report?.candidates?.candidates.orEmpty().none { it.id == id } &&
            string(point.path("status")) in setOf("completed", "partial") &&
            point.path("selected_index_ids").any { string(it) == id }
    }.map { it.path("ordinal").intValue() }.distinct().sorted()

    private fun sameCandidate(value: JsonNode, key: CandidateKey): Boolean {
        val definition = key.definition
        return string(value.path("id")) == key.id && string(value.path("ddl")) == key.ddl &&
            (string(value.path("method")) ?: "btree") == definition.method &&
            string(value.path("schema")) == definition.table.schema && string(value.path("table")) == definition.table.name &&
            value.path("keys").toList().map { listOf(string(it.path("column")), string(it.path("direction")), string(it.path("nulls"))) } ==
            definition.keys.map { listOf(it.column.name, it.direction.name.lowercase(Locale.ROOT).takeIf { definition.method == "btree" },
                it.nulls.name.lowercase(Locale.ROOT).takeIf { definition.method == "btree" }) } &&
            value.path("included_columns").toList().map(::string) == definition.includedColumns.map { it.name }
    }

    private fun sameUsage(value: JsonNode, candidate: IndexCandidate): Boolean =
        number(value.path("estimated_bytes")) == number(candidate.estimatedBytes) &&
            number(value.path("budgeted_bytes")) == number(candidate.budgetedBytes) &&
            number(value.path("write_units_per_hour")) == number(candidate.writeUnitsPerHour)

    private fun summaryDefinition(value: JsonNode, short: Boolean): String {
        val schema = string(value.path("schema")) ?: "Нет данных"
        val table = string(value.path("table")) ?: "Нет данных"
        val name = if (!short || ambiguousTable(schema, table)) "$schema.$table" else table
        val keys = value.path("keys").joinToString(", ") { key ->
            (string(key.path("column")) ?: "Нет данных") + if (short) "" else
                listOfNotNull(string(key.path("direction")), string(key.path("nulls"))?.let { "NULLS $it" })
                    .joinToString(" ", prefix = " ")
        }
        val include = value.path("included_columns").takeIf { it.isArray && !it.isEmpty }
            ?.joinToString(", ", " INCLUDE (", ")") { string(it) ?: "Нет данных" }.orEmpty()
        val method = string(value.path("method")) ?: "btree"
        return name + " · $method " + "($keys)$include"
    }

    private fun ambiguousTable(schema: String, table: String): Boolean =
        candidateGroups.keys.any { it.definition.table.name == table && it.definition.table.schema != schema } ||
            summaryCandidates.any { string(it.path("table")) == table && string(it.path("schema")) != schema }

    private fun string(value: JsonNode): String? = value.takeIf { it.isString }?.stringValue()
    private fun number(value: JsonNode): String = if (value.isNumber) number(value.decimalValue()) else "Нет данных"

    private fun queryIds(report: AnalysisReport): List<String> =
        (report.input?.queries.orEmpty().map { it.id } + report.result.baseline?.costByQueryId.orEmpty().keys +
            report.result.selected?.evaluation?.costByQueryId.orEmpty().keys).distinct()

    private fun evaluations(report: AnalysisReport): List<Pair<String, EvaluationResult?>> = listOf(
        "Исходные индексы" to report.result.baseline,
        "Выбранный набор" to report.result.selected?.evaluation,
        "best_robust" to report.result.bestRobust?.evaluation,
        "best_mean" to report.result.bestMean?.evaluation,
        "Ручной набор" to report.manualComparison?.evaluation,
    )

    private fun shortDefinition(definition: IndexDefinition): String {
        val table = definition.table
        val ambiguous = ambiguousTable(table.schema, table.name)
        val name = if (ambiguous) "${table.schema}.${table.name}" else table.name
        return name + " · ${definition.method} " + definition.keys.joinToString(", ", "(", ")") { it.column.name } +
            definition.includedColumns.takeIf { it.isNotEmpty() }?.joinToString(", ", " INCLUDE (", ")") { it.name }.orEmpty()
    }

    private fun definition(value: IndexDefinition): String = buildString {
        append(value.table.schema).append('.').append(value.table.name).append(" · ")
        if (value.unique) append("UNIQUE ")
        append(value.method).append(" (")
        append(value.keys.joinToString { key ->
            buildString {
                append(key.column.name)
                if (value.method == "btree") append(' ').append(key.direction.name).append(" NULLS ").append(key.nulls.name)
                key.collation?.let { append(" COLLATE ").append(it) }
                key.operatorClass?.let { append(' ').append(it) }
            }
        })
        append(')')
        if (value.includedColumns.isNotEmpty()) append(value.includedColumns.joinToString(", ", " INCLUDE (", ")") { it.name })
    }

    private fun StringBuilder.planCell(plan: ExplainPlan?) {
        append("<td>")
        val key = plan?.let { plans[it.json] }
        if (key == null) append("Нет данных")
        else append("<a href=\"#series-plan-$key\">План ${key + 1}</a>")
        append("</td>")
    }

    private fun StringBuilder.copyable(value: String, id: String, label: String, attributes: String) {
        append("<pre id=\"").append(id).append("\" ").append(attributes).append("><code>")
            .append(h(value)).append("</code></pre><button type=\"button\" data-copy=\"")
            .append(id).append("\" hidden>").append(h(label)).append("</button> <span class=\"copy-status\" aria-live=\"polite\"></span>")
    }

    private fun StringBuilder.table(headers: List<String>, caption: String, content: StringBuilder.() -> Unit) {
        append("<div class=\"table-scroll\" tabindex=\"0\" role=\"region\" aria-label=\"").append(h(caption)).append("\"><table>")
        append("<caption>").append(h(caption)).append("</caption><thead><tr>")
        headers.forEach { append("<th scope=\"col\">").append(h(it)).append("</th>") }
        append("</tr></thead><tbody>")
        content()
        append("</tbody></table></div>")
    }

    private fun StringBuilder.row(vararg values: String) {
        append("<tr>")
        values.forEachIndexed { index, value -> cell(value, heading = index == 0) }
        append("</tr>")
    }

    private fun StringBuilder.cell(value: String, heading: Boolean = false) {
        val tag = if (heading) "th" else "td"
        append('<').append(tag)
        if (heading) append(" scope=\"row\"")
        append('>').append(h(value)).append("</").append(tag).append('>')
    }

    private fun StringBuilder.entry(label: String, value: String) {
        append("<div><dt>").append(h(label)).append("</dt><dd>").append(h(value)).append("</dd></div>")
    }

    private fun number(value: Number?): String = when (value) {
        null -> "Нет данных"
        is BigDecimal -> value.stripTrailingZeros().toPlainString().replace('.', ',')
        is Double -> if (value.isFinite()) BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', ',') else "Нет данных"
        is Float -> if (value.isFinite()) BigDecimal(value.toString()).stripTrailingZeros().toPlainString().replace('.', ',') else "Нет данных"
        else -> value.toString().replace('.', ',')
    }

    private fun h(value: String): String = buildString {
        value.forEach { append(when (it) {
            '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"; else -> it.toString()
        }) }
    }
}
