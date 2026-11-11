package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.postgres.AnalysisSessionFactory
import ru.itmo.indexbudget.postgres.JdbcAnalysisSession
import ru.itmo.indexbudget.report.AtomicReportExporter
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.HtmlReportRenderer
import ru.itmo.indexbudget.report.ReportExporter
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Real SQL fixture on an explicitly selected disposable DB; no shared demo state is used. */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_HYPOPG_TEST_URL", matches = ".+")
class CandidateRankingHypopgIntegrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `both rankings use one real complete baseline and export equal size pools under identical limits`() {
        connect().use { admin ->
            val schema = "ranking_" + UUID.randomUUID().toString().replace("-", "")
            execute(admin, "CREATE EXTENSION IF NOT EXISTS hypopg WITH SCHEMA public")
            execute(admin, "CREATE SCHEMA $schema")
            try {
                val cheapColumns = (1..17).map { "c" + it.toString().padStart(2, '0') }
                execute(admin, "CREATE TABLE $schema.cheap (${cheapColumns.joinToString(", ") { "$it integer" }}, payload text)")
                execute(admin, "INSERT INTO $schema.cheap SELECT ${cheapColumns.joinToString(", ") { "i" }}, repeat('c', 100) " +
                    "FROM generate_series(1, 100) i")
                execute(admin, "CREATE TABLE $schema.expensive (a integer, payload text)")
                execute(admin, "INSERT INTO $schema.expensive SELECT i, repeat('e', 100) FROM generate_series(1, 50000) i")
                // Fixture statistics are prepared separately from ordinary analysis.
                execute(admin, "ANALYZE $schema.cheap")
                execute(admin, "ANALYZE $schema.expensive")

                val queries = cheapColumns.map { column -> QueryInput("cheap_$column",
                    "SELECT payload FROM $schema.cheap WHERE $column = 42") } +
                    QueryInput("rare_expensive", "SELECT payload FROM $schema.expensive WHERE a = 42")
                val source = directory.resolve("workload.json")
                Files.writeString(source, JsonMapper().writeValueAsString(mapOf(
                    "schema_version" to 2,
                    "objective" to "robust",
                    "queries" to queries.map { mapOf("id" to it.id, "sql" to it.sql) },
                    "profiles" to listOf(mapOf("id" to "default", "weight" to 1.0,
                        "calls_per_hour" to queries.associate { it.id to if (it.id == "rare_expensive") 1.0 else 100.0 })),
                    "constraints" to mapOf("storage_budget_bytes" to Long.MAX_VALUE,
                        "write_budget_units_per_hour" to null, "max_indexes" to 1),
                )))

                val frequency = analyze(source, CandidateRanking.FREQUENCY)
                val cost = analyze(source, CandidateRanking.BASELINE_COST)
                val baseline = checkNotNull(cost.report.result.baseline)
                assertEquals(queries.map { it.id }.toSet(), baseline.costByQueryId.keys)
                assertEquals(queries.map { it.id }.toSet(), baseline.plansByQueryId.keys)
                assertEquals(frequency.report.result.baseline, baseline)
                assertEquals(frequency.report.inputHash, cost.report.inputHash)
                assertEquals(frequency.report.input?.constraints, cost.report.input?.constraints)
                assertEquals(frequency.report.result.diagnostics.limits, cost.report.result.diagnostics.limits)
                assertTrue(baseline.costByQueryId.getValue("rare_expensive") >
                    100.0 * baseline.costByQueryId.filterKeys { it.startsWith("cheap_") }.values.max())
                assertTrue(frequency.report.candidates.candidates.take(16).none { "rare_expensive" in it.sourceQueryIds })
                assertEquals(setOf("rare_expensive"), cost.report.candidates.candidates.first().sourceQueryIds)

                for ((ranking, observation) in listOf(CandidateRanking.FREQUENCY to frequency,
                    CandidateRanking.BASELINE_COST to cost)) {
                    val report = observation.report
                    assertEquals(RunStatus.COMPLETED, report.result.status)
                    assertEquals(16, report.candidates.candidates.size)
                    assertTrue(report.result.diagnostics.candidateDecisions.none {
                        it.reason in setOf(CandidateReason.STORAGE_BUDGET, CandidateReason.WRITE_BUDGET,
                            CandidateReason.HYPOTHETICAL_INDEX_ERROR)
                    })
                    assertEquals(16, observation.sizeCalls)
                    assertEquals(1, observation.baselineCalls, "Ranking must reuse the one baseline evaluation")
                    val statistics = report.result.diagnostics.statistics
                    assertEquals(17, statistics.completedEvaluations)
                    assertEquals(observation.evaluateCalls, statistics.completedEvaluations)
                    assertEquals(statistics.completedEvaluations * queries.size, statistics.explainAttempts)
                    assertEquals(statistics.evaluationAttempts, statistics.completedEvaluations)
                    assertEquals(ranking, report.candidateRanking)

                    val json = JsonMapper().readTree(Files.readString(observation.output.resolve("report.json")))
                    val html = Files.readString(observation.output.resolve("report.html"))
                    val sql = Files.readString(observation.output.resolve("indexes.sql"))
                    assertEquals(ranking.wireName, json["candidate_ranking"].stringValue())
                    assertEquals(report.runId, json["run_id"].stringValue())
                    assertTrue(html.contains(ranking.wireName))
                    assertTrue(html.contains(report.runId))
                    assertTrue(sql.contains("-- run_id: ${report.runId}"))
                    assertTrue(sql.contains("-- input_hash: ${report.inputHash}"))
                    assertTrue(observation.closed)
                }
                assertEquals(0, scalar(admin,
                    "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = '$schema'"),
                    "Ordinary pipeline runs must not create physical indexes")
            } finally {
                execute(admin, "DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }

    private data class Observation(
        val report: AnalysisReport,
        val output: Path,
        val evaluateCalls: Int,
        val baselineCalls: Int,
        val sizeCalls: Int,
        val closed: Boolean,
    )

    private fun analyze(source: Path, ranking: CandidateRanking): Observation {
        val context = RunContext()
        val input = WorkloadReader().read(source, context, candidateRankingOverride = ranking)
        context.completePhase()
        val output = directory.resolve(ranking.wireName)
        val connection = connect()
        val actual = JdbcAnalysisSession(connection)
        var evaluateCalls = 0
        var baselineCalls = 0
        var sizeCalls = 0
        var report: AnalysisReport? = null
        val observed = object : AnalysisSession by actual {
            override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload,
                context: RunContext): EvaluationResult {
                evaluateCalls++
                if (candidates.isEmpty()) baselineCalls++
                return actual.evaluate(candidates, workload, context)
            }

            override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long {
                sizeCalls++
                return actual.estimateSize(candidate, context)
            }

            override fun close() {
                assertEquals(0, scalar(connection, "SELECT count(*) FROM public.hypopg()"))
                actual.close()
            }
        }
        try {
            val exporter = AtomicReportExporter(HtmlReportRenderer())
            AnalysisPipeline(
                sqlAnalyzer = JSqlParserSqlAnalyzer(),
                sessionFactory = object : AnalysisSessionFactory {
                    override fun open(context: RunContext): AnalysisSession = observed
                },
                candidateGenerator = ProfileAwareCandidateGenerator(),
                metrics = MetricsCalculatorImpl(), comparator = ObjectiveComparatorImpl(),
                optimizer = LimitedRepairOptimizer(), reportBuilder = DefaultReportBuilder(),
                reportExporter = ReportExporter { built, destination, runContext ->
                    assertTrue(connection.isClosed)
                    val counters = runContext.statistics()
                    exporter.export(built, destination, runContext)
                    assertEquals(counters, runContext.statistics(), "Export must reuse saved plans without EXPLAIN")
                    report = built
                },
                shutdownCancellation = ShutdownCancellation { AutoCloseable {} },
            ).run(input, output, context)
            return Observation(checkNotNull(report), output, evaluateCalls, baselineCalls, sizeCalls, connection.isClosed)
        } finally {
            actual.close()
        }
    }

    private fun connect(): Connection = DriverManager.getConnection(
        System.getenv("INDEXBUDGET_HYPOPG_TEST_URL"), System.getenv("INDEXBUDGET_HYPOPG_TEST_USER"),
        System.getenv("INDEXBUDGET_HYPOPG_TEST_PASSWORD"),
    )

    private fun execute(connection: Connection, statement: String) = connection.createStatement().use { it.execute(statement) }

    private fun scalar(connection: Connection, statement: String): Int = connection.createStatement().use {
        it.executeQuery(statement).use { rows -> check(rows.next()); rows.getInt(1) }
    }
}
