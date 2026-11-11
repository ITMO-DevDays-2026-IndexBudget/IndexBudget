package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.candidates.CandidateGenerator
import ru.itmo.indexbudget.candidates.PostgresDdlRenderer
import ru.itmo.indexbudget.cli.IndexBudgetCli
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.optimizer.GreedyOptimizer
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.postgres.AnalysisSessionFactory
import ru.itmo.indexbudget.report.AtomicReportExporter
import ru.itmo.indexbudget.report.HtmlReportRenderer
import tools.jackson.databind.node.ObjectNode
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.ReportBuilder
import ru.itmo.indexbudget.report.ReportRenderer
import ru.itmo.indexbudget.sql.ParsedWorkload
import ru.itmo.indexbudget.sql.SqlAnalyzer
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

/** Production CLI/pipeline/search/metrics/report ports, with a scripted database and renderer seam. */
class ReportIntegrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `completed artifacts share the selected objective run hash and saved result`() {
        for (objective in Objective.entries) {
            val run = execute("completed-${objective.name}", objective = objective)
            assertEquals(0, run.exitCode, run.stderr)
            val report = requireNotNull(run.rendered)
            assertSame(run.built, report)
            assertEquals(RunStatus.COMPLETED, report.result.status)
            assertEquals(objective, report.result.selectedObjective)
            assertEquals("16.test", report.environment.postgresVersion)
            assertEquals("1.test", report.environment.hypopgVersion)
            assertEquals(1, run.versionReads)
            assertEquals(3, run.evaluationCalls)
            assertEquals(6, report.result.diagnostics.statistics.explainAttempts)
            assertTrue(run.closed)

            val json = mapper.readTree(Files.readString(run.output.resolve("report.json")))
            assertEquals(run.context.runId, json.get("run_id").stringValue())
            assertEquals(run.context.inputHash, json.get("input_hash").stringValue())
            assertEquals("completed", json.get("status").stringValue())
            assertEquals(objective.name.lowercase(), json.get("selected_objective").stringValue())
            assertEquals("evaluated_sets", json.get("comparison_scope").stringValue())
            val expectedId = if (objective == Objective.ROBUST) "records_created_at" else "records_group_created_at"
            assertEquals(listOf(expectedId), report.result.selected!!.evaluation.candidateIds)
            assertEquals(listOf(expectedId), json.get("selected").get("candidate_ids").toList().map { it.stringValue() })
            assertEquals(listOf(expectedId), json.get("selected_indexes").toList().map { it.get("id").stringValue() })
            for ((queryId, plan) in report.result.selected!!.evaluation.plansByQueryId) {
                assertEquals(mapper.readTree(plan.json), json.get("selected").get("plans_by_query_id").get(queryId))
            }
            val sql = Files.readString(run.output.resolve("indexes.sql"))
            assertTrue(sql.contains("-- run_id: ${run.context.runId}"))
            assertTrue(sql.contains("-- input_hash: ${run.context.inputHash}"))
            for (candidate in report.candidates.candidates) {
                assertEquals(candidate.id == expectedId, sql.contains(PostgresDdlRenderer().render(candidate.definition)))
            }
            assertEquals("renderer:${run.context.runId}", Files.readString(run.output.resolve("report.html")))
            assertEquals(setOf("report.json", "report.html", "indexes.sql"), fileNames(run.output))
        }
    }

    @Test
    fun `failed later evaluation saves partial after closing the session and returns three`() {
        val run = execute("partial", failEvaluation = 3)
        assertEquals(3, run.exitCode, run.stderr)
        val report = requireNotNull(run.rendered)
        assertSame(run.built, report)
        assertEquals(RunStatus.PARTIAL, report.result.status)
        assertEquals(listOf("records_created_at"), report.result.selected!!.evaluation.candidateIds)
        assertTrue(StopReason.EVALUATION_FAILED in report.result.diagnostics.stopReasons)
        assertEquals(3, run.evaluationCalls)
        assertEquals(2, report.result.diagnostics.statistics.completedEvaluations)
        assertTrue(run.closed)
        assertEquals(setOf("report.json", "report.html", "indexes.sql"), fileNames(run.output))
    }

    @Test
    fun `failed baseline exports diagnostics without recommendations and returns one`() {
        val run = execute("failed", failEvaluation = 1)
        assertEquals(1, run.exitCode, run.stderr)
        val report = requireNotNull(run.rendered)
        assertEquals(RunStatus.FAILED, report.result.status)
        assertNull(report.result.baseline)
        assertNull(report.result.selected)
        assertEquals(1, run.evaluationCalls)
        assertTrue(run.closed)
        assertEquals(setOf("report.json", "report.html"), fileNames(run.output))
        val json = mapper.readTree(Files.readString(run.output.resolve("report.json")))
        assertEquals("failed", json.get("status").stringValue())
        assertTrue(run.stderr.contains("EXPLAIN_FAILED:"))
    }

    @Test
    fun `renderer failure cannot return completed or leak its exception`() {
        val run = execute("renderer-failure", renderFailure = true)
        assertEquals(1, run.exitCode, run.stderr)
        assertTrue(run.stderr.contains("EXPORT_FAILED:"))
        assertFalse(run.stderr.contains("private-password"))
        assertTrue(run.closed)
        assertFalse(Files.exists(run.output))
        assertFalse(AnalysisPhase.REPORT in run.context.completedPhases())
        assertNoStagingDirectories()
    }

    @Test
    fun `destination occupied during rendering preserves the other run and returns one`() {
        val run = execute("occupied", occupyOutput = true)
        assertEquals(1, run.exitCode, run.stderr)
        assertTrue(run.stderr.contains("EXPORT_FAILED:"))
        assertFalse(run.stderr.contains("OUTPUT_DIRECTORY_NOT_EMPTY:"))
        assertTrue(run.closed)
        assertEquals(setOf("another-run.sql"), fileNames(run.output))
        assertEquals("other run", Files.readString(run.output.resolve("another-run.sql")))
        assertNoStagingDirectories()
    }

    @Test
    fun `CLI carries provenance and shared summary to JSON and HTML without additional EXPLAIN`() {
        val run = execute("provenance", withProvenance = true)
        assertEquals(0, run.exitCode, run.stderr)
        assertTrue(run.closed)
        assertEquals(3, run.evaluationCalls)
        assertEquals(6, run.context.statistics().explainAttempts)
        val report = requireNotNull(run.rendered)
        assertSame(run.built, report)
        val json = mapper.readTree(Files.readString(run.output.resolve("report.json")))
        assertEquals(report.summary.meanEffect.gainPercent, json["summary"]["mean_effect"]["gain_percent"].doubleValue())
        assertEquals(report.summary.worstRatio, json["summary"]["worst_ratio"].doubleValue())
        assertEquals(report.workloadProvenance?.sourceId.toString(), json["workload_provenance"]["source_id"].stringValue())
        val html = Files.readString(run.output.resolve("report.html"))
        assertTrue(html.contains("pg_stat_statements"))
        assertTrue(html.contains("Снижение на 50%"))
        assertTrue(html.contains("Историческое время выполнения"))
    }

    private fun execute(
        name: String,
        objective: Objective = Objective.ROBUST,
        failEvaluation: Int? = null,
        renderFailure: Boolean = false,
        occupyOutput: Boolean = false,
        withProvenance: Boolean = false,
    ): Run {
        val run = Run(directory.resolve(name))
        val input = ContractFixtures.workload(objective).let {
            it.copy(constraints = it.constraints.copy(maxIndexes = 1))
        }
        val source = directory.resolve("$name.json")
        val document = mapper.readTree(workloadJson(input)) as ObjectNode
        if (withProvenance) {
            document.set("provenance", mapper.readTree(
                Files.readString(Path.of("examples/workload-v2-provenance.json")))["provenance"])
        }
        Files.writeString(source, mapper.writeValueAsString(document))
        val canonicalCandidates = ContractFixtures.candidates().map { candidate ->
            candidate.copy(definition = candidate.definition.copy(keys = candidate.definition.keys.map { key ->
                key.copy(operatorClass = if (key.column.name == "created_at")
                    "pg_catalog.timestamp_ops" else "pg_catalog.int4_ops")
            }))
        }
        val session = object : AnalysisSession {
            override val databaseVersions: DatabaseVersions
                get() {
                    assertFalse(run.closed)
                    run.versionReads++
                    return DatabaseVersions("16.test", "1.test")
                }

            override fun readCatalog(tables: Set<TableName>, context: RunContext) = ContractFixtures.catalog()

            override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long {
                assertFalse(run.closed)
                return requireNotNull(ContractFixtures.candidates().single { it.id == candidate.id }.estimatedBytes)
            }

            override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload,
                                  context: RunContext): EvaluationResult {
                assertFalse(run.closed, "Export must never reevaluate saved results")
                run.evaluationCalls++
                context.reserveEvaluationAttempt()
                if (run.evaluationCalls == failEvaluation) {
                    context.reserveExplainAttempt()
                    throw AnalysisException(AnalysisError(ErrorCode.EXPLAIN_FAILED, "Synthetic EXPLAIN failure."))
                }
                workload.queries.forEach { context.reserveExplainAttempt() }
                val result = when (candidates.singleOrNull()?.id) {
                    null -> ContractFixtures.baselineEvaluation()
                    "records_created_at" -> ContractFixtures.robustEvaluation()
                    else -> ContractFixtures.meanEvaluation()
                }
                context.recordCompletedEvaluation()
                return result
            }

            override fun cancel() = Unit
            override fun close() { run.closed = true }
        }
        val builder = DefaultReportBuilder()
        val pipeline = AnalysisPipeline(
            sqlAnalyzer = object : SqlAnalyzer {
                override fun parse(input: WorkloadInput, context: RunContext) = object : ParsedWorkload {
                    override val input = input
                    override val referencedTables = setOf(TableName("records", "public"))
                }
                override fun resolve(parsed: ParsedWorkload, catalog: CatalogSnapshot, context: RunContext) =
                    ContractFixtures.resolvedWorkload(objective).copy(input = parsed.input)
            },
            sessionFactory = object : AnalysisSessionFactory {
                override fun open(context: RunContext): AnalysisSession = session
            },
            candidateGenerator = object : CandidateGenerator {
                override fun generate(workload: ResolvedWorkload, catalog: CatalogSnapshot, context: RunContext) =
                    CandidateBatch(canonicalCandidates)
            },
            metrics = MetricsCalculatorImpl(), comparator = ObjectiveComparatorImpl(), optimizer = GreedyOptimizer(),
            reportBuilder = ReportBuilder { workload, candidates, result, context ->
                assertTrue(run.closed)
                builder.build(workload, candidates, result, context).also { run.built = it }
            },
            reportExporter = AtomicReportExporter(object : ReportRenderer {
                override fun render(report: AnalysisReport): String {
                    assertTrue(run.closed)
                    run.rendered = report
                    if (renderFailure) throw IOException("postgresql://user:private-password@host/database")
                    if (occupyOutput) {
                        Files.createDirectories(run.output)
                        Files.writeString(run.output.resolve("another-run.sql"), "other run")
                    }
                    return if (withProvenance) HtmlReportRenderer().render(report) else "renderer:${report.runId}"
                }
            }),
            shutdownCancellation = ShutdownCancellation { AutoCloseable {} },
        )
        val stderr = StringWriter()
        run.exitCode = IndexBudgetCli(WorkloadReader(), analysisRunner = pipeline).execute(
            arrayOf("analyze", "--workload", source.toString(), "--output-dir", run.output.toString()),
            out = PrintWriter(StringWriter()), err = PrintWriter(stderr, true), context = run.context,
        )
        run.stderr = stderr.toString()
        return run
    }

    private fun workloadJson(input: WorkloadInput): String = mapper.writeValueAsString(mapOf(
        "schema_version" to 2, "objective" to input.objective.name.lowercase(),
        "queries" to input.queries.map { mapOf("id" to it.id, "sql" to it.sql) },
        "profiles" to input.profiles.map { mapOf("id" to it.id, "weight" to it.weight, "calls_per_hour" to it.callsPerHour) },
        "constraints" to mapOf("storage_budget_bytes" to input.constraints.storageBudgetBytes,
            "write_budget_units_per_hour" to input.constraints.writeBudgetUnitsPerHour, "max_indexes" to 1),
        "table_write_rates" to input.tableWriteRates?.mapValues { (_, rate) -> mapOf(
            "inserts_per_hour" to rate.insertsPerHour, "updates_per_hour" to rate.updatesPerHour,
            "deletes_per_hour" to rate.deletesPerHour, "source" to "manual") },
    ))

    private fun fileNames(path: Path): Set<String> = Files.list(path).use { entries ->
        entries.map { it.fileName.toString() }.toList().toSet()
    }

    private fun assertNoStagingDirectories() {
        assertFalse(fileNames(directory).any { it.startsWith(".indexbudget-") })
    }

    private class Run(val output: Path) {
        val context = RunContext()
        var exitCode = -1
        var stderr = ""
        var closed = false
        var versionReads = 0
        var evaluationCalls = 0
        var built: AnalysisReport? = null
        var rendered: AnalysisReport? = null
    }

    companion object {
        private val mapper = JsonMapper.builder().build()
    }
}
