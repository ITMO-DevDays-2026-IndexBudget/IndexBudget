package ru.itmo.indexbudget.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.AnalysisPipeline
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.cli.ImportWorkloadCommand
import ru.itmo.indexbudget.cli.IndexBudgetCli
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.postgres.AnalysisSessionFactory
import ru.itmo.indexbudget.postgres.JdbcAnalysisSessionFactory
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import ru.itmo.indexbudget.statistics.*
import tools.jackson.databind.JsonNode
import java.io.PrintWriter
import java.io.StringWriter
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/** Only the disposable database from demo/check-import.sh; no saved or fabricated cost vectors. */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_STATS_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_IMPORT_HYPOPG_TEST", matches = "true")
class ReportImportIntegrationTest {
    @TempDir lateinit var directory: Path
    private val mapper = ImportJson.mapper
    private val url get() = System.getenv("INDEXBUDGET_STATS_TEST_URL")
    private fun connection() = DriverManager.getConnection(url, "postgres", "")

    @Test
    fun `real imported windows and recommendation survive shared model and both renderers without new evaluations`() {
        val scenario = importObservedWindows()
        val workload = scenario.workload
        val input = WorkloadReader().read(workload)
        assertManifestProvenance(input, scenario)

        var sessionClosed = false
        var openedSessions = 0
        val jdbc = JdbcAnalysisSessionFactory({ url + (if ('?' in url) "&" else "?") + "user=postgres" })
        val sessions = object : AnalysisSessionFactory {
            override fun open(context: RunContext): AnalysisSession {
                openedSessions++
                val delegate = jdbc.open(context)
                return object : AnalysisSession by delegate {
                    override fun close() { delegate.close(); sessionClosed = true }
                }
            }
        }
        var savedReport: AnalysisReport? = null
        var beforeReport: RunStatistics? = null
        val builder = ReportBuilder { value, candidates, result, context ->
            assertTrue(sessionClosed, "Report must be built after the real analysis session closes")
            beforeReport = context.statistics()
            DefaultReportBuilder().build(value, candidates, result, context).also { savedReport = it }
        }
        val pipeline = AnalysisPipeline(JSqlParserSqlAnalyzer(), sessions, ProfileAwareCandidateGenerator(),
            MetricsCalculatorImpl(), ObjectiveComparatorImpl(), LimitedRepairOptimizer(), builder,
            AtomicReportExporter(HtmlReportRenderer()))
        val context = RunContext()
        val output = directory.resolve("analysis")
        val errors = StringWriter()
        val exit = IndexBudgetCli(WorkloadReader(), analysisRunner = pipeline).execute(arrayOf("analyze", "--workload",
            workload.toString(), "--output-dir", output.toString()), PrintWriter(StringWriter(), true),
            PrintWriter(errors, true), context)
        assertEquals(0, exit, errors.toString())
        assertEquals(1, openedSessions)
        assertTrue(context.statistics().explainAttempts > input.queries.size)
        assertEquals(beforeReport, context.statistics(), "Building and exporting the report must not evaluate SQL")
        val report = requireNotNull(savedReport)
        assertEquals(input.provenance, report.workloadProvenance)
        assertEquals(RunStatus.COMPLETED, report.summary.status)
        assertTrue(requireNotNull(report.summary.selectedIndexIds).isNotEmpty())
        assertTrue(requireNotNull(report.summary.meanEffect.gainPercent) > 0, "Fixture must distinguish a real gain from 0 %")
        assertNull(report.summary.budgetUsage?.writeUnitsPerHour)
        assertEquals(false, report.summary.writeLimitEnabled)

        assertJsonReport(report, workload, output)
        assertHtmlReport(report, output)
        val sql = Files.readString(output.resolve("indexes.sql"))
        assertTrue(sql.contains(context.runId))
        assertTrue(sql.contains(requireNotNull(context.inputHash)))
        val selectedIds = requireNotNull(report.summary.selectedIndexIds)
        assertEquals(report.candidates.candidates.filter { it.id in selectedIds }.sortedBy { it.id }.map { it.ddl },
            sql.lineSequence().filter { it.startsWith("CREATE INDEX ") }.toList())
        assertEquals(beforeReport, context.statistics())

        saveEvidence(workload, output)
    }

    private fun assertManifestProvenance(input: WorkloadInput, scenario: ImportedScenario) {
        val workload = scenario.workload
        val manifestBytes = Files.readAllBytes(workload.resolveSibling("import-manifest.json"))
        val manifest = mapper.readTree(manifestBytes)
        val provenance = requireNotNull(input.provenance)
        val periods = manifest["periods"].associateBy { it["profile_id"].asString() }
        assertEquals(listOf("normal", "campaign"), input.profiles.map { it.id })
        assertEquals(2, input.queries.size)
        assertEquals(manifest["source_id"].asString(), provenance.sourceId.toString())
        assertEquals("${manifest["selection_rule"].asString()}; import_manifest=import-manifest.json; " +
            "sha256=${ObservedWorkloadImporter.hash(manifestBytes)}", provenance.selectionRule)
        val excludedKey = mapper.readTree(mapper.writeValueAsBytes(ImportJson.key(scenario.excludedKey)))
        val excludedEntry = manifest["entries"].single { it["key"] == excludedKey }
        assertEquals("excluded", excludedEntry["status"].asString())
        assertEquals("MISSING_EXAMPLE", excludedEntry["reason"].asString())
        val selectedEntries = manifest["entries"].filter { it["status"].asString() == "selected" }
        assertEquals(input.queries.map { it.id }.toSet(), selectedEntries.map { it["query_id"].asString() }.toSet())
        for (query in input.queries) {
            val entry = selectedEntries.single { it["query_id"].asString() == query.id }
            assertTrue(entry["representative_example"].booleanValue())
            assertEquals(ObservedWorkloadImporter.hash(query.sql.toByteArray()), entry["sql_sha256"].asString())
            assertEquals(QueryExampleSource.USER_SUPPLIED_LITERALS,
                provenance.queryExamples.single { it.queryId == query.id }.source)
        }
        for (window in provenance.windows) {
            val period = periods.getValue(window.profileId)
            assertEquals(Instant.parse(period["start"].asString()), window.startedAt)
            assertEquals(Instant.parse(period["end"].asString()), window.finishedAt)
            assertEquals(period["complete"].booleanValue(), window.complete)
            assertEquals(period["selected_calls"].longValue(), window.coverage.selectedCalls)
            assertEquals(period["available_calls"].longValue(), window.coverage.availableCalls)
            assertEquals(period["selected_historical_exec_time_ms"].doubleValue(), window.coverage.selectedExecTimeMillis)
            assertEquals(period["available_historical_exec_time_ms"].doubleValue(), window.coverage.availableExecTimeMillis)
            assertEquals(if (window.profileId == "normal") 10L else 11L, window.coverage.selectedCalls)
            val (before, after) = scenario.windows.getValue(window.profileId)
            val deltas = snapshotDeltas(before, after)
            val excludedDelta = deltas.getValue(scenario.excludedKey)
            assertEquals(if (window.profileId == "normal") 5L else 4L, excludedDelta.calls)
            assertTrue(excludedDelta.execTimeMillis.signum() > 0)
            val selected = scenario.selectedKeys.map(deltas::getValue)
            assertEquals(selected.sumOf { it.calls }, window.coverage.selectedCalls)
            assertEquals(totalExecTime(selected), requireNotNull(window.coverage.selectedExecTimeMillis), 1e-9)
            // Neither the importer nor its manifest supplies the expected denominator. Collector SQL
            // contributes only its actual matched delta; it cannot compensate for a missing third SQL.
            assertEquals(deltas.values.sumOf { it.calls }, period["available_calls"].longValue())
            assertEquals(deltas.values.sumOf { it.calls }, window.coverage.availableCalls)
            val expectedTime = totalExecTime(deltas.values)
            assertEquals(expectedTime, period["available_historical_exec_time_ms"].doubleValue(), 1e-9)
            assertEquals(expectedTime, requireNotNull(window.coverage.availableExecTimeMillis), 1e-9)
        }
    }

    private data class ObservedDelta(val calls: Long, val execTimeMillis: BigDecimal)

    /** Oracle for this complete, unchanged-epoch fixture, independent of FrequencyCalculator/importer. */
    private fun snapshotDeltas(before: StatisticsSnapshot, after: StatisticsSnapshot): Map<StatisticsKey, ObservedDelta> {
        assertTrue(before.complete)
        assertTrue(after.complete)
        assertEquals(before.source, after.source)
        assertEquals(before.before, before.after)
        assertEquals(before.before, after.before)
        assertEquals(before.before, after.after)
        fun entries(snapshot: StatisticsSnapshot): Map<StatisticsKey, StatisticsEntry> {
            val identified = snapshot.entries.filter { it.toplevel && it.queryid != null }
            return identified.associateBy { entry ->
                StatisticsKey(snapshot.source.sourceId, entry.dbid, entry.userid, requireNotNull(entry.queryid), entry.toplevel)
            }.also { assertEquals(identified.size, it.size) }
        }
        val first = entries(before)
        val last = entries(after)
        // New or missing keys do not have a known delta and are outside the denominator.
        return first.keys.intersect(last.keys).associateWith { key ->
            val start = first.getValue(key)
            val end = last.getValue(key)
            assertEquals(start.query, end.query)
            assertTrue(end.calls >= start.calls && end.rows >= start.rows && end.totalExecTime >= start.totalExecTime)
            if (end.calls == start.calls) {
                assertEquals(start.rows, end.rows)
                assertEquals(start.totalExecTime, end.totalExecTime)
            }
            ObservedDelta(end.calls - start.calls, end.totalExecTime.toBigDecimal() - start.totalExecTime.toBigDecimal())
        }
    }

    private fun totalExecTime(deltas: Collection<ObservedDelta>): Double =
        deltas.fold(BigDecimal.ZERO) { total, delta -> total + delta.execTimeMillis }.toDouble()

    private fun assertJsonReport(report: AnalysisReport, workload: Path, output: Path) {
        val json = mapper.readTree(Files.readAllBytes(output.resolve("report.json")))
        val originalJson = mapper.readTree(Files.readAllBytes(workload))
        assertEquals(originalJson["provenance"], json["workload_provenance"])
        assertEquals(report.runId, json["run_id"].asString())
        assertEquals(report.inputHash, json["input_hash"].asString())
        val summary = json["summary"]
        assertEquals(json["selected"]["candidate_ids"], summary["selected_index_ids"])
        assertEquals(json["selected"]["metrics"]["budget_usage"], summary["budget_usage"])
        assertEquals(json["budgets"], summary["budget_limits"])
        assertEquals(json["selected"]["metrics"]["mean_ratio"], summary["mean_effect"]["ratio"])
        assertEquals(json["selected"]["metrics"]["worst_ratio"], summary["worst_ratio"])
        assertEquals(report.summary.meanEffect.gainPercent, summary["mean_effect"]["gain_percent"].doubleValue())
        assertEquals(report.summary.regressedQueryCount, summary["regressed_query_count"].intValue())
        assertEquals("evaluated_sets", summary["comparison_scope"].asString())
        for (field in listOf("status", "incumbents_coincide", "price_of_robustness")) assertEquals(json[field], summary[field])
        for (field in listOf("stop_reasons", "errors")) assertEquals(json["diagnostics"][field], summary[field])
        assertSeries(report.profileComparisons, json["profile_comparisons"])
        assertSeries(report.queryEffects, json["query_effects"])
    }

    private fun assertHtmlReport(report: AnalysisReport, output: Path) {
        val provenance = requireNotNull(report.workloadProvenance)
        val html = Files.readString(output.resolve("report.html"))
        assertEquals(HtmlReportRenderer().render(report), html)
        val overview = html.substringAfter("id=\"overview\"").substringBefore("</section>")
        val cards = Regex("<div class=\"metric-label\">([^<]+)</div><strong>([^<]+)</strong>")
            .findAll(overview).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(6, cards.size)
        val values = cards.toMap()
        val summary = report.summary
        assertDisplayedNumber(summary.worstRatio, values.getValue("Худшая нормированная стоимость"))
        assertDisplayedNumber(summary.meanEffect.ratio, values.getValue("Средняя нормированная стоимость"))
        assertDisplayedNumber(summary.budgetUsage?.indexCount?.toDouble(), values.getValue("Выбранные индексы"))
        val gain = values.getValue("Снижение взвешенной стоимости планов")
        assertTrue(gain.endsWith(" %"))
        assertDisplayedNumber(summary.meanEffect.gainPercent, gain.removeSuffix(" %"))
        assertDisplayedNumber(summary.regressedQueryCount?.toDouble(), values.getValue("SQL с ростом стоимости"))
        assertDisplayedNumber(report.result.diagnostics.statistics.completedEvaluations.toDouble(),
            values.getValue("Полностью оценённые наборы"))
        val provenanceSection = html.substringAfter("id=\"provenance\"").substringBefore("</section>")
        assertTrue(provenanceSection.contains(provenance.sourceId.toString()))
        assertTrue(provenanceSection.contains(provenance.selectionRule))
        assertTrue(provenanceSection.contains("Историческое время выполнения"))
        assertTrue(provenanceSection.contains("не являются прогнозом времени с новыми индексами"))
        val rows = htmlRows(provenanceSection)
        for (window in provenance.windows) {
            val windowRows = rows.filter { it.first() == window.profileId }
            val coverage = windowRows.single { it.size == 5 }
            assertEquals(listOf(window.profileId, window.startedAt.toString(), window.finishedAt.toString(),
                if (window.complete) "Полное" else "Неполное",
                "${window.coverage.selectedCalls} / ${window.coverage.availableCalls}"), coverage)
            val history = windowRows.single { it.size == 3 }
            assertDisplayedNumber(window.coverage.selectedExecTimeMillis, history[1])
            assertDisplayedNumber(window.coverage.availableExecTimeMillis, history[2])
        }
        for (query in requireNotNull(report.input).queries) assertTrue(rows.contains(listOf(query.id, "Репрезентативные литералы пользователя")))
        val profileRows = htmlRows(html.substringAfter("id=\"profiles\"").substringBefore("</section>"))
        for (series in report.profileComparisons) {
            val row = profileRows.single { it.first() == series.id && it.size == 5 }
            listOf(series.baseline, series.selected, series.bestRobust, series.bestMean).forEachIndexed { i, effect ->
                assertDisplayedNumber(effect.cost, row[i + 1])
            }
        }
    }

    private fun saveEvidence(workload: Path, output: Path) {
        // Reproducible evidence from this actual run, kept out of Git; CI publishes it alongside test results.
        val evidence = Path.of("build/report-import-verification")
        Files.createDirectories(evidence)
        for (name in listOf("before.json", "middle.json", "after.json", "examples.json", "config.json"))
            Files.copy(directory.resolve(name), evidence.resolve(name), REPLACE_EXISTING)
        for (name in listOf("workload.json", "import-manifest.json"))
            Files.copy(workload.resolveSibling(name), evidence.resolve(name), REPLACE_EXISTING)
        for (name in listOf("report.json", "report.html", "indexes.sql"))
            Files.copy(output.resolve(name), evidence.resolve(name), REPLACE_EXISTING)
    }

    private fun assertSeries(expected: List<CostComparisonSeries>, actual: JsonNode) {
        assertEquals(expected.map { it.id }, actual.toList().map { it["id"].asString() })
        expected.zip(actual.toList()).forEach { (series, node) ->
            for ((key, effect) in mapOf("baseline" to series.baseline, "selected" to series.selected,
                "best_robust" to series.bestRobust, "best_mean" to series.bestMean)) {
                for ((field, value) in mapOf("baseline_cost" to effect.baselineCost, "cost" to effect.cost,
                    "gain" to effect.gain, "ratio" to effect.ratio, "gain_percent" to effect.gainPercent)) {
                    if (value == null) assertTrue(node[key][field].isNull)
                    else assertEquals(value, node[key][field].doubleValue())
                }
                assertEquals(effect.relativeStatus.name.lowercase(), node[key]["relative_status"].asString())
            }
        }
    }

    private fun htmlRows(html: String): List<List<String>> = Regex("<tr>(.*?)</tr>").findAll(html).map { row ->
        Regex("<t[hd][^>]*>(.*?)</t[hd]>").findAll(row.groupValues[1]).map { it.groupValues[1] }.toList()
    }.toList()

    private fun assertDisplayedNumber(expected: Double?, text: String) {
        val actual = text.replace("\u202f", "").replace(',', '.').toDouble()
        assertEquals(requireNotNull(expected), actual, 1e-9, "HTML may only round for display")
    }

    private data class ImportedScenario(
        val workload: Path,
        val windows: Map<String, Pair<StatisticsSnapshot, StatisticsSnapshot>>,
        val selectedKeys: List<StatisticsKey>,
        val excludedKey: StatisticsKey,
    )

    private fun importObservedWindows(): ImportedScenario {
        val source = UUID.fromString("d0d60c19-eb9d-42ee-9e01-935560eb7e20")
        val schema = "report_import_" + UUID.randomUUID().toString().replace("-", "")
        connection().use { c -> c.createStatement().use { statement ->
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements")
            statement.execute("CREATE EXTENSION IF NOT EXISTS hypopg")
            statement.execute("CREATE SCHEMA $schema")
            statement.execute("CREATE TABLE $schema.records(value integer, group_id integer)")
            statement.execute("INSERT INTO $schema.records SELECT i, i % 100 FROM generate_series(1,5000) i")
            statement.execute("ANALYZE $schema.records")
        } }
        val queries = listOf("SELECT value FROM $schema.records WHERE value = 7",
            "SELECT group_id FROM $schema.records WHERE group_id = 42",
            "SELECT value FROM $schema.records WHERE value > 42")
        fun execute(counts: List<Int>) = connection().use { c -> c.createStatement().use { statement ->
            queries.zip(counts).forEach { (sql, count) -> repeat(count) {
                statement.executeQuery(sql).use { rows -> while (rows.next()) { /* consume the observed result */ } }
            } }
        } }
        execute(listOf(1, 1, 1))
        val collector = JdbcStatisticsCollector({ connection() })
        val before = collector.collect(source, SnapshotLimits(), RunContext())
        execute(listOf(7, 3, 5))
        val middle = collector.collect(source, SnapshotLimits(), RunContext())
        execute(listOf(2, 9, 4))
        val after = collector.collect(source, SnapshotLimits(), RunContext())
        for ((name, snapshot) in mapOf("before" to before, "middle" to middle, "after" to after))
            Files.write(directory.resolve("$name.json"), SnapshotJson().render(snapshot))
        val keys = queries.map { sql ->
            val row = after.entries.single { it.query?.startsWith(sql.substringBeforeLast(' ') + " ") == true }
            StatisticsKey(source, row.dbid, row.userid, requireNotNull(row.queryid), row.toplevel)
        }
        val examples = queries.take(2).zip(keys).map { (sql, key) ->
            mapOf("key" to ImportJson.key(key), "sql" to sql, "search_path" to listOf("pg_catalog", schema))
        }
        Files.write(directory.resolve("examples.json"), mapper.writeValueAsBytes(mapOf("schema_version" to 1,
            "kind" to "pg_stat_statements_examples", "examples" to examples)))
        Files.write(directory.resolve("config.json"), mapper.writeValueAsBytes(mapOf("schema_version" to 1,
            "kind" to "pg_stat_statements_import_config", "windows" to listOf(
                mapOf("id" to "normal", "weight" to 3, "before" to "before.json", "after" to "middle.json"),
                mapOf("id" to "campaign", "weight" to 1, "before" to "middle.json", "after" to "after.json")),
            "examples" to "examples.json", "constraints" to mapOf("storage_budget_bytes" to 10000000,
                "write_budget_units_per_hour" to null))))
        val output = directory.resolve("imported")
        val errors = StringWriter()
        val exit = ImportWorkloadCommand { connection() }.execute(arrayOf("import-workload", "--config",
            directory.resolve("config.json").toString(), "--output-dir", output.toString()),
            PrintWriter(StringWriter(), true), PrintWriter(errors, true))
        assertEquals(0, exit, errors.toString())
        return ImportedScenario(output.resolve("workload.json"),
            mapOf("normal" to (before to middle), "campaign" to (middle to after)), keys.take(2), keys.last())
    }
}
