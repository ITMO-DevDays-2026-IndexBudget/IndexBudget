package ru.itmo.indexbudget.report

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** A read-only view of the shared report. No metric calculation, database calls or JavaScript. */
@Component
class HtmlReportRenderer : ReportRenderer {
    private val stylesheet = checkNotNull(javaClass.getResource("/report/report.css")).readText()

    override fun render(report: AnalysisReport): String = buildString {
        append("""<!doctype html><html lang="ru"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
            <title>IndexBudget · Отчёт анализа</title><style>""")
        append(stylesheet)
        append("""</style></head><body><a class="skip" href="#main">К содержанию</a>
            <header class="topbar"><a class="brand" href="#overview"><span class="brand-icon" aria-hidden="true">▥</span>IndexBudget</a>
            <span class="top-caption">POSTGRESQL / АНАЛИЗ ИНДЕКСОВ</span><span class="offline">Автономный отчёт</span></header>
            <div class="layout"><aside><div class="nav-label">В ЭТОМ ОТЧЁТЕ</div><nav aria-label="Разделы отчёта">
            <a href="#overview"><span>01</span>Обзор</a><a href="#comparison"><span>02</span>Robust и mean</a>
            <a href="#profiles"><span>03</span>Профили нагрузки</a><a href="#indexes"><span>04</span>Индексы и бюджеты</a>
            <a href="#queries"><span>05</span>Запросы и планы</a><a href="#search"><span>06</span>Границы поиска</a>
            <a href="#run"><span>07</span>Данные запуска</a></nav>
            <div class="nav-note">Planner cost — оценка планировщика. Фактическое время запросов здесь не измеряется.</div></aside>
            <main id="main">""")
        overview(report)
        comparison(report)
        manualComparison(report)
        profiles(report)
        indexes(report)
        queries(report)
        search(report)
        run(report)
        append("""<footer>IndexBudget · Спецификация """)
        append(h(report.specVersion))
        append(" · Оценки помогают выбрать набор для отдельной физической проверки.</footer></main></div></body></html>")
    }

    private fun StringBuilder.manualComparison(report: AnalysisReport) {
        val manual = report.manualComparison ?: return
        append("<section id=\"manual-comparison\"><h2>Ручной набор индексов</h2>")
        append("<p>Отдельная оценка вне автоматического shortlist. Best robust и best mean относятся к обычному поиску.</p>")
        append("<p>Статус: ").append(h(manual.status.wireValue)).append(". Допустимость: ")
        text(if (manual.status != ManualComparisonStatus.COMPLETED) "Не установлена" else if (manual.admissible) "Допустим" else "Нарушает ограничения")
        append(".</p>")
        for (candidate in manual.candidates) {
            append("<pre>").append(h(candidate.ddl ?: "Нет данных")).append("</pre><p>Оценка размера: ")
            text(candidate.estimatedBytes?.toString() ?: "Нет данных")
            append(" байт; с запасом: ").append(h(candidate.budgetedBytes ?: "Нет данных")).append("; write proxy: ")
            text(candidate.writeUnitsPerHour?.toString() ?: "Нет данных")
            append("</p>")
        }
        manual.metrics?.let { value ->
            append("<p>M: ").append(h(value.meanRatio ?: "Нет данных")).append("; R: ").append(h(value.worstRatio ?: "Нет данных")).append("</p>")
            append("<table><thead><tr><th>Профиль</th><th>Baseline cost</th><th>Ручной cost</th><th>Отношение</th></tr></thead><tbody>")
            for (profile in value.profiles) {
                append("<tr><td>").append(h(profile.profileId)).append("</td><td>").append(h(profile.baselineCost))
                append("</td><td>").append(h(profile.cost)).append("</td><td>").append(h(profile.ratio ?: "Нет данных")).append("</td></tr>")
            }
            append("</tbody></table>")
        }
        for (violation in manual.budgetViolations) append("<p>Нарушение бюджета: ").append(h(violation.name)).append("</p>")
        for (violation in manual.evaluationViolations) append("<p>").append(h(violation.code.name)).append("</p>")
        manual.error?.let { append("<p>").append(h(it.code.name)).append(": ").append(h(it.message)).append("</p>") }
        for (decision in manual.decisions) append("<p>").append(h(decision.reason.name)).append(": ").append(h(decision.details)).append("</p>")
        append("<table><thead><tr><th>SQL ID</th><th>Baseline</th><th>Ручной cost</th><th>Регрессия</th></tr></thead><tbody>")
        for (query in manual.queryComparisons) {
            append("<tr><td>").append(h(query.queryId)).append("</td><td>").append(h(query.baselineCost ?: "Нет данных"))
            append("</td><td>").append(h(query.selectedCost ?: "Нет данных")).append("</td><td>")
            text(if (query.regression == true) "Да" else "Нет")
            append("</td></tr>")
        }
        append("</tbody></table><p>Planner cost не является временем; write proxy — модель, не измерение записи.</p></section>")
    }

    private fun StringBuilder.overview(report: AnalysisReport) {
        val result = report.result
        val selected = result.selected
        val metrics = selected?.metrics
        append("<section id=\"overview\" class=\"overview\"><div class=\"eyebrow\">ОТЧЁТ АНАЛИЗА / V")
        append(h(report.schemaVersion)).append("</div><div class=\"title-row\"><h1>Индексы под вашу нагрузку</h1>")
        badge(statusLabel(result.status), result.status.name.lowercase(Locale.ROOT))
        append("</div><p class=\"lead\">")
        text(when (result.status) {
            RunStatus.COMPLETED -> "Ограниченный поиск завершён. Сохранён лучший найденный набор для выбранного критерия."
            RunStatus.PARTIAL -> "Поиск остановлен досрочно. Сохранён лучший полный допустимый результат из исследованного пула."
            RunStatus.FAILED -> "Анализ завершился ошибкой. Рекомендованный набор отсутствует."
        })
        append("</p>")
        if (report.environment.applicationVersion == "fixture") {
            append("<p class=\"notice demo\"><strong>Демонстрационные данные.</strong> Синтетическая фикстура для проверки отчёта; это не результат анализа PostgreSQL.</p>")
        }
        append("<div class=\"run-strip\"><span>Режим анализа <strong>")
        text(modeName(report.analysisMode))
        append("</strong></span><span>Критерий поиска <strong>")
        text(result.selectedObjective.name.lowercase(Locale.ROOT))
        append("</strong></span><span>Длительность <strong>").append(h(number(report.durationMillis))).append(" мс</strong></span>")
        append("<span>Причина остановки <strong>")
        text(result.diagnostics.stopReasons.takeIf { it.isNotEmpty() }?.joinToString { stopLabel(it) } ?: "Нет данных")
        append("</strong></span></div><div class=\"metric-grid\">")
        metric("Худшая нормированная стоимость", setRatio(metrics, metrics?.worstRatio), "R · меньше — лучше", "accent")
        metric("Средняя нормированная стоимость", setRatio(metrics, metrics?.meanRatio), "M · меньше — лучше")
        metric("Выбранные индексы", number(metrics?.budgetUsage?.indexCount), "Лимит: ${number(report.input?.constraints?.maxIndexes)}")
        metric("Полностью оценённые наборы", number(result.diagnostics.statistics.completedEvaluations), if (report.manualComparison == null) "Один общий пул для robust и mean" else "Включая отдельную ручную оценку")
        append("</div>")
        if (selected != null && selected.evaluation.candidateIds.isEmpty()) {
            append("<p class=\"notice\">Выбран пустой набор: новые индексы не предлагаются. Baseline включает уже существующие реальные индексы.</p>")
        }
        append("<p class=\"muted\">Нормированная стоимость: 1 — уровень baseline, ниже 1 — уменьшение planner cost, выше 1 — регрессия. «Нет данных» и «Неприменимо» не означают ноль.</p></section>")
    }

    private fun StringBuilder.comparison(report: AnalysisReport) {
        section("comparison", "02", "Устойчивость или средний эффект", "Два критерия, один исследованный пул")
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
        configuration("best_robust", "Robust", "Сдерживает худшее отношение к baseline", robust, report.result.selectedObjective == Objective.ROBUST)
        configuration("best_mean", "Mean", "Минимизирует ожидаемую стоимость", mean, report.result.selectedObjective == Objective.MEAN)
        append("</div><div class=\"price\"><div><strong>Цена устойчивости</strong><p>C_mean(best_robust) − C_mean(best_mean), в единицах взвешенного planner cost</p></div><strong class=\"price-value\">")
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
        append("<dl class=\"values\">")
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
        append("</article>")
    }

    private fun StringBuilder.profiles(report: AnalysisReport) {
        section("profiles", "03", "Эффект по профилям", "Метрики выбранного набора")
        val metrics = report.result.selected?.metrics
        val profileIds = (report.input?.profiles.orEmpty().map { it.id } + metrics?.profiles.orEmpty().map { it.profileId }).distinct()
        append("<p>Нормированный вес задаёт вклад в среднюю стоимость. Каждый профиль участвует в худшем критерии независимо от веса.</p>")
        table(listOf("Профиль", "Вес", "Baseline cost", "Final cost", "Отношение", "Выигрыш, cost", "Выигрыш, %")) {
            profileIds.forEach { id ->
                val profile = metrics?.profiles?.firstOrNull { it.profileId == id }
                row(id, number(profile?.normalizedWeight), number(profile?.baselineCost), number(profile?.cost),
                    ratio(profile?.ratio, profile?.baselineCost), number(profile?.gain), percent(profile?.gainPercent, profile?.baselineCost))
            }
        }
        if (profileIds.isEmpty()) append("<p class=\"empty\">Профили отсутствуют в сохранённой модели.</p>")
        append("<p class=\"muted\">Cost — единицы планировщика с учётом частот. Это не миллисекунды, не измеренное ускорение и не SLA. При нулевом baseline отношение и процент неприменимы.</p></section>")
    }

    private fun StringBuilder.indexes(report: AnalysisReport) {
        section("indexes", "04", "Индексы и бюджеты", "Расходы выбранного набора")
        val usage = report.result.selected?.metrics?.budgetUsage
        val constraints = report.input?.constraints
        append("<div class=\"budget-grid\"><div class=\"budget-card\"><h3>Место</h3><p class=\"budget-number\">")
        text(number(usage?.budgetedBytes))
        append(" <small>/ ").append(h(number(constraints?.storageBudgetBytes))).append(" байт</small></p><p class=\"budget-mib\">")
        text("${mib(usage?.budgetedBytes)} / ${mib(constraints?.storageBudgetBytes)}")
        append("</p><p>Оценка HypoPG: ")
        text(storageSize(usage?.estimatedBytes))
        append(". Коэффициент запаса: ").append(h(number(usage?.sizeSafetyFactor))).append(".</p></div>")
        append("<div class=\"budget-card\"><h3>Запись</h3><p class=\"budget-number\">").append(h(number(usage?.writeUnitsPerHour)))
        append(" <small>write units/час</small></p><p>Бюджет: ")
        text(if (constraints == null) "Нет данных" else constraints.writeBudgetUnitsPerHour?.let { "${number(it)} units/час" } ?: "Отключён")
        append(".</p></div></div><p class=\"muted\">Место учитывается с запасом для каждого индекса. Write units — вспомогательная модель изменений строк × (число ключей + число INCLUDE-колонок), а не измеренное замедление INSERT/UPDATE/DELETE.</p>")
        val selected = report.result.selected
        if (selected == null) {
            append("<p class=\"empty\">Рекомендованные индексы отсутствуют: пригодный результат не сохранён.</p>")
        } else if (selected.evaluation.candidateIds.isEmpty()) {
            append("<p class=\"empty\">Пустой набор. Новые индексы не нужны для сохранённого решения.</p>")
        } else {
            selected.evaluation.candidateIds.forEach { id ->
                append("<article class=\"index-card\" data-selected-index=\"").append(h(id)).append("\"><h3><code>").append(h(id)).append("</code></h3>")
                val candidate = report.candidates.candidates.firstOrNull { it.id == id }
                if (candidate == null) {
                    append("<p>Описание индекса отсутствует в модели.</p>")
                } else {
                    append("<p class=\"muted\">").append(h(definition(candidate))).append("</p><dl class=\"values\">")
                    entry("Оценка", storageSize(candidate.estimatedBytes))
                    entry("С запасом", storageSize(candidate.budgetedBytes))
                    entry("INCLUDE", candidate.definition.includedColumns.takeIf { it.isNotEmpty() }
                        ?.joinToString { it.name } ?: "Нет")
                    entry("Write units/час", number(candidate.writeUnitsPerHour))
                    entry("Источники SQL", candidate.sourceQueryIds.sorted().joinToString())
                    if (candidate.sourceBranches.isNotEmpty()) entry("Ветви условий", candidate.sourceBranches
                        .sortedWith(compareBy({ it.queryId }, { it.conditionPath }))
                        .joinToString { "${it.queryId}: ${it.conditionPath}" })
                    entry("Происхождение", candidate.generationReasons.sortedBy { it.name }.joinToString { it.name })
                    append("</dl>")
                    if (candidate.definition.includedColumns.isNotEmpty()) {
                        append("<p>INCLUDE позволяет рассмотреть index-only scan; обращения к таблице зависят от visibility map и не исключены.</p>")
                    }
                    append("<details><summary>DDL из модели</summary>")
                    pre(candidate.ddl ?: "DDL отсутствует в модели.")
                    append("</details>")
                }
                append("</article>")
            }
        }
        append("<details><summary>Исходный профиль записи</summary>")
        val rates = report.input?.tableWriteRates
        if (rates == null) append("<p>Профиль записи не задан. Неизвестные write units не заменяются нулём.</p>") else {
            table(listOf("Таблица", "INSERT/час", "UPDATE/час", "DELETE/час", "Источник")) {
                rates.toSortedMap().forEach { (table, rate) -> row(table, number(rate.insertsPerHour), number(rate.updatesPerHour), number(rate.deletesPerHour), rate.source.name.lowercase(Locale.ROOT)) }
            }
        }
        append("</details></section>")
    }

    private fun StringBuilder.queries(report: AnalysisReport) {
        section("queries", "05", "Запросы и планы", "Один общий вектор costs на каждый набор")
        append("<p>Baseline сохраняет существующие реальные индексы. Final cost относится ко всему выбранному набору. Отдельные SQL могут регрессировать, даже если критерий набора улучшился.</p>")
        val baseline = report.result.baseline
        val selected = report.result.selected?.evaluation
        val profiles = report.input?.profiles.orEmpty()
        val queryIds = (report.input?.queries.orEmpty().map { it.id } + baseline?.costByQueryId.orEmpty().keys + selected?.costByQueryId.orEmpty().keys).distinct()
        table(listOf("SQL") + profiles.map { "${it.id}, вызовов/час" } + listOf("Baseline cost", "Final cost", "Изменение")) {
            queryIds.forEachIndexed { index, id ->
                val before = baseline?.costByQueryId?.get(id)
                val after = selected?.costByQueryId?.get(id)
                val regression = before != null && after != null && after > before
                append(if (regression) "<tr class=\"regression\">" else "<tr>")
                append("<th scope=\"row\"><a href=\"#query-").append(index).append("\">").append(h(id)).append("</a></th>")
                profiles.forEach { cell(number(it.callsPerHour[id])) }
                cell(number(before))
                cell(number(after))
                cell(when {
                    before == null || after == null -> "Нет данных"
                    regression -> "Регрессия"
                    after < before -> "Снижение cost"
                    else -> "Без изменений"
                })
                append("</tr>")
            }
        }
        costGuards(report)
        queryIds.forEachIndexed { index, id ->
            append("<details id=\"query-").append(index).append("\"><summary>SQL и сохранённые планы · ").append(h(id)).append("</summary><h4>Исходный SQL</h4>")
            pre(report.input?.queries?.firstOrNull { it.id == id }?.sql ?: "Исходный SQL отсутствует в модели.")
            append("<h4>Baseline · EXPLAIN JSON</h4>")
            pre(baseline?.plansByQueryId?.get(id)?.json ?: "План отсутствует.")
            append("<h4>Выбранный набор · EXPLAIN JSON</h4>")
            pre(selected?.plansByQueryId?.get(id)?.json ?: "План отсутствует.")
            append("<details><summary>Планы обоих лучших наборов</summary><h4>best_robust</h4>")
            pre(report.result.bestRobust?.evaluation?.plansByQueryId?.get(id)?.json ?: "План отсутствует.")
            append("<h4>best_mean</h4>")
            pre(report.result.bestMean?.evaluation?.plansByQueryId?.get(id)?.json ?: "План отсутствует.")
            append("</details></details>")
        }
        append("</section>")
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

    private fun StringBuilder.search(report: AnalysisReport) {
        section("search", "06", "Границы поиска", "Что было исследовано и где сработали ограничения")
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
        cap("01", "Колонки до генерации пар", "${candidates.columnLimitPerQueryTable} на (SQL, таблица)")
        cap("02", "Локальные кандидаты", "${candidates.localCandidateLimit} на (SQL, таблица)")
        cap("03", "Общий shortlist", "${candidates.fixedPoolSelection?.admissibleCandidates ?: candidates.preparation?.finalShortlistedCandidates ?: candidates.shortlistedCandidates} / ${candidates.globalCandidateLimit}")
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
        append("</details><details><summary>Все сохранённые кандидаты и оценки</summary>")
        table(listOf("ID / определение", "Оценка, байт и MiB", "С запасом, байт и MiB", "Write units/час", "Источники SQL", "Ветви условий")) {
            report.candidates.candidates.forEach { candidate ->
                row("${candidate.id} / ${definition(candidate)}", storageSize(candidate.estimatedBytes), storageSize(candidate.budgetedBytes),
                    number(candidate.writeUnitsPerHour), candidate.sourceQueryIds.sorted().joinToString(),
                    candidate.sourceBranches.sortedWith(compareBy({ it.queryId }, { it.conditionPath }))
                        .joinToString { "${it.queryId}: ${it.conditionPath}" }.ifEmpty { "Нет OR-происхождения" })
            }
        }
        append("</details>")
        val warnings = (candidates.warnings + search.warnings).distinct()
        val errors = (search.errors + listOfNotNull((report.result as? SearchResult.Failed)?.error)).distinct()
        if (warnings.isNotEmpty() || errors.isNotEmpty()) {
            append("<h3>Предупреждения и ошибки</h3>")
            warnings.forEach { diagnostic(it.code.name, it.message, it.queryId, it.profileId, it.details, "warning") }
            errors.forEach { diagnostic(it.code.name, it.message, it.queryId, it.profileId, it.details, "error") }
        }
        append("</section>")
    }

    private fun StringBuilder.run(report: AnalysisReport) {
        section("run", "07", "Данные запуска", "Идентичность отчёта и воспроизводимость")
        append("<dl class=\"values metadata\">")
        entry("Run ID", report.runId)
        entry("SHA-256 входа", report.inputHash ?: "Нет данных")
        entry("Schema / spec", "${report.schemaVersion} / ${report.specVersion}")
        entry("Статус", report.result.status.name.lowercase(Locale.ROOT))
        entry("Режим анализа", modeName(report.analysisMode))
        entry("Рейтинг shortlist", rankingName(report.candidateRanking))
        entry("Причины остановки", report.result.diagnostics.stopReasons.joinToString { it.name }.ifEmpty { "Нет данных" })
        entry("Завершённые этапы", report.result.diagnostics.completedPhases.joinToString(" → ") { it.name.lowercase(Locale.ROOT) }.ifEmpty { "Нет завершённых этапов" })
        entry("Приложение", report.environment.applicationVersion)
        entry("Java / Kotlin", "${report.environment.javaVersion} / ${report.environment.kotlinVersion ?: "Нет данных"}")
        entry("PostgreSQL / HypoPG", "${report.environment.postgresVersion ?: "Нет данных"} / ${report.environment.hypopgVersion ?: "Нет данных"}")
        append("</dl><div class=\"notice\"><strong>Границы результата</strong><p>Поиск ограничен кандидатами, бюджетами и числом оценок. Глобальный оптимум не гарантирован. Выигрыш planner cost не обещает ускорение или SLA; регрессии отдельных SQL возможны. Для проверки нужны отдельные измерения времени и размера на тестовой БД.</p></div></section>")
    }

    private fun StringBuilder.diagnostic(code: String, message: String, query: String?, profile: String?, details: Map<String, String>, kind: String) {
        append("<article class=\"notice ").append(kind).append("\"><strong>").append(h(code)).append("</strong><p>").append(h(message)).append("</p>")
        if (query != null) append("<p>SQL: ").append(h(query)).append("</p>")
        if (profile != null) append("<p>Профиль: ").append(h(profile)).append("</p>")
        if (details.isNotEmpty()) pre(detailsText(details))
        append("</article>")
    }

    private fun StringBuilder.section(id: String, index: String, title: String, subtitle: String) {
        append("<section id=\"").append(id).append("\"><div class=\"section-heading\"><span class=\"section-number\">").append(index)
            .append("</span><div><h2>").append(title).append("</h2><p>").append(subtitle).append("</p></div></div>")
    }

    private fun StringBuilder.metric(label: String, value: String, caption: String, style: String = "") {
        append("<div class=\"metric ").append(style).append("\"><div class=\"metric-label\">").append(h(label))
            .append("</div><strong>").append(h(value)).append("</strong><small>").append(h(caption)).append("</small></div>")
    }

    private fun StringBuilder.cap(index: String, title: String, value: String) {
        append("<div class=\"cap\"><span>").append(index).append("</span><h3>").append(title).append("</h3><p>").append(h(value)).append("</p></div>")
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

    private fun StringBuilder.cell(value: String) { append("<td>").append(h(value)).append("</td>") }
    private fun StringBuilder.entry(label: String, value: String) { append("<div><dt>").append(h(label)).append("</dt><dd>").append(h(value)).append("</dd></div>") }
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
        val formatter = DecimalFormat("#,##0.#########", symbols)
        val formatted = formatter.format(value)
        // Do not display a tiny positive value as zero; no scientific/domain rounding is performed here.
        return if (value.toDouble() != 0.0 && (formatted == "0" || formatted == "-0")) {
            BigDecimal(value.toString()).stripTrailingZeros().toString().replace('.', ',')
        } else formatted
    }

    private fun storageSize(bytes: Long?): String =
        if (bytes == null) "Нет данных" else "${number(bytes)} байт (${mib(bytes)})"

    /** Display conversion only; exact bytes remain visible and budget decisions stay in domain. */
    private fun mib(bytes: Long?): String {
        if (bytes == null) return "Нет данных"
        val exact = BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(1_048_576L))
        val rounded = exact.setScale(3, RoundingMode.HALF_EVEN)
        if (bytes > 0 && rounded.signum() == 0) return "< 0,001 MiB"
        val prefix = if (exact.compareTo(rounded) == 0) "" else "≈"
        return "$prefix${number(rounded)} MiB"
    }

    private fun ratio(value: Double?, baseline: Double?) = if (value == null && baseline == 0.0) "Неприменимо" else number(value)
    private fun setRatio(metrics: SetMetrics?, value: Double?) = if (metrics != null && value == null) "Неприменимо" else number(value)
    private fun percent(value: Double?, baseline: Double?) = ratio(value, baseline)
    private fun tableName(table: TableRef) = "${table.schema}.${table.name}"
    private fun definition(candidate: IndexCandidate): String =
        "${tableName(candidate.definition.table)} · ${candidate.definition.method} (" +
            candidate.definition.keys.joinToString { key ->
                "${key.column.name} ${key.direction.name} NULLS ${key.nulls.name}" +
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
        RunStatus.COMPLETED -> "completed · Завершён"
        RunStatus.PARTIAL -> "partial · Частичный результат"
        RunStatus.FAILED -> "failed · Ошибка"
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
