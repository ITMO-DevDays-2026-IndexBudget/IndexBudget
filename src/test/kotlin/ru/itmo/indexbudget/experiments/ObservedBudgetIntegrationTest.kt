package ru.itmo.indexbudget.experiments

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
import ru.itmo.indexbudget.statistics.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.HexFormat
import java.util.UUID
import kotlin.math.ceil

/** #109. Invoked only by check-scenarios.sh --observed-budgets on its own disposable seed v1. */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_SCENARIOS_TEST_URL", matches = ".+")
class ObservedBudgetIntegrationTest {
    private val mapper = JsonMapper()
    private val scenario = Path.of("demo/scenarios")
    private val metrics = MetricsCalculatorImpl()

    @Test
    fun `observed mixed workload explains criteria and independent budget transitions`() {
        val output = Files.createTempDirectory(Path.of("build"), "observed-budget-runs-")
        val source = UUID.randomUUID()
        val collector = JdbcTableStatisticsCollector({ analysis() })
        fun capture() = collector.collect(source, SnapshotLimits(), RunContext())
        val original = WorkloadReader().read(scenario.resolve("workload.json"), RunContext())
        admin().use { connection ->
            assertEquals("v1", scalar(connection, "SELECT version FROM demo_meta.seed_status WHERE ready"))
            assertEquals("200000", scalar(connection, "SELECT count(*) FROM public.records"))
        }
        listOf("workload.json", "catalog.json", "observe-writes.sql").forEach {
            Files.copy(scenario.resolve(it), output.resolve(if (it == "workload.json") "source-workload.json" else it))
        }
        Files.copy(Path.of("demo/postgres/init/001-seed.sql"), output.resolve("seed.sql"))
        val before = capture()
        assertTrue(before.complete)
        Files.write(output.resolve("write-before.json"), TableSnapshotJson().render(before))
        val affected = linkedMapOf<String, Int>()
        val observationStart = System.nanoTime()
        // Actual mixed activity, not EXPLAIN. Kept outside all analysis sessions.
        admin().use { connection -> connection.createStatement().use { statement ->
            for (query in original.queries) {
                if (statement.execute(query.sql)) statement.resultSet.use { rows -> while (rows.next()) { /* Drain SELECT. */ } }
                else affected[query.id] = statement.updateCount
            }
            val writes = Files.readString(output.resolve("observe-writes.sql"))
                .lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            writes.split(';').filter { it.isNotBlank() }.forEachIndexed { index, sql ->
                affected["additional_write_$index"] = statement.executeUpdate(sql)
            }
            statement.execute("SELECT pg_catalog.pg_stat_force_next_flush()")
        } }
        assertEquals(mapOf("update_payload" to 17, "delete_recent" to 2,
            "additional_write_0" to 10, "additional_write_1" to 1,
            "additional_write_2" to 3, "additional_write_3" to 1), affected)
        // An explicit real observation window. Never forge timestamps to manufacture hourly rates.
        val remainingMillis = 5000 - (System.nanoTime() - observationStart) / 1_000_000
        if (remainingMillis > 0) Thread.sleep(remainingMillis)
        var after = capture()
        val publicationDeadline = System.nanoTime() + 5_000_000_000L
        fun deltas(snapshot: TableSnapshot, table: String): List<Long> {
            val a = before.entries.single { it.schema == "public" && it.name == table }
            val b = snapshot.entries.single { it.schema == "public" && it.name == table }
            return listOf(checkNotNull(b.nTupIns) - checkNotNull(a.nTupIns),
                checkNotNull(b.nTupUpd) - checkNotNull(a.nTupUpd), checkNotNull(b.nTupDel) - checkNotNull(a.nTupDel))
        }
        while ((deltas(after, "records") != listOf(10L, 17L, 2L) ||
                deltas(after, "groups") != listOf(1L, 3L, 1L)) && System.nanoTime() < publicationDeadline) {
            Thread.sleep(50)
            after = capture()
        }
        Files.write(output.resolve("write-after.json"), TableSnapshotJson().render(after))
        assertTrue(after.complete)
        assertEquals(listOf(10L, 17L, 2L), deltas(after, "records"))
        assertEquals(listOf(1L, 3L, 1L), deltas(after, "groups"))
        val observed = WriteProfileCalculator().calculate(
            TableWriteSnapshotJson.read(output.resolve("write-before.json")),
            TableWriteSnapshotJson.read(output.resolve("write-after.json")))
        assertTrue(observed.complete)
        assertTrue(observed.tableFaults.isEmpty())
        val rates = observed.rates.filterKeys { it in setOf("public.records", "public.groups") }
        assertEquals(2, rates.size)
        val maxRowRate = rates.values.maxOf { it.insertsPerHour + it.updatesPerHour + it.deletesPerHour }
        // Five indexes, at most two keys + two INCLUDE columns each: this cannot bind storage runs.
        val unboundWrites = ceil(maxRowRate * 20) + 1
        val workload = mapper.readTree(Files.readString(output.resolve("source-workload.json"))) as ObjectNode
        workload.set("table_write_rates", mapper.valueToTree(rates.mapValues { (_, rate) -> mapOf(
            "inserts_per_hour" to rate.insertsPerHour, "updates_per_hour" to rate.updatesPerHour,
            "deletes_per_hour" to rate.deletesPerHour, "source" to "observed") }))
        workload.set("table_write_rates_origin", mapper.valueToTree(mapOf(
            "kind" to "observed", "window" to mapOf("from" to observed.from, "to" to observed.to,
                "period_seconds" to observed.periodSeconds), "complete" to observed.complete,
            "reasons" to observed.reasons.map { it.name }, "source" to mapOf(
                "source_id" to observed.sourceId.toString(), "database_oid" to observed.databaseOid,
                "postgres_version" to observed.postgresVersion))))
        (workload["constraints"] as ObjectNode).put("write_budget_units_per_hour", unboundWrites)
        writeJson(output.resolve("workload.json"), workload)
        // Explicitly nonbinding storage for the write experiment; SQL, profiles and algorithm stay fixed.
        val writeWorkload = workload.deepCopy()
        (writeWorkload["constraints"] as ObjectNode).put("storage_budget_bytes", 1_073_741_824L)
        writeJson(output.resolve("write-workload.json"), writeWorkload)
        Files.copy(scenario.resolve("storage-series.json"), output.resolve("storage-series.json"))
        writeJson(output.resolve("write-series.json"), mapOf("schema_version" to 1,
            "budget" to "write_budget_units_per_hour", "values" to listOf(0.0,
                ceil(maxRowRate * 1.1), ceil(maxRowRate * 3.1), unboundWrites)))

        // Owner prepares statistics once after observation. No writes/ANALYZE during analysis or series.
        admin().use { connection -> connection.createStatement().use {
            it.execute("ANALYZE public.records"); it.execute("ANALYZE public.groups")
        } }
        val fingerprintBefore = admin().use(::fingerprint)
        val indexesBefore = admin().use(::physicalIndexes)
        val settings = analysis().use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("""SELECT name, setting, unit, source FROM pg_catalog.pg_settings
                WHERE category LIKE 'Query Tuning%' OR name IN
                ('server_version', 'block_size', 'shared_buffers', 'work_mem', 'track_counts', 'autovacuum')
                ORDER BY name""").use { rows -> buildList { while (rows.next()) add(mapOf(
                    "name" to rows.getString(1), "setting" to rows.getString(2),
                    "unit" to rows.getString(3), "source" to rows.getString(4))) } }
        } }
        writeJson(output.resolve("settings.json"), settings)
        val connections = mutableListOf<Connection>()
        val reports = linkedMapOf<Path, AnalysisReport>()
        val exporter = AtomicReportExporter(HtmlReportRenderer())
        val pipeline = AnalysisPipeline(JSqlParserSqlAnalyzer(), object : AnalysisSessionFactory {
            override fun open(context: RunContext): AnalysisSession = JdbcAnalysisSession(analysis().also {
                connections += it
            }).also { it.readDatabaseVersions(context) }
        }, ProfileAwareCandidateGenerator(), metrics, ObjectiveComparatorImpl(), LimitedRepairOptimizer(),
            DefaultReportBuilder(), ReportExporter { report, destination, context ->
                assertTrue(connections.all { it.isClosed })
                exporter.export(report, destination, context)
                reports[destination] = report
                verifyReport(report, destination)
            }, shutdownCancellation = ShutdownCancellation { AutoCloseable {} })
        val context = RunContext()
        val input = WorkloadReader().read(output.resolve("workload.json"), context)
        assertEquals(original.queries, input.queries)
        assertEquals(original.profiles, input.profiles)
        assertEquals(original.manualIndexes, input.manualIndexes)
        pipeline.run(input, output.resolve("main"), context)
        val main = reports.getValue(output.resolve("main"))
        assertTrue(checkNotNull(main.result.selected).evaluation.candidateIds.size >= 2)
        assertEquals(ManualComparisonStatus.COMPLETED, main.manualComparison?.status)
        assertTrue(checkNotNull(main.manualComparison).admissible)

        val seriesFacts = linkedMapOf<String, Any>()
        for (axis in listOf("storage", "write")) {
            val run = RunContext()
            val file = if (axis == "write") "write-workload.json" else "workload.json"
            val seriesInput = WorkloadReader().read(output.resolve(file), run)
            val request = BudgetSeriesReader().read(output.resolve("$axis-series.json"), run)
            val result = BudgetSeriesPipeline(pipeline, ShutdownCancellation { AutoCloseable {} })
                .run(seriesInput, request, output.resolve(axis), run)
            assertNull(result.preparationError)
            assertEquals(4, result.points.size)
            assertTrue(result.points.all { it.conditionsMatch == true })
            val selected = result.points.map { checkNotNull(it.result?.selected) }
            assertTrue(selected.first().evaluation.candidateIds.isEmpty())
            assertTrue(selected.last().evaluation.candidateIds.size >= 2)
            assertTrue(selected.drop(1).map { it.evaluation.candidateIds }.distinct().size >= 2,
                "$axis must change a recommendation between NONZERO budgets")
            val summary = mapper.readTree(Files.readString(output.resolve("$axis/series.json")))
            // Prove that the other budget cannot exclude ANY set of <=5 candidates in this pool.
            val otherExpense = if (axis == "write") "budgeted_bytes" else "write_units_per_hour"
            val bound = summary["preparation"]["candidates"].toList().map { it[otherExpense].asDouble() }
                .sortedDescending().take(5).sum()
            val fixedBudget = if (axis == "write") seriesInput.constraints.storageBudgetBytes.toDouble()
                else checkNotNull(seriesInput.constraints.writeBudgetUnitsPerHour)
            assertTrue(bound < fixedBudget, "$axis: the other budget must be demonstrably nonbinding")
            seriesFacts[axis] = mapOf("fixed_other_budget" to fixedBudget,
                "top_five_other_expenses_bound" to bound, "nonzero_budget_transition" to true,
                "points" to summary["points"], "candidate_ids" to summary["preparation"]["candidate_ids"])
        }
        assertEquals(fingerprintBefore, admin().use(::fingerprint))
        assertEquals(indexesBefore, admin().use(::physicalIndexes))
        assertTrue(connections.all { it.isClosed })
        analysis().use { assertEquals("0", scalar(it, "SELECT count(*) FROM public.hypopg()")) }
        writeJson(output.resolve("summary.json"), mapOf(
            "schema_version" to 1, "kind" to "observed_write_budget_experiment",
            "basis" to "real_postgresql_planner_cost_not_runtime_benchmark",
            "seed_version" to "v1", "seed_sha256" to sha256(output.resolve("seed.sql")),
            "postgres_version" to main.environment.postgresVersion, "hypopg_version" to main.environment.hypopgVersion,
            "read_profiles_source" to "scenario_106_fixed_synthetic_frequencies",
            "write_profile_source" to "one_real_table_statistics_window",
            "window_seconds" to observed.periodSeconds, "affected_rows" to affected,
            "table_deltas" to mapOf("public.records" to deltas(after, "records"), "public.groups" to deltas(after, "groups")),
            "historical_hot_updates_per_hour" to observed.hotUpdatesPerHour,
            "analysis_data_fingerprints" to fingerprintBefore, "physical_indexes" to indexesBefore,
            "data_unchanged_by_analysis" to true, "physical_indexes_unchanged" to true,
            "connections_closed" to true, "series" to seriesFacts,
            "runs" to reports.map { (path, report) -> runSummary(output.relativize(path), report) },
        ))
        Files.writeString(Path.of("build/observed-budget-latest.txt"), output.toString() + "\n")
        println("Observed budget evidence: $output")
    }

    private fun verifyReport(report: AnalysisReport, destination: Path) {
        val result = report.result
        assertTrue(result.status in setOf(RunStatus.COMPLETED, RunStatus.PARTIAL))
        if (result.status == RunStatus.PARTIAL) {
            assertEquals(setOf(ErrorCode.REPAIR_LIMIT), result.diagnostics.errors.map { it.code }.toSet())
            assertTrue(StopReason.REPAIR_LIMIT in result.diagnostics.stopReasons)
        } else assertTrue(result.diagnostics.errors.isEmpty())
        val input = checkNotNull(report.input)
        val baseline = checkNotNull(result.baseline)
        val ids = input.queries.map { it.id }.toSet()
        assertEquals(ids, baseline.costByQueryId.keys)
        assertEquals(setOf(StatementKind.SELECT, StatementKind.UPDATE, StatementKind.DELETE), report.statementKinds.values.toSet())
        assertEquals(ComparisonScope.EVALUATED_SETS, report.comparisonScope)
        assertEquals(1, report.costGuards.size)
        assertFalse(report.costGuards.single().violated)
        for (configuration in listOf(checkNotNull(result.bestRobust), checkNotNull(result.bestMean))) {
            assertEquals(ids, configuration.evaluation.costByQueryId.keys)
            assertEquals(baseline.costByQueryId.getValue("all_records"), configuration.evaluation.costByQueryId.getValue("all_records"))
            val candidates = report.candidates.candidates.filter { it.id in configuration.evaluation.candidateIds }
            val budget = metrics.assessBudget(candidates, input)
            assertTrue(budget.admissible)
            assertEquals(configuration.metrics.budgetUsage, budget.usage)
            // Independent arithmetic oracle: each table rate once per key/INCLUDE column, never DML calls.
            val writes = candidates.sumOf { candidate ->
                val table = candidate.definition.table
                val rates = checkNotNull(input.tableWriteRates).getValue("${table.schema}.${table.name}")
                (rates.insertsPerHour + rates.updatesPerHour + rates.deletesPerHour) *
                    (candidate.definition.keys.size + candidate.definition.includedColumns.size)
            }
            assertEquals(writes, checkNotNull(budget.usage.writeUnitsPerHour), 1e-8 * maxOf(1.0, writes))
            val changedCalls = input.copy(profiles = input.profiles.map { profile -> profile.copy(
                callsPerHour = profile.callsPerHour.mapValues { it.value * 100 }) })
            assertEquals(budget, metrics.assessBudget(candidates, changedCalls))
            val manualRates = input.copy(tableWriteRatesOrigin = null,
                tableWriteRates = input.tableWriteRates?.mapValues { it.value.copy(source = WriteRateSource.MANUAL) })
            assertEquals(budget, metrics.assessBudget(candidates, manualRates))
        }
        val json = mapper.readTree(Files.readString(destination.resolve("report.json")))
        assertEquals(report.runId, json["run_id"].asString())
        assertEquals(report.inputHash, json["input_hash"].asString())
        assertEquals(result.selected?.evaluation?.candidateIds, json["selected"]["candidate_ids"].toList().map { it.asString() })
        for (file in listOf("report.html", "indexes.sql")) {
            val text = Files.readString(destination.resolve(file))
            assertTrue(text.contains(report.runId))
            assertTrue(text.contains(checkNotNull(report.inputHash)))
        }
        val sql = Files.readString(destination.resolve("indexes.sql"))
        assertEquals(json["selected_indexes"].toList().map { it["ddl"].asString().trim().removeSuffix(";") }.sorted(),
            sql.lineSequence().filter { it.startsWith("CREATE INDEX") }.map { it.trim().removeSuffix(";") }.sorted().toList())
    }

    /** Projection of saved report fields; no second implementation of M/R or price of robustness. */
    private fun runSummary(path: Path, report: AnalysisReport): Map<String, Any?> {
        val json = mapper.readTree(JsonReportWriter().render(report))
        fun configuration(name: String): JsonNode = (json[name].deepCopy() as ObjectNode).also {
            it.remove("plans_by_query_id")
        }
        fun worst(value: EvaluatedConfiguration?) = value?.metrics?.profiles
            ?.filter { it.ratio == value.metrics.worstRatio }?.map { it.profileId }
        return mapOf("directory" to path.toString(), "run_id" to report.runId,
            "input_hash" to report.inputHash, "status" to json["status"], "budgets" to json["budgets"],
            "comparison_scope" to json["comparison_scope"], "incumbents_coincide" to report.incumbentsCoincide,
            "price_of_robustness" to report.priceOfRobustness, "duration_millis" to report.durationMillis,
            "best_robust" to configuration("best_robust"), "best_mean" to configuration("best_mean"),
            "worst_profiles_robust" to worst(report.result.bestRobust), "worst_profiles_mean" to worst(report.result.bestMean),
            "selected_indexes" to json["selected_indexes"], "cost_guards" to json["cost_guards"],
            "manual_comparison" to (json["manual_comparison"].deepCopy() as? ObjectNode)?.also {
                (it["configuration"] as? ObjectNode)?.remove("plans_by_query_id")
            }, "statistics" to json["diagnostics"]["statistics"], "stop_reasons" to json["diagnostics"]["stop_reasons"],
            "guard_rejections" to report.result.diagnostics.warnings.count {
                it.details["code"] == "CRITICAL_QUERY_REGRESSION"
            })
    }

    private fun writeJson(path: Path, value: Any) {
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n")
    }
    private fun sha256(path: Path) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
    private fun analysis(): Connection = DriverManager.getConnection(System.getenv("INDEXBUDGET_SCENARIOS_TEST_URL"),
        "indexbudget_scenarios", System.getenv("INDEXBUDGET_SCENARIOS_PASSWORD"))
    private fun admin(): Connection = DriverManager.getConnection(System.getenv("INDEXBUDGET_SCENARIOS_TEST_URL"),
        "demo_admin", System.getenv("INDEXBUDGET_SCENARIOS_ADMIN_PASSWORD") ?: "")
    private fun scalar(connection: Connection, sql: String): String = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
    }
    private fun fingerprint(connection: Connection) = listOf("records", "groups").associateWith { table ->
        scalar(connection, "SELECT pg_catalog.md5(pg_catalog.string_agg(pg_catalog.md5(r::text), '' ORDER BY id)) FROM public.$table r")
    }
    private fun physicalIndexes(connection: Connection) = scalar(connection,
        "SELECT string_agg(indexdef, E'\\n' ORDER BY indexname) FROM pg_catalog.pg_indexes WHERE schemaname='public'")
}
