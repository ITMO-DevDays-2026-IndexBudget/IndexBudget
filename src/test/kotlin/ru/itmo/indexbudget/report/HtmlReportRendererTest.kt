package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.fixtures.HtmlReportFixtures
import java.util.Locale

class HtmlReportRendererTest {
    private val renderer = HtmlReportRenderer()

    @Test
    fun `critical query guards render as planner cost limits with observed ratios`() {
        val source = HtmlReportFixtures.completed()
        val report = source.copy(
            input = source.input?.let {
                it.copy(constraints = it.constraints.copy(
                    queryCostGuards = mapOf("records_by_group" to 1.5, "records_by_value" to 1.0)))
            },
            costGuards = listOf(
                CostGuardOutcome("records_by_group", 1.5, 100.0, 120.0, 1.2, zeroBaseline = false, violated = false),
                CostGuardOutcome("records_by_value", 1.0, 0.0, 5.0, null, zeroBaseline = true, violated = true),
            ),
        )

        val queries = section(renderer.render(report), "queries")
        val guardsTable = capture(queries, "(Ограничения planner cost для критических SQL.*?</table>)", stripTags = false)

        assertTrue(queries.contains("Ограничения planner cost для критических SQL"))
        assertTrue(queries.contains("не latency и не SLA"))
        assertEquals(listOf("records_by_group", "1,5", "100", "120", "1,2", "Соблюдено"),
            rowByKey(guardsTable, "records_by_group"))
        assertEquals(listOf("records_by_value", "1", "0", "5", "Неприменимо (нулевой baseline)",
            "Нарушено: положительный cost при нулевом baseline"), rowByKey(guardsTable, "records_by_value"))
    }

    @Test
    fun `reports without guards have no guard section`() {
        val queries = section(renderer.render(HtmlReportFixtures.completed()), "queries")

        assertFalse(queries.contains("Ограничения planner cost"))
    }

    @Test
    fun `legacy diagnostics show the same original and global shortlist`() {
        val source = HtmlReportFixtures.completed()
        val report = source.copy(candidates = source.candidates.copy(
            diagnostics = CandidateDiagnostics(shortlistedCandidates = 16)))
        val search = section(renderer.render(report), "search")
        assertEquals("16", entryValue(search, "Исходный shortlist"))
        assertEquals("16 / 16", capture(search, "<h3>Общий shortlist</h3><p>(.*?)</p>"))
    }

    @Test
    fun `refill diagnostics distinguish original and final shortlist and display every stop reason`() {
        val source = HtmlReportFixtures.completed()
        for (reason in CandidatePreparationStopReason.entries) {
            val report = source.copy(candidates = source.candidates.copy(
                diagnostics = source.candidates.diagnostics.copy(
                    shortlistedCandidates = 16, initialShortlistedCandidates = 16,
                    reserveCandidates = 2, reserveCandidateLimit = 48, maxSizeAttempts = 32,
                    preparation = CandidatePreparationStatistics(
                        examinedCandidates = 18, sizeAttempts = 18,
                        budgetRejectedCandidates = 14, candidateErrors = 0,
                        refilledCandidates = 2, finalShortlistedCandidates = 4,
                        stopReason = reason,
                    ),
                ),
            ))
            val search = section(renderer.render(report), "search")
            assertEquals("16", entryValue(search, "Исходный shortlist"))
            assertEquals("2 / 48", entryValue(search, "Кандидаты в резерве"))
            assertEquals("4 / 16", capture(search, "<h3>Общий shortlist</h3><p>(.*?)</p>"))
            assertEquals(listOf("Рассмотрено кандидатов", "18", "18"), rowByKey(search, "Рассмотрено кандидатов"))
            assertEquals(listOf("Попытки оценки размера, включая ошибки", "18", "32"),
                rowByKey(search, "Попытки оценки размера, включая ошибки"))
            assertEquals(listOf("Бюджетные отказы", "14", "Не задан"), rowByKey(search, "Бюджетные отказы"))
            assertEquals(listOf("Ошибки отдельных кандидатов", "0", "Не задан"), rowByKey(search, "Ошибки отдельных кандидатов"))
            assertEquals(listOf("Пополнено из резерва", "2", "Не задан"), rowByKey(search, "Пополнено из резерва"))
            assertEquals(listOf("Итоговый shortlist", "4", "16"), rowByKey(search, "Итоговый shortlist"))
            assertTrue(search.contains("Остановка подготовки:"))
            assertTrue(search.contains(reason.name))
            assertFalse(search.contains("Диагностика подготовки кандидатов не сохранена."))
        }
        val legacy = source.copy(candidates = source.candidates.copy(
            diagnostics = source.candidates.diagnostics.copy(shortlistedCandidates = 7)))
        val search = section(renderer.render(legacy), "search")
        assertEquals("7 / 16", capture(search, "<h3>Общий shortlist</h3><p>(.*?)</p>"))
        assertTrue(search.contains("Диагностика подготовки кандидатов не сохранена."))
    }

    @Test
    fun `objective selects the same indices and costs as the shared report`() {
        for (objective in Objective.entries) {
            val report = HtmlReportFixtures.completed(objective = objective)
            val html = renderer.render(report)
            val selected = requireNotNull(report.result.selected)
            assertEquals(selected.evaluation.candidateIds, selectedIds(html))
            assertTrue(html.contains(report.runId))
            assertTrue(html.contains(requireNotNull(report.inputHash)))
            assertTrue(html.contains("evaluated_sets"))
            assertTrue(html.contains("best_robust"))
            assertTrue(html.contains("best_mean"))
            assertTrue(html.contains("Лучшие наборы различаются"))
            assertEquals("88\u202f900", capture(section(html, "comparison"), "<strong class=\"price-value\">(.*?)</strong>"))
            val queryTable = section(html, "queries")
            assertEquals(listOf("SQL", "normal, вызовов/час", "campaign, вызовов/час", "Baseline cost", "Final cost", "Снижение cost, %", "Изменение"), tableRows(queryTable).first())
            assertEquals(listOf("records_by_group", "3\u202f000", "100", "100",
                if (objective == Objective.ROBUST) "50" else "10",
                if (objective == Objective.ROBUST) "50 %" else "90 %", "Снижение cost"), rowByKey(queryTable, "records_by_group"))
            assertEquals(listOf("records_by_value", "100", "3\u202f000", "100",
                if (objective == Objective.ROBUST) "50" else "100",
                if (objective == Objective.ROBUST) "50 %" else "0 %",
                if (objective == Objective.ROBUST) "Снижение cost" else "Без изменений"), rowByKey(queryTable, "records_by_value"))
            assertTrue(html.contains("Демонстрационные данные"))
        }
    }

    @Test
    fun `renderer displays supplied metrics budgets and price without recalculating them`() {
        val report = HtmlReportFixtures.completed()
        val result = report.result as SearchResult.Completed
        // Deliberately inconsistent values: a view must not silently replace the producer's data.
        val supplied = result.bestRobust.copy(metrics = result.bestRobust.metrics.copy(
            baselineMeanCost = 54321.0, meanCost = 12345.0, meanRatio = 0.321, worstRatio = 0.654,
            // Reverse the model order too: the renderer must associate profiles by ID.
            profiles = listOf(
                ProfileMetrics("campaign", 0.456, 2222.0, 4444.0, 8765.0, 0.222, 27.5),
                ProfileMetrics("normal", 0.123, 1111.0, 3333.0, 4321.0, 0.111, 17.25),
            ),
            budgetUsage = BudgetUsage(111L, 222L, 333.0, 1, sizeSafetyFactor = 1.3),
        ))
        val html = renderer.render(report.copy(result = result.copy(bestRobust = supplied), priceOfRobustness = 9876.5))
        val overview = section(html, "overview")
        assertEquals("0,654", entryValue(overview, "Худшая нормированная стоимость"))
        assertEquals("0,321", entryValue(overview, "Средняя нормированная стоимость"))
        val robust = configuration(html, "best_robust")
        assertEquals("0,654", entryValue(robust, "Худшее отношение R"))
        assertEquals("0,321", entryValue(robust, "Среднее отношение M"))
        assertEquals("12\u202f345", entryValue(robust, "Ожидаемая стоимость C_mean"))
        assertEquals("54\u202f321", entryValue(robust, "Baseline B_mean"))
        assertEquals("111 байт (< 0,001 MiB)", entryValue(robust, "Оценка размера"))
        assertEquals("222 байт (< 0,001 MiB)", entryValue(robust, "С запасом"))
        assertEquals("333", entryValue(robust, "Write units/час"))
        assertEquals(listOf("normal", "3\u202f333", "0,111", "17,25"), rowByKey(robust, "normal"))
        assertEquals(listOf("campaign", "4\u202f444", "0,222", "27,5"), rowByKey(robust, "campaign"))
        val profiles = section(html, "profiles").substringBefore("<summary>Сравнение всех сохранённых наборов")
        assertEquals(listOf("Профиль", "Вес", "Baseline cost", "Final cost", "Отношение", "Выигрыш, cost", "Выигрыш, %"), tableRows(profiles).first())
        assertEquals(listOf("normal", "0,123", "1\u202f111", "3\u202f333", "0,111", "4\u202f321", "17,25"), rowByKey(profiles, "normal"))
        assertEquals(listOf("campaign", "0,456", "2\u202f222", "4\u202f444", "0,222", "8\u202f765", "27,5"), rowByKey(profiles, "campaign"))
        val mean = configuration(html, "best_mean")
        assertEquals("66\u202f100", entryValue(mean, "Ожидаемая стоимость C_mean"))
        val storage = storageCard(html)
        assertEquals("222 / 67\u202f108\u202f864 байт", capture(storage, "<p class=\"budget-number\">(.*?)</p>"))
        assertTrue(storage.contains("Коэффициент запаса: 1,3"))
        assertEquals("9\u202f876,5", capture(section(html, "comparison"), "<strong class=\"price-value\">(.*?)</strong>"))
    }

    @Test
    fun `decisions copied by pipeline appear once while distinct contexts survive`() {
        val report = HtmlReportFixtures.completed()
        val result = report.result as SearchResult.Completed
        val original = CandidateDecision(CandidateReason.COLUMN_SHORTLIST_TRUNCATED,
            queryId = "q1", table = TableRef("public", "wide"), phase = AnalysisPhase.CANDIDATES,
            details = mapOf("column" to "c17"))
        val otherQuery = original.copy(queryId = "q2")
        val otherPhase = original.copy(phase = AnalysisPhase.GREEDY)
        val otherDetails = original.copy(details = mapOf("column" to "c18"))
        val searchOnly = CandidateDecision(CandidateReason.NOT_SELECTED, candidateId = "i1", phase = AnalysisPhase.REPAIR)
        val reportWithDecisions = report.copy(
            candidates = report.candidates.copy(diagnostics = report.candidates.diagnostics.copy(decisions = listOf(original))),
            result = result.copy(diagnostics = result.diagnostics.copy(
                candidateDecisions = listOf(original.copy(), otherQuery, otherPhase, otherDetails, searchOnly))),
        )
        val html = renderer.render(reportWithDecisions)
        val decisions = capture(section(html, "search"), "<summary>Причины решений по кандидатам</summary>(.*?)</details>", stripTags = false)
        assertEquals(listOf(
            listOf("Причина", "Кандидат / SQL / таблица", "Этап", "Подробности"),
            listOf("Отсечение колонок (COLUMN_SHORTLIST_TRUNCATED)", "q1 / public.wide", "candidates", "column: c17"),
            listOf("Отсечение колонок (COLUMN_SHORTLIST_TRUNCATED)", "q2 / public.wide", "candidates", "column: c17"),
            listOf("Отсечение колонок (COLUMN_SHORTLIST_TRUNCATED)", "q1 / public.wide", "greedy", "column: c17"),
            listOf("Отсечение колонок (COLUMN_SHORTLIST_TRUNCATED)", "q1 / public.wide", "candidates", "column: c18"),
            listOf("Не выбран поиском (NOT_SELECTED)", "i1", "repair", "Нет данных"),
        ), tableRows(decisions))
    }

    @Test
    fun `storage shows exact bytes and MiB in budgets comparisons and candidate lists`() {
        val html = renderer.render(HtmlReportFixtures.completed())
        val storage = storageCard(html)
        assertEquals("314\u202f573 / 67\u202f108\u202f864 байт", capture(storage, "<p class=\"budget-number\">(.*?)</p>"))
        assertEquals("≈0,3 MiB / 64 MiB", capture(storage, "<p class=\"budget-mib\">(.*?)</p>"))
        assertTrue(storage.contains("Оценка HypoPG: 262\u202f144 байт (0,25 MiB)"))
        val robust = configuration(html, "best_robust")
        assertEquals("262\u202f144 байт (0,25 MiB)", entryValue(robust, "Оценка размера"))
        assertEquals("314\u202f573 байт (≈0,3 MiB)", entryValue(robust, "С запасом"))
        val mean = configuration(html, "best_mean")
        assertEquals("524\u202f288 байт (0,5 MiB)", entryValue(mean, "Оценка размера"))
        assertEquals("629\u202f146 байт (≈0,6 MiB)", entryValue(mean, "С запасом"))
        val selected = capture(section(html, "indexes"), "<article[^>]*data-selected-index=\"records_created_at\"[^>]*>(.*?)</article>", stripTags = false)
        assertEquals("262\u202f144 байт (0,25 MiB)", entryValue(selected, "Оценка"))
        assertEquals("314\u202f573 байт (≈0,3 MiB)", entryValue(selected, "С запасом"))
        val candidates = capture(section(html, "search"), "<summary>Все сохранённые кандидаты и оценки</summary>(.*?)</details>", stripTags = false)
        assertEquals(listOf("262\u202f144 байт (0,25 MiB)", "314\u202f573 байт (≈0,3 MiB)"), tableRows(candidates)[1].subList(1, 3))
    }

    @Test
    fun `storage formatting preserves null zero sub-MiB and large byte values`() {
        val report = HtmlReportFixtures.completed()
        val input = requireNotNull(report.input)
        val limits = listOf(0L to "0 MiB", 1L to "< 0,001 MiB", 1_048_576L to "1 MiB",
            Long.MAX_VALUE to "≈8\u202f796\u202f093\u202f022\u202f208 MiB")
        for ((bytes, expectedMiB) in limits) {
            val html = renderer.render(report.copy(input = input.copy(constraints = input.constraints.copy(storageBudgetBytes = bytes))))
            val card = storageCard(html)
            assertEquals("≈0,3 MiB / $expectedMiB", capture(card, "<p class=\"budget-mib\">(.*?)</p>"))
            if (bytes == Long.MAX_VALUE) assertEquals("314\u202f573 / 9\u202f223\u202f372\u202f036\u202f854\u202f775\u202f807 байт", capture(card, "<p class=\"budget-number\">(.*?)</p>"))
        }
        val missing = storageCard(renderer.render(ContractFixtures.report(status = RunStatus.FAILED).copy(input = null)))
        assertEquals("Нет данных / Нет данных", capture(missing, "<p class=\"budget-mib\">(.*?)</p>"))
        assertTrue(missing.contains("Оценка HypoPG: Нет данных."))
    }

    @Test
    fun `completed partial and failed retain honest state and diagnostics`() {
        val same = renderer.render(HtmlReportFixtures.completed(sameIncumbents = true))
        assertTrue(same.contains("Лучшие наборы robust и mean совпадают"))
        val partialReport = ContractFixtures.report(status = RunStatus.PARTIAL)
        val partial = renderer.render(partialReport)
        assertTrue(partial.contains("partial · Частичный результат"))
        assertTrue(partial.contains("Поиск остановлен досрочно"))
        assertTrue(partial.contains("EXPLAIN_FAILED"))
        assertTrue(partial.contains("EVALUATION_FAILED"))
        assertEquals(partialReport.result.selected?.evaluation?.candidateIds, selectedIds(partial))
        val failed = renderer.render(ContractFixtures.report(status = RunStatus.FAILED))
        assertTrue(failed.contains("failed · Ошибка"))
        assertTrue(failed.contains("Synthetic baseline failure"))
        assertTrue(selectedIds(failed).isEmpty())
        assertFalse(failed.contains("CREATE INDEX"))
        assertFalse(failed.contains("Пустой набор. Новые индексы"))
        assertTrue(failed.contains("Нет сохранённого решения"))
    }

    @Test
    fun `zero baseline and empty set are different from missing data`() {
        val html = renderer.render(HtmlReportFixtures.zeroBaseline())
        assertTrue(html.contains("Выбран пустой набор"))
        assertTrue(html.contains("Неприменимо"))
        assertTrue(html.contains("ZERO_BASELINE"))
        assertTrue(selectedIds(html).isEmpty())
        val missing = renderer.render(ContractFixtures.report(status = RunStatus.FAILED).copy(input = null, inputHash = null))
        assertTrue(missing.contains("Нет данных"))
        assertTrue(missing.contains("Профили отсутствуют"))
        assertFalse(missing.contains("Выбран пустой набор"))
    }

    @Test
    fun `saved query costs and regressions remain visible without the input workload`() {
        val report = HtmlReportFixtures.regression().copy(input = null)
        assertTrue(report.queryEffects.isEmpty())
        val html = renderer.render(report)
        val queries = section(html, "queries")
        assertEquals(listOf("records_by_value", "100", "120", "Нет данных", "Регрессия"),
            rowByKey(queries, "records_by_value"))
        assertEquals(listOf("records_by_group", "100", "20", "Нет данных", "Снижение cost"),
            rowByKey(queries, "records_by_group"))
        assertTrue(queries.contains("class=\"regression\""))
        assertTrue(queries.contains("Исходный SQL отсутствует в модели."))

        val failed = ContractFixtures.report(status = RunStatus.FAILED)
        val baselineOnly = failed.copy(input = null, result = (failed.result as SearchResult.Failed).copy(
            baseline = ContractFixtures.baselineEvaluation(),
        ))
        assertEquals(listOf("records_by_value", "100", "Нет данных", "Нет данных", "Нет данных"),
            rowByKey(section(renderer.render(baselineOnly), "queries"), "records_by_value"))
    }

    @Test
    fun `disabled write budget unknown units and explicit zero remain distinct`() {
        val report = ContractFixtures.report(withWriteProfile = false)
        val html = renderer.render(report)
        val section = html.substringAfter("<section id=\"indexes\"").substringBefore("<section id=\"queries\"")
        assertTrue(section.contains("Бюджет: Отключён"))
        assertTrue(section.contains("Нет данных <small>write units/час"))
        assertTrue(section.contains("Профиль записи не задан"))
        val zeroInput = requireNotNull(report.input).let { it.copy(constraints = it.constraints.copy(writeBudgetUnitsPerHour = 0.0)) }
        val zero = renderer.render(report.copy(input = zeroInput))
        assertTrue(zero.contains("Бюджет: 0 units/час"))
        val enabledProfile = ContractFixtures.report().let {
            it.copy(input = requireNotNull(it.input).let { input -> input.copy(constraints = input.constraints.copy(writeBudgetUnitsPerHour = null)) })
        }
        val enabledProfileHtml = renderer.render(enabledProfile)
        assertTrue(enabledProfileHtml.contains("4\u202f100 <small>write units/час"))
        assertTrue(enabledProfileHtml.contains("Бюджет: Отключён"))
    }

    @Test
    fun `per-query regressions have visible labels independent of chosen objective`() {
        val html = renderer.render(HtmlReportFixtures.regression())
        val row = html.substringAfter("class=\"regression\">").substringBefore("</tr>")
        assertTrue(row.contains("records_by_value"))
        assertTrue(row.contains("<td>100</td>"))
        assertTrue(row.contains("<td>120</td>"))
        assertTrue(row.contains("Регрессия"))
        assertTrue(html.contains("3\u202f000"))
        assertTrue(html.contains("вызовов/час"))
    }

    @Test
    fun `all untrusted text including attribute values and plans is escaped`() {
        val payload = "</style></script><img src=x onerror=alert(1)><script>alert(2)</script>&\"'"
        val source = HtmlReportFixtures.completed()
        val input = requireNotNull(source.input)
        val candidate = source.candidates.candidates.first().copy(id = payload, ddl = payload,
            sourceQueryIds = setOf(payload), definition = source.candidates.candidates.first().definition.copy(table = TableRef(payload, payload)))
        val evaluation = EvaluationResult(listOf(payload), mapOf(payload to 1.0), mapOf(payload to ExplainPlan(payload)))
        val configuration = requireNotNull(source.result.bestRobust).copy(evaluation = evaluation,
            metrics = requireNotNull(source.result.bestRobust).metrics.let { it.copy(profiles = it.profiles.map { p -> p.copy(profileId = payload) }) })
        val report = source.copy(runId = payload, inputHash = payload, environment = source.environment.copy(applicationVersion = payload),
            input = input.copy(queries = listOf(QueryInput(payload, payload)), profiles = listOf(Profile(payload, 1.0, mapOf(payload to 1.0)))),
            candidates = CandidateBatch(listOf(candidate), CandidateDiagnostics(decisions = listOf(CandidateDecision(
                CandidateReason.NOT_SELECTED, payload, payload, TableRef(payload, payload), details = mapOf(payload to payload))))),
            result = SearchResult.Partial(Objective.ROBUST, evaluation.copy(candidateIds = emptyList()), configuration, configuration,
                SearchDiagnostics(stopReasons = listOf(StopReason.EVALUATION_FAILED),
                    warnings = listOf(AnalysisWarning(WarningCode.EVALUATION_FAILED, payload, payload, payload, mapOf(payload to payload))),
                    errors = listOf(AnalysisError(ErrorCode.EXPLAIN_FAILED, payload, payload, payload, mapOf(payload to payload))))))
        val html = renderer.render(report)
        assertFalse(html.contains(payload))
        assertSafeScript(html)
        assertFalse(html.contains("<img"))
        val escaped = "&lt;/style&gt;&lt;/script&gt;&lt;img src=x onerror=alert(1)&gt;&lt;script&gt;alert(2)&lt;/script&gt;&amp;&quot;&#39;"
        assertTrue(html.contains("data-selected-index=\"$escaped\""))
        assertTrue(html.contains("<pre><code>$escaped</code></pre>"))
        assertTrue(html.contains("default-src 'none'"))
    }

    @Test
    fun `wide SQL candidate caps unknown counts and rejected estimates are preserved`() {
        val report = HtmlReportFixtures.wide()
        val html = renderer.render(report)
        val sql = requireNotNull(report.input).queries.first().sql
        assertTrue(html.contains(sql))
        assertTrue(html.contains("c100 = 100"))
        assertTrue(html.contains("100 → 16"))
        assertTrue(html.contains("COLUMN_SHORTLIST_TRUNCATED"))
        assertTrue(html.contains("LOCAL_CANDIDATE_LIMIT"))
        assertTrue(html.contains("GLOBAL_CANDIDATE_LIMIT"))
        assertTrue(html.contains("NOT_EVALUATED_DUE_TO_LIMIT"))
        assertTrue(html.contains("REPAIR_LIMIT"))
        assertTrue(html.contains("<dt>Не перечислено вариантов</dt><dd>Нет данных</dd>"))
        assertTrue(html.contains("<th scope=\"row\">Непроверенные конфигурации</th><td>Нет данных</td>"))
        val unknownRow = html.substringAfter("wide_not_evaluated / ").substringBefore("</tr>")
        assertTrue(unknownRow.contains("<td>Нет данных</td><td>Нет данных</td><td>Нет данных</td>"))
    }

    @Test
    fun `large SQL is not truncated and generated navigation uses safe ordinal ids`() {
        val report = HtmlReportFixtures.completed()
        val input = requireNotNull(report.input)
        val sql = "SELECT '" + "x".repeat(65_000) + "' FROM public.records"
        val html = renderer.render(report.copy(input = input.copy(queries = input.queries.map { it.copy(sql = sql) })))
        assertTrue(html.contains("x".repeat(65_000)))
        assertTrue(html.contains("href=\"#query-0\""))
        assertTrue(html.contains("id=\"query-0\""))
    }

    @Test
    fun `all fixtures are standalone and output is deterministic across locale changes`() {
        val previous = Locale.getDefault()
        try {
            val report = HtmlReportFixtures.completed()
            val html = renderer.render(report)
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(html, renderer.render(report))
            HtmlReportFixtures.all().values.forEach { fixture ->
                val output = renderer.render(fixture)
                assertTrue(output.startsWith("<!doctype html>"))
                assertTrue(output.endsWith("</body></html>"))
                assertSafeScript(output)
                assertFalse(output.contains("<link"))
                assertFalse(output.contains("src="))
                assertFalse(output.contains("@import"))
                assertFalse(output.contains("url("))
                assertFalse(output.contains("jdbc:"))
                assertFalse(output.contains("postgresql://"))
                assertFalse(output.contains("INDEXBUDGET_DATABASE_URL"))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `tiny supplied positive weights do not appear as zero`() {
        val report = HtmlReportFixtures.completed()
        val result = report.result as SearchResult.Completed
        val config = result.bestRobust.copy(metrics = result.bestRobust.metrics.let { metrics ->
            metrics.copy(profiles = metrics.profiles.map { it.copy(normalizedWeight = 1e-15) })
        })
        val html = renderer.render(report.copy(result = result.copy(bestRobust = config)))
        assertTrue(html.contains("1E-15"))
    }

    private fun assertSafeScript(html: String) {
        val scripts = Regex("<script>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        assertEquals(1, scripts.size)
        val source = checkNotNull(javaClass.getResource("/report/report.js")).readText()
        assertEquals(source, scripts.single().groupValues[1])
        val hash = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)))
        assertTrue(html.contains("script-src 'sha256-$hash'"))
        assertFalse(html.contains("script-src 'unsafe-inline'"))
    }

    private fun selectedIds(html: String) = Regex("data-selected-index=\"([^\"]*)\"").findAll(html).map { it.groupValues[1] }.toList()

    // Scoped extraction of this renderer's controlled markup: missing/duplicate fields fail too.
    private fun capture(html: String, pattern: String, stripTags: Boolean = true): String {
        val matches = Regex(pattern, RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        assertEquals(1, matches.size, "Expected exactly one match for $pattern")
        val value = matches.single().groupValues[1]
        return if (stripTags) plainText(value) else value
    }

    private fun plainText(html: String) = html.replace(Regex("<[^>]*>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    private fun section(html: String, id: String) = capture(html, "<section id=\"${Regex.escape(id)}\"[^>]*>(.*?)</section>", stripTags = false)
    private fun configuration(html: String, key: String) = capture(html, "<article[^>]*data-configuration=\"${Regex.escape(key)}\"[^>]*>(.*?)</article>", stripTags = false)
    private fun entryValue(html: String, label: String) = capture(html, "<dt>${Regex.escape(label)}</dt><dd>(.*?)</dd>")
    private fun metricValue(html: String, label: String) = capture(html, "<div class=\"metric-label\">${Regex.escape(label)}</div><strong>(.*?)</strong>")
    private fun storageCard(html: String) = capture(section(html, "indexes"), "<div class=\"budget-card\"><h3>Место</h3>(.*?)</div>", stripTags = false)
    private fun tableRows(html: String): List<List<String>> = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { row ->
        Regex("<t[hd]\\b[^>]*>(.*?)</t[hd]>", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1]).map { plainText(it.groupValues[1]) }.toList()
    }.toList()
    private fun rowByKey(html: String, key: String) = tableRows(html).single { it.firstOrNull() == key }
}
