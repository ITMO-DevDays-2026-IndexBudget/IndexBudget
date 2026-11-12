package ru.itmo.indexbudget.examples

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import ru.itmo.indexbudget.application.*
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.*
import ru.itmo.indexbudget.report.*
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.HexFormat

/** Requires check-scenarios.sh's OWN seeded DB, not the shared stand or a generic test schema. */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_SCENARIOS_TEST_URL", matches = ".+")
class DemoScenarioIntegrationTest {
    private val root = Path.of("demo/scenarios")
    private val mapper = JsonMapper()

    @Test
    fun `published seed workload and budget series preserve usable bounded results without changing data`() {
        val output = Files.createTempDirectory(Path.of("build"), "scenario-runs-")
        admin().use { admin ->
            assertEquals("v1", scalar(admin, "SELECT version FROM demo_meta.seed_status WHERE ready"))
            assertEquals("200000", scalar(admin, "SELECT count(*) FROM public.records"))
            assertEquals("1000", scalar(admin, "SELECT count(*) FROM public.groups"))
            assertEquals("0", scalar(admin, "SELECT count(*) FROM public.records WHERE id IS NULL OR group_id IS NULL OR value IS NULL OR created_at IS NULL OR payload IS NULL"))
            val before = fingerprint(admin)
            val physicalIndexes = scalar(admin, "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = 'public'")
            val context = RunContext()
            val input = WorkloadReader().read(root.resolve("workload.json"), context)
            val reports = mutableListOf<AnalysisReport>()
            val connections = mutableListOf<Connection>()
            val exporter = AtomicReportExporter(HtmlReportRenderer())
            val pipeline = AnalysisPipeline(JSqlParserSqlAnalyzer(), object : AnalysisSessionFactory {
                override fun open(context: RunContext): AnalysisSession {
                    val connection = analysis()
                    connections += connection
                    return JdbcAnalysisSession(connection).also { it.readDatabaseVersions(context) }
                }
            }, ProfileAwareCandidateGenerator(), MetricsCalculatorImpl(), ObjectiveComparatorImpl(),
                LimitedRepairOptimizer(), DefaultReportBuilder(), ReportExporter { report, destination, run ->
                    assertTrue(connections.all { it.isClosed })
                    exporter.export(report, destination, run)
                    reports += report
                }, shutdownCancellation = ShutdownCancellation { AutoCloseable {} })

            val main = pipeline.run(input, output.resolve("main"), context)
            requireUsableOutcome(main)
            val mainReport = reports.last()
            assertTrue(checkNotNull(main.selected).evaluation.candidateIds.size >= 2,
                "This fixed demo must actually permit selecting 2+ indexes, not only set max_indexes=5")
            assertEquals(ManualComparisonStatus.COMPLETED, mainReport.manualComparison?.status)
            assertTrue(checkNotNull(mainReport.manualComparison).admissible)
            val ids = input.queries.map { it.id }.toSet()
            assertEquals(ids, main.baseline?.costByQueryId?.keys)
            assertEquals(ids, main.selected?.evaluation?.costByQueryId?.keys)
            assertEquals(setOf(StatementKind.SELECT, StatementKind.UPDATE, StatementKind.DELETE), mainReport.statementKinds.values.toSet())
            assertTrue(mainReport.costGuards.all { !it.violated })
            assertEquals("16.15", mainReport.environment.postgresVersion?.substringBefore(' '))
            assertEquals("1.4.2", mainReport.environment.hypopgVersion)

            // Every published negative example is checked against the actual, complete seed catalog.
            val analyzer = JSqlParserSqlAnalyzer()
            val metadata = mapper.readTree(Files.readString(root.resolve("catalog.json")))
            JdbcAnalysisSession(analysis()).use { session ->
                val run = RunContext()
                val parsed = analyzer.parse(input, run)
                val catalog = session.readCatalog(parsed.referencedTables, run)
                assertEquals(2, catalog.tables.size)
                for (example in metadata["unsupported_examples"]) {
                    val id = example["id"].asString()
                    val one = input.copy(queries = listOf(QueryInput(id, example["sql"].asString())),
                        profiles = listOf(Profile("p", 1.0, mapOf(id to 1.0))))
                    val error = assertThrows(AnalysisException::class.java) {
                        analyzer.resolve(analyzer.parse(one, run), catalog, run)
                    }.error
                    assertEquals(example["error_code"].asString(), error.code.name)
                    assertEquals(id, error.queryId)
                }
            }

            val seriesResults = linkedMapOf<String, BudgetSeriesResult>()
            for (file in listOf("storage-series.json", "write-series.json")) {
                val seriesContext = RunContext()
                val sameInput = WorkloadReader().read(root.resolve("workload.json"), seriesContext)
                val request = BudgetSeriesReader().read(root.resolve(file), seriesContext)
                val result = BudgetSeriesPipeline(pipeline, ShutdownCancellation { AutoCloseable {} })
                    .run(sameInput, request, output.resolve(file.removeSuffix(".json")), seriesContext)
                assertNull(result.preparationError, file)
                for (point in result.points) {
                    val saved = checkNotNull(point.result) { "$file point ${point.ordinal}: ${point.error}" }
                    requireUsableOutcome(saved)
                    assertEquals(saved.status.name, point.status.name, file)
                }
                assertEquals(if (result.points.any { it.status == SeriesPointStatus.PARTIAL })
                    SeriesStatus.PARTIAL else SeriesStatus.COMPLETED, result.status, file)
                assertTrue(result.points.all { it.conditionsMatch == true }, file)
                assertTrue(result.points.first().result?.selected?.evaluation?.candidateIds?.isEmpty() == true, file)
                assertTrue(checkNotNull(result.points.last().result?.selected).evaluation.candidateIds.size >= 2, file)
                seriesResults[file] = result
            }
            for (report in reports) {
                val baseline = checkNotNull(report.result.baseline)
                val selected = checkNotNull(report.result.selected).evaluation
                assertEquals(ids, baseline.costByQueryId.keys)
                assertEquals(ids, selected.costByQueryId.keys)
                assertEquals(baseline.costByQueryId.getValue("all_records"), selected.costByQueryId.getValue("all_records"),
                    "Retain the broad filter's no-benefit result without changing the planner")
                assertFalse(report.costGuards.any { it.violated })
            }
            assertEquals(before, fingerprint(admin), "Analysis and manual comparison must not execute DML")
            assertEquals(physicalIndexes, scalar(admin, "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = 'public'"))
            assertTrue(connections.all { it.isClosed })
            analysis().use { connection -> assertEquals("0", scalar(connection, "SELECT count(*) FROM public.hypopg()")) }
            val evidence = Path.of("build/scenarios-verification")
            Files.createDirectories(evidence)
            val seed = Files.readAllBytes(Path.of("demo/postgres/init/001-seed.sql"))
            Files.writeString(evidence.resolve("verification.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf(
                "kind" to "real_planner_verification_not_runtime_benchmark",
                "seed_version" to "v1", "seed_sha256" to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seed)),
                "postgres_version" to mainReport.environment.postgresVersion, "hypopg_version" to mainReport.environment.hypopgVersion,
                "input_hash" to mainReport.inputHash, "data_unchanged" to true, "physical_indexes_unchanged" to true,
                "main_status" to main.status.name.lowercase(), "main_stop_reasons" to main.diagnostics.stopReasons.map { it.name },
                "main_selected_count" to main.selected?.evaluation?.candidateIds?.size,
                "baseline_costs" to main.baseline?.costByQueryId, "selected_costs" to main.selected?.evaluation?.costByQueryId,
                "series_selected_counts" to seriesResults.mapValues { (_, result) ->
                    result.points.map { it.result?.selected?.evaluation?.candidateIds?.size } },
                "series_outcomes" to seriesResults.mapValues { (_, result) -> mapOf(
                    "status" to result.status.name.lowercase(),
                    "points" to result.points.map { point -> mapOf("status" to point.status.name.lowercase(),
                        "stop_reasons" to point.result?.diagnostics?.stopReasons?.map { it.name }) },
                ) },
            )) + "\n")
            // Stable evidence directory is for CI downloads; never rewrite earlier run artifacts.
            Files.writeString(evidence.resolve("runs-directory.txt"), output.toString() + "\n")
        }
    }

    /** Normal mode can exhaust fixed-S0 repair; that is usable partial, never completed. */
    private fun requireUsableOutcome(result: SearchResult) {
        assertTrue(result.status in setOf(RunStatus.COMPLETED, RunStatus.PARTIAL))
        if (result.status == RunStatus.PARTIAL) {
            assertEquals(setOf(ErrorCode.REPAIR_LIMIT), result.diagnostics.errors.map { it.code }.toSet())
            assertTrue(StopReason.REPAIR_LIMIT in result.diagnostics.stopReasons)
            assertTrue(result.diagnostics.stopReasons.all { it in setOf(
                StopReason.NO_IMPROVEMENT, StopReason.MAX_INDEXES, StopReason.REPAIR_LIMIT) })
            assertEquals(result.diagnostics.limits.maxRepairAttempts, result.diagnostics.statistics.repairAttempts)
        } else assertTrue(result.diagnostics.errors.isEmpty())
        assertNotNull(result.baseline)
        assertNotNull(result.bestRobust)
        assertNotNull(result.bestMean)
        assertNotNull(result.selected)
    }

    private fun analysis(): Connection = DriverManager.getConnection(System.getenv("INDEXBUDGET_SCENARIOS_TEST_URL"),
        "indexbudget_scenarios", System.getenv("INDEXBUDGET_SCENARIOS_PASSWORD"))
    private fun admin(): Connection = DriverManager.getConnection(System.getenv("INDEXBUDGET_SCENARIOS_TEST_URL"),
        "demo_admin", System.getenv("INDEXBUDGET_SCENARIOS_ADMIN_PASSWORD") ?: "")
    private fun scalar(connection: Connection, sql: String): String = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
    }
    private fun fingerprint(connection: Connection) = listOf("records", "groups").map { table ->
        scalar(connection, "SELECT pg_catalog.md5(pg_catalog.string_agg(pg_catalog.md5(r::text), '' ORDER BY id)) FROM public.$table r")
    }
}
