package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.candidates.CandidateGenerator
import ru.itmo.indexbudget.candidates.PostgresDdlRenderer
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.postgres.AnalysisSessionFactory
import ru.itmo.indexbudget.report.AtomicReportExporter
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.ReportBuilder
import ru.itmo.indexbudget.report.ReportRenderer
import ru.itmo.indexbudget.sql.ParsedWorkload
import ru.itmo.indexbudget.sql.SqlAnalyzer
import java.nio.file.Files
import java.nio.file.Path

/** Full search and production export, with a scripted session and an HTML renderer seam. */
class LimitedRepairPipelineTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `failed workload vector preserves both incumbents and exports after closing the session`() {
        assertSavedResult(ErrorCode.EXPLAIN_FAILED, StopReason.EVALUATION_FAILED)
    }

    @Test
    fun `lost session stops search and exports both saved incumbents without new evaluations`() {
        assertSavedResult(ErrorCode.DB_CONNECTION_ERROR, StopReason.CONNECTION_LOST)
    }

    @Test
    fun `numeric cleanup failure after a failed query exports failed without recommendations`() {
        assertSavedResult(ErrorCode.EXPLAIN_FAILED, StopReason.EVALUATION_FAILED, ErrorCode.NUMERIC_ERROR)
    }

    @Test
    fun `failed query followed by lost cleanup session retains both stop reasons and saved results`() {
        assertSavedResult(ErrorCode.EXPLAIN_FAILED, StopReason.EVALUATION_FAILED, ErrorCode.DB_CONNECTION_ERROR)
    }

    private fun assertSavedResult(code: ErrorCode, reason: StopReason, cleanupCode: ErrorCode? = null) {
        val input = ContractFixtures.workload(withWriteProfile = false).let {
            it.copy(constraints = it.constraints.copy(maxIndexes = 1))
        }
        val candidates = ContractFixtures.candidates(withWriteProfile = false).let {
            it + it.first().copy(id = "z_failed", estimatedBytes = 1, budgetedBytes = 2)
        }
        var closed = false
        val calls = mutableListOf<List<String>>()
        var built: AnalysisReport? = null
        var rendered: AnalysisReport? = null
        val session = object : AnalysisSession {
            override fun readCatalog(tables: Set<TableName>, context: RunContext) = ContractFixtures.catalog()

            override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long {
                assertFalse(closed)
                return requireNotNull(candidates.single { it.id == candidate.id }.estimatedBytes)
            }

            override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload,
                                  context: RunContext): EvaluationResult {
                assertFalse(closed, "Export must use saved vectors and plans")
                val ids = candidates.map { it.id }.sorted()
                calls += ids
                context.reserveEvaluationAttempt()
                for ((index, query) in workload.queries.sortedBy { it.id }.withIndex()) {
                    context.reserveExplainAttempt()
                    if (ids == listOf("z_failed") && index == 1) {
                        val failure = AnalysisException(AnalysisError(code, "Synthetic second-query failure.", queryId = query.id))
                        if (cleanupCode != null) failure.addSuppressed(AnalysisException(
                            AnalysisError(cleanupCode, "Synthetic cleanup failure."),
                        ))
                        throw failure
                    }
                }
                val evaluation = when (ids.singleOrNull()) {
                    null -> ContractFixtures.baselineEvaluation()
                    "records_created_at" -> ContractFixtures.robustEvaluation()
                    "records_group_created_at" -> ContractFixtures.meanEvaluation()
                    else -> error("Unexpected fixture configuration")
                }
                context.recordCompletedEvaluation()
                return evaluation
            }

            override fun cancel() = Unit
            override fun close() { closed = true }
        }
        val builder = DefaultReportBuilder()
        val pipeline = AnalysisPipeline(
            sqlAnalyzer = object : SqlAnalyzer {
                override fun parse(input: WorkloadInput, context: RunContext) = object : ParsedWorkload {
                    override val input = input
                    override val referencedTables = setOf(TableName("records", "public"))
                }

                override fun resolve(parsed: ParsedWorkload, catalog: CatalogSnapshot, context: RunContext) =
                    ContractFixtures.resolvedWorkload(withWriteProfile = false).copy(input = parsed.input)
            },
            sessionFactory = object : AnalysisSessionFactory {
                override fun open(context: RunContext): AnalysisSession = session
            },
            candidateGenerator = object : CandidateGenerator {
                override fun generate(workload: ResolvedWorkload, catalog: CatalogSnapshot, context: RunContext) =
                    CandidateBatch(candidates)
            },
            metrics = MetricsCalculatorImpl(),
            comparator = ObjectiveComparatorImpl(),
            optimizer = LimitedRepairOptimizer(),
            reportBuilder = ReportBuilder { workload, batch, result, context ->
                assertTrue(closed)
                assertEquals(4, calls.size)
                builder.build(workload, batch, result, context).also { built = it }
            },
            reportExporter = AtomicReportExporter(object : ReportRenderer {
                override fun render(report: AnalysisReport): String {
                    assertTrue(closed)
                    assertEquals(4, calls.size)
                    rendered = report
                    return "renderer:${report.runId}"
                }
            }),
            shutdownCancellation = ShutdownCancellation { AutoCloseable {} },
        )
        val context = RunContext(clock = NanoClock { 0L }).also { it.recordInputHash("a".repeat(64)) }
        val output = directory.resolve(code.name.lowercase())

        val result = pipeline.run(input, output, context)

        val fatalCleanup = cleanupCode == ErrorCode.NUMERIC_ERROR
        if (fatalCleanup) {
            assertEquals(RunStatus.FAILED, result.status)
            assertEquals(ErrorCode.EXPLAIN_FAILED, (result as SearchResult.Failed).error.code)
            assertEquals(ContractFixtures.baselineEvaluation(), result.baseline)
            assertNull(result.selected)
            assertNull(result.bestRobust)
            assertNull(result.bestMean)
        } else {
            assertEquals(RunStatus.PARTIAL, result.status)
            assertTrue(reason in result.diagnostics.stopReasons)
            if (cleanupCode == ErrorCode.DB_CONNECTION_ERROR) {
                assertTrue(StopReason.CONNECTION_LOST in result.diagnostics.stopReasons)
            }
            assertEquals(listOf("records_created_at"), result.bestRobust!!.evaluation.candidateIds)
            assertEquals(listOf("records_group_created_at"), result.bestMean!!.evaluation.candidateIds)
            assertEquals(ContractFixtures.robustEvaluation().plansByQueryId, result.bestRobust!!.evaluation.plansByQueryId)
            assertEquals(ContractFixtures.meanEvaluation().plansByQueryId, result.bestMean!!.evaluation.plansByQueryId)
        }
        assertEquals(listOf(emptyList(), listOf("records_created_at"), listOf("records_group_created_at"),
            listOf("z_failed")), calls)
        assertEquals(8, result.diagnostics.statistics.explainAttempts)
        assertEquals(4, result.diagnostics.statistics.evaluationAttempts)
        assertEquals(3, result.diagnostics.statistics.completedEvaluations)
        assertTrue(result.diagnostics.errors.any { it.code == code && it.queryId != null })
        if (cleanupCode != null) assertTrue(result.diagnostics.errors.any { it.code == cleanupCode })
        assertSame(built, rendered)
        assertEquals(result, requireNotNull(rendered).result)
        assertTrue(closed)
        val expectedFiles = if (fatalCleanup) setOf("report.json", "report.html")
            else setOf("report.json", "report.html", "indexes.sql")
        assertEquals(expectedFiles, Files.list(output).use { entries ->
            entries.map { it.fileName.toString() }.toList().toSet()
        })
        if (fatalCleanup) {
            assertFalse(Files.exists(output.resolve("indexes.sql")))
        } else {
            val sql = Files.readString(output.resolve("indexes.sql"))
            assertTrue(sql.contains(PostgresDdlRenderer().render(candidates.first().definition)))
            assertFalse(sql.contains(PostgresDdlRenderer().render(candidates[1].definition)))
        }
        assertEquals(4, calls.size)
    }
}
