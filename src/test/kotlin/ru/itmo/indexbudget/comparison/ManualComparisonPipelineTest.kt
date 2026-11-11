package ru.itmo.indexbudget.comparison

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import ru.itmo.indexbudget.application.*
import ru.itmo.indexbudget.candidates.CandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.postgres.AnalysisSessionFactory
import ru.itmo.indexbudget.report.*
import ru.itmo.indexbudget.sql.*
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class ManualComparisonPipelineTest {
    @TempDir lateinit var directory: Path

    @Test fun `better manual set outside shortlist preserves both automatic incumbents and exported SQL`() {
        val control = Harness(manualEnabled = false)
        val expected = control.execute(directory.resolve("control"))
        val run = Harness()
        val result = run.execute(directory.resolve("manual"))
        assertAutomaticResults(expected, result)
        assertNotEquals(result.bestRobust, result.bestMean)
        val report = run.report!!
        assertEquals(control.report!!.priceOfRobustness, report.priceOfRobustness)
        assertEquals(control.report!!.summary, report.summary)
        assertEquals(control.report!!.profileComparisons, report.profileComparisons)
        assertEquals(control.report!!.queryEffects, report.queryEffects)
        val manual = report.manualComparison!!
        assertTrue(manual.admissible)
        assertTrue(manual.metrics!!.meanCost < result.bestMean!!.metrics.meanCost)
        assertTrue(manual.metrics.worstRatio!! < result.bestRobust!!.metrics.worstRatio!!)
        assertTrue(manual.candidates.none { candidate -> report.candidates.candidates.any { it.id == candidate.id } })
        val sql = Files.readString(directory.resolve("manual/indexes.sql"))
        report.candidates.candidates.filter { it.id in result.selected!!.evaluation.candidateIds }.forEach {
            assertTrue(sql.contains(it.ddl!!))
        }
        manual.candidates.forEach { assertFalse(sql.contains(it.ddl!!)) }
        val json = JsonMapper().readTree(Files.readString(directory.resolve("manual/report.json")))
        assertEquals("completed", json["manual_comparison"]["status"].stringValue())
        assertEquals(JsonMapper().readTree(Files.readString(directory.resolve("control/report.json")))["price_of_robustness"],
            json["price_of_robustness"])
        assertEquals(1, run.manualEvaluations)
        assertEquals(8, result.diagnostics.statistics.explainAttempts)
        assertEquals(4, result.diagnostics.statistics.completedEvaluations)
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode::class, names = ["EXPLAIN_FAILED", "HYPOTHETICAL_INDEX_ERROR"])
    fun `local manual failure leaves automatic run completed`(code: ErrorCode) {
        val run = Harness()
        run.onManualQuery = { _, _ -> fail(code) }
        val result = run.execute(directory)
        assertEquals(RunStatus.COMPLETED, result.status)
        assertSavedAutomatic(run, result)
        assertNoManualCosts(run, code)
        assertEquals(7, result.diagnostics.statistics.explainAttempts)
        assertEquals(3, result.diagnostics.statistics.completedEvaluations)
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode::class, names = ["DEADLINE_EXCEEDED", "CANCELLED", "DB_CONNECTION_ERROR", "EXPLAIN_LIMIT"])
    fun `stop inside manual vector exports partial and saved automatic plans without another EXPLAIN`(code: ErrorCode) {
        val run = Harness()
        run.onManualQuery = { position, context ->
            if (position == 1) when (code) {
                ErrorCode.DEADLINE_EXCEEDED -> { run.advance(91); context.checkDeadline() }
                ErrorCode.CANCELLED -> { context.cancel(); context.checkDeadline() }
                else -> fail(code)
            }
        }
        val result = run.execute(directory)
        assertEquals(RunStatus.PARTIAL, result.status)
        assertSavedAutomatic(run, result)
        assertNoManualCosts(run, code)
        assertEquals(8, result.diagnostics.statistics.explainAttempts)
        assertEquals(3, result.diagnostics.statistics.completedEvaluations)
        assertTrue(Files.readString(directory.resolve("report.html")).contains(code.name))
        assertTrue(Files.readString(directory.resolve("indexes.sql")).contains("CREATE INDEX"))
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode::class, names = ["DB_CONNECTION_ERROR", "INTERNAL_ERROR"])
    fun `cleanup attached to local manual failure is propagated through pipeline`(cleanup: ErrorCode) {
        val run = Harness()
        run.onManualQuery = { _, _ ->
            throw AnalysisException(AnalysisError(ErrorCode.EXPLAIN_FAILED, "Synthetic query failure")).apply {
                addSuppressed(AnalysisException(AnalysisError(cleanup, "Synthetic cleanup failure")))
            }
        }
        val result = run.execute(directory)
        assertEquals(if (cleanup == ErrorCode.INTERNAL_ERROR) RunStatus.FAILED else RunStatus.PARTIAL, result.status)
        assertTrue(result.diagnostics.errors.any { it.code == cleanup })
        if (result.status == RunStatus.PARTIAL) assertSavedAutomatic(run, result)
        else assertFalse(Files.exists(directory.resolve("indexes.sql")))
        assertNoManualCosts(run, ErrorCode.EXPLAIN_FAILED)
    }

    @Test fun `deadline between manual size estimates preserves known sizes and unknown tail`() {
        val run = Harness(twoManualIndexes = true)
        run.onManualSize = { count -> if (count == 1) run.advance(91) }
        val result = run.execute(directory)
        assertEquals(RunStatus.PARTIAL, result.status)
        assertSavedAutomatic(run, result)
        assertNoManualCosts(run, ErrorCode.DEADLINE_EXCEEDED)
        val candidates = run.report!!.manualComparison!!.candidates
        assertNotNull(candidates[0].estimatedBytes)
        assertNotNull(candidates[0].budgetedBytes)
        assertNull(candidates[1].estimatedBytes)
        assertNull(candidates[1].budgetedBytes)
        assertNull(candidates[1].writeUnitsPerHour)
        assertEquals(1, run.manualSizes)
        assertEquals(0, run.manualEvaluations)
        assertEquals(6, result.diagnostics.statistics.explainAttempts)
    }

    @Test fun `unsupported manual key is invalid input before baseline`() {
        val run = Harness()
        run.catalog = run.catalog.copy(tables = run.catalog.tables.map { table ->
            table.copy(columns = table.columns.map { if (it.name == "value") it.copy(typeName = "json", defaultOperatorClass = null) else it })
        })
        val result = run.execute(directory)
        assertEquals(RunStatus.FAILED, result.status)
        val error = (result as SearchResult.Failed).error
        assertEquals(ErrorCode.INVALID_INPUT, error.code)
        assertEquals(2, error.code.defaultExitCode)
        assertEquals("manual_indexes.0.keys.0", error.details["field"])
        assertEquals(0, result.diagnostics.statistics.explainAttempts)
        assertEquals(1, run.closes)
    }

    private fun assertSavedAutomatic(run: Harness, result: SearchResult) {
        assertEquals(ContractFixtures.robustEvaluation(), result.bestRobust!!.evaluation)
        assertEquals(ContractFixtures.meanEvaluation(), result.bestMean!!.evaluation)
        assertEquals(result.bestRobust, result.selected)
        assertEquals(1, run.closes)
        assertTrue(run.exportedAfterClose)
    }

    private fun assertAutomaticResults(expected: SearchResult, actual: SearchResult) {
        assertEquals(expected.bestRobust, actual.bestRobust)
        assertEquals(expected.bestMean, actual.bestMean)
        assertEquals(expected.selected, actual.selected)
        assertEquals(expected.baseline, actual.baseline)
    }

    private fun assertNoManualCosts(run: Harness, code: ErrorCode) {
        val manual = run.report!!.manualComparison!!
        assertEquals(ManualComparisonStatus.FAILED, manual.status)
        assertEquals(code, manual.error!!.code)
        assertNull(manual.evaluation)
        assertNull(manual.metrics)
        assertTrue(manual.queryComparisons.isEmpty())
        assertFalse(manual.admissible)
        assertEquals(1, run.closes)
        assertTrue(run.exportedAfterClose)
    }

    private class Harness(manualEnabled: Boolean = true, twoManualIndexes: Boolean = false) {
        private var now = 0L
        val context = RunContext(clock = NanoClock { now }).apply { recordInputHash("a".repeat(64)) }
        private val definitions = listOf("value", "group_id").take(if (twoManualIndexes) 2 else 1).map {
            IndexDefinition(ContractFixtures.recordsTable, listOf(IndexKey(ColumnRef(ContractFixtures.recordsTable, it))))
        }
        val input = ContractFixtures.workload().let { it.copy(constraints = it.constraints.copy(maxIndexes = 1),
            manualIndexes = if (manualEnabled) definitions else null) }
        var catalog = ContractFixtures.catalog()
        var onManualQuery: (Int, RunContext) -> Unit = { _, _ -> }
        var onManualSize: (Int) -> Unit = {}
        var manualSizes = 0
        var manualEvaluations = 0
        var closes = 0
        var exportedAfterClose = false
        var report: AnalysisReport? = null
        private val automatic = ContractFixtures.candidates().map { it.copy(estimatedBytes = null, budgetedBytes = null, writeUnitsPerHour = null) }

        fun advance(seconds: Long) { now += Duration.ofSeconds(seconds).toNanos() }

        fun execute(output: Path): SearchResult {
            val analyzer = object : SqlAnalyzer {
                override fun parse(input: WorkloadInput, context: RunContext) = object : ParsedWorkload {
                    override val input = this@Harness.input
                    override val referencedTables = setOf(TableName("records", "public"))
                }
                override fun resolve(parsed: ParsedWorkload, catalog: CatalogSnapshot, context: RunContext) =
                    ContractFixtures.resolvedWorkload().copy(input = input)
            }
            val session = object : AnalysisSession {
                override fun readCatalog(tables: Set<TableName>, context: RunContext) = catalog
                override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long {
                    check(closes == 0)
                    if (candidate.id !in automatic.map { it.id }) { manualSizes++; onManualSize(manualSizes) }
                    return 100L
                }
                override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload, context: RunContext): EvaluationResult {
                    check(closes == 0)
                    val ids = candidates.map { it.id }
                    val isManual = ids.isNotEmpty() && ids.none { id -> automatic.any { it.id == id } }
                    if (isManual) manualEvaluations++
                    context.reserveEvaluationAttempt()
                    val costs = linkedMapOf<String, Double>()
                    input.queries.forEachIndexed { position, query ->
                        context.reserveExplainAttempt()
                        if (isManual) onManualQuery(position, context)
                        costs[query.id] = when {
                            ids.isEmpty() -> 100.0
                            isManual -> 1.0
                            ids.single() == "records_created_at" -> 50.0
                            position == 0 -> 10.0
                            else -> 100.0
                        }
                    }
                    context.recordCompletedEvaluation()
                    // Reuse saved fixture plans for automatic results, to verify their preservation.
                    return when {
                        ids.isEmpty() -> ContractFixtures.baselineEvaluation()
                        isManual -> EvaluationResult(ids, costs, costs.mapValues { ExplainPlan("[]") })
                        ids.single() == "records_created_at" -> ContractFixtures.robustEvaluation()
                        else -> ContractFixtures.meanEvaluation()
                    }
                }
                override fun cancel() = Unit
                override fun close() { closes++ }
            }
            val generator = object : CandidateGenerator {
                override fun generate(workload: ResolvedWorkload, catalog: CatalogSnapshot, context: RunContext) = CandidateBatch(automatic)
            }
            val exporter = AtomicReportExporter(HtmlReportRenderer())
            val pipeline = AnalysisPipeline(analyzer, object : AnalysisSessionFactory {
                override fun open(context: RunContext): AnalysisSession = session
            }, generator,
                MetricsCalculatorImpl(), ObjectiveComparatorImpl(), LimitedRepairOptimizer(), DefaultReportBuilder(),
                ReportExporter { value, path, ctx ->
                    val before = ctx.statistics()
                    report = value
                    exportedAfterClose = closes == 1
                    exporter.export(value, path, ctx)
                    assertEquals(before, ctx.statistics(), "Export must not issue EXPLAIN")
                }, shutdownCancellation = ShutdownCancellation { AutoCloseable {} })
            return pipeline.run(input, output, context)
        }
    }

    companion object {
        private fun fail(code: ErrorCode): Nothing = throw AnalysisException(AnalysisError(code, "Synthetic manual failure"))
    }
}
