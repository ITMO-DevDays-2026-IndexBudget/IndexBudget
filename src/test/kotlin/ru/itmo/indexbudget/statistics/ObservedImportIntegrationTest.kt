package ru.itmo.indexbudget.statistics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.*
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.cli.ImportWorkloadCommand
import ru.itmo.indexbudget.cli.IndexBudgetCli
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.JdbcAnalysisSessionFactory
import ru.itmo.indexbudget.report.*
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Opt-in only to disposable PostgreSQL 16 with pg_stat_statements preloaded. */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_STATS_TEST_URL", matches = ".+")
class ObservedImportIntegrationTest {
    @TempDir lateinit var directory: Path
    private val url get() = System.getenv("INDEXBUDGET_STATS_TEST_URL")
    private fun connection(): Connection = DriverManager.getConnection(url, "postgres", "")

    @Test fun `actual snapshots import through CLI with closed resources and replayable provenance`() {
        val (workload, manifest) = importActualSnapshots()
        val input = WorkloadReader().read(workload)
        assertEquals(1, input.queries.size)
        val metadata = ImportJson.mapper.readTree(input.observedWorkload!!.json)
        assertTrue(metadata["periods"][0]["calls_coverage"].doubleValue() in 0.0..1.0)
        assertEquals(input.profiles.single().callsPerHour.values.single(),
            metadata["periods"][0]["selected_calls_per_hour"].doubleValue())
        assertEquals(ObservedWorkloadImporter.hash(Files.readAllBytes(manifest)), metadata["manifest_sha256"].asString())
        assertTrue(input.profiles.single().callsPerHour.values.single() > 0)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "INDEXBUDGET_IMPORT_HYPOPG_TEST", matches = "true")
    fun `actual workload snapshots import analyze and HTML JSON SQL carry the same provenance`() {
        connection().use { c -> c.createStatement().use { it.execute("CREATE EXTENSION IF NOT EXISTS hypopg") } }
        val (workload, _) = importActualSnapshots()
        val input = WorkloadReader().read(workload)
        val pipeline = AnalysisPipeline(JSqlParserSqlAnalyzer(), JdbcAnalysisSessionFactory({ url }),
            ProfileAwareCandidateGenerator(), MetricsCalculatorImpl(), ObjectiveComparatorImpl(), LimitedRepairOptimizer(),
            DefaultReportBuilder(), AtomicReportExporter(HtmlReportRenderer()))
        val destination = directory.resolve("analysis")
        val out = StringWriter(); val err = StringWriter()
        val context = RunContext()
        val exit = IndexBudgetCli(WorkloadReader(), analysisRunner = pipeline).execute(arrayOf("analyze", "--workload",
            workload.toString(), "--output-dir", destination.toString()), PrintWriter(out, true), PrintWriter(err, true), context)
        assertEquals(0, exit, err.toString())
        val json = ImportJson.mapper.readTree(Files.readAllBytes(destination.resolve("report.json")))
        val provenance = ImportJson.mapper.readTree(input.observedWorkload!!.json)
        assertEquals(provenance, json["observed_workload"])
        assertEquals(context.runId, json["run_id"].asString())
        assertEquals(input.queries.map { it.id }.toSet(), json["queries"].toList().map { it["id"].asString() }.toSet())
        assertTrue(context.statistics().explainAttempts > 0)
        assertTrue(Files.readString(destination.resolve("report.html")).contains("pg_stat_statements_import_provenance"))
        assertTrue(Files.readString(destination.resolve("indexes.sql")).contains(context.runId))
    }

    private fun importActualSnapshots(): Pair<Path, Path> {
        val source = UUID.fromString("d0d60c19-eb9d-42ee-9e01-935560eb7e20")
        val schema = "import_probe_" + UUID.randomUUID().toString().replace("-", "")
        connection().use { c -> c.createStatement().use { statement ->
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements")
            statement.execute("CREATE SCHEMA $schema")
            statement.execute("CREATE TABLE $schema.records(value integer)")
            statement.execute("INSERT INTO $schema.records SELECT generate_series(1,1000)")
            statement.execute("ANALYZE $schema.records")
        } }
        fun execute(times: Int) = connection().use { c -> c.createStatement().use { statement ->
            repeat(times) { statement.executeQuery("SELECT value FROM $schema.records WHERE value = 7").close() }
        } }
        execute(1) // Stable initial counter, never invent a missing starting observation.
        val collector = JdbcStatisticsCollector({ connection() })
        val before = collector.collect(source, SnapshotLimits(), RunContext())
        execute(7)
        val after = collector.collect(source, SnapshotLimits(), RunContext())
        val row = after.entries.single { it.query?.startsWith("SELECT value FROM $schema.records") == true }
        val key = StatisticsKey(source, row.dbid, row.userid, requireNotNull(row.queryid), row.toplevel)
        // The collector's own SQL is outside this fixture's selected observation scope. Keep it in the
        // saved snapshot: denominator and exclusions must remain honest rather than pre-filtering rows.
        Files.write(directory.resolve("before.json"), SnapshotJson().render(before))
        Files.write(directory.resolve("after.json"), SnapshotJson().render(after))
        Files.write(directory.resolve("examples.json"), ImportJson.mapper.writeValueAsBytes(mapOf("schema_version" to 1,
            "kind" to "pg_stat_statements_examples", "examples" to listOf(mapOf("key" to ImportJson.key(key),
                "sql" to "SELECT value FROM $schema.records WHERE value = 7", "search_path" to listOf("pg_catalog", schema))))))
        Files.write(directory.resolve("config.json"), ImportJson.mapper.writeValueAsBytes(mapOf("schema_version" to 1,
            "kind" to "pg_stat_statements_import_config", "windows" to listOf(mapOf("id" to "observed", "weight" to 1,
                "before" to "before.json", "after" to "after.json")), "examples" to "examples.json",
            "constraints" to mapOf("storage_budget_bytes" to 10000000, "write_budget_units_per_hour" to null))))
        val opened = mutableListOf<Connection>()
        val command = ImportWorkloadCommand { connection().also { opened += it } }
        val output = directory.resolve("imported")
        val err = StringWriter()
        val status = command.execute(arrayOf("import-workload", "--config", directory.resolve("config.json").toString(),
            "--output-dir", output.toString()), PrintWriter(StringWriter(), true), PrintWriter(err, true))
        assertEquals(0, status, err.toString())
        assertTrue(opened.isNotEmpty() && opened.all { it.isClosed })
        return output.resolve("workload.json") to output.resolve("import-manifest.json")
    }
}
