package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Instant

class ReportSummaryTest {
    private val mapper = JsonMapper.builder().build()
    private val builder = DefaultReportBuilder()

    @Test
    fun `mean effect uses ratio of weighted costs and worst profile does not hide query regression`() {
        val source = ContractFixtures.report(objective = Objective.MEAN)
        val input = requireNotNull(source.input).copy(profiles = listOf(
            Profile("normal", 1.0, mapOf("records_by_group" to 100.0, "records_by_value" to 10.0)),
            Profile("campaign", 1.0, mapOf("records_by_group" to 10.0, "records_by_value" to 100.0)),
        ))
        val baseline = evaluation(emptyList(), 100.0, 50.0)
        val selected = evaluation(listOf("records_group_created_at"), 40.0, 100.0)
        val metrics = MetricsCalculatorImpl()
        val base = EvaluatedConfiguration(baseline, metrics.calculate(input, baseline, baseline,
            metrics.assessBudget(emptyList(), input).usage))
        val mean = EvaluatedConfiguration(selected, metrics.calculate(input, baseline, selected,
            metrics.assessBudget(source.candidates.candidates.filter { it.id in selected.candidateIds }, input).usage))
        val context = RunContext().also { it.cancel() }
        val report = builder.build(input, source.candidates,
            SearchResult.Completed(Objective.MEAN, baseline, base, mean), context)
        val summary = report.summary

        assertEquals(8250.0, summary.meanEffect.baselineCost)
        assertEquals(7700.0, summary.meanEffect.cost)
        assertEquals(550.0, summary.meanEffect.gain)
        assertEquals(14.0 / 15, requireNotNull(summary.meanEffect.ratio), 1e-12)
        assertEquals(100.0 / 15, requireNotNull(summary.meanEffect.gainPercent), 1e-12)
        assertEquals(26.0 / 15, requireNotNull(summary.worstRatio), 1e-12)
        assertEquals(1, summary.regressedQueryCount)
        assertEquals(false, summary.incumbentsCoincide)
        assertEquals(550.0, summary.priceOfRobustness)
        assertSame(mean.metrics.budgetUsage, summary.budgetUsage)
        assertEquals(-100.0, report.queryEffects.last().selected.gainPercent)
        assertEquals(2.0, report.queryEffects.last().selected.ratio)
        assertEquals(RunStatistics(), context.statistics())
        val json = mapper.readTree(JsonReportWriter().render(report))
        assertEquals(550.0, json["summary"]["mean_effect"]["gain"].doubleValue())
        assertEquals(1, json["summary"]["regressed_query_count"].intValue())
        assertEquals(json["selected"]["metrics"]["budget_usage"], json["summary"]["budget_usage"])
        assertEquals(json["selected"]["candidate_ids"], json["summary"]["selected_index_ids"])
        assertEquals(summary.meanEffect.gainPercent, json["summary"]["mean_effect"]["gain_percent"].doubleValue())
        report.profileComparisons.forEachIndexed { index, series ->
            assertEquals(series.selected.cost, json["profile_comparisons"][index]["selected"]["cost"].doubleValue())
            assertEquals(series.bestMean.gainPercent, json["profile_comparisons"][index]["best_mean"]["gain_percent"].doubleValue())
            assertEquals(series.bestRobust.cost, series.baseline.cost)
        }
        val html = HtmlReportRenderer().render(report)
        assertTrue(html.contains("<dt>Регрессии SQL</dt><dd>1 из 2</dd>"))
        assertTrue(html.contains("M = C_mean / B_mean"))
        assertTrue(html.contains("R = max(C_s / B_s)"))
        assertTrue(html.contains("Рост на 100%"))
    }

    @Test
    fun `zero baseline unknown usage disabled and zero limits remain distinct`() {
        val input = ContractFixtures.workload(withWriteProfile = false).let { it.copy(
            constraints = it.constraints.copy(storageBudgetBytes = 0, maxIndexes = 0),
        ) }
        val baseline = evaluation(emptyList(), 0.0, 0.0)
        val calculator = MetricsCalculatorImpl()
        val base = EvaluatedConfiguration(baseline, calculator.calculate(input, baseline, baseline,
            calculator.assessBudget(emptyList(), input).usage))
        val report = builder.build(input, CandidateBatch(emptyList()),
            SearchResult.Completed(Objective.ROBUST, baseline, base, base), RunContext())
        val summary = report.summary
        assertEquals(emptyList<String>(), summary.selectedIndexIds)
        assertEquals(RelativeValueStatus.ZERO_BASELINE, summary.meanEffect.relativeStatus)
        assertEquals(0.0, summary.meanEffect.cost)
        assertNull(summary.meanEffect.gainPercent)
        assertNull(summary.worstRatio)
        assertNull(summary.budgetUsage?.writeUnitsPerHour)
        assertEquals(0, summary.regressedQueryCount)
        assertTrue(requireNotNull(summary.incumbentsCoincide))
        assertEquals(0.0, summary.priceOfRobustness)
        report.profileComparisons.forEach { assertEquals(RelativeValueStatus.ZERO_BASELINE, it.selected.relativeStatus) }
        report.queryEffects.forEach { assertEquals(RelativeValueStatus.ZERO_BASELINE, it.selected.relativeStatus) }
        val json = mapper.readTree(JsonReportWriter().render(report))["summary"]
        assertFalse(json["write_limit_enabled"].booleanValue())
        assertTrue(json["budget_usage"]["write_units_per_hour"].isNull)
        assertEquals(0, json["budget_limits"]["storage_budget_bytes"].intValue())
        assertTrue(HtmlReportRenderer().render(report).contains("Процент неприменим: нулевой baseline"))

        val withWrites = input.copy(tableWriteRates = mapOf("public.records" to TableWriteRates(0.0, 0.0, 0.0)),
            constraints = input.constraints.copy(writeBudgetUnitsPerHour = 0.0))
        val known = base.copy(metrics = base.metrics.copy(budgetUsage = calculator.assessBudget(emptyList(), withWrites).usage))
        val zeroWrite = builder.build(withWrites, CandidateBatch(emptyList()),
            SearchResult.Completed(Objective.ROBUST, baseline, known, known), RunContext())
        val zeroJson = mapper.readTree(JsonReportWriter().render(zeroWrite))["summary"]
        assertTrue(zeroJson["write_limit_enabled"].booleanValue())
        assertEquals(0.0, zeroJson["budget_usage"]["write_units_per_hour"].doubleValue())
    }

    @Test
    fun `partial and failed preserve reasons and never fabricate a recommendation`() {
        for (status in listOf(RunStatus.PARTIAL, RunStatus.FAILED)) {
            val source = ContractFixtures.report(status = status)
            val report = builder.build(requireNotNull(source.input), source.candidates, source.result, RunContext())
            assertEquals(status, report.summary.status)
            assertEquals(ComparisonScope.EVALUATED_SETS, report.summary.comparisonScope)
            assertEquals(source.result.diagnostics.stopReasons, report.summary.stopReasons)
            assertTrue(report.summary.errors.any { it.code == ErrorCode.EXPLAIN_FAILED })
            if (status == RunStatus.FAILED) {
                assertNull(report.summary.selectedIndexIds)
                assertNull(report.summary.budgetUsage)
                assertNull(report.summary.regressedQueryCount)
                assertNull(report.summary.incumbentsCoincide)
                assertNull(report.summary.priceOfRobustness)
                assertEquals(RelativeValueStatus.UNAVAILABLE, report.summary.meanEffect.relativeStatus)
            } else {
                assertEquals(listOf("records_created_at"), report.summary.selectedIndexIds)
                assertEquals(true, report.summary.incumbentsCoincide)
                assertEquals(0.0, report.summary.priceOfRobustness)
            }
            assertNull(report.workloadProvenance)
            assertTrue(mapper.readTree(JsonReportWriter().render(report))["workload_provenance"].isNull)
        }
    }

    @Test
    fun `failed with saved baseline keeps absolute query data without retrying profile metrics`() {
        val source = ContractFixtures.report(status = RunStatus.FAILED)
        val failed = (source.result as SearchResult.Failed).copy(baseline = ContractFixtures.baselineEvaluation())
        val report = builder.build(requireNotNull(source.input), source.candidates, failed, RunContext())
        assertEquals(100.0, report.queryEffects.first().baseline.cost)
        assertEquals(100.0, report.queryEffects.first().selected.baselineCost)
        assertEquals(RelativeValueStatus.UNAVAILABLE, report.queryEffects.first().selected.relativeStatus)
        assertNull(report.profileComparisons.first().baseline.cost)
    }

    @Test
    fun `import metadata survives shared model JSON and escaped HTML independently of cost changes`() {
        val input = WorkloadReader().read(Path.of("examples/workload-v2-provenance.json"))
        val source = ContractFixtures.report()
        val metadata = requireNotNull(input.provenance).copy(selectionRule = "<script>alert('x')</script>")
        val report = builder.build(input.copy(provenance = metadata), source.candidates, source.result, RunContext())
        assertSame(metadata, report.workloadProvenance)
        val json = mapper.readTree(JsonReportWriter().render(report))["workload_provenance"]
        assertEquals(metadata.selectionRule, json["selection_rule"].stringValue())
        assertEquals("available_complete_entries", json["windows"][0]["coverage"]["denominator"].stringValue())
        assertEquals(12000.0, json["windows"][0]["coverage"]["selected_exec_time_millis"].doubleValue())
        assertEquals("user_supplied_literals", json["query_examples"][0]["source"].stringValue())
        val html = HtmlReportRenderer().render(report)
        assertFalse(html.contains("<script>alert("))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("Историческое время выполнения"))
        assertTrue(html.contains("не предсказывает время с новым набором"))
        assertTrue(html.contains("Репрезентативные литералы пользователя"))
        assertTrue(html.contains(metadata.sourceId.toString()))
    }

    @Test
    fun `individual zero baseline regression stays visible when the profile improves`() {
        val input = ContractFixtures.workload().copy(profiles = listOf(
            Profile("normal", 1.0, mapOf("records_by_group" to 1.0, "records_by_value" to 1.0)),
        ))
        val baseline = evaluation(emptyList(), 0.0, 100.0)
        val evaluated = evaluation(listOf("records_created_at"), 10.0, 20.0)
        val calculator = MetricsCalculatorImpl()
        val candidates = CandidateBatch(ContractFixtures.candidates().take(1))
        val selected = EvaluatedConfiguration(evaluated, calculator.calculate(input, baseline, evaluated,
            calculator.assessBudget(candidates.candidates, input).usage))
        val report = builder.build(input, candidates,
            SearchResult.Completed(Objective.ROBUST, baseline, selected, selected), RunContext())
        assertEquals(0.3, report.summary.worstRatio)
        assertEquals(1, report.summary.regressedQueryCount)
        val effect = report.queryEffects.first().selected
        assertEquals(-10.0, effect.gain)
        assertNull(effect.gainPercent)
        assertEquals(RelativeValueStatus.ZERO_BASELINE, effect.relativeStatus)
        val json = mapper.readTree(JsonReportWriter().render(report))
        assertEquals("zero_baseline", json["query_effects"][0]["selected"]["relative_status"].stringValue())
        assertTrue(json["queries"][0]["regression"].booleanValue())
    }

    @Test
    fun `HTML provenance rows preserve profile windows completeness coverage and example sources`() {
        val input = WorkloadReader().read(Path.of("examples/workload-v2-provenance.json"))
        val metadata = requireNotNull(input.provenance).copy(
            windows = listOf(
                ObservationWindow("normal", Instant.parse("2026-11-10T08:00:00Z"),
                    Instant.parse("2026-11-10T09:00:00Z"), false, ObservationCoverage(null, 0, 0.0, null)),
                ObservationWindow("campaign", Instant.parse("2026-11-10T10:00:00Z"),
                    Instant.parse("2026-11-10T12:00:00Z"), true, ObservationCoverage(12, 34, 56.0, 78.0)),
            ),
            queryExamples = listOf(
                QueryExample("records_by_group", QueryExampleSource.USER_SUPPLIED_LITERALS),
                QueryExample("records_by_value", QueryExampleSource.ORIGINAL_LITERAL_SQL),
            ),
        )
        val source = ContractFixtures.report()
        val report = builder.build(input.copy(provenance = metadata), source.candidates, source.result, RunContext())
        val provenanceHtml = HtmlReportRenderer().render(report).substringAfter("id=\"provenance\"").substringBefore("</section>")
        fun rowsIn(fragment: String) = Regex("<tr>(.*?)</tr>").findAll(fragment).map { row ->
            Regex("<t[hd][^>]*>(.*?)</t[hd]>").findAll(row.groupValues[1]).map { it.groupValues[1] }.toList()
        }.toList()
        val rows = rowsIn(provenanceHtml)
        assertEquals(listOf(
            listOf("normal", "2026-11-10T08:00:00Z", "2026-11-10T09:00:00Z", "60\u00a0мин", "Неполное", "Нет данных / 0"),
            listOf("campaign", "2026-11-10T10:00:00Z", "2026-11-10T12:00:00Z", "120\u00a0мин", "Полное", "12 / 34"),
            listOf("normal", "0", "Нет данных"),
            listOf("campaign", "56", "78"),
        ), rows.filter { it.firstOrNull() in setOf("normal", "campaign") })
        assertEquals(listOf(
            listOf("SQL 1", "Репрезентативные литералы пользователя"),
            listOf("SQL 2", "Исходный SQL с литералами"),
        ), rowsIn(provenanceHtml.substringAfter("<h3>Примеры SQL</h3>")).filter { it.firstOrNull() in setOf("SQL 1", "SQL 2") })
    }

    @Test
    fun `summary does not restore a saved zero ratio after recognizing positive cost underflow`() {
        val source = ContractFixtures.report()
        val original = requireNotNull(source.result.selected)
        val selected = original.copy(metrics = original.metrics.copy(
            baselineMeanCost = 5e305, meanCost = 5e-309, meanRatio = 0.0,
        ))
        val report = source.copy(result = SearchResult.Completed(Objective.ROBUST,
            requireNotNull(source.result.baseline), selected, selected))
        assertEquals(RelativeValueStatus.OUT_OF_RANGE, report.summary.meanEffect.relativeStatus)
        assertNull(report.summary.meanEffect.ratio)
        assertEquals(5e-309, report.summary.meanEffect.cost)
        val json = mapper.readTree(JsonReportWriter().render(report))["summary"]["mean_effect"]
        assertEquals("out_of_range", json["relative_status"].stringValue())
        assertTrue(json["ratio"].isNull)
    }

    @Test
    fun `unrepresentable query percentages preserve valid aggregate metrics and export`() {
        val input = ContractFixtures.workload().copy(profiles = listOf(
            Profile("normal", 1.0, mapOf("records_by_group" to 1.0, "records_by_value" to 1.0)),
        ))
        val baseline = evaluation(emptyList(), 1e-200, 1e200)
        val evaluated = evaluation(listOf("records_created_at"), 1e200, 0.0)
        val calculator = MetricsCalculatorImpl()
        val candidates = CandidateBatch(ContractFixtures.candidates().take(1))
        val selected = EvaluatedConfiguration(evaluated, calculator.calculate(input, baseline, evaluated,
            calculator.assessBudget(candidates.candidates, input).usage))
        val report = builder.build(input, candidates,
            SearchResult.Completed(Objective.ROBUST, baseline, selected, selected), RunContext())
        assertEquals(1.0, report.summary.meanEffect.ratio)
        assertEquals(0.0, report.summary.meanEffect.gainPercent)
        assertEquals(RelativeValueStatus.OUT_OF_RANGE, report.queryEffects.first().selected.relativeStatus)
        val json = mapper.readTree(JsonReportWriter().render(report))["query_effects"][0]["selected"]
        assertTrue(json["ratio"].isNull)
        assertTrue(json["gain_percent"].isNull)
        assertEquals(1e200, json["cost"].doubleValue())
        assertTrue(HtmlReportRenderer().render(report).contains("Вне числового диапазона"))
    }

    @Test
    fun `query ratio underflow preserves costs and finite aggregate metrics in JSON`() {
        val input = ContractFixtures.workload().copy(profiles = listOf(
            Profile("normal", 1.0, mapOf("records_by_group" to 1.0, "records_by_value" to 1.0)),
        ))
        val baseline = evaluation(emptyList(), 1e200, 1e200)
        val evaluated = evaluation(listOf("records_created_at"), 1e-200, 1e200)
        val calculator = MetricsCalculatorImpl()
        val candidates = CandidateBatch(ContractFixtures.candidates().take(1))
        val selected = EvaluatedConfiguration(evaluated, calculator.calculate(input, baseline, evaluated,
            calculator.assessBudget(candidates.candidates, input).usage))
        val report = builder.build(input, candidates,
            SearchResult.Completed(Objective.ROBUST, baseline, selected, selected), RunContext())
        assertEquals(0.5, report.summary.meanEffect.ratio)
        assertEquals(50.0, report.summary.meanEffect.gainPercent)
        val json = mapper.readTree(JsonReportWriter().render(report))
        val effect = json["query_effects"][0]["selected"]
        assertEquals("out_of_range", effect["relative_status"].stringValue())
        assertTrue(effect["ratio"].isNull)
        assertEquals(100.0, effect["gain_percent"].doubleValue())
        assertEquals(1e-200, effect["cost"].doubleValue())
        assertEquals(1e-200, json["queries"][0]["selected_cost"].doubleValue())
    }

    private fun evaluation(ids: List<String>, first: Double, second: Double): EvaluationResult {
        val costs = mapOf("records_by_group" to first, "records_by_value" to second)
        return EvaluationResult(ids, costs, costs.mapValues { ExplainPlan("""[{"Plan":{"Total Cost":${it.value}}}]""") })
    }
}
