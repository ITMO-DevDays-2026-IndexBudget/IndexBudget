package ru.itmo.indexbudget.postgres

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import ru.itmo.indexbudget.application.*
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.report.*
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Only the explicitly selected disposable HypoPG test database, never the demo server. */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_HYPOPG_TEST_URL", matches = ".+")
class BrinHypopgIntegrationTest {
    private val mapper = JsonMapper()

    @Test
    fun `varchar BRIN default opclass and collation match a real index`() {
        connect().use { admin ->
            execute(admin, "CREATE SCHEMA brin_catalog_demo")
            try {
                execute(admin, "CREATE TABLE brin_catalog_demo.labels (label varchar(50) COLLATE \"C\")")
                execute(admin, "CREATE INDEX real_brin_label ON brin_catalog_demo.labels USING brin (label)")
                connect().use { connection ->
                    JdbcAnalysisSession(connection).use { session ->
                        val table = session.readCatalog(setOf(TableName("labels", "brin_catalog_demo")), RunContext())
                            .tables.single()
                        val column = table.columns.single()
                        val index = table.existingIndexes.single()
                        assertEquals("brin", index.method)
                        val actual = index.keys.single()
                        assertEquals("pg_catalog.text_minmax_ops", actual.operatorClass)
                        assertEquals("pg_catalog.\"C\"", actual.collation)
                        assertEquals(actual.operatorClass, column.defaultOperatorClasses["brin"])
                        assertEquals(actual.collation, column.collation)
                    }
                }
            } finally {
                execute(admin, "DROP SCHEMA brin_catalog_demo CASCADE")
            }
        }
    }

    @Test
    fun `real BRIN size costs budgets mixed sets cleanup and three matching artifacts`() {
        Files.createDirectories(Path.of("build/brin-verification"))
        val output = Files.createTempDirectory(Path.of("build/brin-verification"), "run-")
        connect().use { admin ->
            execute(admin, "CREATE EXTENSION IF NOT EXISTS hypopg WITH SCHEMA public")
            // A fixed schema makes the saved workload and IDs reproducible. Creation fails if already owned.
            execute(admin, "CREATE SCHEMA brin_demo")
            try {
                execute(admin, "CREATE TABLE brin_demo.events (sequence bigint, payload text, unsupported json)")
                execute(admin, "INSERT INTO brin_demo.events SELECT i, repeat('x', 160), '{}'::json FROM generate_series(1, 1000000) i")
                execute(admin, "VACUUM ANALYZE brin_demo.events")
                val source = output.resolve("workload.json")
                Files.writeString(source, Files.readString(Path.of("examples/workload-v2-brin.json")))
                val input = WorkloadReader().read(source)
                val context = RunContext()
                val parsed = JSqlParserSqlAnalyzer().parse(input, context)
                val samples: List<Map<String, Any?>>
                var brinBytes = 0L
                var btreeBytes = 0L
                connect().use { connection ->
                    JdbcAnalysisSession(connection).use { session ->
                        val catalog = session.readCatalog(parsed.referencedTables, context)
                        val columns = catalog.tables.single().columns
                        assertEquals("pg_catalog.int8_ops", columns.single { it.name == "sequence" }.defaultOperatorClass)
                        assertEquals("pg_catalog.int8_minmax_ops", columns.single { it.name == "sequence" }.defaultOperatorClasses["brin"])
                        assertNull(columns.single { it.name == "unsupported" }.defaultOperatorClasses["brin"])
                        val resolved = JSqlParserSqlAnalyzer().resolve(parsed, catalog, context)
                        val baseline = session.evaluate(emptyList(), resolved, context)
                        val batch = ProfileAwareCandidateGenerator().generate(resolved, catalog, context, baseline)
                        assertEquals(setOf("btree", "brin"), batch.candidates.map { it.definition.method }.toSet())
                        assertEquals(2, batch.candidates.size)
                        val sized = batch.candidates.map { it.copy(estimatedBytes = session.estimateSize(it, context)) }
                        brinBytes = checkNotNull(sized.single { it.definition.method == "brin" }.estimatedBytes) { "Expected estimatedBytes to be available" }
                        btreeBytes = checkNotNull(sized.single { it.definition.method == "btree" }.estimatedBytes) { "Expected estimatedBytes to be available" }
                        assertTrue(brinBytes > 0 && brinBytes < btreeBytes)
                        samples = (listOf(emptyList<IndexCandidate>()) + sized.map { listOf(it) } + listOf(sized)).map { set ->
                            val evaluation = session.evaluate(set, resolved, context)
                            assertEquals(input.queries.map { it.id }.toSet(), evaluation.costByQueryId.keys)
                            assertEquals(0, scalar(connection, "SELECT count(*) FROM public.hypopg()"))
                            val budget = MetricsCalculatorImpl().assessBudget(set, input)
                            mapOf("methods" to set.map { it.definition.method }, "cost_by_query_id" to evaluation.costByQueryId,
                                "estimated_bytes" to budget.usage.estimatedBytes, "budgeted_bytes" to budget.usage.budgetedBytes,
                                "write_units_per_hour" to budget.usage.writeUnitsPerHour)
                        }
                        val brinCosts = samples.single { it["methods"] == listOf("brin") }["cost_by_query_id"] as Map<*, *>
                        assertTrue((brinCosts["range"] as Double) < baseline.costByQueryId.getValue("range"))
                        // Save the negative example too: reading every row need not benefit from an index.
                        assertEquals(baseline.costByQueryId.getValue("all_rows"), brinCosts["all_rows"])
                        val original = sized.single { it.definition.method == "brin" }
                        val bad = original.copy(definition = original.definition.copy(keys = original.definition.keys.map {
                            it.copy(operatorClass = "pg_catalog.int8_ops")
                        }))
                        assertThrows(AnalysisException::class.java) { session.estimateSize(bad, context) }
                        assertEquals(0, scalar(connection, "SELECT count(*) FROM public.hypopg()"))
                        assertEquals(baseline.costByQueryId, session.evaluate(emptyList(), resolved, context).costByQueryId)
                        // Real duplicate and nondefault reloptions are tested only on this disposable fixture.
                        execute(admin, "CREATE INDEX real_brin ON brin_demo.events USING brin (sequence)")
                        var after = session.readCatalog(parsed.referencedTables, context)
                        var generated = ProfileAwareCandidateGenerator().generate(resolved, after, context)
                        assertEquals("btree", generated.candidates.single().definition.method)
                        assertTrue(generated.diagnostics.decisions.any { it.reason == CandidateReason.EXACT_EXISTING_DUPLICATE })
                        execute(admin, "ALTER INDEX brin_demo.real_brin SET (pages_per_range = 64)")
                        after = session.readCatalog(parsed.referencedTables, context)
                        assertFalse(after.tables.single().existingIndexes.single().defaultStorageParameters)
                        generated = ProfileAwareCandidateGenerator().generate(resolved, after, context)
                        assertEquals(2, generated.candidates.size)
                        execute(admin, "DROP INDEX brin_demo.real_brin")
                    }
                }
                val generous = analyze(source, output.resolve("generous"))
                assertEquals(setOf("btree", "brin"), generous.candidates.candidates.map { it.definition.method }.toSet())
                val constrainedSource = output.resolve("workload-tight.json")
                val tight = mapper.readTree(Files.readString(source)) as tools.jackson.databind.node.ObjectNode
                tight.withObject("/constraints").put("storage_budget_bytes", brinBytes * 2)
                Files.writeString(constrainedSource, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tight))
                val constrained = analyze(constrainedSource, output.resolve("tight"))
                val selected = checkNotNull(constrained.result.selected) { "Expected selected to be available" }.evaluation.candidateIds
                assertEquals(listOf("brin"), constrained.candidates.candidates.filter { it.id in selected }.map { it.definition.method })
                assertTrue(constrained.result.diagnostics.candidateDecisions.any { it.reason == CandidateReason.STORAGE_BUDGET })
                assertEquals(ManualComparisonStatus.COMPLETED, checkNotNull(constrained.manualComparison) { "Expected manualComparison to be available" }.status)
                assertEquals(setOf("brin", "btree"), constrained.manualComparison.candidates.map { it.definition.method }.toSet())
                assertFalse(constrained.manualComparison.admissible)
                assertTrue(BudgetViolation.STORAGE_BUDGET in constrained.manualComparison.budgetViolations)
                assertTrue(checkNotNull(constrained.candidates.diagnostics.preparation) { "Expected preparation to be available" }.sizeAttempts <= 32)
                assertTrue(constrained.candidates.diagnostics.preparation.finalShortlistedCandidates <= 16)
                assertTrue(constrained.result.diagnostics.statistics.explainAttempts <= 2000)
                analyzeSeries(source, output, brinBytes, btreeBytes)
                assertEquals(0, scalar(admin, "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = 'brin_demo'"))
                val summary = mapOf("postgres_version" to string(admin, "SHOW server_version"),
                    "hypopg_version" to string(admin, "SELECT extversion FROM pg_extension WHERE extname = 'hypopg'"),
                    "rows" to 1000000, "fixture" to "ordered sequence, 160-byte payload, default planner settings",
                    "brin_estimated_bytes" to brinBytes, "btree_estimated_bytes" to btreeBytes,
                    "tight_storage_budget_bytes" to brinBytes * 2, "samples" to samples,
                    "note" to "Planner costs and estimated bytes only. Write proxy is not measured maintenance cost.")
                Files.writeString(output.resolve("measurements.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n")
            } finally {
                execute(admin, "DROP SCHEMA brin_demo CASCADE")
            }
        }
    }

    private fun analyzeSeries(source: Path, output: Path, brinBytes: Long, btreeBytes: Long) {
        val json = mapper.readTree(Files.readString(source)) as tools.jackson.databind.node.ObjectNode
        json.remove("manual_indexes")
        val seriesSource = output.resolve("workload-series.json")
        Files.writeString(seriesSource, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json))
        val context = RunContext()
        val input = WorkloadReader().read(seriesSource, context)
        val sizeCalls = mutableListOf<Int>()
        val pipeline = AnalysisPipeline(sqlAnalyzer = JSqlParserSqlAnalyzer(),
            sessionFactory = object : AnalysisSessionFactory {
                override fun open(context: RunContext): AnalysisSession {
                    val connection = connect()
                    val actual = JdbcAnalysisSession(connection)
                    val position = sizeCalls.size
                    sizeCalls += 0
                    return object : AnalysisSession by actual {
                        override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long {
                            sizeCalls[position]++
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
            reportExporter = AtomicReportExporter(HtmlReportRenderer()), shutdownCancellation = ShutdownCancellation { AutoCloseable {} })
        val result = BudgetSeriesPipeline(pipeline, ShutdownCancellation { AutoCloseable {} }).run(input,
            BudgetSeriesRequest(SeriesBudget.STORAGE, listOf(0L, brinBytes * 2, btreeBytes * 2).map { java.math.BigDecimal.valueOf(it) }),
            output.resolve("series"), context)
        assertEquals(SeriesStatus.COMPLETED, result.status)
        assertEquals(listOf(2, 0, 0, 0), sizeCalls)
        val summary = mapper.readTree(Files.readString(output.resolve("series/series.json")))
        assertEquals(2, summary["schema_version"].intValue())
        assertEquals(setOf("brin", "btree"), summary["preparation"]["candidates"].toList().map { it["method"].stringValue() }.toSet())
        assertTrue(Files.readString(output.resolve("series/report.html")).contains("btree, brin"))
        assertEquals(4, checkNotNull(result.points[1].report) { "Expected report to be available" }.schemaVersion)
        assertEquals(listOf("brin"), checkNotNull(result.points[1].report) { "Expected report to be available" }.let { report ->
            report.candidates.candidates.filter { it.id in checkNotNull(report.result.selected) { "Expected selected to be available" }.evaluation.candidateIds }.map { it.definition.method }
        })
    }

    private fun analyze(source: Path, output: Path): AnalysisReport {
        val context = RunContext()
        val input = WorkloadReader().read(source, context)
        context.completePhase()
        val connection = connect()
        val actual = JdbcAnalysisSession(connection)
        val observed = object : AnalysisSession by actual {
            override fun close() {
                assertEquals(0, scalar(connection, "SELECT count(*) FROM public.hypopg()"))
                actual.close()
            }
        }
        var report: AnalysisReport? = null
        val exporter = AtomicReportExporter(HtmlReportRenderer())
        try {
            AnalysisPipeline(sqlAnalyzer = JSqlParserSqlAnalyzer(),
                sessionFactory = object : AnalysisSessionFactory {
                    override fun open(context: RunContext): AnalysisSession = observed
                }, candidateGenerator = ProfileAwareCandidateGenerator(), metrics = MetricsCalculatorImpl(),
                comparator = ObjectiveComparatorImpl(), optimizer = LimitedRepairOptimizer(), reportBuilder = DefaultReportBuilder(),
                reportExporter = ReportExporter { built, destination, run ->
                    assertTrue(connection.isClosed)
                    val before = run.statistics()
                    exporter.export(built, destination, run)
                    assertEquals(before, run.statistics())
                    report = built
                }, shutdownCancellation = ShutdownCancellation { AutoCloseable {} },
            ).run(input, output, context)
        } finally { actual.close() }
        val result = checkNotNull(report)
        assertEquals(RunStatus.COMPLETED, result.result.status)
        val json = mapper.readTree(Files.readString(output.resolve("report.json")))
        val html = Files.readString(output.resolve("report.html"))
        val sql = Files.readString(output.resolve("indexes.sql"))
        assertEquals(4, json["schema_version"].intValue())
        assertEquals(listOf("btree", "brin"), json["index_methods"].toList().map { it.stringValue() })
        assertEquals(result.runId, json["run_id"].stringValue())
        assertTrue(html.contains(result.runId) && sql.contains(result.runId))
        assertTrue(html.contains(checkNotNull(result.inputHash) { "Expected inputHash to be available" }) && sql.contains(result.inputHash))
        assertTrue(html.contains("btree, brin"))
        assertTrue(sql.contains("-- index_methods: btree,brin"))
        val recommendation = html.substringAfter("<ul class=\"recommended-indexes\">", "").substringBefore("</ul>")
        assertTrue(recommendation.isNotEmpty())
        for (candidate in result.candidates.candidates.filter { it.id in checkNotNull(result.result.selected) { "Expected selected to be available" }.evaluation.candidateIds }) {
            assertTrue(sql.contains(checkNotNull(candidate.ddl) { "Expected ddl to be available" }))
            val expected = when (candidate.definition.method) {
                "btree" -> "events · btree (sequence)"
                "brin" -> "events · brin (sequence)"
                else -> error("Unexpected method")
            }
            assertTrue(recommendation.contains("<code>$expected</code>"), recommendation)
        }
        return result
    }

    private fun connect() = DriverManager.getConnection(System.getenv("INDEXBUDGET_HYPOPG_TEST_URL"),
        System.getenv("INDEXBUDGET_HYPOPG_TEST_USER"), System.getenv("INDEXBUDGET_HYPOPG_TEST_PASSWORD"))
    private fun execute(connection: Connection, sql: String) = connection.createStatement().use { it.execute(sql) }
    private fun scalar(connection: Connection, sql: String) = string(connection, sql).toInt()
    private fun string(connection: Connection, sql: String) = connection.createStatement().use {
        it.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
    }
}
