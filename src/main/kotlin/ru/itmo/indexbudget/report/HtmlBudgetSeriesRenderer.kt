package ru.itmo.indexbudget.report

import tools.jackson.databind.JsonNode
import ru.itmo.indexbudget.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Base64
import java.util.Locale

/** A view of saved series/report models. No SQL, EXPLAIN or domain metric recalculation. */
class HtmlBudgetSeriesRenderer {
    private val stylesheet = checkNotNull(javaClass.getResource("/report/report.css")).readText() +
        checkNotNull(javaClass.getResource("/report/series.css")).readText()
    private val script = checkNotNull(javaClass.getResource("/report/series.js")).readText() + "\n" +
        checkNotNull(javaClass.getResource("/report/report.js")).readText()
    private val scriptHash = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8)))

    fun render(summary: JsonNode, reports: Map<Int, AnalysisReport>? = null): String = buildString {
        require(summary.path("schema_version").intValue() in 1..2)
        val points = summary.path("points").toList()
        val storage = summary.path("budget").stringValue() == "storage_budget_bytes"
        val candidates = summary.path("preparation").path("candidates").associateBy { it.path("id").stringValue() }
        val saved = reports.orEmpty()
        validateReports(summary, points, saved)
        val initial = defaultPoint(points)?.path("ordinal")?.intValue()
        append("""<!doctype html><html lang="ru"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; ${if (reports != null) "script-src 'sha256-$scriptHash'; " else ""}base-uri 'none'; form-action 'none'">
            <title>IndexBudget · Сравнение бюджетов</title><style>""")
        append(stylesheet).append("""</style></head><body><a class="skip" href="#main">К содержанию</a>
            <header class="topbar"><a class="brand" href="#overview">IndexBudget</a><span class="top-caption">Отчёт по PostgreSQL</span><a class="header-link" href="#conditions">Как получена оценка</a></header>
            <div class="layout series-layout"><aside class="series-sidebar"><nav aria-label="Разделы отчёта">
            <a href="#overview">Вывод</a><a href="#points">Сравнение бюджетов</a>""")
        if (reports != null) append("<a href=\"#series-queries\">Влияние на запросы</a><a href=\"#series-resources\">Ресурсы и индексы</a><a href=\"#series-workload\">Исходная нагрузка</a>")
        else append("<a href=\"#series-query-catalog\">SQL и планы</a><a href=\"#series-index-catalog\">Индексы и DDL</a>")
        append("<a href=\"#conditions\">Методика и ограничения</a><a href=\"#next\">Следующие шаги</a></nav></aside><main id=\"main\">")
        append("<section id=\"overview\" class=\"overview\"><h1>Индексы при разных бюджетах ").append(if (storage) "места" else "записи").append("</h1>")
        append(if (storage) "<p class=\"budget-definition\">Бюджет места — лимит размера дополнительных индексов с запасом.</p>"
            else "<p class=\"budget-definition\">Бюджет записи — лимит условных затрат на обслуживание новых индексов при изменении данных, ед./ч.</p>")
        if (reports != null && points.isNotEmpty()) {
            append("<div class=\"series-selector\" hidden><label for=\"series-variant\">Просматриваемый бюджет</label> <select id=\"series-variant\">")
            for (point in points) {
                val ordinal = point.path("ordinal").intValue()
                append("<option value=\"$ordinal\" data-catalog-state=\"").append(when { !usable(point) -> "unavailable"; ids(point).isEmpty() -> "empty"; else -> "selected" }).append("\"").append(if (ordinal == initial) " selected" else "").append(">")
                    .append(h(budget(point.path("budget_value"), storage))).append(" · вариант $ordinal · ")
                    .append(h(statusLabel(point.path("status").stringValue()))).append("</option>")
            }
            append("</select></div><p class=\"selection-note muted\">Выбор для просмотра, не рекомендация увеличивать бюджет.</p>")
        }
        for (point in points) overviewResult(summary, point, candidates, saved[point.path("ordinal").intValue()], storage)
        if (points.isEmpty()) append("<p>Результаты вариантов ещё не сохранены.</p>")
        append("<p class=\"cost-definition\"><strong>Planner cost</strong> — оценка затрат PostgreSQL, не время выполнения. Снижение cost не гарантирует ускорения.</p>")
        if (summary.path("settings").path("queries").any { it.path("statement_kind").isString && it.path("statement_kind").stringValue() in setOf("update", "delete") }
            || saved.values.any { report -> report.statementKinds.values.any { it != StatementKind.SELECT } }) {
            notice("В нагрузке есть UPDATE/DELETE: cost оценивает поиск и подготовку строк. Фактическая модификация таблицы не включена; это не прогноз полного ускорения DML.")
        }
        append(WriteProfileReport.render(summary.path("settings").path("table_write_rates"), summary.path("settings").path("table_write_rates_origin")))
        if (summary.path("status").stringValue() == "running") notice("Серия ещё выполняется. Не все точки рассчитаны.")
        if (points.any { usable(it) && !it.path("conditions_match").isTrue }) notice("Сопоставимость ограничена: условия изменились или не проверены. Разницу нельзя объяснять только бюджетом.")
        val comparable = points.filter { usable(it) && it.path("conditions_match").isTrue }.sortedBy { it.path("budget_value").decimalValue() }
        if (comparable.zipWithNext().any { (a, b) -> a.path("metrics").path("profiles").any { previous ->
            val next = profile(b, previous.path("profile_id").stringValue())?.path("gain_percent")
            previous.path("gain_percent").isNumber && next?.isNumber == true && next.decimalValue() < previous.path("gain_percent").decimalValue()
        } }) notice("Обнаружен немонотонный эффект: больший бюджет ухудшил оценку хотя бы одного профиля. Поиск ограничен.")
        error(summary.path("preparation").path("error"))
        append("</section>")
        pointsTable(summary, points, candidates, storage, reports != null)
        if (reports != null) {
            val renderer = HtmlReportRenderer()
            val catalog = HtmlSeriesCatalog(saved, summary)
            append("<div id=\"series-detail-content\"><section id=\"series-queries\">")
            for (point in points) variant(point) {
                val report = saved[point.path("ordinal").intValue()]
                if (report == null) append("<h2>Влияние на запросы</h2><p>Оценки запросов для этого варианта не сохранены.</p>") else {
                    append(renderer.renderSection(report, HtmlReportSection.QUERIES, prefix(point)))
                    append(renderer.renderSection(report, HtmlReportSection.PROFILES, prefix(point)))
                }
            }
            append(catalog.queries()).append("</section><section id=\"series-resources\">")
            for (point in points) variant(point) {
                val report = saved[point.path("ordinal").intValue()]
                if (report == null) append("<h2>Ресурсы и индексы</h2><p>Оценка ресурсов отсутствует.</p>")
                else append(renderer.renderSection(report, HtmlReportSection.INDEXES, prefix(point))
                    .replace("href=\"#${prefix(point)}resource-model\"", "href=\"#series-resource-model\""))
            }
            append(catalog.indexes()).append("</section><section id=\"series-workload\">")
            // Share identical observations/inputs; never substitute one point's changed provenance for another.
            val groups = saved.entries.groupBy { (_, r) -> listOf(r.workloadProvenance, r.input?.queries, r.input?.profiles, r.input?.tableWriteRates, r.input?.tableWriteRatesOrigin) }
            for (group in groups.values) {
                append("<div data-variants=\"").append(group.joinToString(",") { it.key.toString() }).append("\">")
                append(renderer.renderSection(group.first().value, HtmlReportSection.PROVENANCE, "variant-${group.first().key}-"))
                append("</div>")
            }
            for (point in points.filter { !saved.containsKey(it.path("ordinal").intValue()) }) variant(point) { append("<h2>Исходная нагрузка</h2><p>Подробные исходные данные не сохранены.</p>") }
            append("</section></div>")
        } else {
            val catalog = HtmlSeriesCatalog(emptyMap(), summary)
            append(catalog.queries()).append(catalog.indexes())
        }
        append("<section id=\"conditions\"><h2>Как получена оценка и где её границы</h2><p>Baseline — оценка с существующими индексами. Новые наборы оценены целиком через HypoPG, без физического построения. Поиск ограничен и не гарантирует лучший возможный набор.</p>")
        append("<details><summary>Ограничения сравнения</summary><p>Пул кандидатов фиксирован; каждый бюджет исследует собственные комбинации. Глобальный оптимум и монотонное улучшение не гарантированы. Совпадение наблюдаемых каталога, baseline, планов и версий не доказывает неизменность всех данных и настроек БД.</p><p>Planner cost не обещает ускорение или SLA. Место — оценка HypoPG с запасом; write units/ч — условная модель, не количество операций и не измеренное замедление записи.</p></details>")
        for (group in points.filter(::usable).groupBy { ids(it) }.values.filter { it.size > 1 })
            append("<details><summary>Одинаковый набор у точек ").append(h(group.joinToString { "№${it.path("ordinal").intValue()}" })).append("</summary><p>Дополнительный бюджет не обязан менять рекомендацию; при разных условиях эффект может отличаться.</p></details>")
        if (reports != null) {
            val renderer = HtmlReportRenderer()
            saved.values.firstOrNull()?.let {
                append(renderer.renderSection(it, HtmlReportSection.RESOURCE_MODEL).replace("id=\"resource-model\"", "id=\"series-resource-model\""))
            }
            append("<details><summary>Критерии, лимиты и диагностика запусков</summary>")
            for ((ordinal, report) in saved.toSortedMap()) {
                append("<details class=\"series-detail\" data-variant=\"$ordinal\" id=\"variant-$ordinal\"><summary>Вариант $ordinal · ").append(h(statusLabel(report.result.status.name.lowercase(Locale.ROOT)))).append("</summary>")
                val sections = listOfNotNull(
                    HtmlReportSection.COMPARISON.takeIf { (report.input?.profiles?.size ?: report.result.selected?.metrics?.profiles?.size ?: 0) > 1 },
                    HtmlReportSection.MANUAL_COMPARISON, HtmlReportSection.SEARCH, HtmlReportSection.RUN)
                for (section in sections) append(renderer.renderSection(report, section, "variant-$ordinal-", includeCandidateCatalog = false))
                append("</details>")
            }
            append("</details>")
        }
        append("<details><summary>Идентификатор и параметры серии</summary><p>По умолчанию показан пригодный вариант с наибольшим бюджетом; если его нет — доступная диагностика. Это выбор для просмотра.</p><dl class=\"values\">")
        entry("Значения бюджета", summary.path("values").joinToString(" · ") { number(it) + " " + if (storage) "байт" else "условных ед./ч" })
        for ((label, value) in listOf("Серия" to summary.path("series_id"), "Hash workload" to summary.path("source").path("workload_input_hash"), "Источник" to summary.path("source").path("workload_source"), "Цель" to summary.path("settings").path("selected_objective"), "Режим" to summary.path("settings").path("analysis_mode"), "Рейтинг" to summary.path("settings").path("candidate_ranking"), "Методы индексов" to summary.path("settings").path("index_methods"), "Длительность, мс" to summary.path("duration_millis"), "Общий лимит, с" to summary.path("timeout_seconds")))
            entry(label, if (value.isArray) value.joinToString { it.stringValue() } else if (value.isString) value.stringValue() else number(value))
        append("</dl><p><a href=\"series.json\">Сводка JSON</a></p></details></section><section id=\"next\"><h2>Что делать дальше</h2><ol><li>Уточнить период, охват, частоты и параметры SQL.</li><li>Выбрать допустимый бюджет по таблице сравнения.</li><li>Проверить весь набор на тестовой БД: время запросов, нагрузку записи и фактический размер индексов.</li></ol></section><footer>IndexBudget · Спецификация 0.2 · Оценка для отдельной физической проверки</footer></main></div>")
        if (reports != null) append("<script>").append(script).append("</script>")
        append("</body></html>")
    }

    private fun validateReports(summary: JsonNode, points: List<JsonNode>, reports: Map<Int, AnalysisReport>) {
        for (point in points) reports[point.path("ordinal").intValue()]?.let { report ->
            require(report.runId == point.path("run_id").stringValue())
            require(report.inputHash == summary.path("source").path("workload_input_hash").stringValue())
            require(report.result.status.name.lowercase(Locale.ROOT) == point.path("status").stringValue())
            require(report.result.selected?.evaluation?.candidateIds?.sorted() == if (point.path("selected_index_ids").isArray) ids(point) else null)
        }
        require(reports.keys.all { ordinal -> points.any { it.path("ordinal").intValue() == ordinal } })
    }
    private fun prefix(point: JsonNode) = "variant-${point.path("ordinal").intValue()}-"
    private fun defaultPoint(points: List<JsonNode>) = points.filter(::usable).ifEmpty { points }.maxByOrNull { it.path("budget_value").decimalValue() }
    private fun StringBuilder.variant(point: JsonNode, body: StringBuilder.() -> Unit) {
        val ordinal = point.path("ordinal").intValue()
        append("<div class=\"variant-section\" data-variant=\"$ordinal\" data-report-prefix=\"variant-$ordinal-\"><p class=\"variant-label muted\">Вариант $ordinal</p>")
        body(); append("</div>")
    }

    private fun StringBuilder.overviewResult(summary: JsonNode, point: JsonNode, candidates: Map<String, JsonNode>, report: AnalysisReport?, storage: Boolean) {
        val ordinal = point.path("ordinal").intValue()
        append("<article class=\"series-result\" id=\"variant-$ordinal-overview\" data-overview-variant=\"$ordinal\"><p class=\"variant-label muted\">Вариант $ordinal · ")
            .append(h(budget(point.path("budget_value"), storage))).append("</p><h2>Предлагаемый набор</h2>")
        when {
            !usable(point) -> append("<p>Рекомендация отсутствует: пригодный результат не сохранён.</p>")
            ids(point).isEmpty() -> append("<p>Выбран пустой набор. Новые индексы не предлагаются.</p>")
            else -> append("<p class=\"recommendation\">").append(ids(point).joinToString(" + ") { id -> "<a href=\"#series-index-catalog\"><code>${h(shortDefinition(candidates[id], candidates.values, "Определение неизвестно"))}</code></a>" }).append("</p>")
        }
        append("<p class=\"effect-sentence\">Оценочная стоимость запросов с учётом частот: ")
        val profiles = if (usable(point)) point.path("metrics").path("profiles").toList() else emptyList()
        if (profiles.isEmpty()) append("Эффект не определён")
        else append(profiles.joinToString("; ") { profile -> (if (profiles.size > 1) h(profile.path("profile_id").stringValue()) + " — " else "") + h(effectLabel(profile)) })
        append(" по сравнению с существующими индексами.</p><p class=\"muted\">").append(h(statusLabel(point.path("status").stringValue())))
        append(". Рост стоимости: ").append(h(report?.summary?.regressedQueryCount?.let { "$it из ${report.input?.queries?.size ?: report.result.baseline?.costByQueryId?.size ?: "нет данных"} SQL" } ?: "нет данных")).append(".</p>")
        if (point.path("status").stringValue() == "partial") append("<p class=\"warning-text\">Поиск остановлен досрочно; сохранён промежуточный результат.</p>")
        append("<div class=\"workload-context\"><strong>Основа вывода</strong><p>")
        val count = report?.input?.queries?.size ?: summary.path("settings").path("queries").takeIf { it.isArray }?.size()
        append(count ?: "Нет данных").append(" SQL · профилей частот: ").append(summary.path("settings").path("profiles").size()).append(". ")
        val provenance = report?.workloadProvenance
        if (provenance == null) {
            append(when (summary.path("source").path("workload_source").stringValue()) {
                "manual_json" -> "SQL и частоты вызовов заданы вручную. Их период и охват неизвестны; репрезентативность не подтверждена."
                "pg_stat_statements" -> "pg_stat_statements. Период, полнота и охват наблюдений не сохранены."
                else -> "Источник, период и охват неизвестны."
            })
        } else {
            append("pg_stat_statements; завершение поиска не подтверждает репрезентативность.</p><ul class=\"observation-windows\">")
            for (window in provenance.windows) {
                val d = java.time.Duration.between(window.startedAt, window.finishedAt)
                val milliseconds = BigDecimal.valueOf(d.seconds).movePointRight(3) + BigDecimal.valueOf(d.nano.toLong()).movePointLeft(6)
                append("<li>").append(h(window.profileId)).append(" · ").append(h(window.startedAt.toString())).append(" — ")
                    .append(h(window.finishedAt.toString())).append(" · ").append(h(compact(milliseconds))).append(" мс · ")
                    .append(if (window.complete) "полное наблюдение" else "<strong class=\"warning-text\">неполное наблюдение</strong>")
                    .append(" · выбрано вызовов: ").append(h(window.coverage.selectedCalls?.toString() ?: "нет данных"))
                    .append(" из ").append(h(window.coverage.availableCalls?.toString() ?: "нет данных")).append(" доступных</li>")
            }
            append("</ul><p>")
        }
        append("</p></div></article>")
    }

    private fun StringBuilder.pointsTable(summary: JsonNode, points: List<JsonNode>, candidates: Map<String, JsonNode>, storage: Boolean, interactive: Boolean) {
        val single = summary.path("settings").path("profiles").size() == 1
        append("<section id=\"points\"><h2>Что меняется с бюджетом</h2><div class=\"table-scroll\" tabindex=\"0\" role=\"region\" aria-label=\"Сравнение бюджетов\"><table class=\"series-table\"><caption>Стоимость с учётом частот · сравнение с существующими индексами</caption><thead><tr><th>Бюджет ")
            .append(if (storage) "места" else "записи, усл. ед./ч").append("</th><th>Дополнительные индексы</th><th>Изменение стоимости</th><th>Ресурсы и статус</th></tr></thead><tbody>")
        for (point in points) {
            val ordinal = point.path("ordinal").intValue()
            append("<tr id=\"point-$ordinal\" data-status=\"").append(h(point.path("status").stringValue())).append("\"><th scope=\"row\">")
            if (interactive) append("<button type=\"button\" class=\"variant-button\" data-select-variant=\"$ordinal\" aria-pressed=\"false\">") else append("<span>")
            append(h(budget(point.path("budget_value"), storage))).append(if (interactive) "</button>" else "</span>")
            append("<small>Вариант $ordinal</small></th><td>")
            when { !usable(point) -> append("Нет результата"); ids(point).isEmpty() -> append("Пустой набор · без новых индексов")
                else -> append(ids(point).joinToString(" + ") { "<a href=\"#series-index-catalog\"><code>${h(shortDefinition(candidates[it], candidates.values, "Определение неизвестно"))}</code></a>" }) }
            append("</td><td>")
            if (usable(point)) for (profile in point.path("metrics").path("profiles")) {
                append("<p data-profile-id=\"").append(h(profile.path("profile_id").stringValue())).append("\" data-gain=\"").append(if (profile.path("gain_percent").isNumber) h(profile.path("gain_percent").decimalValue().toString()) else "").append("\">")
                if (!single) append(h(profile.path("profile_id").stringValue())).append(" · ")
                append(h(effectLabel(profile))).append("</p>")
            } else append("Нет оценки")
            append("</td><td><p>Место с запасом: ").append(if (usable(point)) h(size(point.path("metrics").path("budget_usage").path("budgeted_bytes"))) else "Нет данных").append("</p>")
            badge(point.path("status"))
            if (!point.path("conditions_match").isTrue) append("<p class=\"warning-text\">").append(if (point.path("conditions_match").isFalse) "Условия изменились" else "Сопоставимость не установлена").append("</p>")
            append("<details><summary>Точные значения и диагностика</summary>")
            pointDetails(summary, point, storage, single)
            append("</details></td></tr>")
        }
        append("</tbody></table></div></section>")
    }

    private fun StringBuilder.pointDetails(summary: JsonNode, point: JsonNode, storage: Boolean, single: Boolean) {
        val ordinal = point.path("ordinal").intValue()
        append("<dl class=\"values\">")
        entry("Бюджет варианта", number(point.path("budget_value")) + if (storage) " байт" else "\u00a0write units/ч")
        val metrics = point.path("metrics")
        if (usable(point)) {
            if (single) entry("Отношение к baseline", metric(metrics.path("mean_ratio"))) else {
                entry("Среднее отношение M", metric(metrics.path("mean_ratio"))); entry("Худшее отношение R", metric(metrics.path("worst_ratio")))
            }
            entry("Baseline B_mean", number(metrics.path("baseline_mean_cost"))); entry("Selected C_mean", number(metrics.path("mean_cost")))
            for (p in metrics.path("profiles")) {
                val label = p.path("profile_id").stringValue()
                entry("$label · baseline cost", number(p.path("baseline_cost"))); entry("$label · selected cost", number(p.path("cost"))); entry("$label · снижение cost", effect(p))
            }
            val usage = metrics.path("budget_usage"); val limits = summary.path("settings").path("constraints")
            entry("Оценка HypoPG", number(usage.path("estimated_bytes")) + " байт"); entry("Место с запасом", number(usage.path("budgeted_bytes")) + " байт")
            entry("Коэффициент запаса", number(usage.path("size_safety_factor")))
            entry("Лимит места", number(if (storage) point.path("budget_value") else limits.path("storage_budget_bytes")) + " байт")
            entry("Число индексов / лимит", number(usage.path("index_count")) + " / " + number(limits.path("max_indexes")))
            entry("Запись, write units/ч", number(usage.path("write_units_per_hour")))
            val writeLimit = if (storage) limits.path("write_budget_units_per_hour") else point.path("budget_value")
            entry("Лимит записи", if (writeLimit.isNumber) number(writeLimit) + " write units/ч" else "Лимит отключён")
        }
        entry("Статус", point.path("status").stringValue())
        entry("Условия", when { point.path("conditions_match").isTrue -> "Наблюдаемые условия совпали"; point.path("conditions_match").isFalse -> "Условия изменились"; else -> "Сопоставимость не установлена" })
        entry("Причины изменения условий", point.path("condition_changes").joinToString { conditionLabel(it.stringValue()) }.ifEmpty { "Нет данных" })
        entry("Причины остановки", point.path("stop_reasons").joinToString { it.stringValue() }.ifEmpty { "Нет данных" })
        append("</dl>")
        if (summary.path("settings").path("table_write_rates").isNull) append("<p>Модель записи отключена; неизвестный расход не равен нулю.</p>")
        error(point.path("error")); point.path("errors").filter { it != point.path("error") }.forEach { error(it) }
        append("<p class=\"series-links\">")
        for (name in listOf("report.html", "report.json", "indexes.sql")) {
            val path = point.path("artifacts").path(name)
            if (path.isString && path.stringValue() == "point-%02d/$name".format(Locale.ROOT, ordinal)) append("<a href=\"").append(h(path.stringValue())).append("\">").append(name).append("</a> ")
        }
        append("</p>")
    }

    private fun compact(value: BigDecimal): String {
        val rounded = value.setScale(1, RoundingMode.HALF_UP)
        if (rounded.abs() >= BigDecimal("1000000000")) {
            val symbols = DecimalFormatSymbols(Locale.ROOT).apply { decimalSeparator = ',' }
            return DecimalFormat("0.#E0", symbols).format(value).replace('E', 'e')
        }
        return if (rounded.signum() == 0 && value.signum() != 0) number(value) else number(rounded)
    }
    private fun compact(value: JsonNode): String = if (value.isNumber) compact(value.decimalValue()) else "Нет данных"
    private fun percent(value: BigDecimal): String = if (value.signum() != 0 && value.abs() < BigDecimal("0.05")) {
        if (value.signum() > 0) "< 0,1%" else "−< 0,1%"
    } else compact(value) + "%"
    private fun budget(value: JsonNode, storage: Boolean): String = if (storage) size(value) else compact(value) + "\u00a0ед./ч"
    private fun size(value: JsonNode): String {
        if (!value.isNumber) return "Нет данных"
        val bytes = value.decimalValue()
        val (divisor, unit) = when {
            bytes >= BigDecimal(1_073_741_824) -> BigDecimal(1_073_741_824) to "GiB"
            bytes >= BigDecimal(1_048_576) -> BigDecimal(1_048_576) to "MiB"
            bytes >= BigDecimal(1024) -> BigDecimal(1024) to "KiB"
            else -> BigDecimal.ONE to "байт"
        }
        return compact(bytes.divide(divisor)) + "\u00a0" + unit
    }
    private fun effectLabel(profile: JsonNode): String {
        val value = profile.path("gain_percent")
        if (!value.isNumber) return effect(profile)
        val gain = value.decimalValue()
        return when {
            gain.signum() == 0 -> "Без изменения"
            gain.abs() < BigDecimal("0.05") -> if (gain.signum() > 0) "Снижение менее 0,1%" else "Рост менее 0,1%"
            gain.signum() > 0 -> "Снижение на ${percent(gain)}"
            else -> "Рост на ${percent(gain.abs())}"
        }
    }
    private fun shortDefinition(candidate: JsonNode?, candidates: Collection<JsonNode>, fallback: String): String {
        if (candidate == null) return fallback
        val name = candidate.path("table").stringValue()
        val schema = candidate.path("schema").stringValue()
        val ambiguous = candidates.any { it.path("table").stringValue() == name && it.path("schema").stringValue() != schema }
        val label = if (ambiguous) "$schema.$name" else name
        val keys = candidate.path("keys").joinToString(", ") { it.path("column").stringValue() }
        val include = candidate.path("included_columns").takeIf { it.isArray && !it.isEmpty }
            ?.joinToString(", ", " INCLUDE (", ")") { it.stringValue() }.orEmpty()
        val method = candidate.path("method").takeIf { it.isString }?.stringValue() ?: "btree"
        return label + " · $method " + "($keys)$include"
    }
    private fun StringBuilder.entry(label: String, value: String) {
        append("<div><dt>").append(h(label)).append("</dt><dd>").append(h(value)).append("</dd></div>")
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
    private fun StringBuilder.notice(text: String) { append("<p class=\"notice warning\">").append(h(text)).append("</p>") }
    private fun StringBuilder.error(error: JsonNode) {
        if (error.isObject) append("<p class=\"notice error\">").append(h(error.path("code").stringValue()))
            .append(": ").append(h(error.path("message").stringValue())).append("</p>")
    }
    private fun statusLabel(value: String) = when (value) {
        "completed" -> "Завершён"; "partial" -> "Частичный результат"; "failed" -> "Ошибка"
        "skipped" -> "Не рассчитан"; "running" -> "Выполняется"; else -> "Нет данных"
    }
    private fun StringBuilder.badge(status: JsonNode) {
        val value = status.stringValue()
        append("<span class=\"series-status ").append(if (value in setOf("completed", "partial", "failed")) value else "")
            .append("\">").append(h(statusLabel(value))).append("</span>")
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
