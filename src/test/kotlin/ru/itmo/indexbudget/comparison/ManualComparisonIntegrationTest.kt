package ru.itmo.indexbudget.comparison

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.*
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.candidates.CandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.cli.IndexBudgetCli
import java.io.PrintWriter
import java.io.StringWriter
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.JdbcAnalysisSessionFactory
import ru.itmo.indexbudget.report.*
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_HYPOPG_TEST_URL", matches = ".+")
class ManualComparisonIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test fun `real manual and recommended sets share one baseline and publish separate results`() {
        val url = System.getenv("INDEXBUDGET_HYPOPG_TEST_URL")
        val schema = "manual_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, "postgres", "").use { admin ->
            admin.createStatement().use { s ->
                s.execute("CREATE EXTENSION IF NOT EXISTS hypopg")
                s.execute("CREATE SCHEMA $schema")
                s.execute("CREATE TABLE $schema.records (id integer PRIMARY KEY, a integer, payload text)")
                s.execute("INSERT INTO $schema.records SELECT i, i % 1000, repeat('x', 80) FROM generate_series(1, 50000) i")
                s.execute("VACUUM ANALYZE $schema.records")
            }
            try {
                val source = directory.resolve("input.json")
                Files.writeString(source, """
                    {"schema_version":2,"queries":[{"id":"q1","sql":"SELECT id FROM $schema.records WHERE a = 42"}],
                     "profiles":[{"id":"read","weight":1,"calls_per_hour":{"q1":100}}],
                     "constraints":{"storage_budget_bytes":100000000,"write_budget_units_per_hour":null},
                     "manual_indexes":[{"schema":"$schema","table":"records","keys":[{"column":"a"},{"column":"id"}]}]}
                """.trimIndent())
                val context = RunContext()
                val input = WorkloadReader().read(source, context)
                val output = directory.resolve("report")
                var rendered: AnalysisReport? = null
                val html = HtmlReportRenderer()
                val renderer = object : ReportRenderer {
                    override fun render(report: AnalysisReport): String {
                        rendered = report
                        return html.render(report)
                    }
                }
                val generator = object : CandidateGenerator {
                    override fun generate(workload: ResolvedWorkload, catalog: CatalogSnapshot, context: RunContext): CandidateBatch {
                        val batch = ProfileAwareCandidateGenerator().generate(workload, catalog, context)
                        return CandidateBatch(batch.candidates.filter {
                            it.definition.keys.map { key -> key.column.name } == listOf("a") &&
                                it.definition.includedColumns.isEmpty()
                        })
                    }
                }
                val pipeline = AnalysisPipeline(JSqlParserSqlAnalyzer(),
                    JdbcAnalysisSessionFactory(databaseUrl = { url }, connect = { DriverManager.getConnection(url, "postgres", "") }),
                    generator, MetricsCalculatorImpl(), ObjectiveComparatorImpl(),
                    LimitedRepairOptimizer(), DefaultReportBuilder(), AtomicReportExporter(renderer),
                    shutdownCancellation = ShutdownCancellation { AutoCloseable {} })
                val controlContext = RunContext()
                val controlSource = directory.resolve("control-input.json")
                val controlJson = JsonMapper().readTree(Files.readString(source)) as tools.jackson.databind.node.ObjectNode
                controlJson.remove("manual_indexes")
                Files.writeString(controlSource, controlJson.toString())
                val controlInput = WorkloadReader().read(controlSource, controlContext)
                val control = pipeline.run(controlInput, directory.resolve("control"), controlContext)
                val controlReport = rendered!!
                val result = pipeline.run(input, output, context)
                assertEquals(RunStatus.COMPLETED, result.status)
                val report = checkNotNull(rendered)
                val manual = report.manualComparison!!
                assertTrue(manual.admissible)
                assertNotNull(result.selected)
                assertTrue(manual.evaluation!!.costByQueryId.getValue("q1") < result.baseline!!.costByQueryId.getValue("q1"))
                assertEquals(control.bestRobust, result.bestRobust)
                assertEquals(control.bestMean, result.bestMean)
                assertEquals(control.selected, result.selected)
                assertEquals(controlReport.priceOfRobustness, report.priceOfRobustness)
                assertEquals(control.baseline!!.costByQueryId, result.baseline!!.costByQueryId)
                assertNotEquals(manual.candidates.map { it.id }, result.selected!!.evaluation.candidateIds)
                assertTrue(manual.candidates.none { candidate -> report.candidates.candidates.any { it.id == candidate.id } })
                assertTrue(manual.evaluation.costByQueryId.getValue("q1") < result.selected!!.evaluation.costByQueryId.getValue("q1"))
                val json = JsonMapper().readTree(Files.readString(output.resolve("report.json")))
                assertEquals("separate_manual_evaluation", json["manual_comparison"]["comparison_scope"].stringValue())
                assertEquals("evaluated_sets", json["comparison_scope"].stringValue())
                assertTrue(Files.readString(output.resolve("report.html")).contains("Ручной набор индексов"))
                val sql = Files.readString(output.resolve("indexes.sql"))
                assertEquals(Files.readAllLines(directory.resolve("control/indexes.sql")).filter { it.startsWith("CREATE INDEX") },
                    sql.lines().filter { it.startsWith("CREATE INDEX") })
                report.candidates.candidates.filter { it.id in result.selected!!.evaluation.candidateIds }.forEach {
                    assertTrue(sql.contains(it.ddl!!))
                }
                manual.candidates.forEach { assertFalse(sql.contains(it.ddl!!)) }
                // Validate input diagnostics against real catalog metadata before any baseline.
                admin.createStatement().use {
                    it.execute("ALTER TABLE $schema.records ADD COLUMN payload_json json, ADD COLUMN \" \" integer")
                }
                val invalidSource = directory.resolve("unsupported.json")
                Files.writeString(invalidSource, Files.readString(source).replace(
                    "{\"column\":\"a\"},{\"column\":\"id\"}", "{\"column\":\"payload_json\"}"))
                val err = StringWriter()
                val exit = IndexBudgetCli(WorkloadReader(), analysisRunner = pipeline).execute(arrayOf(
                    "analyze", "--workload", invalidSource.toString(), "--output-dir", directory.resolve("unsupported").toString()),
                    PrintWriter(StringWriter()), PrintWriter(err))
                assertEquals(2, exit)
                assertTrue(err.toString().contains("INVALID_INPUT"))
                val failed = JsonMapper().readTree(Files.readString(directory.resolve("unsupported/report.json")))
                assertEquals("manual_indexes.0.keys.0", failed["error"]["details"]["field"].stringValue())
                assertEquals(0, failed["diagnostics"]["statistics"]["explain_attempts"].intValue())
                val whitespaceSource = directory.resolve("whitespace.json")
                Files.writeString(whitespaceSource, Files.readString(source).replace(
                    "{\"column\":\"a\"},{\"column\":\"id\"}", "{\"column\":\" \"}"))
                val whitespaceContext = RunContext()
                val whitespaceInput = WorkloadReader().read(whitespaceSource, whitespaceContext)
                val whitespace = pipeline.run(whitespaceInput, directory.resolve("whitespace"), whitespaceContext)
                assertEquals(RunStatus.COMPLETED, whitespace.status)
                assertEquals(" ", rendered!!.manualComparison!!.candidates.single().definition.keys.single().column.name)
                assertTrue(rendered!!.manualComparison!!.admissible)
                admin.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname='$schema'").use {
                    it.next(); assertEquals(1, it.getInt(1), "Only the original physical primary key may exist")
                } }
            } finally {
                admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
