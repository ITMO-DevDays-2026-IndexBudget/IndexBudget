package ru.itmo.indexbudget.comparison

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.*
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
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
                s.execute("ANALYZE $schema.records")
            }
            try {
                val source = directory.resolve("input.json")
                Files.writeString(source, """
                    {"schema_version":2,"queries":[{"id":"q1","sql":"SELECT id FROM $schema.records WHERE a = 42"}],
                     "profiles":[{"id":"read","weight":1,"calls_per_hour":{"q1":100}}],
                     "constraints":{"storage_budget_bytes":100000000,"write_budget_units_per_hour":null},
                     "manual_indexes":[{"schema":"$schema","table":"records","keys":[{"column":"a"}]}]}
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
                val pipeline = AnalysisPipeline(JSqlParserSqlAnalyzer(),
                    JdbcAnalysisSessionFactory(databaseUrl = { url }, connect = { DriverManager.getConnection(url, "postgres", "") }),
                    ProfileAwareCandidateGenerator(), MetricsCalculatorImpl(), ObjectiveComparatorImpl(),
                    LimitedRepairOptimizer(), DefaultReportBuilder(), AtomicReportExporter(renderer),
                    shutdownCancellation = ShutdownCancellation { AutoCloseable {} })
                val result = pipeline.run(input, output, context)
                assertEquals(RunStatus.COMPLETED, result.status)
                val report = rendered!!
                val manual = report.manualComparison!!
                assertTrue(manual.admissible)
                assertNotNull(result.selected)
                assertTrue(manual.evaluation!!.costByQueryId.getValue("q1") < result.baseline!!.costByQueryId.getValue("q1"))
                assertEquals(manual.candidates.map { it.id }, result.selected!!.evaluation.candidateIds)
                assertEquals(result.selected!!.evaluation.costByQueryId, manual.evaluation.costByQueryId)
                val json = JsonMapper().readTree(Files.readString(output.resolve("report.json")))
                assertEquals("separate_manual_evaluation", json["manual_comparison"]["comparison_scope"].stringValue())
                assertEquals("evaluated_sets", json["comparison_scope"].stringValue())
                assertTrue(Files.readString(output.resolve("report.html")).contains("Ручной набор индексов"))
                assertTrue(Files.exists(output.resolve("indexes.sql")))
                admin.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname='$schema'").use {
                    it.next(); assertEquals(1, it.getInt(1), "Only the original physical primary key may exist")
                } }
            } finally {
                admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
