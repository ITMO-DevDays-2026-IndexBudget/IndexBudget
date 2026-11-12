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
import java.time.Duration
import java.time.Instant
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
        val metadata = requireNotNull(input.provenance)
        assertEquals(7L, metadata.windows.single().coverage.selectedCalls)
        assertTrue(requireNotNull(metadata.windows.single().coverage.availableCalls) >= 7)
        assertTrue(metadata.selectionRule.endsWith(ObservedWorkloadImporter.hash(Files.readAllBytes(manifest))))
        assertTrue(input.profiles.single().callsPerHour.values.single() > 0)
    }

    @Test
    fun `no eligible examples publishes a failed manifest without a successful workload`() {
        val (workload, manifest) = importActualSnapshots("DELETE FROM public.records", success = false)
        assertFalse(Files.exists(workload))
        val json = ImportJson.mapper.readTree(Files.readAllBytes(manifest))
        assertEquals("failed", json["status"].asString())
        assertTrue(json["entries"].any { it["reason"].asString() == "SQL_REJECTED" })
    }

    @Test
    fun `an observed table write window yields one write profile and refuses silent fallbacks`() {
        val source = UUID.randomUUID()
        val schema = "write_probe_" + UUID.randomUUID().toString().replace("-", "")
        connection().use { c -> c.createStatement().use { statement ->
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements")
            statement.execute("CREATE SCHEMA $schema")
            statement.execute("CREATE TABLE $schema.records(id integer PRIMARY KEY, value integer)")
            // A second active table of the same database that the workload SQL never touches.
            statement.execute("CREATE TABLE $schema.orders(id integer PRIMARY KEY, amount integer)")
            statement.execute("INSERT INTO $schema.records SELECT i, i FROM generate_series(1, 100) i")
            statement.execute("ANALYZE $schema.records")
            statement.execute("ANALYZE $schema.orders")
        } }

        data class Counters(val inserts: Long, val updates: Long, val deletes: Long, val hot: Long, val oid: Long)
        fun counters(table: String): Counters = connection().use { c -> c.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT t.n_tup_ins, t.n_tup_upd, t.n_tup_del, t.n_tup_hot_upd, t.relid " +
                    "FROM pg_catalog.pg_stat_user_tables t " +
                    "WHERE t.schemaname OPERATOR(pg_catalog.=) '$schema' AND t.relname OPERATOR(pg_catalog.=) '$table'"
            ).use { rows ->
                check(rows.next())
                Counters(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4), rows.getLong(5))
            }
        } }
        fun databaseOid(): Long = connection().use { c -> c.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT oid FROM pg_catalog.pg_database " +
                    "WHERE datname OPERATOR(pg_catalog.=) pg_catalog.current_database()"
            ).use { rows -> check(rows.next()); rows.getLong(1) }
        } }

        val recordsBefore = counters("records")
        val ordersBefore = counters("orders")
        val beforeAt = Instant.now()
        connection().use { c -> c.createStatement().use { statement ->
            repeat(10) { statement.execute("INSERT INTO $schema.records VALUES (1000 + $it, $it)") }
            repeat(5) { statement.execute("UPDATE $schema.records SET value = -value WHERE id OPERATOR(pg_catalog.=) ${it + 1}") }
            repeat(2) { statement.execute("DELETE FROM $schema.records WHERE id OPERATOR(pg_catalog.=) ${50 + it}") }
            // Activity on a table outside the workload: it must stay out of the exported rates.
            repeat(3) { statement.execute("INSERT INTO $schema.orders VALUES ($it, $it)") }
        } }
        val recordsAfter = counters("records")
        val ordersAfter = counters("orders")
        val afterAt = Instant.now()
        val databaseOid = databaseOid()
        // Statements window for the read side: one stable query executed between two real collections.
        val collector = JdbcStatisticsCollector({ connection() })
        // Stable initial counter: never invent a missing starting observation.
        connection().use { c -> c.createStatement().use { statement ->
            statement.executeQuery("SELECT value FROM $schema.records WHERE value = 7").close()
        } }
        val statementBefore = collector.collect(source, SnapshotLimits(), RunContext())
        connection().use { c -> c.createStatement().use { statement ->
            repeat(3) { statement.executeQuery("SELECT value FROM $schema.records WHERE value = 7").close() }
        } }
        val statementAfter = collector.collect(source, SnapshotLimits(), RunContext())
        // pg_stat_statements normalizes literals, so the saved text keeps the normalized form.
        val statementRow = statementAfter.entries.single {
            it.query?.startsWith("SELECT value FROM $schema.records") == true
        }
        val exampleKey = StatisticsKey(source, statementRow.dbid, statementRow.userid,
            requireNotNull(statementRow.queryid), statementRow.toplevel)

        fun tableSnapshot(at: Instant, records: Counters, orders: Counters) = TableWriteSnapshot(
            snapshotId = UUID.nameUUIDFromBytes((source.toString() + at).toByteArray()),
            source = TableWriteSource(source, databaseOid, statementRow.userid, trackCounts = true,
                postgresVersion = "16.8", statsReset = null),
            startedAt = at.toString(), finishedAt = at.toString(),
            entries = listOf(
                TableWriteEntry(records.oid, schema, "records",
                    records.inserts, records.updates, records.deletes, records.hot),
                TableWriteEntry(orders.oid, schema, "orders",
                    orders.inserts, orders.updates, orders.deletes, orders.hot)))
        Files.write(directory.resolve("write-before.json"),
            TableWriteSnapshotJson.render(tableSnapshot(beforeAt, recordsBefore, ordersBefore)))
        Files.write(directory.resolve("write-after.json"),
            TableWriteSnapshotJson.render(tableSnapshot(afterAt, recordsAfter, ordersAfter)))
        Files.write(directory.resolve("write-examples.json"), ImportJson.mapper.writeValueAsBytes(mapOf(
            "schema_version" to 1, "kind" to "pg_stat_statements_examples",
            "examples" to listOf(mapOf("key" to ImportJson.key(exampleKey),
                "sql" to "SELECT value FROM $schema.records WHERE value = 7",
                "search_path" to listOf("pg_catalog", schema))))))
        Files.write(directory.resolve("stmt-before.json"), SnapshotJson().render(statementBefore))
        Files.write(directory.resolve("stmt-after.json"), SnapshotJson().render(statementAfter))

        fun config(name: String, vararg extra: Pair<String, Any?>, writeBudget: Int? = null): Path {
            val path = directory.resolve(name)
            Files.write(path, ImportJson.mapper.writeValueAsBytes(mapOf(
                "schema_version" to 1, "kind" to "pg_stat_statements_import_config",
                "windows" to listOf(mapOf("id" to "observed", "weight" to 1,
                    "before" to "stmt-before.json", "after" to "stmt-after.json")),
                "examples" to "write-examples.json",
                "constraints" to mapOf("storage_budget_bytes" to 10_000_000,
                    "write_budget_units_per_hour" to writeBudget)) + extra.toMap()))
            return path
        }
        fun run(path: Path, output: String): Pair<Int, String> {
            val err = StringWriter()
            val status = ImportWorkloadCommand { connection() }.execute(arrayOf("import-workload",
                "--config", path.toString(), "--output-dir", directory.resolve(output).toString()),
                PrintWriter(StringWriter(), true), PrintWriter(err, true))
            return status to err.toString()
        }

        // One explicit observed window produces the shared write profile with its origin. Rates
        // cover exactly the resolved workload tables: orders changes stay in snapshot diagnostics
        // instead of leaking into the workload and failing validation.
        val windowConfig = config("write-config.json", "table_write_window" to
            mapOf("before" to "write-before.json", "after" to "write-after.json"))
        val (status, err) = run(windowConfig, "write-imported")
        assertEquals(0, status, err)
        val input = WorkloadReader().read(directory.resolve("write-imported/workload.json"))
        val rates = requireNotNull(input.tableWriteRates)
        assertEquals(setOf("$schema.records"), rates.keys)
        assertEquals(WriteRateSource.OBSERVED, rates.getValue("$schema.records").source)
        val elapsedSeconds = Duration.between(beforeAt, afterAt).toNanos() / 1.0e9
        assertEquals(10.0 * 3600.0 / elapsedSeconds, rates.getValue("$schema.records").insertsPerHour, 0.5)
        assertEquals(5.0 * 3600.0 / elapsedSeconds, rates.getValue("$schema.records").updatesPerHour, 0.5)
        assertEquals(2.0 * 3600.0 / elapsedSeconds, rates.getValue("$schema.records").deletesPerHour, 0.5)
        val origin = requireNotNull(input.tableWriteRatesOrigin)
        assertEquals(true, origin.complete)
        assertEquals(source.toString(), origin.sourceId)
        val manifestPath = directory.resolve("write-imported/import-manifest.json")
        val manifest = ImportJson.mapper.readTree(Files.readAllBytes(manifestPath))
        assertEquals("pg_stat_user_tables", manifest["write_profile"]["source"].asString())
        assertEquals(setOf("$schema.records"),
            manifest["write_profile"]["rates_by_table"].properties().map { it.key }.toSet())
        assertEquals(listOf("$schema.records"), manifest["write_profile"]["workload_tables"].toList().map { it.asString() })
        assertTrue(manifest["write_profile"]["table_faults"].isEmpty)
        assertTrue(manifest["write_profile"]["limitations"].toList().map { it.asString() }
            .contains("observed_rows_never_mixed_with_pg_stat_statements"))
        // One owner assembles the manifest and hashes the exact published bytes: the write profile
        // section is part of the provenance reference.
        assertTrue(requireNotNull(input.provenance).selectionRule
            .endsWith(ObservedWorkloadImporter.hash(Files.readAllBytes(manifestPath))))

        // A counter decrease on the workload table with an enabled budget fails honestly; it never
        // falls back to manual values.
        val decreased = Files.readString(directory.resolve("write-after.json"))
            .replaceFirst(Regex("\"n_tup_ins\":(\\d+)"), "\"n_tup_ins\":1")
        Files.write(directory.resolve("write-decreased.json"), decreased.toByteArray())
        val incompleteConfig = config("write-incomplete.json", "table_write_window" to
            mapOf("before" to "write-before.json", "after" to "write-decreased.json"),
            writeBudget = 100)
        val (incompleteStatus, incompleteErr) = run(incompleteConfig, "write-incomplete-imported")
        assertEquals(2, incompleteStatus)
        assertTrue(incompleteErr.contains("INVALID_WRITE_PROFILE"), incompleteErr)
        assertFalse(Files.exists(directory.resolve("write-incomplete-imported/workload.json")))

        // A window collected by a different source UUID never labels this workload's rates.
        val foreign = UUID.randomUUID()
        fun resourced(name: String) = Files.readString(directory.resolve(name))
            .replace("\"source_id\":\"$source\"", "\"source_id\":\"$foreign\"")
        Files.write(directory.resolve("write-foreign-before.json"), resourced("write-before.json").toByteArray())
        Files.write(directory.resolve("write-foreign-after.json"), resourced("write-after.json").toByteArray())
        val foreignConfig = config("write-foreign-config.json", "table_write_window" to
            mapOf("before" to "write-foreign-before.json", "after" to "write-foreign-after.json"))
        val (foreignStatus, foreignErr) = run(foreignConfig, "write-foreign-imported")
        assertEquals(2, foreignStatus)
        assertTrue(foreignErr.contains("The write window must come from the observed source"), foreignErr)

        // Manual and observed write sources are mutually exclusive; the choice stays explicit.
        val bothConfig = config("write-both.json",
            "table_write_window" to mapOf("before" to "write-before.json", "after" to "write-after.json"),
            "table_write_rates" to mapOf("$schema.records" to
                mapOf("inserts_per_hour" to 1, "updates_per_hour" to 1, "deletes_per_hour" to 1)))
        val (bothStatus, bothErr) = run(bothConfig, "write-both-imported")
        assertEquals(2, bothStatus)
        assertTrue(bothErr.contains("not both"), bothErr)

        connection().use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "INDEXBUDGET_IMPORT_HYPOPG_TEST", matches = "true")
    fun `actual workload snapshots import analyze and HTML JSON SQL carry the same provenance`() {
        connection().use { c -> c.createStatement().use { it.execute("CREATE EXTENSION IF NOT EXISTS hypopg") } }
        val (workload, _) = importActualSnapshots()
        val input = WorkloadReader().read(workload)
        val analysisUrl = url + (if ('?' in url) "&" else "?") + "user=postgres"
        val pipeline = AnalysisPipeline(JSqlParserSqlAnalyzer(), JdbcAnalysisSessionFactory({ analysisUrl }),
            ProfileAwareCandidateGenerator(), MetricsCalculatorImpl(), ObjectiveComparatorImpl(), LimitedRepairOptimizer(),
            DefaultReportBuilder(), AtomicReportExporter(HtmlReportRenderer()))
        val destination = directory.resolve("analysis")
        val out = StringWriter(); val err = StringWriter()
        val context = RunContext()
        val exit = IndexBudgetCli(WorkloadReader(), analysisRunner = pipeline).execute(arrayOf("analyze", "--workload",
            workload.toString(), "--output-dir", destination.toString()), PrintWriter(out, true), PrintWriter(err, true), context)
        assertEquals(0, exit, err.toString())
        val json = ImportJson.mapper.readTree(Files.readAllBytes(destination.resolve("report.json")))
        val provenance = requireNotNull(input.provenance)
        assertEquals(provenance.sourceId.toString(), json["workload_provenance"]["source_id"].asString())
        assertEquals(provenance.selectionRule, json["workload_provenance"]["selection_rule"].asString())
        assertEquals(context.runId, json["run_id"].asString())
        assertEquals(input.queries.map { it.id }.toSet(), json["queries"].toList().map { it["id"].asString() }.toSet())
        assertTrue(context.statistics().explainAttempts > 0)
        assertTrue(Files.readString(destination.resolve("report.html")).contains("import-manifest.json"))
        assertTrue(Files.readString(destination.resolve("indexes.sql")).contains(context.runId))
    }

    private fun importActualSnapshots(exampleOverride: String? = null, success: Boolean = true): Pair<Path, Path> {
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
                "sql" to (exampleOverride ?: "SELECT value FROM $schema.records WHERE value = 7"), "search_path" to listOf("pg_catalog", schema))))))
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
        assertEquals(if (success) 0 else 2, status, err.toString())
        assertTrue(opened.isNotEmpty() && opened.all { it.isClosed })
        return output.resolve("workload.json") to output.resolve("import-manifest.json")
    }
}
