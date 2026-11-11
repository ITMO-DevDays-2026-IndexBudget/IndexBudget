package ru.itmo.indexbudget.optimizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.HtmlReportRenderer
import ru.itmo.indexbudget.report.JsonReportWriter
import tools.jackson.databind.json.JsonMapper

class AnalysisModeOptimizerTest {
    @Test
    fun `presets spend their repair budgets on the same fixed pool and retain complete results`() {
        val pool = candidates()
        val runs = AnalysisMode.entries.associateWith { mode ->
            run(mode, pool) { ids ->
                when (ids) {
                    listOf("i00", "i01") -> 90.0
                    listOf("i14", "i15") -> 50.0
                    else -> 95.0
                }
            }
        }

        val expected = mapOf(
            AnalysisMode.FAST to Expected(8, 500, 25, RunStatus.PARTIAL, 0.9, listOf("i00", "i01"), 112),
            AnalysisMode.NORMAL to Expected(28, 900, 45, RunStatus.PARTIAL, 0.9, listOf("i00", "i01"), 92),
            AnalysisMode.DEEP to Expected(120, 2740, 137, RunStatus.COMPLETED, 0.5, listOf("i14", "i15"), 0),
        )
        for ((mode, run) in runs) {
            assertSame(pool, run.batch.candidates)
            assertEquals(mode, run.context.analysisMode)
            assertEquals(mode.limits, run.result.diagnostics.limits)
            assertResult(expected.getValue(mode), run)
            assertSavedExportDoesNoWork(run)
        }
        val fastCalls = runs.getValue(AnalysisMode.FAST).evaluator.calls
        val normalCalls = runs.getValue(AnalysisMode.NORMAL).evaluator.calls
        val deepCalls = runs.getValue(AnalysisMode.DEEP).evaluator.calls
        assertEquals(fastCalls, normalCalls.take(fastCalls.size))
        assertEquals(normalCalls, deepCalls.take(normalCalls.size))
        assertEquals(listOf("i14", "i15"), deepCalls.last())
    }

    @Test
    fun `deeper analysis can spend more work without improving quality`() {
        val pool = candidates()
        val runs = AnalysisMode.entries.map { mode -> run(mode, pool) { 80.0 } }

        for (run in runs) {
            assertSame(pool, run.batch.candidates)
            assertEquals(listOf("i00", "i01"), run.result.selected?.evaluation?.candidateIds)
            assertEquals(0.8, run.result.selected?.metrics?.meanRatio)
            assertEquals(0.8, run.result.selected?.metrics?.worstRatio)
            assertEquals(run.result.bestRobust, run.result.bestMean)
        }
        assertEquals(listOf(500, 900, 2740), runs.map { it.context.statistics().explainAttempts })
        assertEquals(listOf(8, 28, 120), runs.map { it.context.statistics().repairAttempts })
        assertEquals(listOf(RunStatus.PARTIAL, RunStatus.PARTIAL, RunStatus.COMPLETED),
            runs.map { it.result.status })
    }

    private fun assertResult(expected: Expected, run: ModeRun) {
        val result = run.result
        assertEquals(expected.status, result.status)
        assertEquals(expected.selectedIds, result.selected?.evaluation?.candidateIds)
        assertEquals(expected.ratio, result.selected?.metrics?.meanRatio)
        assertEquals(expected.ratio, result.selected?.metrics?.worstRatio)
        assertEquals(result.bestRobust, result.bestMean)
        assertSame(run.baseline, result.baseline)
        assertSame(run.evaluator.results.getValue(expected.selectedIds), result.selected?.evaluation)
        assertEquals(RunStatistics(
            explainAttempts = expected.explainAttempts,
            evaluationAttempts = expected.evaluations,
            completedEvaluations = expected.evaluations,
            repairAttempts = expected.repairAttempts,
        ), run.context.statistics())
        assertEquals(run.context.statistics(), result.diagnostics.statistics)
        assertEquals(expected.unexamined.toLong(), result.diagnostics.unexaminedConfigurations)
        assertEquals(expected.evaluations, run.evaluator.calls.size)
        assertEquals(run.evaluator.calls.size, run.evaluator.calls.distinct().size)
        // An improvement must not restart repair around the newly found pair.
        assertTrue(run.evaluator.calls.all { it.size <= 2 })
        val queryIds = run.workload.input.queries.map { it.id }.toSet()
        for (evaluation in run.evaluator.results.values) {
            assertEquals(queryIds, evaluation.costByQueryId.keys)
            assertEquals(queryIds, evaluation.plansByQueryId.keys)
        }
        assertTrue(AnalysisPhase.GREEDY in result.diagnostics.completedPhases)
        if (expected.status == RunStatus.PARTIAL) {
            assertTrue(StopReason.REPAIR_LIMIT in result.diagnostics.stopReasons)
            assertTrue(AnalysisPhase.REPAIR !in result.diagnostics.completedPhases)
        } else {
            assertTrue(StopReason.SEARCH_COMPLETE in result.diagnostics.stopReasons)
            assertTrue(AnalysisPhase.REPAIR in result.diagnostics.completedPhases)
        }
    }

    private fun assertSavedExportDoesNoWork(run: ModeRun) {
        val beforeStatistics = run.context.statistics()
        val beforeCalls = run.evaluator.calls.toList()
        val report = DefaultReportBuilder().build(run.workload.input, run.batch, run.result, run.context)
        val json = JsonMapper.builder().build().readTree(JsonReportWriter().render(report))
        val html = HtmlReportRenderer().render(report)

        assertSame(run.result, report.result)
        assertSame(run.baseline, report.result.baseline)
        assertSame(run.result.selected?.evaluation, report.result.selected?.evaluation)
        assertEquals(run.context.analysisMode.name.lowercase(), json.get("analysis_mode").stringValue())
        assertEquals(run.result.selected?.evaluation?.candidateIds?.joinToString(","),
            json.get("selected").get("plans_by_query_id").get("q00").get("fixture_set").stringValue())
        assertTrue(html.contains("q00"))
        assertTrue(html.contains("fixture_set"))
        assertEquals(beforeStatistics, run.context.statistics())
        assertEquals(beforeCalls, run.evaluator.calls)
    }

    private fun run(
        mode: AnalysisMode,
        pool: List<IndexCandidate>,
        pairCost: (List<String>) -> Double,
    ): ModeRun {
        val queries = (0 until 20).map { QueryInput("q%02d".format(it), "SELECT 1") }
        val input = WorkloadInput(queries,
            listOf(Profile("shared", 1.0, queries.associate { it.id to 1.0 })),
            Constraints(1_000_000, null, maxIndexes = 5), analysisMode = mode)
        val workload = ResolvedWorkload(input, queries.map {
            ResolvedQuery(it.id, it.sql, emptySet(), emptySet(), emptyList(), emptyList())
        })
        val context = RunContext(clock = NanoClock { 0L }, analysisMode = mode)
        context.startSearch()
        context.enterPhase(AnalysisPhase.BASELINE)
        val evaluator = CountingEvaluator(pairCost)
        val baseline = evaluator.evaluate(emptyList(), workload, context)
        context.completePhase()
        val batch = CandidateBatch(pool)
        val result = LimitedRepairOptimizer().optimize(workload, batch, baseline, evaluator,
            MetricsCalculatorImpl(), ObjectiveComparatorImpl(), context) {}
        return ModeRun(workload, batch, context, evaluator, baseline, result)
    }

    private fun candidates(): List<IndexCandidate> {
        val table = TableRef("public", "records")
        return (0 until 16).map { index ->
            IndexCandidate("i%02d".format(index),
                IndexDefinition(table, listOf(IndexKey(ColumnRef(table, "column_$index"),
                    operatorClass = "pg_catalog.int4_ops"))),
                setOf("q00"), setOf(GenerationReason.SINGLE_COLUMN), estimatedBytes = 1)
        }
    }

    private data class Expected(
        val repairAttempts: Int,
        val explainAttempts: Int,
        val evaluations: Int,
        val status: RunStatus,
        val ratio: Double,
        val selectedIds: List<String>,
        val unexamined: Int,
    )

    private data class ModeRun(
        val workload: ResolvedWorkload,
        val batch: CandidateBatch,
        val context: RunContext,
        val evaluator: CountingEvaluator,
        val baseline: EvaluationResult,
        val result: SearchResult,
    )

    private class CountingEvaluator(private val pairCost: (List<String>) -> Double) : ConfigurationEvaluator {
        val calls = mutableListOf<List<String>>()
        val results = linkedMapOf<List<String>, EvaluationResult>()

        override fun evaluate(
            candidates: List<IndexCandidate>,
            workload: ResolvedWorkload,
            context: RunContext,
        ): EvaluationResult {
            context.reserveEvaluationAttempt()
            val ids = candidates.map { it.id }.sorted()
            calls += ids
            val cost = if (ids.size == 2) pairCost(ids) else 100.0
            val costs = workload.queries.sortedBy { it.id }.associate { query ->
                context.reserveExplainAttempt()
                query.id to cost
            }
            val evaluation = EvaluationResult(ids, costs, costs.mapValues { (query, value) ->
                ExplainPlan("""{"fixture_set":"${ids.joinToString(",")}","query":"$query","cost":$value}""")
            })
            results[ids] = evaluation
            context.recordCompletedEvaluation()
            return evaluation
        }
    }
}
