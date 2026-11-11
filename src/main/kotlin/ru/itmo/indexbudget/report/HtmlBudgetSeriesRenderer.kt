package ru.itmo.indexbudget.report

import tools.jackson.databind.JsonNode
import ru.itmo.indexbudget.domain.AnalysisReport
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.Base64

/** Read-only view of the exact v1 snapshot published as series.json; only chart geometry is calculated here. */
class HtmlBudgetSeriesRenderer {
    private val stylesheet = checkNotNull(javaClass.getResource("/report/report.css")).readText() +
        checkNotNull(javaClass.getResource("/report/series.css")).readText()
    private val script = checkNotNull(javaClass.getResource("/report/series.js")).readText() + "\n" +
        checkNotNull(javaClass.getResource("/report/report.js")).readText()
    private val scriptHash = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8)))

    fun render(summary: JsonNode, reports: Map<Int, AnalysisReport>? = null): String = buildString {
        require(summary.path("schema_version").intValue() == 1)
        val points = summary.path("points").toList()
        val storage = summary.path("budget").stringValue() == "storage_budget_bytes"
        val unit = if (storage) "байт" else "write units/ч"
        val candidates = summary.path("preparation").path("candidates").associateBy { it.path("id").stringValue() }
        append("""<!doctype html><html lang="ru"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; ${if (reports != null) "script-src 'sha256-$scriptHash'; " else ""}base-uri 'none'; form-action 'none'">
            <title>IndexBudget · Сравнение бюджетов</title><style>""")
        append(stylesheet)
        append("""</style></head><body><a class="skip" href="#main">К содержанию</a>
            <header class="topbar"><a class="brand" href="#overview"><span class="brand-icon" aria-hidden="true">▥</span>IndexBudget</a>
            <span class="top-caption">POSTGRESQL / СЕРИЯ БЮДЖЕТОВ</span><span class="offline">Автономный отчёт</span></header>
            <div class="layout series-layout">""")
        navigation(points, reports)
        append("""<main id="main" class="series-main"><section id="overview" class="overview">
            <p class="eyebrow">ОДИН ПУЛ КАНДИДАТОВ · НЕЗАВИСИМЫЕ ПОИСКИ</p>
            <div class="title-row"><h1>Что даёт больший бюджет</h1>""")
        badge(summary.path("status"))
        append("</div><p class=\"lead\">Сравнение рассчитанных вариантов по ")
        append(if (storage) "бюджету места" else "бюджету записи")
        append(". Эффект — снижение взвешенной стоимости планов, а не измеренное ускорение SQL.</p>")
        append("<p><a href=\"series.json\">Сводка JSON</a> · Значения бюджета: ")
        append(summary.path("values").joinToString(" · ") { h(number(it)) })
        append(" ").append(unit).append("</p>")
        if (reports != null) append("<p><a href=\"#variant-details\">Перейти к подробному отчёту выбранного варианта ↓</a></p>")
        if (summary.path("status").stringValue() == "running")
            notice("Серия ещё выполняется. Ожидающие точки не рассчитаны; обновите файл после завершения.")
        if (points.any { usable(it) && !it.path("conditions_match").isTrue })
            notice("Сопоставимость ограничена: условия изменились или не проверены. Разницу нельзя объяснять только бюджетом; отметки × на диаграммах и причины в таблице.")
        val repeated = points.filter(::usable).groupBy { ids(it) }.values.filter { it.size > 1 }
        for (group in repeated) notice("Одинаковый набор у точек ${group.joinToString { "№${it.path("ordinal").intValue()}" }}. " +
            "Дополнительный бюджет не обязан менять рекомендацию; при разных условиях одинаковые индексы могут иметь разный эффект.")
        val comparable = points.filter { usable(it) && it.path("conditions_match").isTrue }
            .sortedBy { it.path("budget_value").decimalValue() }
        if (comparable.zipWithNext().any { (a, b) ->
                a.path("metrics").path("profiles").any { previous ->
                    val next = profile(b, previous.path("profile_id").stringValue())
                    val before = previous.path("gain_percent")
                    val after = next?.path("gain_percent")
                    before.isNumber && after?.isNumber == true && after.decimalValue() < before.decimalValue()
                }
            }) notice("Обнаружен немонотонный эффект: у большего бюджета снижение cost хотя бы одного профиля меньше. " +
                "Поиски ограничены и независимы; выбранная цель также может допускать компромисс между профилями.")
        error(summary.path("preparation").path("error"))
        append("</section><section id=\"effects\"><h2>Бюджет → эффект профилей</h2>")
        append("<p class=\"muted\">Выше — больше снижение cost; отрицательное значение — ухудшение. " +
            "Столбцы идут по возрастанию бюджета. Сплошной столбец — completed, штриховка — partial; × — условия не подтверждены. " +
            "Failed и skipped не получают вымышленных значений. Линий между точками нет: промежуточные бюджеты не рассчитаны.</p>")
        val profiles = summary.path("settings").path("profiles").toList().map { it.path("id").stringValue() }
        val gains = points.filter(::usable).flatMap { it.path("metrics").path("profiles").toList() }
            .mapNotNull { it.path("gain_percent").takeIf(JsonNode::isNumber)?.decimalValue() }
        val low = minOf(BigDecimal.ZERO, gains.minOrNull() ?: BigDecimal.ZERO)
        val high = maxOf(BigDecimal(100), gains.maxOrNull() ?: BigDecimal(100))
        for (id in profiles) chart(points, id, unit, low, high)
        append("</section><section id=\"points\"><h2>Бюджет → индексы → расход → статус</h2>")
        append("<p class=\"muted\">Порядок точек совпадает с запросом серии. M — отношение средних взвешенных costs; " +
            "R — худшее отношение cost профиля к его baseline. Меньше — лучше. Проценты и расходы взяты из сводки JSON.</p>")
        append("<table class=\"series-table\"><thead><tr><th>Точка и бюджет</th><th>Индексы</th><th>Эффект и расход</th><th>Статус и файлы</th></tr></thead><tbody>")
        for (point in points) {
            val ordinal = point.path("ordinal").intValue()
            append("<tr id=\"point-$ordinal\"><th scope=\"row\">№$ordinal<br>")
            append(h(number(point.path("budget_value")))).append(" ").append(unit).append("</th>")
            append("<td data-label=\"Индексы\">")
            when {
                !usable(point) -> append("Нет пригодного результата")
                ids(point).isEmpty() -> append("Пустой набор · без дополнительных индексов")
                else -> for (id in ids(point)) {
                    append("<details><summary>").append(h(id)).append("</summary><pre>")
                    append(h(candidates[id]?.path("ddl")?.stringValue() ?: "Определение отсутствует в сводке"))
                    append("</pre></details>")
                }
            }
            append("</td><td data-label=\"Эффект и расход\">")
            if (usable(point)) {
                val metrics = point.path("metrics")
                append("<p>M: ").append(h(metric(metrics.path("mean_ratio"))))
                append(" · R: ").append(h(metric(metrics.path("worst_ratio")))).append("</p>")
                append("<p>Средний planner cost: ").append(h(number(metrics.path("baseline_mean_cost"))))
                    .append(" → ").append(h(number(metrics.path("mean_cost")))).append("</p>")
                for (p in metrics.path("profiles")) {
                    append("<p><strong>").append(h(p.path("profile_id").stringValue())).append("</strong>: ")
                    append(h(effect(p))).append("<br><small>Planner cost: ")
                    append(h(number(p.path("baseline_cost")))).append(" → ").append(h(number(p.path("cost"))))
                    append("</small></p>")
                }
                val usage = metrics.path("budget_usage")
                append("<p>Место: оценка ").append(h(number(usage.path("estimated_bytes")))).append(" байт; с запасом ")
                append(h(number(usage.path("budgeted_bytes")))).append(" байт (×")
                append(h(number(usage.path("size_safety_factor")))).append(").</p>")
                val constraints = summary.path("settings").path("constraints")
                append("<p>Лимит места: ").append(h(number(if (storage) point.path("budget_value") else constraints.path("storage_budget_bytes"))))
                append(" байт. Индексов: ").append(h(number(usage.path("index_count")))).append(" / ")
                append(h(number(constraints.path("max_indexes")))).append(".</p>")
                val writeLimit = if (storage) constraints.path("write_budget_units_per_hour") else point.path("budget_value")
                append("<p>Запись: ")
                append(h(number(usage.path("write_units_per_hour")))).append(" write units/ч. ")
                append(if (writeLimit.isNumber) "Лимит: ${h(number(writeLimit))} write units/ч."
                    else "Лимит отключён.")
                if (summary.path("settings").path("table_write_rates").isNull)
                    append(" Модель записи отключена; неизвестный расход не равен нулю.")
                append("</p>")
            } else append("Нет данных")
            append("</td><td data-label=\"Статус и файлы\">")
            badge(point.path("status"))
            append("<p>").append(when {
                point.path("conditions_match").isTrue -> "Наблюдаемые условия совпали"
                point.path("conditions_match").isFalse -> "Условия изменились"
                else -> "Сопоставимость не установлена"
            }).append("</p>")
            if (!point.path("condition_changes").isEmpty)
                append("<p>").append(h(point.path("condition_changes").joinToString { conditionLabel(it.stringValue()) })).append("</p>")
            if (!point.path("stop_reasons").isEmpty)
                append("<p>Остановка: ").append(h(point.path("stop_reasons").joinToString { it.stringValue() })).append("</p>")
            error(point.path("error"))
            point.path("errors").filter { it != point.path("error") }.forEach { error(it) }
            append("<p class=\"series-links\">")
            if (reports?.containsKey(ordinal) == true)
                append("<a href=\"#variant-$ordinal\">Открыть здесь</a> ")
            for (name in listOf("report.html", "report.json", "indexes.sql")) {
                val path = point.path("artifacts").path(name)
                // Only paths generated by the series writer are navigable; no schemes, traversal or external resources.
                if (path.isString && path.stringValue() == "point-%02d/$name".format(java.util.Locale.ROOT, ordinal))
                    append("<a href=\"").append(h(path.stringValue())).append("\">").append(name).append("</a> ")
            }
            append("</p></td></tr>")
        }
        append("</tbody></table></section><section id=\"conditions\"><h2>Условия и границы сравнения</h2>")
        append("<p>Один фиксированный пул кандидатов; каждая точка исследует собственный пул комбинаций. " +
            "Ограниченный поиск не гарантирует глобальный оптимум или монотонное улучшение при увеличении бюджета. " +
            "Совпадение каталога, baseline, планов и версий не доказывает неизменность всех данных и настроек БД.</p>")
        append("<p>Размер — оценка HypoPG с отдельным запасом; write units/ч — условная модель записи. " +
            "Planner cost не является временем выполнения или обещанием SLA. Полная диагностика доступна в отчётах точек.</p>")
        append("<dl class=\"values metadata\">")
        for ((label, value) in listOf(
            "Серия" to summary.path("series_id"), "Hash workload" to summary.path("source").path("workload_input_hash"),
            "Источник" to summary.path("source").path("workload_source"),
            "Цель" to summary.path("settings").path("selected_objective"),
            "Режим" to summary.path("settings").path("analysis_mode"),
            "Рейтинг" to summary.path("settings").path("candidate_ranking"),
            "Длительность, мс" to summary.path("duration_millis"), "Общий лимит, с" to summary.path("timeout_seconds"),
        )) append("<div><dt>").append(label).append("</dt><dd>")
            .append(h(if (value.isString) value.stringValue() else number(value))).append("</dd></div>")
        append("</dl></section>")
        if (reports != null) detailedReports(summary, points, reports, unit)
        append("<footer>IndexBudget · Спецификация 0.2 · Сводка серии v1</footer></main></div>")
        if (reports != null) append("<script>").append(script).append("</script>")
        append("</body></html>")
    }

    private fun StringBuilder.navigation(points: List<JsonNode>, reports: Map<Int, AnalysisReport>?) {
        append("""<aside class="series-sidebar"><div class="nav-label">СРАВНЕНИЕ БЮДЖЕТОВ</div>
            <nav aria-label="Разделы сравнения бюджетов">
            <a href="#overview">Обзор серии</a><a href="#effects">Эффект по бюджетам</a>
            <a href="#points">Варианты и индексы</a><a href="#conditions">Условия сравнения</a>""")
        if (reports != null) append("<a href=\"#variant-details\">Выбор варианта</a>")
        append("</nav>")
        val available = points.filter { reports?.containsKey(it.path("ordinal").intValue()) == true }
        val selected = defaultPoint(available)
        if (selected != null) {
            val ordinal = selected.path("ordinal").intValue()
            append("<div class=\"nav-label series-nav-label\">ВАРИАНТ №<span id=\"series-nav-variant\">$ordinal</span></div>")
            append("<nav aria-label=\"Разделы выбранного варианта\" class=\"series-variant-nav\">")
            for ((index, entry) in listOf(
                "overview" to "Обзор", "comparison" to "Robust и mean",
                "profiles" to "Профили нагрузки", "indexes" to "Индексы и бюджеты",
                "queries" to "Запросы и планы", "search" to "Границы поиска",
                "run" to "Данные запуска", "provenance" to "Происхождение нагрузки",
            ).withIndex()) {
                val (id, label) = entry
                append("<a data-section=\"$id\" href=\"#variant-$ordinal-$id\"><span>0${index + 1}</span>$label</a>")
            }
            append("</nav>")
        }
        append("<div class=\"nav-note\">Planner cost — оценка планировщика. Фактическое время запросов здесь не измеряется.</div></aside>")
    }

    private fun defaultPoint(available: List<JsonNode>): JsonNode? =
        available.filter(::usable).ifEmpty { available }.maxByOrNull { it.path("budget_value").decimalValue() }

    private fun StringBuilder.detailedReports(summary: JsonNode, points: List<JsonNode>, reports: Map<Int, AnalysisReport>, unit: String) {
        val available = points.filter { reports.containsKey(it.path("ordinal").intValue()) }
        val selected = defaultPoint(available)
        append("<section id=\"variant-details\"><h2>Подробный отчёт варианта</h2>")
        if (selected == null) {
            append("<p class=\"empty\">Подробные отчёты пока недоступны. Состояния точек и ошибки показаны в сводке выше.</p></section>")
            return
        }
        append(if (usable(selected))
            "<p>По умолчанию открыт рассчитанный вариант с наибольшим бюджетом. Это выбор для просмотра, а не рекомендация увеличивать бюджет.</p>"
            else "<p>Пригодного результата нет. Открыта диагностика доступного варианта с наибольшим бюджетом.</p>")
        append("<div class=\"series-selector\" hidden><label for=\"series-variant\">Вариант бюджета</label> <select id=\"series-variant\">")
        for (point in available) {
            val ordinal = point.path("ordinal").intValue()
            append("<option value=\"").append(ordinal).append("\"")
            if (point == selected) append(" selected")
            append(">№").append(ordinal).append(" · ").append(h(number(point.path("budget_value"))))
                .append(" ").append(unit).append(" · ").append(h(point.path("status").stringValue())).append("</option>")
        }
        append("</select></div><div id=\"series-detail-content\">")
        val renderer = HtmlReportRenderer()
        for (point in available) {
            val ordinal = point.path("ordinal").intValue()
            val report = reports.getValue(ordinal)
            require(report.runId == point.path("run_id").stringValue())
            require(report.inputHash == summary.path("source").path("workload_input_hash").stringValue())
            require(report.result.status.name.lowercase() == point.path("status").stringValue())
            require(report.result.selected?.evaluation?.candidateIds?.sorted() ==
                if (point.path("selected_index_ids").isArray) ids(point) else null)
            append("<details class=\"series-detail\" id=\"variant-$ordinal\" data-report-prefix=\"variant-$ordinal-\"")
            if (point == selected) append(" open")
            append("><summary>№").append(ordinal).append(" · Бюджет ").append(h(number(point.path("budget_value"))))
                .append(" ").append(unit).append(" · ").append(h(point.path("status").stringValue())).append("</summary>")
            append(renderer.renderContent(report, "variant-$ordinal-"))
            append("</details>")
        }
        append("</div></section>")
    }

    private fun StringBuilder.chart(points: List<JsonNode>, id: String, unit: String, low: BigDecimal, high: BigDecimal) {
        val ordered = points.sortedBy { it.path("budget_value").decimalValue() }
        val storage = unit == "байт"
        append("<article class=\"series-chart\"><h3>").append(h(id)).append("</h3>")
        append("<p class=\"muted\">Снижение cost, % · общая шкала ")
            .append(h(compact(low))).append(" … ").append(h(compact(high))).append(" %</p>")
        append("<div class=\"series-columns\" style=\"--columns:").append(maxOf(1, ordered.size)).append("\">")
        for (point in ordered) {
            val ordinal = point.path("ordinal").intValue()
            val p = profile(point, id)
            val gain = if (usable(point)) p?.path("gain_percent")?.takeIf(JsonNode::isNumber)?.decimalValue() else null
            val status = point.path("status").stringValue()
            val statusText = when (status) {
                "partial" -> "partial"; "failed" -> "Ошибка"; "skipped" -> "Не рассчитана"; else -> "completed"
            }
            val description = if (usable(point)) p?.let(::effect) ?: "Нет данных" else "Нет пригодного результата"
            val title = "№$ordinal · ${number(point.path("budget_value"))} $unit · $description · $statusText" +
                if (point.path("conditions_match").isTrue) "" else " · Условия не подтверждены"
            append("<a class=\"series-column\" href=\"#point-$ordinal\" title=\"").append(h(title))
                .append("\" aria-label=\"").append(h(title)).append("\">")
            append("<span class=\"series-value\">").append(if (gain == null) "—" else h(compact(gain)) + " %").append("</span>")
            append("<span class=\"series-track\"><span class=\"series-zero\" style=\"bottom:")
                .append(position(BigDecimal.ZERO, low, high)).append("%\"></span>")
            if (gain != null) {
                append("<span class=\"series-bar ").append(if (status == "partial") "partial" else "completed")
                if (gain.signum() < 0) append(" negative")
                if (gain.signum() == 0) append(" zero")
                append("\" style=\"bottom:").append(position(minOf(gain, BigDecimal.ZERO), low, high))
                    .append("%;height:").append(position(gain.abs(), BigDecimal.ZERO, high.subtract(low))).append("%\"></span>")
            } else append("<span class=\"series-unavailable\">")
                .append(h(if (usable(point)) description else statusText)).append("</span>")
            append("</span><span class=\"series-budget\"><strong>")
            val budget = point.path("budget_value").decimalValue()
            append(h(compact(if (storage) budget.divide(BigDecimal(1_048_576)) else budget))).append("</strong>")
            append("<small>№$ordinal")
            if (gain != null && status == "partial") append(" · partial")
            if (gain != null && !point.path("conditions_match").isTrue) append(" · ×")
            append("</small></span></a>")
        }
        append("</div><p class=\"series-axis-title\">Бюджет, ").append(if (storage) "MiB" else unit)
            .append("</p><p class=\"muted\">Равные интервалы обозначают отдельные варианты бюджета. ")
            .append("Точные значения и статусы — в таблице по нажатию на столбец.</p></article>")
    }

    private fun compact(value: BigDecimal): String {
        val rounded = value.setScale(2, RoundingMode.HALF_EVEN)
        return if (rounded.compareTo(value) == 0 || rounded.signum() == 0 && value.signum() != 0) number(value)
        else "≈${number(rounded)}"
    }

    private fun profile(point: JsonNode, id: String) = point.path("metrics").path("profiles")
        .firstOrNull { it.path("profile_id").stringValue() == id }
    private val JsonNode.isTrue get() = isBoolean && booleanValue()
    private val JsonNode.isFalse get() = isBoolean && !booleanValue()
    private fun usable(point: JsonNode) = point.path("status").stringValue() in setOf("completed", "partial") &&
        point.path("metrics").isObject && point.path("selected_index_ids").isArray
    private fun ids(point: JsonNode) = point.path("selected_index_ids").toList().map { it.stringValue() }.sorted()
    private fun effect(profile: JsonNode): String = when {
        profile.path("gain_percent").isNumber -> "${number(profile.path("gain_percent"))} %"
        profile.path("baseline_cost").isNumber && profile.path("baseline_cost").doubleValue() == 0.0 -> "Неприменимо: нулевой baseline"
        else -> "Нет данных"
    }
    private fun metric(value: JsonNode) = if (value.isNull) "Неприменимо" else number(value)
    private fun number(value: JsonNode) = if (value.isNumber) number(value.decimalValue()) else "Нет данных"
    private fun number(value: BigDecimal) = value.stripTrailingZeros().toPlainString().replace('.', ',')
    private fun position(value: BigDecimal, low: BigDecimal, high: BigDecimal): String =
        if (low == high) "50" else value.subtract(low).multiply(BigDecimal(100))
            .divide(high.subtract(low), 8, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
    private fun StringBuilder.notice(text: String) { append("<p class=\"notice warning\">").append(h(text)).append("</p>") }
    private fun StringBuilder.error(error: JsonNode) {
        if (error.isObject) append("<p class=\"notice error\">").append(h(error.path("code").stringValue()))
            .append(": ").append(h(error.path("message").stringValue())).append("</p>")
    }
    private fun StringBuilder.badge(status: JsonNode) {
        val value = status.stringValue()
        val label = when (value) {
            "completed" -> "Завершён"; "partial" -> "Частичный результат"; "failed" -> "Ошибка"
            "skipped" -> "Не рассчитана"; "running" -> "Выполняется"; else -> "Нет данных"
        }
        append("<span class=\"badge ").append(if (value in setOf("completed", "partial", "failed")) value else "")
            .append("\">").append(h(value)).append(" · ").append(label).append("</span>")
    }
    private fun conditionLabel(value: String) = when (value) {
        "catalog" -> "каталог"; "baseline_costs" -> "baseline cost"; "baseline_plans" -> "планы baseline"
        "database_versions" -> "версии БД/HypoPG"; "source" -> "источник БД"
        "source_unavailable" -> "источник не проверен"; "conditions_unavailable" -> "условия недоступны"
        else -> value
    }
    private fun h(value: String) = buildString {
        value.forEach { append(when (it) {
            '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"; else -> it.toString()
        }) }
    }
}
