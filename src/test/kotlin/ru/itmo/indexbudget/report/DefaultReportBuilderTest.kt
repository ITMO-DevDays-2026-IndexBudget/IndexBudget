package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.candidates.PostgresDdlRenderer
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures

class DefaultReportBuilderTest {
    private val environment = EnvironmentInfo("test", "25", "test-kotlin")
    private val builder = DefaultReportBuilder(environment = environment)

    @Test
    fun `build uses saved incumbent metrics and plans and captures input identity`() {
        val source = ContractFixtures.report()
        val result = source.result as SearchResult.Completed
        val saved = result.copy(bestMean = result.bestMean.copy(
            metrics = result.bestMean.metrics.copy(meanCost = 66_101.0),
        ))
        var nanos = 0L
        val context = RunContext(runId = "report-run", clock = NanoClock { nanos })
        context.recordInputHash("a".repeat(64))
        nanos = 123_000_000L

        val report = builder.build(requireNotNull(source.input), source.candidates, saved, context)

        assertEquals("report-run", report.runId)
        assertEquals("a".repeat(64), report.inputHash)
        assertEquals(123L, report.durationMillis)
        assertEquals(environment, report.environment)
        assertSame(saved, report.result)
        assertSame(saved.bestMean.evaluation.plansByQueryId, report.result.bestMean?.evaluation?.plansByQueryId)
        assertEquals(88_899.0, report.priceOfRobustness)
        assertEquals(310_000.0, report.baselineMetrics?.meanCost)
        assertEquals(false, report.incumbentsCoincide)
        assertEquals(mapOf("normal" to 0.9, "campaign" to 0.1), report.normalizedProfileWeights)
        report.candidates.candidates.forEach { candidate ->
            assertEquals(PostgresDdlRenderer().render(candidate.definition), candidate.ddl)
        }
    }

    @Test
    fun `partial can be built after cancellation and exhausted time without new work`() {
        var nanos = 0L
        val context = RunContext(clock = NanoClock { nanos })
        context.recordInputHash("a".repeat(64))
        context.cancel()
        nanos = 100_000_000_000L
        val source = ContractFixtures.report(status = RunStatus.PARTIAL)

        val report = builder.build(requireNotNull(source.input), source.candidates, source.result, context)

        assertEquals(RunStatus.PARTIAL, report.result.status)
        assertEquals(0.0, report.priceOfRobustness)
        assertEquals(true, report.incumbentsCoincide)
        assertEquals(source.result.diagnostics, report.result.diagnostics)
        assertEquals(RunStatistics(), context.statistics())
    }

    @Test
    fun `builder retains the effective mode when finalizing every status without database work`() {
        for (mode in AnalysisMode.entries) {
            for (status in RunStatus.entries) {
                val source = ContractFixtures.report(status = status)
                var nanos = 0L
                val context = RunContext(clock = NanoClock { nanos })
                context.selectAnalysisMode(mode)
                context.recordInputHash("b".repeat(64))
                nanos = mode.limits.commandTimeout.toNanos()
                context.cancel()
                val before = context.statistics()

                val report = builder.build(requireNotNull(source.input).copy(analysisMode = mode),
                    source.candidates, source.result, context)

                assertEquals(mode, report.analysisMode)
                assertEquals("b".repeat(64), report.inputHash)
                assertSame(source.result, report.result)
                assertEquals(before, context.statistics())
            }
        }
    }

    @Test
    fun `failed numeric baseline is retained without retrying failed metrics`() {
        val source = ContractFixtures.report(status = RunStatus.FAILED)
        val failed = (source.result as SearchResult.Failed).copy(
            baseline = ContractFixtures.baselineEvaluation(),
            error = AnalysisError(ErrorCode.NUMERIC_ERROR, "Numeric overflow"),
        )
        val rejectCalculations = object : MetricsCalculator by MetricsCalculatorImpl() {
            override fun calculate(workload: WorkloadInput, baseline: EvaluationResult,
                evaluation: EvaluationResult, usage: BudgetUsage): SetMetrics = error("Must not retry failed metrics")
        }

        val report = DefaultReportBuilder(rejectCalculations, environment).build(
            requireNotNull(source.input), source.candidates, failed, RunContext(),
        )

        assertSame(failed.baseline, report.result.baseline)
        assertNull(report.baselineMetrics)
        assertNull(report.priceOfRobustness)
        assertNull(report.incumbentsCoincide)
        assertNull(report.queryComparisons.first().selectedCost)
        assertNull(report.queryComparisons.first().regression)
    }

    @Test
    fun `discarded candidates retain unknown expenses and diagnostics`() {
        val source = ContractFixtures.report(sameIncumbents = true)
        val discarded = ContractFixtures.unestimatedCandidate()
        val batch = source.candidates.copy(
            candidates = source.candidates.candidates + discarded,
            diagnostics = source.candidates.diagnostics.copy(decisions = listOf(
                CandidateDecision(CandidateReason.GLOBAL_CANDIDATE_LIMIT, discarded.id),
            )),
        )

        val report = builder.build(requireNotNull(source.input), batch, source.result, RunContext())
        val unknown = report.candidates.candidates.single { it.id == discarded.id }

        assertNull(unknown.estimatedBytes)
        assertNull(unknown.budgetedBytes)
        assertNull(unknown.writeUnitsPerHour)
        assertEquals(batch.diagnostics, report.candidates.diagnostics)
    }

    @Test
    fun `query regression is explicit although the common mean objective improves`() {
        val input = ContractFixtures.workload(Objective.MEAN)
        val baseline = ContractFixtures.baselineConfiguration()
        val original = ContractFixtures.meanConfiguration()
        val evaluation = original.evaluation.copy(
            costByQueryId = mapOf("records_by_group" to 10.0, "records_by_value" to 110.0),
        )
        val mean = EvaluatedConfiguration(evaluation, MetricsCalculatorImpl().calculate(
            input, baseline.evaluation, evaluation, original.metrics.budgetUsage,
        ))
        val result = SearchResult.Completed(Objective.MEAN, baseline.evaluation, baseline, mean)

        val report = builder.build(input, CandidateBatch(ContractFixtures.candidates()), result, RunContext())

        assertEquals(QueryCostComparison("records_by_value", 100.0, 110.0, true), report.queryComparisons.last())
        assertEquals(QueryCostComparison("records_by_group", 100.0, 10.0, false), report.queryComparisons.first())
        assertTrue(requireNotNull(report.result.selected).metrics.meanRatio!! < 1.0)
    }

    @Test
    fun `cost guard outcomes describe the selected set with limits and observed ratios`() {
        val source = ContractFixtures.workload(Objective.MEAN)
        val input = source.copy(constraints = source.constraints.copy(
            queryCostGuards = mapOf("records_by_group" to 2.0, "records_by_value" to 1.1)))
        val baseline = ContractFixtures.baselineConfiguration()
        val original = ContractFixtures.meanConfiguration()
        val evaluation = original.evaluation.copy(
            costByQueryId = mapOf("records_by_group" to 10.0, "records_by_value" to 110.0),
        )
        val mean = EvaluatedConfiguration(evaluation, MetricsCalculatorImpl().calculate(
            input, baseline.evaluation, evaluation, original.metrics.budgetUsage,
        ))
        val result = SearchResult.Completed(Objective.MEAN, baseline.evaluation, baseline, mean)

        val report = builder.build(input, CandidateBatch(ContractFixtures.candidates()), result, RunContext())

        assertEquals(listOf("records_by_group", "records_by_value"), report.costGuards.map { it.queryId })
        val group = report.costGuards.first()
        assertEquals(2.0, group.maxRatio)
        assertEquals(100.0, group.baselineCost)
        assertEquals(10.0, group.selectedCost)
        assertEquals(0.1, group.observedRatio)
        assertFalse(group.violated)
        val value = report.costGuards.last()
        // Equality with the 1.1 limit passes.
        assertEquals(1.1, value.observedRatio)
        assertFalse(value.violated)
    }

    @Test
    fun `failed runs keep requested guards echo without selected outcomes`() {
        val source = ContractFixtures.workload()
        val input = source.copy(constraints = source.constraints.copy(
            queryCostGuards = mapOf("records_by_group" to 1.05)))
        val failed = ContractFixtures.failedResult()

        val report = builder.build(input, CandidateBatch(ContractFixtures.candidates()), failed, RunContext())

        assertTrue(report.costGuards.isEmpty())
        assertEquals(mapOf("records_by_group" to 1.05), report.input?.constraints?.queryCostGuards)
    }

    @Test
    fun `zero baseline and absent write profile preserve nulls instead of fabricating zeros`() {
        val input = ContractFixtures.workload(withWriteProfile = false)
        val baseline = ContractFixtures.baselineEvaluation().let { it.copy(
            costByQueryId = it.costByQueryId.mapValues { 0.0 },
        ) }
        val metrics = MetricsCalculatorImpl()
        val configuration = EvaluatedConfiguration(baseline, metrics.calculate(
            input, baseline, baseline, metrics.assessBudget(emptyList(), input).usage,
        ))
        val result = SearchResult.Completed(Objective.ROBUST, baseline, configuration, configuration)

        val report = builder.build(input, CandidateBatch(emptyList()), result, RunContext())

        assertEquals(0.0, report.baselineMetrics?.meanCost)
        assertNull(report.baselineMetrics?.meanRatio)
        assertNull(report.baselineMetrics?.worstRatio)
        assertNull(report.baselineMetrics?.budgetUsage?.writeUnitsPerHour)
        report.baselineMetrics?.profiles?.forEach { assertNull(it.ratio); assertNull(it.gainPercent) }
        assertEquals(true, report.incumbentsCoincide)
        assertEquals(0.0, report.priceOfRobustness)
    }

    @Test
    fun `incumbents without corresponding definitions cannot produce a report`() {
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(ContractFixtures.workload(), CandidateBatch(emptyList()),
                ContractFixtures.completedResult(), RunContext())
        }
    }
}
