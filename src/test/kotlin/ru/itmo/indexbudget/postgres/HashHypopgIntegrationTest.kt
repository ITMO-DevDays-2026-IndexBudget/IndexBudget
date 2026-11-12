package ru.itmo.indexbudget.postgres

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import ru.itmo.indexbudget.application.*
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.candidates.BtreeCandidateCanonicalizer
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.report.*
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Only the explicitly selected disposable test DB; fixtures never run against the shared stand. */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_HYPOPG_TEST_URL", matches = ".+")
class HashHypopgIntegrationTest {
    private val mapper = JsonMapper()

    @Test
    fun `hash mixed sets real costs budgets catalog cleanup and artifacts remain consistent`() {
        Files.createDirectories(Path.of("build/hash-verification"))
        val output = Files.createTempDirectory(Path.of("build/hash-verification"), "run-")
        connect().use { admin ->
            execute(admin, "CREATE EXTENSION IF NOT EXISTS hypopg WITH SCHEMA public")
            execute(admin, "CREATE SCHEMA hash_demo")
            try {
                execute(admin, "CREATE TABLE hash_demo.events (sequence integer, payload varchar(160) COLLATE \"C\", unsupported json)")
                execute(admin, "INSERT INTO hash_demo.events SELECT i, repeat('x', 160), '{}'::json FROM generate_series(1, 200000) i")
                execute(admin, "VACUUM ANALYZE hash_demo.events")
                val source = output.resolve("workload.json")
                Files.copy(Path.of("examples/workload-v2-hash.json"), source)
                val input = WorkloadReader().read(source)
                val context = RunContext()
                val analyzer = JSqlParserSqlAnalyzer()
                val parsed = analyzer.parse(input, context)
                val samples = mutableListOf<Map<String, Any?>>()
                var largestBudgeted = 0L
                connect().use { connection ->
                    JdbcAnalysisSession(connection).use { session ->
                        val catalog = session.readCatalog(parsed.referencedTables, context)
                        val columns = catalog.tables.single().columns.associateBy { it.name }
                        assertEquals("pg_catalog.int4_ops", columns.getValue("sequence").defaultOperatorClasses["hash"])
                        assertEquals("pg_catalog.text_ops", columns.getValue("payload").defaultOperatorClasses["hash"])
                        assertNull(columns.getValue("unsupported").defaultOperatorClasses["hash"])
                        val resolved = analyzer.resolve(parsed, catalog, context)
                        val baseline = session.evaluate(emptyList(), resolved, context)
                        val batch = ProfileAwareCandidateGenerator().generate(resolved, catalog, context, baseline)
                        assertEquals(setOf("btree", "hash"), batch.candidates.map { it.definition.method }.toSet())
                        assertEquals(2, batch.candidates.size)
                        val sized = batch.candidates.map { it.copy(estimatedBytes = session.estimateSize(it, context)) }
                        assertTrue(sized.all { it.estimatedBytes!! > 0 })
                        val metrics = MetricsCalculatorImpl()
                        largestBudgeted = metrics.assessBudget(sized, input).usage.budgetedBytes
                        for (set in listOf(emptyList<IndexCandidate>()) + sized.map { listOf(it) } + listOf(sized)) {
                            val evaluation = session.evaluate(set, resolved, context)
                            assertEquals(setOf("equality", "all_rows"), evaluation.costByQueryId.keys)
                            assertEquals(0, scalar(connection, "SELECT count(*) FROM public.hypopg()"))
                            assertEquals(baseline.costByQueryId.getValue("all_rows"), evaluation.costByQueryId["all_rows"])
                            if (set.isNotEmpty()) assertTrue(evaluation.costByQueryId.getValue("equality") < baseline.costByQueryId.getValue("equality"))
                            val budget = metrics.assessBudget(set, input)
                            assertTrue(budget.admissible)
                            assertEquals(115.0 * set.size, budget.usage.writeUnitsPerHour)
                            samples += mapOf("methods" to set.map { it.definition.method }, "cost_by_query_id" to evaluation.costByQueryId,
                                "estimated_bytes" to budget.usage.estimatedBytes, "budgeted_bytes" to budget.usage.budgetedBytes,
                                "write_units_per_hour" to budget.usage.writeUnitsPerHour)
                        }
                        val original = sized.single { it.definition.method == "hash" }
                        val bad = original.copy(definition = original.definition.copy(keys = original.definition.keys.map {
                            it.copy(operatorClass = "pg_catalog.int4_minmax_ops")
                        }))
                        assertThrows(AnalysisException::class.java) { session.estimateSize(bad, context) }
                        assertEquals(0, scalar(connection, "SELECT count(*) FROM public.hypopg()"))
                        assertEquals(baseline.costByQueryId, session.evaluate(emptyList(), resolved, context).costByQueryId)
                        execute(admin, "CREATE INDEX real_hash ON hash_demo.events USING hash (sequence)")
                        var after = session.readCatalog(parsed.referencedTables, context)
                        assertEquals("hash", after.tables.single().existingIndexes.single().method)
                        val realDefinition = IndexDefinition(after.tables.single().ref,
                            after.tables.single().existingIndexes.single().keys, method = "hash")
                        assertEquals(original.definition, BtreeCandidateCanonicalizer(after, context).canonicalize(realDefinition))
                        var generated = ProfileAwareCandidateGenerator().generate(resolved, after, context)
                        assertEquals("btree", generated.candidates.single().definition.method)
                        assertTrue(generated.diagnostics.decisions.any { it.reason == CandidateReason.EXACT_EXISTING_DUPLICATE })
                        execute(admin, "ALTER INDEX hash_demo.real_hash SET (fillfactor = 75)")
                        after = session.readCatalog(parsed.referencedTables, context)
                        assertFalse(after.tables.single().existingIndexes.single().defaultStorageParameters)
                        generated = ProfileAwareCandidateGenerator().generate(resolved, after, context)
                        assertEquals(2, generated.candidates.size)
                        execute(admin, "DROP INDEX hash_demo.real_hash")
                        execute(admin, "CREATE INDEX real_text_hash ON hash_demo.events USING hash (payload)")
                        val textKey = session.readCatalog(parsed.referencedTables, context).tables.single().existingIndexes.single().keys.single()
                        assertEquals(columns.getValue("payload").defaultOperatorClasses["hash"], textKey.operatorClass)
                        assertEquals(columns.getValue("payload").collation, textKey.collation)
                        execute(admin, "DROP INDEX hash_demo.real_text_hash")
                    }
                }
                val connections = mutableListOf<Connection>()
                val sizeCalls = mutableListOf<Int>()
                val reports = mutableListOf<AnalysisReport>()
                val pipeline = pipeline(connections, sizeCalls, reports)
                val analysisContext = RunContext()
                val run = pipeline.run(WorkloadReader().read(source, analysisContext), output.resolve("analysis"), analysisContext)
                assertEquals(RunStatus.COMPLETED, run.status)
                val report = reports.single()
                assertEquals(4, report.schemaVersion)
                assertEquals(ManualComparisonStatus.COMPLETED, report.manualComparison!!.status)
                assertTrue(report.manualComparison.admissible)
                assertTrue(report.candidates.diagnostics.preparation!!.sizeAttempts <= 32)
                assertTrue(report.result.diagnostics.statistics.explainAttempts <= 2000)
                val json = mapper.readTree(Files.readString(output.resolve("analysis/report.json")))
                assertEquals(listOf("btree", "hash"), json["index_methods"].toList().map { it.stringValue() })
                val html = Files.readString(output.resolve("analysis/report.html"))
                val sql = Files.readString(output.resolve("analysis/indexes.sql"))
                assertTrue(html.contains(report.runId) && sql.contains(report.runId))
                assertTrue(html.contains(report.inputHash!!) && sql.contains(report.inputHash))
                assertTrue(html.contains("btree, hash") && sql.contains("-- index_methods: btree,hash"))
                for (candidate in report.candidates.candidates.filter { it.id in report.result.selected!!.evaluation.candidateIds }) {
                    assertTrue(sql.contains(candidate.ddl!!))
                }
                val seriesJson = mapper.readTree(Files.readString(source)) as tools.jackson.databind.node.ObjectNode
                seriesJson.remove("manual_indexes")
                val seriesSource = output.resolve("workload-series.json")
                Files.writeString(seriesSource, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(seriesJson))
                for (budget in listOf(SeriesBudget.STORAGE, SeriesBudget.WRITE)) {
                    val before = sizeCalls.size
                    val values = if (budget == SeriesBudget.STORAGE) listOf(0L, largestBudgeted / 2, largestBudgeted)
                        else listOf(0L, 115L, 230L)
                    val seriesContext = RunContext()
                    val seriesInput = WorkloadReader().read(seriesSource, seriesContext)
                    val result = BudgetSeriesPipeline(pipeline, ShutdownCancellation { AutoCloseable {} }).run(seriesInput,
                        BudgetSeriesRequest(budget, values.map(BigDecimal::valueOf)), output.resolve("series-${budget.name}"), seriesContext)
                    assertEquals(SeriesStatus.COMPLETED, result.status)
                    assertEquals(listOf(2, 0, 0, 0), sizeCalls.drop(before))
                    assertTrue(result.points.first().report!!.result.selected!!.evaluation.candidateIds.isEmpty())
                    assertTrue(result.points.all { it.report!!.schemaVersion == 4 })
                    val summary = mapper.readTree(Files.readString(output.resolve("series-${budget.name}/series.json")))
                    assertEquals(2, summary["schema_version"].intValue())
                    assertEquals(setOf("btree", "hash"), summary["preparation"]["candidates"].toList().map { it["method"].stringValue() }.toSet())
                }
                assertTrue(connections.all { it.isClosed })
                assertEquals(0, scalar(admin, "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = 'hash_demo'"))
                assertEquals(200000, scalar(admin, "SELECT count(*) FROM hash_demo.events"))
                Files.writeString(output.resolve("measurements.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf(
                    "postgres_version" to string(admin, "SHOW server_version"),
                    "hypopg_version" to string(admin, "SELECT extversion FROM pg_extension WHERE extname = 'hypopg'"),
                    "rows" to 200000, "samples" to samples,
                    "note" to "Planner costs and hypothetical sizes, not runtime or measured write overhead.")) + "\n")
            } finally { execute(admin, "DROP SCHEMA hash_demo CASCADE") }
        }
    }

    private fun pipeline(connections: MutableList<Connection>, sizes: MutableList<Int>, reports: MutableList<AnalysisReport>) = AnalysisPipeline(
        sqlAnalyzer = JSqlParserSqlAnalyzer(), sessionFactory = object : AnalysisSessionFactory {
            override fun open(context: RunContext): AnalysisSession {
                val connection = connect().also(connections::add)
                val actual = JdbcAnalysisSession(connection)
                val position = sizes.size
                sizes += 0
                return object : AnalysisSession by actual {
                    override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long {
                        sizes[position]++
                        return actual.estimateSize(candidate, context)
                    }
                    override fun close() {
                        assertEquals(0, scalar(connection, "SELECT count(*) FROM public.hypopg()"))
                        actual.close()
                    }
                }
            }
        }, candidateGenerator = ProfileAwareCandidateGenerator(), metrics = MetricsCalculatorImpl(),
        comparator = ObjectiveComparatorImpl(), optimizer = LimitedRepairOptimizer(), reportBuilder = DefaultReportBuilder(),
        reportExporter = ReportExporter { report, destination, context ->
            assertTrue(connections.all { it.isClosed })
            val before = context.statistics()
            AtomicReportExporter(HtmlReportRenderer()).export(report, destination, context)
            assertEquals(before, context.statistics())
            reports += report
        }, shutdownCancellation = ShutdownCancellation { AutoCloseable {} })

    private fun connect() = DriverManager.getConnection(System.getenv("INDEXBUDGET_HYPOPG_TEST_URL"),
        System.getenv("INDEXBUDGET_HYPOPG_TEST_USER"), System.getenv("INDEXBUDGET_HYPOPG_TEST_PASSWORD"))
    private fun execute(connection: Connection, sql: String) = connection.createStatement().use { it.execute(sql) }
    private fun scalar(connection: Connection, sql: String) = string(connection, sql).toInt()
    private fun string(connection: Connection, sql: String) = connection.createStatement().use {
        it.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
    }
}
