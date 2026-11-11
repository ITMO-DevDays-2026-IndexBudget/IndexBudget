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
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Opt-in fixtures in an explicitly selected disposable DB; never use the shared demo database. */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_HYPOPG_TEST_URL", matches = ".+")
class BudgetSeriesHypopgIntegrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `real budget points change recommendations while sharing preparation and closing every session`() {
        connect().use { admin ->
            val schema = "series_" + UUID.randomUUID().toString().replace("-", "")
            execute(admin, "CREATE EXTENSION IF NOT EXISTS hypopg WITH SCHEMA public")
            execute(admin, "CREATE SCHEMA $schema")
            try {
                execute(admin, "CREATE TABLE $schema.items (a integer, b integer, payload text)")
                execute(admin, "INSERT INTO $schema.items SELECT i, i, repeat('x', 100) FROM generate_series(1, 30000) i")
                // Statistics belong to fixture setup, outside ordinary analysis.
                execute(admin, "ANALYZE $schema.items")
                val input = WorkloadInput(
                    queries = listOf(QueryInput("qa", "SELECT a FROM $schema.items WHERE a = 42"),
                        QueryInput("qb", "SELECT b FROM $schema.items WHERE b = 42")),
                    profiles = listOf(Profile("default", 1.0, mapOf("qa" to 1.0, "qb" to 1.0))),
                    constraints = Constraints(Long.MAX_VALUE, 2.0, maxIndexes = 2),
                    tableWriteRates = mapOf("$schema.items" to TableWriteRates(1.0, 0.0, 0.0)),
                )
                val connections = mutableListOf<Connection>()
                val sizeCalls = mutableListOf<Int>()
                val evaluateCalls = mutableListOf<Int>()
                val contexts = mutableListOf<RunContext>()
                val reports = mutableListOf<AnalysisReport>()
                val exporter = AtomicReportExporter(HtmlReportRenderer())
                val pipeline = AnalysisPipeline(
                    sqlAnalyzer = JSqlParserSqlAnalyzer(),
                    sessionFactory = object : AnalysisSessionFactory {
                        override fun open(context: RunContext): AnalysisSession {
                            val index = connections.size
                            val connection = connect()
                            val actual = JdbcAnalysisSession(connection)
                            connections += connection
                            sizeCalls += 0
                            evaluateCalls += 0
                            contexts += context
                            return object : AnalysisSession by actual {
                                override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long {
                                    sizeCalls[index]++
                                    return actual.estimateSize(candidate, context)
                                }

                                override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload,
                                    context: RunContext): EvaluationResult {
                                    evaluateCalls[index]++
                                    return actual.evaluate(candidates, workload, context)
                                }

                                override fun close() {
                                    try {
                                        assertEquals(0, scalar(connection, "SELECT count(*) FROM public.hypopg()"),
                                            "Each session must remove every hypothesis before closing")
                                    } finally {
                                        actual.close()
                                    }
                                }
                            }
                        }
                    },
                    candidateGenerator = ProfileAwareCandidateGenerator(),
                    metrics = MetricsCalculatorImpl(), comparator = ObjectiveComparatorImpl(),
                    optimizer = LimitedRepairOptimizer(), reportBuilder = DefaultReportBuilder(),
                    reportExporter = ReportExporter { report, destination, context ->
                        assertTrue(connections.all { it.isClosed }, "Export must follow physical session cleanup")
                        val counters = context.statistics()
                        exporter.export(report, destination, context)
                        assertEquals(counters, context.statistics(), "Export must not evaluate additional sets")
                        reports += report
                    },
                    shutdownCancellation = ShutdownCancellation { AutoCloseable {} },
                )
                val context = RunContext().apply { recordInputHash("a".repeat(64)) }
                val result = BudgetSeriesPipeline(pipeline,
                    shutdownCancellation = ShutdownCancellation { AutoCloseable {} }).run(
                    input, BudgetSeriesRequest(SeriesBudget.WRITE,
                        listOf(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TWO)), directory.resolve("series"), context)

                assertEquals(SeriesStatus.COMPLETED, result.status)
                assertEquals(listOf(SeriesPointStatus.COMPLETED, SeriesPointStatus.COMPLETED, SeriesPointStatus.COMPLETED),
                    result.points.map { it.status })
                assertEquals(listOf(0, 1, 2), result.points.map { checkNotNull(it.result?.selected).evaluation.candidateIds.size })
                assertEquals(listOf(0.0, 1.0, 2.0), result.points.map { it.result?.selected?.metrics?.budgetUsage?.writeUnitsPerHour })
                assertTrue(result.points.all { it.conditionsMatch == true })
                assertTrue(result.points.all { it.conditions?.databaseVersions?.postgresVersion?.startsWith("16.") == true })
                assertTrue(result.points.all { it.conditions?.databaseVersions?.hypopgVersion == "1.4.2" })
                assertTrue(result.points.all { it.conditions?.sourceFingerprint?.matches(Regex("[a-f0-9]{64}")) == true })
                assertEquals(1, result.points.map { it.conditions?.sourceFingerprint }.distinct().size)
                assertEquals(actualSourceFingerprint(admin), result.points.first().conditions?.sourceFingerprint,
                    "Source identity must describe the actual PostgreSQL session, not the configured JDBC hosts")
                assertEquals(4, connections.size, "One preparation and three independent point sessions")
                assertEquals(4, contexts.distinct().size)
                assertTrue(connections.all { it.isClosed })
                assertEquals(2, sizeCalls.first())
                assertEquals(listOf(0, 0, 0), sizeCalls.drop(1), "Only common preparation measures sizes")
                assertEquals(listOf(1, 1, 3, 4), evaluateCalls,
                    "Preparation and points evaluate independent baselines; shared singleton sets must be reevaluated per point")
                assertEquals(3, reports.size)
                val poolIds = reports.first().candidates.candidates.map { it.id }
                assertEquals(2, poolIds.size)
                assertTrue(reports.all { it.candidates.candidates.map { candidate -> candidate.id } == poolIds })
                assertTrue(reports.all { it.candidates.diagnostics.preparationScope == CandidatePreparationScope.COMMON_POOL })
                assertEquals(1, reports.map { it.result.baseline?.costByQueryId }.distinct().size)
                assertEquals(3, reports.map { it.runId }.distinct().size)
                reports.forEachIndexed { index, report ->
                    val output = directory.resolve("series/point-${(index + 1).toString().padStart(2, '0')}")
                    val json = JsonMapper().readTree(Files.readString(output.resolve("report.json")))
                    val html = Files.readString(output.resolve("report.html"))
                    val sql = Files.readString(output.resolve("indexes.sql"))
                    assertEquals(report.runId, json["run_id"].stringValue())
                    assertTrue(html.contains(report.runId))
                    assertTrue(sql.contains("-- run_id: ${report.runId}"))
                    assertTrue(sql.contains("-- input_hash: ${report.inputHash}"))
                    assertEquals(index, Regex("CREATE INDEX").findAll(sql).count())
                    assertEquals(contexts[index + 1].statistics().explainAttempts,
                        evaluateCalls[index + 1] * input.queries.size)
                }
                val summary = JsonMapper().readTree(Files.readString(directory.resolve("series/series.json")))
                assertEquals(1, summary["schema_version"].intValue())
                assertEquals(3, summary["points"].size())
                assertEquals(poolIds, List(summary["preparation"]["candidate_ids"].size()) { summary["preparation"]["candidate_ids"][it].stringValue() })
                summary["points"].forEachIndexed { index, point ->
                    assertEquals(reports[index].runId, point["run_id"].stringValue())
                    assertEquals(reports[index].result.selected?.evaluation?.candidateIds,
                        List(point["selected_index_ids"].size()) { point["selected_index_ids"][it].stringValue() })
                    assertTrue(point["conditions_match"].booleanValue())
                    for (artifact in listOf("report.json", "report.html", "indexes.sql")) {
                        val link = point["artifacts"][artifact].stringValue()
                        assertTrue(Files.isRegularFile(directory.resolve("series").resolve(link)))
                    }
                }
                assertEquals(0, scalar(admin,
                    "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = '$schema'"),
                    "Series analysis must create no physical indexes")
            } finally {
                execute(admin, "DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }

    private fun actualSourceFingerprint(connection: Connection): String = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT pg_catalog.inet_server_addr()::pg_catalog.text, " +
            "pg_catalog.inet_server_port(), pg_catalog.current_database(), SESSION_USER, CURRENT_USER").use { rows ->
            check(rows.next())
            val address = checkNotNull(rows.getString(1))
            val identity = JsonMapper().writeValueAsString(listOf(address, rows.getInt(2), rows.getString(3),
                rows.getString(4), rows.getString(5)))
            java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8)))
        }
    }

    private fun connect(): Connection = DriverManager.getConnection(System.getenv("INDEXBUDGET_HYPOPG_TEST_URL"),
        System.getenv("INDEXBUDGET_HYPOPG_TEST_USER"), System.getenv("INDEXBUDGET_HYPOPG_TEST_PASSWORD"))

    private fun execute(connection: Connection, statement: String) = connection.createStatement().use { it.execute(statement) }

    private fun scalar(connection: Connection, statement: String): Int = connection.createStatement().use {
        it.executeQuery(statement).use { rows -> check(rows.next()); rows.getInt(1) }
    }
}
