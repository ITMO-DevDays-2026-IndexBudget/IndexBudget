package ru.itmo.indexbudget.report

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.Base64
import java.security.MessageDigest

enum class HtmlReportSection {
    OVERVIEW, QUERIES, QUERY_DETAILS, PROFILES, INDEXES, INDEX_DETAILS, PROVENANCE,
    METHOD, RESOURCE_MODEL, SEARCH, RUN, COMPARISON, MANUAL_COMPARISON, NEXT,
}

/** A read-only view of the shared report. No metric calculation or database calls; the static script only controls the view. */
@Component
class HtmlReportRenderer : ReportRenderer {
    private val stylesheet = checkNotNull(javaClass.getResource("/report/report.css")).readText()

    private val script = checkNotNull(javaClass.getResource("/report/report.js")).readText()
    private val scriptHash = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8)))

    override fun render(report: AnalysisReport): String = buildString {
        append("""<!doctype html><html lang="ru"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-$scriptHash'; base-uri 'none'; form-action 'none'">
            <title>IndexBudget · Отчёт анализа</title><style>""")
        append(stylesheet)
        append("""</style></head><body><a class="skip" href="#main">К содержанию</a>
            <header class="topbar"><a class="brand" href="#overview">IndexBudget</a>
            <span class="top-caption">PostgreSQL · анализ индексов</span><span class="offline">Автономный отчёт</span></header>
            <div class="layout"><aside><nav aria-label="Разделы отчёта">
            <a href="#overview">Вывод</a><a href="#queries">Влияние на запросы</a>
            <a href="#indexes">Ресурсы и индексы</a><a href="#provenance">Исходная нагрузка</a>
            <a href="#method">Методика и ограничения</a><a href="#next">Следующие шаги</a>
            </nav></aside>
            <main id="main">""")
        append(renderContent(report))
        append("</main></div><script>")
        append(script)
        append("</script></body></html>")
    }

    /** Render saved data as independently reusable sections; no metric or query evaluation takes place here. */
    fun renderSection(report: AnalysisReport, section: HtmlReportSection, prefix: String = "", includeCandidateCatalog: Boolean = true): String =
        prefixContent(buildString {
            when (section) {
                HtmlReportSection.OVERVIEW -> overview(report)
                HtmlReportSection.QUERIES -> queries(report)
                HtmlReportSection.QUERY_DETAILS -> { this.section("query-details", "SQL и планы", ""); queryDetails(report); append("</section>") }
                HtmlReportSection.PROFILES -> profiles(report)
                HtmlReportSection.INDEXES -> indexes(report)
                HtmlReportSection.INDEX_DETAILS -> { this.section("index-details", "Каталог индексов и DDL", ""); indexDetails(report); append("</section>") }
                HtmlReportSection.PROVENANCE -> provenance(report)
                HtmlReportSection.METHOD -> method(report)
                HtmlReportSection.RESOURCE_MODEL -> resourceModel()
                HtmlReportSection.SEARCH -> search(report, includeCandidateCatalog)
                HtmlReportSection.RUN -> run(report)
                HtmlReportSection.COMPARISON -> comparison(report)
                HtmlReportSection.MANUAL_COMPARISON -> manualComparison(report, includeCandidateCatalog)
                HtmlReportSection.NEXT -> nextSteps()
            }
        }, prefix)

    /** The same saved report, embedded without document chrome; ordinal prefixes isolate local anchors. */
    fun renderContent(report: AnalysisReport, prefix: String = ""): String = prefixContent(buildString {
        overview(report)
        queries(report, includeDetails = true)
        if (!singleProfile(report)) profiles(report)
        indexes(report, includeDetails = true)
        provenance(report)
        method(report, includeComparison = true)
        if (report.manualComparison != null) {
            append("<details class=\"technical-section\"><summary>Ручной набор индексов</summary>")
            manualComparison(report)
            append("</details>")
        }
        append("<details class=\"technical-section\"><summary>Завершение поиска, лимиты и диагностика</summary>")
        search(report)
        append("</details><details class=\"technical-section\"><summary>Данные запуска</summary>")
        run(report)
        append("</details>")
        nextSteps()
        append("<footer>IndexBudget · Спецификация ").append(h(report.specVersion)).append(" · Автономный отчёт</footer>")
    }, prefix)

    private fun prefixContent(content: String, prefix: String): String {
        require(prefix.isEmpty() || prefix.matches(Regex("variant-[1-5]-")))
        if (prefix.isEmpty()) return content
        // Only renderer-owned HTML attributes are rewritten; SQL/text has already been escaped.
        return content.replace(Regex("""(\s(?:id|for|data-copy)=")([^"]+)(")""")) {
            it.groupValues[1] + prefix + it.groupValues[2] + it.groupValues[3]
        }.replace(Regex("""(\shref="#)([^"]+)(")""")) {
            it.groupValues[1] + (if (it.groupValues[2].startsWith("series-")) "" else prefix) + it.groupValues[2] + it.groupValues[3]
        }
    }

    private fun StringBuilder.manualComparison(report: AnalysisReport, includeCandidateCatalog: Boolean = true) {
        val manual = report.manualComparison ?: return
        append("<section id=\"manual-comparison\"><h2>Ручной набор индексов</h2>")
        append("<p>Отдельная оценка вне автоматического shortlist. Best robust и best mean относятся к обычному поиску.</p>")
        append("<p>Статус: ").append(h(manual.status.wireValue)).append(". Допустимость: ")
        text(if (manual.status != ManualComparisonStatus.COMPLETED) "Не установлена" else if (manual.admissible) "Допустим" else "Нарушает ограничения")
        append(".</p>")
        if (!includeCandidateCatalog && manual.candidates.isNotEmpty()) {
            append("<p><a href=\"#series-index-catalog\">DDL ручного набора в общем каталоге</a></p>")
        }
        for (candidate in manual.candidates) {
            if (includeCandidateCatalog) append("<pre>").append(h(candidate.ddl ?: "Нет данных")).append("</pre>")
            append("<p>Оценка размера: ")
            text(candidate.estimatedBytes?.toString() ?: "Нет данных")
            append(" байт; с запасом: ").append(h(candidate.budgetedBytes ?: "Нет данных")).append("; write proxy: ")
            text(candidate.writeUnitsPerHour?.toString() ?: "Нет данных")
            append("</p>")
        }
        manual.metrics?.let { value ->
            if (singleProfile(report)) {
                append("<p>Отношение стоимости к baseline: ").append(h(value.meanRatio ?: "Нет данных")).append("</p>")
            } else {
                append("<p>M: ").append(h(value.meanRatio ?: "Нет данных")).append("; R: ").append(h(value.worstRatio ?: "Нет данных")).append("</p>")
            }
            append("<div class=\"table-scroll\" tabindex=\"0\" role=\"region\" aria-label=\"Сравнение ручного набора\"><table><thead><tr><th>Профиль</th><th>Baseline cost</th><th>Ручной cost</th><th>Отношение</th></tr></thead><tbody>")
            for (profile in value.profiles) {
                append("<tr><td>").append(h(profile.profileId)).append("</td><td>").append(h(profile.baselineCost))
                append("</td><td>").append(h(profile.cost)).append("</td><td>").append(h(profile.ratio ?: "Нет данных")).append("</td></tr>")
            }
            append("</tbody></table></div>")
        }
        for (violation in manual.budgetViolations) append("<p>Нарушение бюджета: ").append(h(violation.name)).append("</p>")
        for (violation in manual.evaluationViolations) append("<p>").append(h(violation.code.name)).append("</p>")
        manual.error?.let { append("<p>").append(h(it.code.name)).append(": ").append(h(it.message)).append("</p>") }
        for (decision in manual.decisions) append("<p>").append(h(decision.reason.name)).append(": ").append(h(decision.details)).append("</p>")
        append("<div class=\"table-scroll\" tabindex=\"0\" role=\"region\" aria-label=\"Сравнение ручного набора\"><table><thead><tr><th>SQL ID</th><th>Baseline</th><th>Ручной cost</th><th>Регрессия</th></tr></thead><tbody>")
        for (query in manual.queryComparisons) {
            append("<tr><td>").append(h(query.queryId)).append("</td><td>").append(h(query.baselineCost ?: "Нет данных"))
            append("</td><td>").append(h(query.selectedCost ?: "Нет данных")).append("</td><td>")
            text(when (query.regression) { true -> "Да"; false -> "Нет"; null -> "Нет данных" })
            append("</td></tr>")
        }
        append("</tbody></table></div><p>Planner cost не является временем; write proxy — модель, не измерение записи.</p></section>")
    }

    private fun StringBuilder.overview(report: AnalysisReport) {
        val result = report.result
        val summary = report.summary
        val selected = result.selected
        append("<section id=\"overview\" class=\"overview\"><div class=\"title-row\"><h1>Результат анализа</h1>")
        badge(statusLabel(result.status), result.status.name.lowercase(Locale.ROOT))
        append("</div>")
        if (report.environment.applicationVersion == "fixture") {
            append("<p class=\"demo muted\">Демонстрационные данные · синтетическая проверка представления.</p>")
        }
        append("<div class=\"recommendation\"><h2>Предлагаемые индексы</h2>")
        when {
            selected == null -> append("<p>Рекомендация отсутствует: пригодный результат не сохранён.</p>")
            selected.evaluation.candidateIds.isEmpty() -> append("<p>Выбран пустой набор. Новые индексы не предлагаются.</p>")
            else -> {
                append("<ul class=\"recommended-indexes\">")
                selected.evaluation.candidateIds.forEachIndexed { index, id ->
                    val candidate = report.candidates.candidates.firstOrNull { it.id == id }
                    append("<li><a href=\"#indexes\"><code>")
                    text(candidate?.let { shortDefinition(it, report) } ?: "Индекс ${index + 1} · определение неизвестно")
                    append("</code></a></li>")
                }
                append("</ul>")
            }
        }
        append("</div><p class=\"effect-sentence\">Оценочная стоимость выбранных запросов с учётом частот: <strong class=\"")
            .append(effectStyle(summary.meanEffect)).append("\">")
        text(if (result.selected == null) "Эффект не определён" else effectLabel(summary.meanEffect))
        append("</strong> по сравнению с существующими индексами.</p>")
        append("<p class=\"muted baseline-note\"><strong>Planner cost</strong> — оценка затрат PostgreSQL, не время выполнения. Снижение cost не гарантирует ускорения.</p>")
        append("<dl class=\"summary-facts\">")
        entry("Регрессии SQL", summary.regressedQueryCount?.let { "$it из ${queryCount(report) ?: "нет данных"}" } ?: "Нет данных")
        append("</dl>")
        if (summary.regressedQueryCount != null && summary.regressedQueryCount > 0) {
            append("<p class=\"error\"><a href=\"#queries\">Посмотреть ухудшения запросов</a></p>")
        }
        if (result.status != RunStatus.COMPLETED) {
            append("<p class=\"notice warning\">")
            text(if (result.status == RunStatus.PARTIAL) "Поиск остановлен досрочно. Сохранён лучший полный допустимый результат. " else "Анализ завершился ошибкой. ")
            text(summary.stopReasons.joinToString { stopLabel(it) })
            summary.errors.forEach { append("<br>").append(h(it.code.name)).append(": ").append(h(it.message)) }
            append("</p>")
        }
        workloadContext(report)
        append("</section>")
    }

    private fun StringBuilder.workloadContext(report: AnalysisReport) {
        append("<div class=\"workload-context\"><h2>Основа вывода</h2><p>")
        text("SQL: ${queryCount(report) ?: "нет данных"} · профилей частот: ${report.input?.profiles?.size ?: "нет данных"}. ")
        val provenance = report.workloadProvenance
        if (provenance == null) {
            append("Источник, период и охват неизвестны; репрезентативность не подтверждена.</p>")
        } else {
            append("Источник — pg_stat_statements, разница снимков.</p><ul class=\"observation-windows\">")
            provenance.windows.forEach { window ->
                append("<li><span>").append(h(window.profileId)).append("</span> · <span class=\"quantity\">")
                text(windowDuration(window))
                append("</span> · ").append(if (window.complete) "полное наблюдение" else "<strong class=\"warning-text\">неполное наблюдение</strong>")
                append(" · выбрано вызовов: ").append(h(number(window.coverage.selectedCalls))).append("</li>")
            }
            append("</ul><p class=\"muted\">Репрезентативность нагрузки требует отдельной проверки. ")
            append("<a href=\"#provenance\">Интервалы, охват и историческое время</a></p>")
        }
        val warnings = (report.result.diagnostics.warnings + report.candidates.diagnostics.warnings).distinct()
        if (warnings.isNotEmpty()) {
            append("<p class=\"warning-text\">Ограничения данных: ")
            text(warnings.joinToString(" ") { it.message })
            append(" <a href=\"#search\">Подробности</a></p>")
        }
        append("</div>")
    }

    private fun StringBuilder.comparison(report: AnalysisReport) {
        section("comparison", "Устойчивость или средний эффект", "Два критерия, один исследованный пул")
        append("<p>Лучшие найденные решения из одного общего пула полностью оценённых допустимых наборов. Поиском управлял <strong>")
        text(report.result.selectedObjective.name.lowercase(Locale.ROOT))
        append("</strong>. Область сравнения: <code>").append(h(report.comparisonScope.name.lowercase(Locale.ROOT))).append("</code>.</p>")
        val robust = report.result.bestRobust
        val mean = report.result.bestMean
        if (robust != null && mean != null) {
            append("<p class=\"notice\">")
            text(if (robust.evaluation.candidateIds == mean.evaluation.candidateIds) {
                "Лучшие наборы robust и mean совпадают. В этом пуле компромисс не потребовал другого набора."
            } else {
                "Лучшие наборы различаются. Robust ориентирован на худший профиль, mean — на ожидаемую стоимость."
            })
            append("</p>")
        }
        append("<div class=\"comparison-grid\">")
        if (report.summary.incumbentsCoincide == true) {
            configuration("best_robust best_mean", "Robust = Mean", "Один набор для обоих критериев", robust, true)
        } else {
            configuration("best_robust", "Robust", "Худшее отношение к исходной стоимости", robust, report.result.selectedObjective == Objective.ROBUST)
            configuration("best_mean", "Mean", "Средняя взвешенная стоимость", mean, report.result.selectedObjective == Objective.MEAN)
        }
        append("</div><div class=\"price\"><div><strong>Цена устойчивости</strong><p>Дополнительная средняя оценка планировщика при выборе robust вместо mean</p></div><strong class=\"price-value\">")
        text(number(report.priceOfRobustness))
        append("</strong></div><p class=\"muted\">Это сравнение найденных решений, а не два независимых поиска. Гарантии глобального оптимума нет.</p></section>")
    }

    private fun StringBuilder.configuration(key: String, title: String, description: String, value: EvaluatedConfiguration?, selected: Boolean) {
        append("<article class=\"configuration")
        if (selected) append(" chosen")
        append("\" data-configuration=\"").append(key).append("\"><div class=\"title-row\"><h3>").append(title).append("</h3>")
        if (selected) badge("Критерий поиска", "selected")
        append("</div><p class=\"muted\">").append(description).append("</p>")
        if (value == null) {
            append("<p>Нет сохранённого решения.</p></article>")
            return
        }
        append("<details><summary>Набор, расходы и точные метрики</summary><dl class=\"values\">")
        entry("Худшее отношение R", setRatio(value.metrics, value.metrics.worstRatio))
        entry("Среднее отношение M", setRatio(value.metrics, value.metrics.meanRatio))
        entry("Ожидаемая стоимость C_mean", number(value.metrics.meanCost))
        entry("Baseline B_mean", number(value.metrics.baselineMeanCost))
        entry("Оценка размера", storageSize(value.metrics.budgetUsage.estimatedBytes))
        entry("С запасом", storageSize(value.metrics.budgetUsage.budgetedBytes))
        entry("Write units/час", number(value.metrics.budgetUsage.writeUnitsPerHour))
        entry("Набор", value.evaluation.candidateIds.joinToString().ifEmpty { "Пустой набор" })
        append("</dl>")
        table(listOf("Профиль", "Стоимость", "Отношение", "Выигрыш, %")) {
            value.metrics.profiles.forEach { profile ->
                row(profile.profileId, number(profile.cost), ratio(profile.ratio, profile.baselineCost), percent(profile.gainPercent, profile.baselineCost))
            }
        }
        append("</details></article>")
    }

    private fun StringBuilder.profiles(report: AnalysisReport) {
        if (singleProfile(report)) return
        section("profiles", "Стоимость по профилям нагрузки", "С учётом частот каждого профиля; меньше — лучше")
        val series = report.profileComparisons
        append("<div class=\"table-scroll\" tabindex=\"0\" role=\"region\" aria-label=\"Стоимость по профилям\"><table class=\"profile-table\"><thead><tr>")
        listOf("Профиль", "До", "С набором", "Изменение").forEach { append("<th scope=\"col\">").append(h(it)).append("</th>") }
        append("</tr></thead><tbody>")
        series.forEach { profile ->
            append("<tr data-profile-id=\"").append(h(profile.id))
                .append("\" data-baseline=\"").append(h(profile.selected.baselineCost ?: profile.baseline.baselineCost ?: ""))
                .append("\" data-cost=\"").append(h(profile.selected.cost ?: ""))
                .append("\" data-gain=\"").append(h(profile.selected.gainPercent ?: ""))
                .append("\"><th scope=\"row\">").append(h(profile.id)).append("</th>")
            cell(compactNumber(profile.selected.baselineCost ?: profile.baseline.baselineCost))
            cell(compactNumber(profile.selected.cost))
            cell(effectLabel(profile.selected))
            append("</tr>")
        }
        append("</tbody></table></div>")
        if (series.isEmpty()) append("<p>Профили отсутствуют в сохранённой модели.</p>")
        append("<details><summary>Точные стоимости и веса</summary>")
        profileMetrics(report)
        append("</details></section>")
    }

    private fun StringBuilder.profileMetrics(report: AnalysisReport) {
        table(listOf("Профиль", "Вес", "Baseline cost", "Selected cost", "Отношение", "Выигрыш, cost", "Выигрыш, %")) {
            report.profileComparisons.forEach { profile ->
                val effect = profile.selected
                row(profile.id, number(report.result.selected?.metrics?.profiles?.firstOrNull { it.profileId == profile.id }?.normalizedWeight ?: report.normalizedProfileWeights[profile.id]),
                    number(effect.baselineCost ?: profile.baseline.baselineCost), number(effect.cost), ratio(effect.ratio, effect.baselineCost),
                    number(effect.gain), percent(effect.gainPercent, effect.baselineCost))
            }
        }
        if (report.profileComparisons.isEmpty()) append("<p>Профили отсутствуют в сохранённой модели.</p>")
    }

    private fun shortDefinition(candidate: IndexCandidate, report: AnalysisReport): String =
        "${shortTable(candidate.definition.table, report)}(" + candidate.definition.keys.joinToString { it.column.name } + ")" +
            candidate.definition.includedColumns.takeIf { it.isNotEmpty() }?.joinToString(", ", " INCLUDE (", ")") { it.name }.orEmpty()

    private fun effectPercent(effect: CostEffect): String = when {
        effect.gainPercent != null -> percentDisplay(effect.gainPercent)
        effect.relativeStatus == RelativeValueStatus.ZERO_BASELINE -> "Неприменимо: нулевой baseline"
        effect.relativeStatus == RelativeValueStatus.OUT_OF_RANGE -> "Вне числового диапазона"
        else -> "Нет данных"
    }

    private fun effectLabel(effect: CostEffect): String = when {
        effect.gainPercent == null && effect.relativeStatus == RelativeValueStatus.ZERO_BASELINE -> "Процент неприменим: нулевой baseline"
        effect.gainPercent == null -> effectPercent(effect)
        effect.gain == 0.0 -> "Без изменения"
        kotlin.math.abs(effect.gainPercent) < 0.05 -> if ((effect.gain ?: 0.0) > 0) "Снижение менее 0,1%" else "Рост менее 0,1%"
        effect.gainPercent > 0 -> "Снижение на ${percentDisplay(effect.gainPercent)}"
        else -> "Рост на ${percentDisplay(kotlin.math.abs(effect.gainPercent))}"
    }

    private fun effectStyle(effect: CostEffect) = when {
        effect.gain == null || effect.gain == 0.0 -> ""
        effect.gain > 0 -> "improvement"
        else -> "regressed-text"
    }

    private fun percentDisplay(value: Double): String = when {
        value != 0.0 && kotlin.math.abs(value) < 0.05 -> if (value > 0) "< 0,1%" else "−< 0,1%"
        else -> compactNumber(value) + "%"
    }

    private fun compactNumber(value: Number?): String {
        if (value == null) return "Нет данных"
        val rounded = BigDecimal(value.toString()).setScale(1, RoundingMode.HALF_UP)
        if (value.toDouble() != 0.0 && rounded.signum() == 0) return if (value.toDouble() > 0) "< 0,1" else "−< 0,1"
        if (rounded.abs() >= BigDecimal("1000000000")) {
            val symbols = DecimalFormatSymbols(Locale.ROOT).apply { decimalSeparator = ',' }
            return DecimalFormat("0.#E0", symbols).format(value).replace('E', 'e')
        }
        return number(rounded)
    }

    private fun readableSize(bytes: Long?): String {
        if (bytes == null) return "Нет данных"
        val (divisor, unit) = when {
            bytes >= 1_073_741_824 -> 1_073_741_824L to "GiB"
            bytes >= 1_048_576 -> 1_048_576L to "MiB"
            bytes >= 1_024 -> 1_024L to "KiB"
            else -> 1L to "байт"
        }
        return compactNumber(BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(divisor))) + "\u00a0" + unit
    }

    private fun shortTable(table: TableRef, report: AnalysisReport): String {
        val ambiguous = report.candidates.candidates.map { it.definition.table }.distinct()
            .any { it.name == table.name && it.schema != table.schema }
        return if (ambiguous) tableName(table) else table.name
    }

    private fun windowDuration(window: ObservationWindow): String {
        val duration = java.time.Duration.between(window.startedAt, window.finishedAt)
        val millis = BigDecimal.valueOf(duration.seconds).movePointRight(3) + BigDecimal.valueOf(duration.nano.toLong()).movePointLeft(6)
        return when {
            millis >= BigDecimal(60_000) -> compactNumber(millis.divide(BigDecimal(60_000), 6, RoundingMode.HALF_UP)) + "\u00a0мин"
            millis >= BigDecimal(1_000) -> compactNumber(millis.movePointLeft(3)) + "\u00a0с"
            else -> compactNumber(millis) + "\u00a0мс"
        }
    }

    private fun StringBuilder.provenance(report: AnalysisReport) {
        section("provenance", "Исходные запросы и частоты", "")
        append("<p>Профиль нагрузки — частоты выполнения SQL для заданного периода или сценария.</p>")
        val profiles = report.input?.profiles.orEmpty()
        table(listOf("SQL") + profiles.map { "${it.id}, вызовов/час" }) {
            report.input?.queries.orEmpty().forEachIndexed { index, query ->
                row(*(listOf("SQL ${index + 1}") + profiles.map { number(it.callsPerHour[query.id]) }).toTypedArray())
            }
        }
        append("<p class=\"muted\">Частоты в пересчёте на час; это не число реально наблюдавшихся вызовов.</p>")
        writeProfile(report)
        val provenance = report.workloadProvenance
        if (provenance == null) {
            append("<p>Происхождение не задано. Для ручного workload источник и охват наблюдений неизвестны.</p></section>")
            return
        }
        append("<dl class=\"values\">")
        entry("Источник", "pg_stat_statements")
        entry("Идентификатор источника", provenance.sourceId.toString())
        entry("Правило отбора", provenance.selectionRule)
        append("</dl><p>Охват относится к доступным полным записям окна, а не ко всему трафику сервера. Неизвестные значения не заменяются нулём.</p>")
        table(listOf("Профиль", "Начало (UTC)", "Конец (UTC)", "Длительность", "Полнота наблюдения", "Вызовы: выбрано / доступно")) {
            provenance.windows.forEach { window ->
                row(window.profileId, window.startedAt.toString(), window.finishedAt.toString(), windowDuration(window),
                    if (window.complete) "Полное" else "Неполное",
                    "${number(window.coverage.selectedCalls)} / ${number(window.coverage.availableCalls)}")
            }
        }
        append("<details><summary>Историческое время и примеры SQL</summary><h3>Историческое время выполнения</h3><p>Измерено до рекомендации, в миллисекундах. Эти данные не являются прогнозом времени с новыми индексами и не пересчитываются из planner cost.</p>")
        table(listOf("Профиль", "Выбранные записи, мс", "Доступные полные записи, мс")) {
            provenance.windows.forEach { window ->
                row(window.profileId, number(window.coverage.selectedExecTimeMillis), number(window.coverage.availableExecTimeMillis))
            }
        }
        append("<h3>Примеры SQL</h3><p>Пример с литералами представляет запись статистики; его план не характеризует все возможные значения параметров.</p>")
        table(listOf("SQL", "Происхождение примера")) {
            provenance.queryExamples.forEach { example ->
                row(queryLabel(example.queryId, report), when (example.source) {
                    QueryExampleSource.USER_SUPPLIED_LITERALS -> "Репрезентативные литералы пользователя"
                    QueryExampleSource.ORIGINAL_LITERAL_SQL -> "Исходный SQL с литералами"
                })
            }
        }
        append("</details></section>")
    }

    private fun StringBuilder.indexes(report: AnalysisReport, includeDetails: Boolean = false) {
        section("indexes", "Ресурсы и рекомендуемые индексы", "")
        val usage = report.summary.budgetUsage
        val constraints = report.summary.budgetLimits
        append("<div class=\"table-scroll\" tabindex=\"0\" role=\"region\" aria-label=\"Расход ресурсов\"><table class=\"resource-table\"><thead><tr><th scope=\"col\">Ресурс</th><th scope=\"col\">Оценка для набора</th><th scope=\"col\">Ограничение</th></tr></thead><tbody>")
        append("<tr><th scope=\"row\">Место</th><td><span class=\"quantity\">").append(h(readableSize(usage?.budgetedBytes)))
            .append("</span> с запасом<span class=\"cell-note\">HypoPG: ").append(h(readableSize(usage?.estimatedBytes))).append("</span></td><td>")
        if (constraints?.storageBudgetBytes == Long.MAX_VALUE) {
            append("<a href=\"#resource-metrics\">Точное значение лимита</a>")
        } else text(readableSize(constraints?.storageBudgetBytes))
        append("</td></tr>")
        row("Обслуживание при записи", compactNumber(usage?.writeUnitsPerHour) + " условных ед./ч", when {
            constraints == null -> "Нет данных"
            constraints.writeBudgetUnitsPerHour == null -> "Бюджет записи отключён"
            constraints.writeBudgetUnitsPerHour == 0.0 -> "Нулевой лимит · ${if (usage?.writeUnitsPerHour == 0.0) "расход 0" else "расход ${compactNumber(usage?.writeUnitsPerHour)}"}"
            else -> compactNumber(constraints.writeBudgetUnitsPerHour) + " условных ед./ч"
        })
        row("Дополнительные индексы", number(usage?.indexCount), number(constraints?.maxIndexes))
        append("</tbody></table></div>")
        if (usage != null) {
            append("<p>Размер оценён HypoPG; расход места учитывается с запасом ×").append(h(number(usage.sizeSafetyFactor)))
                .append(". Фактический размер может отличаться.</p>")
        }
        append("<p class=\"muted\">Бюджет записи — лимит условных затрат на обслуживание новых индексов при изменении данных. <a href=\"#resource-model\">Как устроена модель</a></p>")
        append("<details id=\"resource-metrics\"><summary>Точные расходы и лимиты</summary><dl class=\"values\">")
        entry("Оценка HypoPG", storageSize(usage?.estimatedBytes))
        entry("С запасом", storageSize(usage?.budgetedBytes))
        entry("Бюджет места", storageSize(constraints?.storageBudgetBytes))
        entry("Write units/час", number(usage?.writeUnitsPerHour))
        entry("Лимит записи", constraints?.writeBudgetUnitsPerHour?.let(::number) ?: if (constraints == null) "Нет данных" else "Отключён")
        entry("Число индексов / лимит", "${number(usage?.indexCount)} / ${number(constraints?.maxIndexes)}")
        append("</dl></details>")
        if (includeDetails) {
            append("<h3>Каталог индексов и DDL</h3>")
            indexDetails(report)
        }
        append("</section>")
    }

    private fun StringBuilder.indexDetails(report: AnalysisReport) {
        val selected = report.result.selected
        when {
            selected == null -> append("<p>Рекомендация отсутствует: пригодный результат не сохранён.</p>")
            selected.evaluation.candidateIds.isEmpty() -> append("<p>Выбран пустой набор. Новые индексы не предлагаются.</p>")
            else -> selected.evaluation.candidateIds.forEachIndexed { ordinal, id ->
                append("<article class=\"index-card\" data-selected-index=\"").append(h(id)).append("\">")
                val candidate = report.candidates.candidates.firstOrNull { it.id == id }
                if (candidate == null) {
                    append("<p>Описание индекса отсутствует в модели.</p>")
                    pre(id)
                } else {
                    append("<h3><code>").append(h(shortDefinition(candidate, report))).append("</code></h3>")
                    append("<p class=\"muted\">Оценка: ").append(h(readableSize(candidate.estimatedBytes)))
                        .append(" · с запасом: ").append(h(readableSize(candidate.budgetedBytes))).append("</p>")
                    append("<details><summary>DDL, определение и происхождение</summary>")
                    copyable(candidate.ddl ?: "DDL отсутствует в модели.", "copy-ddl-$ordinal", "Скопировать DDL")
                    pre(id)
                    append("<p>").append(h(definition(candidate))).append("</p><dl class=\"values\">")
                    entry("Оценка", storageSize(candidate.estimatedBytes))
                    entry("С запасом", storageSize(candidate.budgetedBytes))
                    entry("Write units/час", number(candidate.writeUnitsPerHour))
                    entry("Источники SQL", candidate.sourceQueryIds.sorted().joinToString())
                    if (candidate.sourceBranches.isNotEmpty()) entry("Ветви условий", candidate.sourceBranches.sortedWith(compareBy({ it.queryId }, { it.conditionPath })).joinToString { "${it.queryId}: ${it.conditionPath}" })
                    entry("Происхождение", candidate.generationReasons.sortedBy { it.name }.joinToString { it.name })
                    append("</dl><p>Источники SQL объясняют происхождение кандидата, а не измеренную индивидуальную пользу индекса.</p>")
                    if (candidate.definition.includedColumns.isNotEmpty()) append("<p>INCLUDE позволяет рассмотреть index-only scan; обращения к таблице зависят от visibility map и не исключены.</p>")
                    append("</details>")
                }
                append("</article>")
            }
        }
    }

    private fun StringBuilder.writeProfile(report: AnalysisReport) {
        append("<details><summary>Исходный профиль записи</summary>")
        val rates = report.input?.tableWriteRates
        if (rates == null) append("<p>Профиль записи не задан. Неизвестные write units не заменяются нулём.</p>") else {
            table(listOf("Таблица", "INSERT/час", "UPDATE/час", "DELETE/час", "Источник")) {
                rates.toSortedMap().forEach { (table, rate) -> row(table, number(rate.insertsPerHour), number(rate.updatesPerHour), number(rate.deletesPerHour), rate.source.name.lowercase(Locale.ROOT)) }
            }
        }
        append("</details>")
    }

    private fun StringBuilder.queries(report: AnalysisReport, includeDetails: Boolean = false) {
        section("queries", "Каким запросам поможет выбранный набор", "Оценка одного выполнения запроса, planner cost")
        append("<p>До — существующие индексы; с набором — они же и все предложенные. Меньшая оценка лучше.</p>")
        val baseline = report.result.baseline
        val selected = report.result.selected?.evaluation
        val effects = report.queryEffects.associateBy { it.id }
        val comparisons = report.queryComparisons.associateBy { it.queryId }
        val queryIds = (report.input?.queries.orEmpty().map { it.id } + baseline?.costByQueryId.orEmpty().keys + selected?.costByQueryId.orEmpty().keys).distinct()
        fun hasRegression(id: String): Boolean = comparisons[id]?.regression ?: run {
            val before = baseline?.costByQueryId?.get(id)
            val after = selected?.costByQueryId?.get(id)
            before != null && after != null && after > before
        }
        append("<div class=\"query-controls\" hidden><label for=\"query-sort\">Порядок запросов</label> ")
        append("<select id=\"query-sort\"><option value=\"regression\">Сначала ухудшения</option><option value=\"gain\">Сначала наибольшее снижение, %</option><option value=\"cost\">Сначала высокая стоимость после</option><option value=\"original\">Исходный порядок</option></select></div>")
        append("<div id=\"query-comparison\" class=\"query-comparison\">")
        table(listOf("SQL", "До", "С набором", "Изменение")) {
            queryIds.withIndex().sortedWith(compareBy<IndexedValue<String>> { if (hasRegression(it.value)) 0 else 1 }.thenBy { it.index }).forEach { (index, id) ->
                val effect = effects[id]?.selected
                val before = baseline?.costByQueryId?.get(id)
                val after = selected?.costByQueryId?.get(id)
                val regression = hasRegression(id)
                append("<tr data-order=\"").append(index).append("\" data-regression=\"").append(if (regression) 1 else 0)
                    .append("\" data-gain=\"").append(h(effect?.gainPercent ?: ""))
                    .append("\" data-cost=\"").append(h(after ?: "")).append("\"")
                if (regression) append(" class=\"regression\"")
                append("><th scope=\"row\"><a href=\"#query-").append(index).append("\">SQL ").append(index + 1).append("</a></th>")
                cell(compactNumber(before))
                cell(compactNumber(after))
                cell(effect?.let(::effectLabel) ?: when {
                    before == null || after == null -> "Нет данных"
                    regression -> "Рост cost · процент неизвестен"
                    after < before -> "Снижение cost · процент неизвестен"
                    else -> "Без изменения"
                })
                append("</tr>")
            }
        }
        append("</div>")
        costGuards(report)
        if (includeDetails) {
            append("<h3>SQL и планы</h3>")
            queryDetails(report)
        }
        append("</section>")
    }

    private fun StringBuilder.queryDetails(report: AnalysisReport) {
        val baseline = report.result.baseline
        val selected = report.result.selected?.evaluation
        val effects = report.queryEffects.associateBy { it.id }
        val queryIds = (report.input?.queries.orEmpty().map { it.id } + baseline?.costByQueryId.orEmpty().keys + selected?.costByQueryId.orEmpty().keys).distinct()
        queryIds.forEachIndexed { index, id ->
            append("<details class=\"query-detail\" id=\"query-").append(index).append("\"><summary>SQL ").append(index + 1).append(" · текст, точные значения и планы</summary>")
            append("<h3>Исходный SQL</h3>")
            copyable(report.input?.queries?.firstOrNull { it.id == id }?.sql ?: "Исходный SQL отсутствует в модели.", "copy-sql-$index", "Скопировать SQL")
            append("<h3>Полный ID</h3>")
            pre(id)
            append("<dl class=\"values\">")
            entry("Baseline cost", number(baseline?.costByQueryId?.get(id)))
            entry("Selected cost", number(selected?.costByQueryId?.get(id)))
            entry("Снижение cost, %", effects[id]?.selected?.let { percent(it.gainPercent, it.baselineCost) } ?: "Нет данных")
            append("</dl>")
            val plans = linkedMapOf<String, MutableList<String>>()
            fun retain(label: String, plan: ExplainPlan?) {
                if (plan == null) {
                    append("<p>").append(h(label)).append(": план отсутствует.</p>")
                } else plans.getOrPut(plan.json) { mutableListOf() }.add(label)
            }
            retain("Существующие индексы", baseline?.plansByQueryId?.get(id))
            retain("Выбранный набор", selected?.plansByQueryId?.get(id))
            report.result.bestRobust?.let { retain("best_robust", it.evaluation.plansByQueryId[id]) }
            report.result.bestMean?.let { retain("best_mean", it.evaluation.plansByQueryId[id]) }
            plans.entries.forEachIndexed { planIndex, (json, labels) ->
                append("<details class=\"plan-detail\"><summary>").append(h(labels.joinToString(" · "))).append(" · EXPLAIN JSON</summary>")
                copyable(json, "copy-plan-$index-$planIndex", "Скопировать план")
                append("</details>")
            }
            append("</details>")
        }
    }

    private fun StringBuilder.costGuards(report: AnalysisReport) {
        val guards = report.input?.constraints?.queryCostGuards.orEmpty()
        if (guards.isEmpty()) return
        append("<h3>Ограничения planner cost для критических SQL</h3>")
        append("<p class=\"muted\">Предел — максимальное отношение final/baseline planner cost; равенство порогу допустимо. ")
        append("Это ограничение стоимости планировщика, а не latency и не SLA. Наборы с нарушением отклоняются целиком ")
        append("и не обновляют лучшие результаты.</p>")
        val outcomes = report.costGuards.associateBy { it.queryId }
        table(listOf("SQL", "Предел отношения", "Baseline cost", "Final cost", "Наблюдаемое отношение", "Статус")) {
            guards.keys.sorted().forEach { queryId ->
                val outcome = outcomes[queryId]
                if (outcome == null) {
                    row(queryId, number(guards.getValue(queryId)), "Нет данных", "Нет данных", "Нет данных", "Нет данных")
                    return@forEach
                }
                append(if (outcome.violated) "<tr class=\"regression\">" else "<tr>")
                cell(queryId)
                cell(number(outcome.maxRatio))
                cell(number(outcome.baselineCost))
                cell(number(outcome.selectedCost))
                cell(when {
                    outcome.zeroBaseline -> "Неприменимо (нулевой baseline)"
                    else -> number(outcome.observedRatio)
                })
                cell(when {
                    outcome.violated && outcome.zeroBaseline -> "Нарушено: положительный cost при нулевом baseline"
                    outcome.violated -> "Нарушено"
                    else -> "Соблюдено"
                })
                append("</tr>")
            }
        }
    }

    private fun StringBuilder.search(report: AnalysisReport, includeCandidateCatalog: Boolean = true) {
        section("search", "Границы поиска", "Что было исследовано и где сработали ограничения")
        append("<p>Режим анализа: ").append(h(modeName(report.analysisMode))).append(".</p>")
        append("<p>Рейтинг shortlist: ").append(h(rankingName(report.candidateRanking)))
            .append(". Рейтинг задаёт порядок сценарных очередей; критерии поиска и бюджеты сохраняются.</p>")
        val search = report.result.diagnostics
        val candidates = report.candidates.diagnostics
        val stats = search.statistics
        val limits = search.limits
        table(listOf("Счётчик", "Факт", "Лимит")) {
            row("Попытки EXPLAIN, включая ошибки", number(stats.explainAttempts), number(limits.maxExplainAttempts))
            row("Попытки оценки наборов", number(stats.evaluationAttempts), "Не задан")
            row("Полностью оценённые наборы", number(stats.completedEvaluations), "Не задан")
            row("Cache hits", number(stats.cacheHits), "Не задан")
            row("Новые попытки repair", number(stats.repairAttempts), number(limits.maxRepairAttempts))
            row("Непроверенные конфигурации", number(search.unexaminedConfigurations), "Не задан")
        }
        append("<div class=\"caps\">")
        cap("Колонки до генерации пар", "${candidates.columnLimitPerQueryTable} на (SQL, таблица)")
        cap("Локальные кандидаты", "${candidates.localCandidateLimit} на (SQL, таблица)")
        cap("Общий shortlist", "${candidates.fixedPoolSelection?.admissibleCandidates ?: candidates.preparation?.finalShortlistedCandidates ?: candidates.shortlistedCandidates} / ${candidates.globalCandidateLimit}")
        append("</div>")
        append("<dl class=\"values\">")
        entry("Исходный shortlist", number(candidates.initialShortlistedCandidates))
        entry("Кандидаты в резерве", "${number(candidates.reserveCandidates)} / ${number(candidates.reserveCandidateLimit)}")
        append("</dl>")
        if (candidates.preparationScope == CandidatePreparationScope.COMMON_POOL) {
            append("<p>Статистика подготовки относится к общему пулу при максимальном бюджете.</p>")
        }
        candidates.fixedPoolSelection?.let { point ->
            table(listOf("Текущая бюджетная точка", "Факт")) {
                row("Кандидаты общего пула", number(point.poolCandidates))
                row("Допустимы для текущей точки", number(point.admissibleCandidates))
                row("Бюджетные отказы текущей точки", number(point.budgetRejectedCandidates))
                row("Попытки оценки размера текущей точки", number(point.sizeAttempts))
            }
        }
        candidates.preparation?.let { preparation ->
            table(listOf(if (candidates.preparationScope == CandidatePreparationScope.COMMON_POOL) "Общая подготовка пула" else "Подготовка кандидатов", "Факт", "Лимит")) {
                row("Рассмотрено кандидатов", number(preparation.examinedCandidates),
                    number(candidates.initialShortlistedCandidates + candidates.reserveCandidates))
                row("Попытки оценки размера, включая ошибки", number(preparation.sizeAttempts), number(candidates.maxSizeAttempts))
                row("Бюджетные отказы", number(preparation.budgetRejectedCandidates), "Не задан")
                row("Ошибки отдельных кандидатов", number(preparation.candidateErrors), "Не задан")
                row("Пополнено из резерва", number(preparation.refilledCandidates), "Не задан")
                row("Итоговый shortlist", number(preparation.finalShortlistedCandidates), number(candidates.globalCandidateLimit))
            }
            val reason = when (preparation.stopReason) {
                CandidatePreparationStopReason.SHORTLIST_FULL -> "Shortlist заполнен"
                CandidatePreparationStopReason.RESERVE_EXHAUSTED -> "Резерв исчерпан"
                CandidatePreparationStopReason.SIZE_LIMIT -> "Достигнут лимит оценок размера"
                CandidatePreparationStopReason.DEADLINE_EXCEEDED -> "Исчерпан бюджет времени"
                CandidatePreparationStopReason.CANCELLED -> "Анализ отменён"
                CandidatePreparationStopReason.CONNECTION_LOST -> "Потеряно соединение с БД"
                CandidatePreparationStopReason.FAILED -> "Ошибка подготовки"
            }
            append("<p>Остановка подготовки: ").append(h(reason)).append(" (")
                .append(h(preparation.stopReason.name)).append(").</p>")
        } ?: append("<p class=\"muted\">Диагностика подготовки кандидатов не сохранена.</p>")
        table(listOf("SQL / таблица", "Колонки: найдено → оставлено", "Перечислено", "После дедупликации", "Оставлено локально", "INCLUDE: перечислено → оставлено")) {
            candidates.localScopes.forEach { scope ->
                row("${scope.queryId} / ${tableName(scope.table)}", "${scope.discoveredColumns} → ${scope.retainedColumns}",
                    number(scope.enumeratedDefinitions), number(scope.deduplicatedDefinitions), number(scope.retainedDefinitions),
                    "${scope.enumeratedIncludeDefinitions} → ${scope.retainedIncludeDefinitions}")
            }
        }
        if (candidates.localScopes.isEmpty()) append("<p class=\"muted\">Подробные локальные счётчики не сохранены.</p>")
        candidates.localScopes.filter { it.excludedColumns.isNotEmpty() }.forEach { scope ->
            append("<details><summary>Исключённые колонки · ").append(h(scope.queryId)).append(" / ").append(h(tableName(scope.table))).append("</summary>")
            pre(scope.excludedColumns.joinToString("\n") { "${tableName(it.table)}.${it.name}" })
            append("</details>")
        }
        append("<dl class=\"values\">")
        entry("До общей дедупликации", number(candidates.candidatesBeforeGlobalDeduplication))
        entry("После общей дедупликации", number(candidates.candidatesAfterGlobalDeduplication))
        entry("Не перечислено вариантов", number(candidates.ungeneratedDefinitions))
        entry("Общий бюджет времени, мс", number(limits.commandTimeout.toMillis()))
        entry("Бюджет поиска с baseline, мс", number(limits.searchTimeout.toMillis()))
        entry("Один EXPLAIN, не более, мс", number(limits.explainTimeout.toMillis()))
        entry("Подключение, не более, мс", number(limits.connectTimeout.toMillis()))
        append("</dl><p class=\"muted\">Ограничения времени учитывают оставшийся бюджет; задержки драйвера, сети и экспорта могут увеличить время возврата. Отсечение кандидатов не означает незавершённый поиск. Непроверенные варианты не оценены по полезности.</p>")
        append("<details><summary>Причины решений по кандидатам</summary>")
        // Pipeline diagnostics already include generation decisions; standalone reports may not.
        val decisions = (candidates.decisions + search.candidateDecisions).distinct()
        if (decisions.isEmpty()) append("<p>Решения не сохранены.</p>") else {
            table(listOf("Причина", "Кандидат / SQL / таблица", "Этап", "Подробности")) {
                decisions.forEach { decision ->
                    row("${candidateReason(decision.reason)} (${decision.reason.name})",
                        listOfNotNull(decision.candidateId, decision.queryId, decision.table?.let(::tableName)).joinToString(" / ").ifEmpty { "Нет данных" },
                        decision.phase?.name?.lowercase(Locale.ROOT) ?: "Нет данных", detailsText(decision.details))
                }
            }
        }
        append("</details>")
        if (includeCandidateCatalog) {
            append("<details><summary>Все сохранённые кандидаты и оценки</summary>")
            val selectedIds = report.result.selected?.evaluation?.candidateIds.orEmpty().toSet()
            if (selectedIds.isNotEmpty()) append("<p>Выбранные индексы — в <a href=\"#indexes\">каталоге рекомендации</a>. Остальные кандидаты:</p>")
            table(listOf("ID / определение", "Оценка, байт и MiB", "С запасом, байт и MiB", "Write units/час", "Источники SQL", "Ветви условий")) {
                report.candidates.candidates.filterNot { it.id in selectedIds }.forEach { candidate ->
                    row("${candidate.id} / ${definition(candidate)}", storageSize(candidate.estimatedBytes), storageSize(candidate.budgetedBytes),
                        number(candidate.writeUnitsPerHour), candidate.sourceQueryIds.sorted().joinToString(),
                        candidate.sourceBranches.sortedWith(compareBy({ it.queryId }, { it.conditionPath }))
                            .joinToString { "${it.queryId}: ${it.conditionPath}" }.ifEmpty { "Нет OR-происхождения" })
                }
            }
            append("</details>")
        }
        val warnings = (candidates.warnings + search.warnings).distinct()
        val errors = (search.errors + listOfNotNull((report.result as? SearchResult.Failed)?.error)).distinct()
        if (warnings.isNotEmpty() || errors.isNotEmpty()) {
            append("<h3>Предупреждения и ошибки</h3>")
            warnings.forEach { diagnostic(it.code.name, it.message, it.queryId, it.profileId, it.details, "warning") }
            errors.forEach { diagnostic(it.code.name, it.message, it.queryId, it.profileId, it.details, "error") }
        }
        append("</section>")
    }

    private fun StringBuilder.method(report: AnalysisReport, includeComparison: Boolean = false) {
        section("method", "Как получена оценка и где её границы", "")
        append("<p>Write proxy — условная модель по числу колонок: одинаковое число колонок B-tree и BRIN не означает одинаковую реальную цену записи.</p>")
        append("<p><strong>Baseline</strong> — оценка с существующими индексами. Новые наборы оценены целиком через HypoPG, без физического построения. Поиск ограничен и не гарантирует лучший возможный набор.</p>")
        append("<details><summary>Ограничения методики</summary><p>Вывод относится только к переданным SQL и параметрам. Время запросов и записи, фактический размер индексов и конкуренция запросов не измерены. Planner cost не обещает SLA.</p></details>")
        append("<details><summary>Точные метрики выбранного набора</summary><p>Baseline — существующие реальные индексы. Selected — весь добавленный гипотетический набор. Planner cost не переводится во время выполнения.</p><dl class=\"values\">")
        val metrics = report.result.selected?.metrics
        if (singleProfile(report)) entry("Отношение стоимости к baseline", setRatio(metrics, metrics?.meanRatio)) else {
            entry("Среднее отношение M", setRatio(metrics, metrics?.meanRatio))
            entry("Худшее отношение R", setRatio(metrics, metrics?.worstRatio))
        }
        entry("Baseline B_mean", number(metrics?.baselineMeanCost))
        entry("Selected C_mean", number(metrics?.meanCost))
        entry("Снижение средней стоимости, %", percent(report.summary.meanEffect.gainPercent, report.summary.meanEffect.baselineCost))
        entry("Критерий поиска", report.result.selectedObjective.name.lowercase(Locale.ROOT))
        entry("Область сравнения", report.comparisonScope.name.lowercase(Locale.ROOT))
        append("</dl><p>M = C_mean / B_mean; среднее учитывает веса профилей. R = max(C_s / B_s) среди положительных baseline. При одном профиле критерии совпадают. Цена устойчивости = C_mean(best_robust) − C_mean(best_mean). Проценты неприменимы при нулевом baseline.</p></details>")
        if (singleProfile(report)) {
            append("<details><summary>Точные стоимости профиля и вес</summary>")
            profileMetrics(report)
            append("</details>")
        } else if (includeComparison) {
            append("<details><summary>").append(if (report.incumbentsCoincide == true) "Оба критерия выбрали один набор" else "Сравнение критериев robust и mean").append("</summary>")
            comparison(report)
            append("</details>")
        }
        resourceModel()
        append("</section>")
    }

    private fun StringBuilder.resourceModel() {
        append("<details id=\"resource-model\"><summary>Как оцениваются место и запись</summary><p>Место: для каждого индекса учитывается округлённая вверх оценка HypoPG с заданным запасом, затем размеры складываются. KiB = 1 024 байта; MiB = 1 048 576 байт.</p>")
        append("<p>Запись — условная модель, не число операций и не измеренное замедление. Модель одного индекса: <code>(INSERT + UPDATE + DELETE строк/ч) × (число ключей + число INCLUDE-колонок)</code>. Расходы индексов суммируются.</p>")
        append("<p>Модель не измеряет WAL, I/O, HOT, ширину ключей, разрастание индексов или отложенную очистку после DELETE.</p></details>")
    }

    private fun StringBuilder.nextSteps() {
        section("next", "Что делать дальше", "")
        append("<ol><li>Уточнить период, охват, частоты и параметры SQL.</li><li>Проверить допустимые расходы места и записи.</li><li>Проверить весь набор на тестовой БД: время запросов, нагрузку записи и фактический размер индексов.</li></ol></section>")
    }

    private fun StringBuilder.run(report: AnalysisReport) {
        section("run", "Данные запуска", "Идентичность отчёта и воспроизводимость")
        append("<dl class=\"values metadata\">")
        entry("Run ID", report.runId)
        entry("SHA-256 входа", report.inputHash ?: "Нет данных")
        entry("Schema / spec", "${report.schemaVersion} / ${report.specVersion}")
        entry("Статус", report.result.status.name.lowercase(Locale.ROOT))
        entry("Длительность анализа, мс", number(report.durationMillis))
        entry("Режим анализа", modeName(report.analysisMode))
        entry("Рейтинг shortlist", rankingName(report.candidateRanking))
        entry("Методы индексов", report.indexMethods.sortedBy { it.ordinal }.joinToString { it.wireName })
        entry("Причины остановки", report.result.diagnostics.stopReasons.joinToString { it.name }.ifEmpty { "Нет данных" })
        entry("Завершённые этапы", report.result.diagnostics.completedPhases.joinToString(" → ") { it.name.lowercase(Locale.ROOT) }.ifEmpty { "Нет завершённых этапов" })
        entry("Приложение", report.environment.applicationVersion)
        entry("Java / Kotlin", "${report.environment.javaVersion} / ${report.environment.kotlinVersion ?: "Нет данных"}")
        entry("PostgreSQL / HypoPG", "${report.environment.postgresVersion ?: "Нет данных"} / ${report.environment.hypopgVersion ?: "Нет данных"}")
        append("</dl></section>")
    }

    private fun StringBuilder.diagnostic(code: String, message: String, query: String?, profile: String?, details: Map<String, String>, kind: String) {
        append("<article class=\"notice ").append(kind).append("\"><strong>").append(h(code)).append("</strong><p>").append(h(message)).append("</p>")
        if (query != null) append("<p>SQL: ").append(h(query)).append("</p>")
        if (profile != null) append("<p>Профиль: ").append(h(profile)).append("</p>")
        if (details.isNotEmpty()) pre(detailsText(details))
        append("</article>")
    }

    private fun StringBuilder.section(id: String, title: String, subtitle: String) {
        append("<section id=\"").append(id).append("\"><div class=\"section-heading\"><h2>").append(title)
            .append("</h2>")
        if (subtitle.isNotEmpty()) append("<p>").append(subtitle).append("</p>")
        append("</div>")
    }

    private fun StringBuilder.cap(title: String, value: String) {
        append("<div class=\"cap\"><h3>").append(title).append("</h3><p>").append(h(value)).append("</p></div>")
    }

    private fun StringBuilder.badge(label: String, style: String) {
        append("<span class=\"badge ").append(style).append("\">").append(h(label)).append("</span>")
    }

    private fun StringBuilder.table(headers: List<String>, body: StringBuilder.() -> Unit) {
        append("<div class=\"table-scroll\" tabindex=\"0\" role=\"region\" aria-label=\"Таблица: ").append(h(headers.joinToString(", "))).append("\"><table><thead><tr>")
        headers.forEach { append("<th scope=\"col\">").append(h(it)).append("</th>") }
        append("</tr></thead><tbody>")
        body()
        append("</tbody></table></div>")
    }

    private fun StringBuilder.row(vararg values: String) {
        append("<tr>")
        values.forEachIndexed { index, value ->
            if (index == 0) append("<th scope=\"row\">").append(h(value)).append("</th>") else cell(value)
        }
        append("</tr>")
    }

    private fun StringBuilder.cell(value: String) {
        append("<td>")
        text(value)
        append("</td>")
    }
    private fun StringBuilder.entry(label: String, value: String) { append("<div><dt>").append(h(label)).append("</dt><dd>").append(h(value)).append("</dd></div>") }
    private fun StringBuilder.copyable(value: String, id: String, label: String) {
        append("<pre id=\"").append(id).append("\"><code>").append(h(value)).append("</code></pre>")
        append("<button type=\"button\" class=\"copy-button\" data-copy=\"").append(id).append("\" hidden>")
            .append(h(label)).append("</button> <span class=\"copy-status\" aria-live=\"polite\"></span>")
    }

    private fun queryCount(report: AnalysisReport): Int? = report.input?.queries?.size
        ?: report.result.baseline?.costByQueryId?.size
        ?: report.result.selected?.evaluation?.costByQueryId?.size

    private fun singleProfile(report: AnalysisReport): Boolean =
        (report.input?.profiles?.size ?: report.result.selected?.metrics?.profiles?.size) == 1

    private fun queryLabel(id: String, report: AnalysisReport): String {
        val index = report.input?.queries?.indexOfFirst { it.id == id } ?: -1
        return if (index >= 0) "SQL ${index + 1}" else id
    }

    private fun StringBuilder.pre(value: String) { append("<pre><code>").append(h(value)).append("</code></pre>") }
    private fun StringBuilder.text(value: String) { append(h(value)) }

    private fun rankingName(ranking: CandidateRanking): String = ranking.wireName + " · " + when (ranking) {
        CandidateRanking.FREQUENCY -> "Нормированные частоты"
        CandidateRanking.BASELINE_COST -> "Частоты × baseline cost · экспериментальный"
    }

    private fun modeName(mode: AnalysisMode): String = mode.name.lowercase(Locale.ROOT) + " · " + when (mode) {
        AnalysisMode.FAST -> "Быстрый"
        AnalysisMode.NORMAL -> "Обычный"
        AnalysisMode.DEEP -> "Глубокий"
    }

    private fun number(value: Number?): String {
        if (value == null) return "Нет данных"
        val symbols = DecimalFormatSymbols(Locale.ROOT).apply { groupingSeparator = '\u202f'; decimalSeparator = ',' }
        val formatter = DecimalFormat("#,##0." + "#".repeat(340), symbols)
        val formatted = formatter.format(value)
        // Do not display a tiny positive value as zero; no scientific/domain rounding is performed here.
        return if (value.toDouble() != 0.0 && (formatted == "0" || formatted == "-0")) {
            BigDecimal(value.toString()).stripTrailingZeros().toString().replace('.', ',')
        } else formatted
    }

    private fun storageSize(bytes: Long?): String =
        if (bytes == null) "Нет данных" else "${number(bytes)}\u00a0байт (${mib(bytes)})"

    /** Display conversion only; exact bytes remain visible and budget decisions stay in domain. */
    private fun mib(bytes: Long?): String {
        if (bytes == null) return "Нет данных"
        val exact = BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(1_048_576L))
        val rounded = exact.setScale(3, RoundingMode.HALF_EVEN)
        if (bytes > 0 && rounded.signum() == 0) return "< 0,001 MiB"
        val prefix = if (exact.compareTo(rounded) == 0) "" else "≈"
        return "$prefix${number(rounded)}\u00a0MiB"
    }

    private fun ratio(value: Double?, baseline: Double?) = if (value == null && baseline == 0.0) "Неприменимо" else number(value)
    private fun setRatio(metrics: SetMetrics?, value: Double?) = if (metrics != null && value == null) "Неприменимо" else number(value)
    private fun percent(value: Double?, baseline: Double?) = ratio(value, baseline)
    private fun tableName(table: TableRef) = "${table.schema}.${table.name}"
    private fun definition(candidate: IndexCandidate): String =
        "${tableName(candidate.definition.table)} · ${candidate.definition.method} (" +
            candidate.definition.keys.joinToString { key ->
                key.column.name + (if (candidate.definition.method == "btree") " ${key.direction.name} NULLS ${key.nulls.name}" else "") +
                    (key.collation?.let { " COLLATE $it" } ?: "") + (key.operatorClass?.let { " $it" } ?: "")
            } + ")" + candidate.definition.includedColumns.takeIf { it.isNotEmpty() }
                ?.joinToString(", ", " INCLUDE (", ")") { it.name }.orEmpty()
    private fun detailsText(details: Map<String, String>) = details.toSortedMap().entries.joinToString("\n") { "${it.key}: ${it.value}" }.ifEmpty { "Нет данных" }

    private fun h(value: Any): String = buildString {
        value.toString().forEach { ch ->
            append(when (ch) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"; else -> ch.toString() })
        }
    }

    private fun statusLabel(status: RunStatus) = when (status) {
        RunStatus.COMPLETED -> "Анализ завершён"
        RunStatus.PARTIAL -> "Частичный результат"
        RunStatus.FAILED -> "Ошибка анализа"
    }

    private fun stopLabel(reason: StopReason): String = when (reason) {
        StopReason.SEARCH_COMPLETE -> "Поиск завершён"
        StopReason.NO_CANDIDATES -> "Нет кандидатов"
        StopReason.NO_IMPROVEMENT -> "Нет улучшения"
        StopReason.MAX_INDEXES -> "Достигнут лимит индексов"
        StopReason.ZERO_STORAGE_BUDGET -> "Нулевой бюджет места"
        StopReason.ZERO_MAX_INDEXES -> "Индексы запрещены лимитом"
        StopReason.ZERO_BASELINE -> "Нулевой baseline"
        StopReason.DEADLINE_EXCEEDED -> "Исчерпан бюджет времени"
        StopReason.CANCELLED -> "Отмена"
        StopReason.EXPLAIN_LIMIT -> "Лимит EXPLAIN"
        StopReason.REPAIR_LIMIT -> "Лимит repair"
        StopReason.EVALUATION_FAILED -> "Ошибка оценки"
        StopReason.CONNECTION_LOST -> "Потеря соединения"
    }

    private fun candidateReason(reason: CandidateReason): String = when (reason) {
        CandidateReason.EXACT_EXISTING_DUPLICATE -> "Точный реальный дубль"
        CandidateReason.NO_BRIN_OPERATOR_CLASS -> "Нет подходящего BRIN opclass"
        CandidateReason.METHOD_VARIANT_LIMIT -> "Общий предел вариантов методов"
        CandidateReason.NO_BTREE_OPERATOR_CLASS -> "Нет подходящего B-tree opclass"
        CandidateReason.COLUMN_SHORTLIST_TRUNCATED -> "Отсечение колонок"
        CandidateReason.INCLUDE_PAYLOAD_LIMIT -> "Лимит INCLUDE-колонок"
        CandidateReason.INCLUDE_VARIANT_LIMIT -> "Лимит INCLUDE-вариантов"
        CandidateReason.LOCAL_CANDIDATE_LIMIT -> "Локальный лимит"
        CandidateReason.GLOBAL_CANDIDATE_LIMIT -> "Общий лимит"
        CandidateReason.ZERO_BASELINE_COST_SUPPORT -> "Нулевой вклад baseline cost"
        CandidateReason.STORAGE_BUDGET -> "Превышение бюджета места"
        CandidateReason.WRITE_BUDGET -> "Превышение бюджета записи"
        CandidateReason.MAX_INDEXES -> "Достигнут лимит индексов"
        CandidateReason.EVALUATION_FAILED -> "Ошибка оценки набора"
        CandidateReason.HYPOTHETICAL_INDEX_ERROR -> "Ошибка гипотезы"
        CandidateReason.NO_MARGINAL_IMPROVEMENT -> "Нет предельной пользы на шаге"
        CandidateReason.NOT_SELECTED -> "Не выбран поиском"
        CandidateReason.NOT_EVALUATED_DUE_TO_LIMIT -> "Не проверен из-за лимита"
    }
}
