package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class BudgetSeriesPipelineTest {
    @TempDir lateinit var directory: Path
    private val input = ContractFixtures.workload().copy(analysisMode = AnalysisMode.FAST,
        candidateRanking = CandidateRanking.BASELINE_COST)
    private val reference = SeriesConditions("catalog-a", mapOf("q" to 100.0), "plans-a", DatabaseVersions("16.15", "1.4.2"), "source-a")
    private val noShutdown = ShutdownCancellation { AutoCloseable {} }

    @Test
    fun `a fixed pool rejects changed critical query guards`() {
        val changed = input.copy(constraints = input.constraints.copy(queryCostGuards = mapOf("records_by_group" to 1.25)))
        val failure = assertThrows(AnalysisException::class.java) {
            CandidatePreparation(MetricsCalculatorImpl()).selectFixedPool(preparation(input).pool, changed, RunContext())
        }
        assertEquals(ErrorCode.INVALID_INPUT, failure.error.code)
    }

    @Test
    fun `critical query guards remain fixed and visible through the entire budget series`() {
        val guarded = input.copy(constraints = input.constraints.copy(queryCostGuards = mapOf("records_by_group" to 1.25)))
        val analysis = FakeAnalysis()
        val result = BudgetSeriesPipeline(analysis, noShutdown).run(guarded,
            request("1000000", "2000000", "3000000"), directory.resolve("out"), RunContext())
        assertEquals(SeriesStatus.COMPLETED, result.status)
        assertEquals(guarded.constraints.queryCostGuards, analysis.prepared!!.pool.input.constraints.queryCostGuards)
        assertTrue(analysis.pointInputs.all { it.constraints.queryCostGuards == guarded.constraints.queryCostGuards })
        assertEquals(1.25, summary()["settings"]["constraints"]["query_cost_guards"]["records_by_group"].doubleValue())
        assertEquals(1.25, summary()["preparation"]["maximum_budgets"]["query_cost_guards"]["records_by_group"].doubleValue())
    }

    @Test
    fun `different actual source and unknown source never appear as matched conditions`() {
        val analysis = FakeAnalysis()
        analysis.point = { _, output, _, _ ->
            publish(output)
            val observed = when (analysis.pointInputs.size) {
                1 -> reference
                2 -> reference.copy(sourceFingerprint = "source-b")
                else -> reference.copy(sourceFingerprint = null)
            }
            SeriesPointRun(ContractFixtures.completedResult(), observed)
        }
        val result = run(analysis)
        assertEquals(SeriesStatus.PARTIAL, result.status)
        assertEquals(listOf(true, false, null), result.points.map { it.conditionsMatch })
        assertEquals(listOf("source"), result.points[1].changes)
        assertEquals(listOf("source_unavailable"), result.points[2].changes)
    }

    @Test
    fun `observed workload source survives common preparation every point and series manifest`() {
        val imported = WorkloadReader().read(Path.of("examples/workload-v2-provenance.json"))
        val analysis = FakeAnalysis()
        val result = BudgetSeriesPipeline(analysis, noShutdown).run(imported,
            request("1000000", "2000000", "3000000"), directory.resolve("out"), RunContext())
        assertEquals(SeriesStatus.COMPLETED, result.status)
        assertEquals(imported.provenance, analysis.prepared!!.pool.input.provenance)
        assertTrue(analysis.pointInputs.all { it.provenance == imported.provenance })
        assertEquals("pg_stat_statements", summary()["source"]["workload_source"].stringValue())
        assertEquals(imported.provenance!!.sourceId.toString(), summary()["source"]["workload_source_id"].stringValue())
    }

    @Test
    fun `unsorted budgets prepare once at maximum and points keep one pool with independent state`() {
        val context = RunContext().also { it.recordInputHash("a".repeat(64)) }
        val contexts = mutableListOf<RunContext>()
        val analysis = FakeAnalysis()
        analysis.prepare = { maximum, child ->
            assertEquals(2_000_000L, maximum.constraints.storageBudgetBytes)
            assertEquals(input.copy(constraints = maximum.constraints), maximum)
            assertEquals(input.constraints.writeBudgetUnitsPerHour, maximum.constraints.writeBudgetUnitsPerHour)
            assertEquals(input.constraints.maxIndexes, maximum.constraints.maxIndexes)
            contexts += child
            assertEquals(RunStatistics(), child.statistics())
            child.reserveEvaluationAttempt()
            child.reserveExplainAttempt()
            child.recordCompletedEvaluation()
            preparation(maximum)
        }
        analysis.point = { at, output, child, pool ->
            assertSame(analysis.prepared!!.pool, pool)
            assertEquals(input.copy(constraints = at.constraints), at)
            assertEquals(RunStatistics(), child.statistics())
            assertEquals("a".repeat(64), child.inputHash)
            assertEquals(AnalysisMode.FAST, child.analysisMode)
            assertEquals(AnalysisMode.FAST.limits, child.limits)
            contexts += child
            child.reserveEvaluationAttempt()
            child.reserveExplainAttempt()
            child.recordCompletedEvaluation()
            child.recordCacheHit()
            publish(output)
            SeriesPointRun(ContractFixtures.completedResult().let { it.copy(diagnostics = it.diagnostics.copy(statistics = child.statistics())) }, reference)
        }
        val result = BudgetSeriesPipeline(analysis, noShutdown).run(input,
            request("2000000", "0", "1000000"), directory.resolve("out"), context)

        assertEquals(SeriesStatus.COMPLETED, result.status)
        assertEquals(0, result.exitCode)
        assertEquals(1, analysis.prepareCalls)
        assertEquals(listOf(2_000_000L, 0L, 1_000_000L), analysis.pointInputs.map { it.constraints.storageBudgetBytes })
        assertEquals(4, contexts.map { it.runId }.distinct().size)
        assertTrue(contexts.all { it !== context })
        assertEquals(RunStatistics(), context.statistics())
        assertEquals(listOf(1, 1, 1), result.points.map { it.result!!.diagnostics.statistics.explainAttempts })
        val summary = summary()
        assertEquals("completed", summary["status"].stringValue())
        assertEquals("fixed_candidate_pool", summary["comparison_scope"].stringValue())
        assertEquals("a".repeat(64), summary["source"]["workload_input_hash"].stringValue())
        assertEquals(2_000_000L, summary["preparation"]["maximum_budgets"]["storage_budget_bytes"].longValue())
        for ((index, point) in result.points.withIndex()) {
            assertEquals(3, point.artifacts.size)
            point.artifacts.values.forEach { relative ->
                assertFalse(Path.of(relative).isAbsolute)
                assertTrue(Files.isRegularFile(directory.resolve("out").resolve(relative)))
            }
            assertEquals("point-%02d/report.json".format(index + 1), point.artifacts["report.json"])
        }
    }

    @Test
    fun `write series changes only write budget and prepares at its largest value`() {
        val analysis = FakeAnalysis()
        analysis.prepare = { maximum, _ ->
            assertEquals(5000.0, maximum.constraints.writeBudgetUnitsPerHour)
            assertEquals(input.constraints.storageBudgetBytes, maximum.constraints.storageBudgetBytes)
            preparation(maximum)
        }
        val request = BudgetSeriesRequest(SeriesBudget.WRITE, listOf("5000", "0", "1200.5").map(::BigDecimal))
        val result = BudgetSeriesPipeline(analysis, noShutdown).run(input, request, directory.resolve("out"), RunContext())
        assertEquals(SeriesStatus.COMPLETED, result.status)
        assertEquals(listOf(5000.0, 0.0, 1200.5), analysis.pointInputs.map { it.constraints.writeBudgetUnitsPerHour })
        assertTrue(analysis.pointInputs.all { it.constraints.storageBudgetBytes == input.constraints.storageBudgetBytes })
        assertTrue(analysis.pointInputs.all { it.copy(constraints = input.constraints) == input })
        assertEquals("write_budget_units_per_hour", summary()["budget"].stringValue())
    }

    @Test
    fun `failed middle point preserves prior summary and still starts next point`() {
        val analysis = FakeAnalysis()
        analysis.point = { _, output, _, _ ->
            when (analysis.pointInputs.size) {
                1 -> { publish(output); SeriesPointRun(ContractFixtures.completedResult(), reference) }
                2 -> {
                    assertEquals("completed", summary()["points"][0]["status"].stringValue())
                    assertTrue(Files.exists(directory.resolve("out/point-01/indexes.sql")))
                    throw AnalysisException(AnalysisError(ErrorCode.DB_CONNECTION_ERROR, "Connection lost."))
                }
                else -> {
                    assertEquals("failed", summary()["points"][1]["status"].stringValue())
                    assertEquals("DB_CONNECTION_ERROR", summary()["points"][1]["error"]["code"].stringValue())
                    publish(output)
                    SeriesPointRun(ContractFixtures.completedResult(), reference)
                }
            }
        }
        val result = run(analysis)
        assertEquals(SeriesStatus.PARTIAL, result.status)
        assertEquals(3, result.exitCode)
        assertEquals(listOf(SeriesPointStatus.COMPLETED, SeriesPointStatus.FAILED, SeriesPointStatus.COMPLETED), result.points.map { it.status })
        assertEquals(3, analysis.pointInputs.size)
        assertEquals("partial", summary()["status"].stringValue())
        assertEquals("synthetic SQL", Files.readString(directory.resolve("out/point-01/indexes.sql")))
    }

    @Test
    fun `parent cancellation during a point stops successors without erasing published points`() {
        val parent = RunContext()
        val analysis = FakeAnalysis()
        analysis.point = { _, output, child, _ ->
            if (analysis.pointInputs.size == 2) {
                assertEquals("completed", summary()["points"][0]["status"].stringValue())
                parent.cancel()
                child.checkDeadline()
                error("Cancelled child must stop")
            }
            publish(output)
            SeriesPointRun(ContractFixtures.completedResult(), reference)
        }
        val result = run(analysis, parent)
        assertEquals(SeriesStatus.PARTIAL, result.status)
        assertEquals(2, analysis.pointInputs.size)
        assertEquals(SeriesPointStatus.SKIPPED, result.points[2].status)
        assertEquals(ErrorCode.CANCELLED, result.points[1].error!!.code)
        assertEquals(ErrorCode.CANCELLED, result.points[2].error!!.code)
        assertTrue(Files.isRegularFile(directory.resolve("out/point-01/report.html")))
        assertFalse(Files.exists(directory.resolve("out/point-03")))
    }

    @Test
    fun `shared deadline caps active child and skips remaining points`() {
        var nanos = 0L
        val parent = RunContext(clock = NanoClock { nanos })
        val analysis = FakeAnalysis()
        analysis.point = { _, output, child, _ ->
            if (analysis.pointInputs.size == 2) {
                nanos = Duration.ofSeconds(3).toNanos()
                assertEquals(Duration.ZERO, child.remainingTime())
                child.checkDeadline()
                error("Expired child must stop")
            }
            publish(output)
            SeriesPointRun(ContractFixtures.completedResult(), reference)
        }
        val result = BudgetSeriesPipeline(analysis, noShutdown).run(input,
            request("1000000", "2000000", "3000000").copy(timeoutSeconds = 2), directory.resolve("out"), parent)
        assertEquals(SeriesStatus.PARTIAL, result.status)
        assertEquals(2, analysis.pointInputs.size)
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, result.points[1].error!!.code)
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, result.points[2].error!!.code)
        assertEquals("skipped", summary()["points"][2]["status"].stringValue())
        assertTrue(Files.isRegularFile(directory.resolve("out/point-01/report.json")))
    }

    @Test
    fun `preparation failure publishes all skipped points and closes cancellation registration`() {
        var registrationClosed = false
        val shutdown = ShutdownCancellation { AutoCloseable { registrationClosed = true } }
        val analysis = FakeAnalysis()
        analysis.prepare = { _, _ -> throw AnalysisException(AnalysisError(ErrorCode.DB_CONNECTION_ERROR, "Preparation connection lost.")) }
        val result = BudgetSeriesPipeline(analysis, shutdown).run(input,
            request("0", "1000000", "2000000"), directory.resolve("out"), RunContext())
        assertTrue(registrationClosed)
        assertEquals(SeriesStatus.FAILED, result.status)
        assertEquals(ErrorCode.DB_CONNECTION_ERROR, result.preparationError!!.code)
        assertTrue(result.points.all { it.status == SeriesPointStatus.SKIPPED && it.runId == null })
        assertEquals(0, analysis.pointInputs.size)
        assertEquals("failed", summary()["status"].stringValue())
        assertEquals("DB_CONNECTION_ERROR", summary()["preparation"]["error"]["code"].stringValue())
    }

    @Test
    fun `catalog baseline plans and versions drift make completed points noncomparable`() {
        val analysis = FakeAnalysis()
        analysis.point = { _, output, _, _ ->
            publish(output)
            SeriesPointRun(ContractFixtures.completedResult(), when (analysis.pointInputs.size) {
                1 -> reference
                2 -> reference.copy(catalogHash = "catalog-b", baselineCosts = mapOf("q" to 101.0),
                    baselinePlansHash = "plans-b", databaseVersions = DatabaseVersions("16.16", "1.4.3"))
                else -> null
            })
        }
        val result = run(analysis)
        assertEquals(SeriesStatus.PARTIAL, result.status)
        assertEquals(listOf(true, false, null), result.points.map { it.conditionsMatch })
        assertEquals(listOf("catalog", "baseline_costs", "baseline_plans", "database_versions"), result.points[1].changes)
        assertEquals(listOf("conditions_unavailable"), result.points[2].changes)
        assertTrue(result.points.all { it.status == SeriesPointStatus.COMPLETED })
        assertFalse(summary()["points"][1]["conditions_match"].booleanValue())
    }

    @Test
    fun `point export failure and unexpected exception preserve earlier output with safe diagnostics`() {
        val analysis = FakeAnalysis()
        analysis.point = { _, output, _, _ ->
            when (analysis.pointInputs.size) {
                1 -> { publish(output); SeriesPointRun(ContractFixtures.completedResult(), reference) }
                2 -> throw AnalysisException(AnalysisError(ErrorCode.EXPORT_FAILED, "Cannot publish analysis artifacts."))
                else -> throw IllegalStateException("jdbc:postgresql://private-host/db?password=private-password")
            }
        }
        val result = run(analysis)
        assertEquals(SeriesStatus.PARTIAL, result.status)
        assertEquals(ErrorCode.EXPORT_FAILED, result.points[1].error!!.code)
        assertEquals(ErrorCode.INTERNAL_ERROR, result.points[2].error!!.code)
        val json = Files.readString(directory.resolve("out/series.json"))
        assertFalse(json.contains("private-password"))
        assertFalse(json.contains("private-host"))
        assertTrue(Files.isRegularFile(directory.resolve("out/point-01/indexes.sql")))
    }

    private fun run(analysis: FakeAnalysis, context: RunContext = RunContext()): BudgetSeriesResult =
        BudgetSeriesPipeline(analysis, noShutdown).run(input, request("1000000", "2000000", "3000000"), directory.resolve("out"), context)

    private fun request(vararg values: String) = BudgetSeriesRequest(SeriesBudget.STORAGE, values.map(::BigDecimal))

    private fun preparation(maximum: WorkloadInput): SeriesPreparation {
        val candidates = ContractFixtures.candidates()
        return SeriesPreparation(PreparedCandidatePool(maximum, CandidateBatch(candidates), candidates), reference)
    }

    private fun publish(output: Path) {
        Files.createDirectories(output)
        Files.writeString(output.resolve("report.json"), "{}")
        Files.writeString(output.resolve("report.html"), "<html>synthetic</html>")
        Files.writeString(output.resolve("indexes.sql"), "synthetic SQL")
    }

    private fun summary(): JsonNode = JsonMapper.builder().build().readTree(Files.readString(directory.resolve("out/series.json")))

    private inner class FakeAnalysis : SeriesAnalysisRunner {
        var prepareCalls = 0
        var prepared: SeriesPreparation? = null
        val pointInputs = mutableListOf<WorkloadInput>()
        var prepare: (WorkloadInput, RunContext) -> SeriesPreparation = { maximum, _ -> preparation(maximum) }
        var point: (WorkloadInput, Path, RunContext, PreparedCandidatePool) -> SeriesPointRun = { _, output, _, _ ->
            publish(output)
            SeriesPointRun(ContractFixtures.completedResult(), reference)
        }

        override fun prepareSeriesPool(input: WorkloadInput, context: RunContext): SeriesPreparation {
            prepareCalls++
            return prepare(input, context).also { prepared = it }
        }

        override fun runSeriesPoint(input: WorkloadInput, outputDirectory: Path, context: RunContext, pool: PreparedCandidatePool): SeriesPointRun {
            pointInputs += input
            return point(input, outputDirectory, context, pool)
        }
    }
}
