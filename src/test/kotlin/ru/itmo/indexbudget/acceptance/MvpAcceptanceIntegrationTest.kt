package ru.itmo.indexbudget.acceptance

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.AnalysisPipeline
import ru.itmo.indexbudget.application.ShutdownCancellation
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.candidates.PostgresDdlRenderer
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.postgres.AnalysisSessionFactory
import ru.itmo.indexbudget.postgres.JdbcAnalysisSession
import ru.itmo.indexbudget.report.AtomicReportExporter
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.HtmlReportRenderer
import ru.itmo.indexbudget.report.ReportBuilder
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Приёмка #21 на явно выбранной одноразовой БД, без подмены SQL/генератора/evaluator. */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_HYPOPG_TEST_URL", matches = ".+")
class MvpAcceptanceIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    private lateinit var admin: Connection
    private lateinit var schema: String
    private val mapper = JsonMapper()
    private val analyzer = JSqlParserSqlAnalyzer()
    private val generator = ProfileAwareCandidateGenerator()

    @BeforeEach
    fun prepareDisposableFixture() {
        admin = connect()
        schema = "acceptance_" + UUID.randomUUID().toString().replace("-", "")
        execute("CREATE EXTENSION IF NOT EXISTS hypopg WITH SCHEMA public")
        execute("CREATE SCHEMA $schema")
    }

    @AfterEach
    fun removeDisposableFixture() {
        if (::admin.isInitialized) admin.use {
            if (::schema.isInitialized) execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    @Test
    fun `adding ninety eight unreferenced columns preserves candidates and star alone has none`() {
        execute("CREATE TABLE $schema.records (c1 integer, c2 integer)")
        val query = "SELECT * FROM $schema.records WHERE c1 = 7 ORDER BY c2 DESC LIMIT 10"
        val narrow = resolveAndGenerate(query)
        assertTrue(narrow.candidates.isNotEmpty())

        execute("ALTER TABLE $schema.records " + (3..100).joinToString(", ") { "ADD COLUMN c$it integer" })
        val wide = resolveAndGenerate(query)
        assertEquals(narrow.candidates, wide.candidates)
        assertEquals(setOf("c1", "c2"), wide.candidates.flatMap { candidate ->
            candidate.definition.keys.map { it.column.name }
        }.toSet())
        assertTrue(resolveAndGenerate("SELECT * FROM $schema.records").candidates.isEmpty())
        assertEquals(0, realIndexCount())
    }

    @Test
    fun `one hundred predicates reach real EXPLAIN while shortlist caps and artifacts stay consistent`() {
        execute("CREATE TABLE $schema.wide (" + (1..100).joinToString(", ") { "c$it integer" } + ")")
        execute("INSERT INTO $schema.wide SELECT " + (1..100).joinToString(", ") { "i" } +
            " FROM generate_series(1, 1000) i")
        execute("ANALYZE $schema.wide")
        val text = "SELECT * FROM $schema.wide WHERE " + (1..100).joinToString(" AND ") { "c$it = 1" }
        val input = workload(listOf(QueryInput("wide", text)))
        val run = analyze("wide", input)
        val report = run.report
        assertNotNull(report.environment.postgresVersion)
        assertNotNull(report.environment.hypopgVersion)
        assertEquals(RunStatus.COMPLETED, report.result.status, report.result.diagnostics.toString())
        assertTrue(run.evaluatedSql.isNotEmpty())
        assertTrue(run.evaluatedSql.all { it == listOf(text) }, "Every real evaluation receives the uncut SQL")
        val baselinePlan = requireNotNull(report.result.baseline).plansByQueryId.getValue("wide").json
        for (index in 1..100) {
            assertTrue(Regex("\\bc$index\\b").containsMatchIn(baselinePlan), "EXPLAIN lost c$index")
        }
        val diagnostics = report.candidates.diagnostics
        val scope = diagnostics.localScopes.single()
        assertEquals(100, scope.discoveredColumns)
        assertEquals(16, scope.retainedColumns)
        assertEquals(84, scope.excludedColumns.size)
        assertEquals(256, scope.enumeratedDefinitions)
        assertEquals(32, scope.retainedDefinitions)
        assertEquals(16, diagnostics.shortlistedCandidates)
        assertEquals(8, report.candidates.candidates.count { it.definition.keys.size == 1 })
        assertEquals(8, report.candidates.candidates.count { it.definition.keys.size == 2 })
        assertTrue(diagnostics.decisions.map { it.reason }.containsAll(listOf(
            CandidateReason.COLUMN_SHORTLIST_TRUNCATED,
            CandidateReason.LOCAL_CANDIDATE_LIMIT,
            CandidateReason.GLOBAL_CANDIDATE_LIMIT,
        )))
        assertNull(diagnostics.ungeneratedDefinitions)
        assertEquals(report.candidates.candidates, resolveAndGenerate(text).candidates.map { candidate ->
            val sized = report.candidates.candidates.single { it.id == candidate.id }
            candidate.copy(estimatedBytes = sized.estimatedBytes, budgetedBytes = sized.budgetedBytes,
                writeUnitsPerHour = sized.writeUnitsPerHour)
        })
        assertArtifacts(run)
        val json = mapper.readTree(Files.readString(run.output.resolve("report.json")))
        assertEquals(text, json["queries"][0]["sql"].stringValue())
        assertEquals(84, json["candidate_diagnostics"]["local_scopes"][0]["excluded_columns"].size())
        assertEquals(0, realIndexCount(), "Ordinary analysis must not create physical indexes")
    }

    @Test
    fun `two table storage and write budgets independently change real recommendations for both objectives`() {
        for (table in listOf("hot", "cold")) {
            execute("CREATE TABLE $schema.$table (lookup integer, payload text)")
            execute("INSERT INTO $schema.$table SELECT i, repeat('x', 80) FROM generate_series(1, 30000) i")
            execute("ANALYZE $schema.$table")
        }
        val queries = listOf(
            QueryInput("hot", "SELECT payload FROM $schema.hot WHERE lookup = 17"),
            QueryInput("cold", "SELECT payload FROM $schema.cold WHERE lookup = 29"),
        )
        val rates = mapOf(
            "$schema.hot" to TableWriteRates(100.0, 300.0, 200.0),
            "$schema.cold" to TableWriteRates(1.0, 1.0, 1.0),
        )
        val input = workload(queries).copy(constraints = Constraints(64L * 1024 * 1024, 1000.0, 2),
            tableWriteRates = rates)
        val broad = analyze("broad", input)
        assertEquals(RunStatus.COMPLETED, broad.report.result.status)
        assertEquals(2, selected(broad).evaluation.candidateIds.size)
        assertEquals(603.0, selected(broad).metrics.budgetUsage.writeUnitsPerHour)
        assertArtifacts(broad)

        val oneIndexStorage = broad.report.candidates.candidates.maxOf { requireNotNull(it.budgetedBytes) }
        assertTrue(oneIndexStorage > 0)
        val storage = analyze("storage", input.copy(constraints = input.constraints.copy(
            storageBudgetBytes = oneIndexStorage)))
        assertEquals(1, selected(storage).evaluation.candidateIds.size)
        assertTrue(selected(storage).metrics.budgetUsage.budgetedBytes <= oneIndexStorage)
        assertEquals(input.constraints.writeBudgetUnitsPerHour, storage.report.input?.constraints?.writeBudgetUnitsPerHour)

        val write = analyze("write", input.copy(constraints = input.constraints.copy(writeBudgetUnitsPerHour = 3.0)))
        assertEquals(1, selected(write).evaluation.candidateIds.size)
        assertEquals(3.0, selected(write).metrics.budgetUsage.writeUnitsPerHour)
        val coldId = broad.report.candidates.candidates.single { it.definition.table.name == "cold" }.id
        assertEquals(listOf(coldId), selected(write).evaluation.candidateIds)
        assertEquals(input.constraints.storageBudgetBytes, write.report.input?.constraints?.storageBudgetBytes)

        val mean = analyze("mean", input.copy(objective = Objective.MEAN))
        assertEquals(Objective.MEAN, mean.report.result.selectedObjective)
        assertEquals(selected(broad).evaluation.candidateIds, selected(mean).evaluation.candidateIds)
        assertArtifacts(mean)
        val repeat = analyze("repeat", input)
        assertEquals(selected(broad).evaluation, selected(repeat).evaluation)

        val disabled = analyze("disabled-write", input.copy(constraints = input.constraints.copy(
            writeBudgetUnitsPerHour = null)))
        assertEquals(603.0, selected(disabled).metrics.budgetUsage.writeUnitsPerHour)
        val absent = analyze("absent-write", input.copy(constraints = input.constraints.copy(
            writeBudgetUnitsPerHour = null), tableWriteRates = null))
        assertNull(selected(absent).metrics.budgetUsage.writeUnitsPerHour)
        val zero = analyze("zero-write", input.copy(constraints = input.constraints.copy(writeBudgetUnitsPerHour = 0.0)))
        assertEquals(emptyList<String>(), selected(zero).evaluation.candidateIds)
        val baselineOnly = analyze("baseline-only", input.copy(constraints = input.constraints.copy(maxIndexes = 0)))
        assertTrue(baselineOnly.report.candidates.candidates.isEmpty())
        assertEquals(1, baselineOnly.context.statistics().completedEvaluations)
        assertEquals(0, realIndexCount())
    }

    private fun resolveAndGenerate(sql: String): CandidateBatch {
        val input = workload(listOf(QueryInput("wide", sql)))
        val context = RunContext()
        val parsed = analyzer.parse(input, context)
        return JdbcAnalysisSession(connect()).use { session ->
            val catalog = session.readCatalog(parsed.referencedTables, context)
            val resolved = analyzer.resolve(parsed, catalog, context)
            generator.generate(resolved, catalog, context)
        }
    }

    private fun analyze(name: String, source: WorkloadInput): Run {
        val context = RunContext()
        val path = temporary.resolve("$name.json")
        Files.writeString(path, mapper.writeValueAsString(mapOf(
            "schema_version" to 2,
            "objective" to source.objective.name.lowercase(),
            "queries" to source.queries.map { mapOf("id" to it.id, "sql" to it.sql) },
            "profiles" to source.profiles.map { mapOf("id" to it.id, "weight" to it.weight,
                "calls_per_hour" to it.callsPerHour) },
            "constraints" to mapOf("storage_budget_bytes" to source.constraints.storageBudgetBytes,
                "write_budget_units_per_hour" to source.constraints.writeBudgetUnitsPerHour,
                "max_indexes" to source.constraints.maxIndexes),
            "table_write_rates" to source.tableWriteRates?.mapValues { (_, rate) -> mapOf(
                "inserts_per_hour" to rate.insertsPerHour, "updates_per_hour" to rate.updatesPerHour,
                "deletes_per_hour" to rate.deletesPerHour, "source" to "manual") },
        )))
        val input = WorkloadReader().read(path, context)
        context.completePhase()
        val connection = connect()
        val delegate = JdbcAnalysisSession(connection)
        val evaluatedSql = mutableListOf<List<String>>()
        val tracked = object : AnalysisSession by delegate {
            override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload,
                                  context: RunContext): EvaluationResult {
                evaluatedSql += workload.queries.map { it.sql }
                return delegate.evaluate(candidates, workload, context)
            }
        }
        var report: AnalysisReport? = null
        var countersBeforeReport: RunStatistics? = null
        val builder = DefaultReportBuilder()
        val pipeline = AnalysisPipeline(
            sqlAnalyzer = analyzer,
            sessionFactory = object : AnalysisSessionFactory {
                override fun open(context: RunContext): AnalysisSession = tracked
            },
            candidateGenerator = generator, metrics = MetricsCalculatorImpl(),
            comparator = ObjectiveComparatorImpl(), optimizer = LimitedRepairOptimizer(),
            reportBuilder = ReportBuilder { workload, candidates, result, runContext ->
                assertTrue(connection.isClosed, "Reports consume saved data after the dedicated connection closes")
                countersBeforeReport = runContext.statistics()
                builder.build(workload, candidates, result, runContext).also { report = it }
            },
            reportExporter = AtomicReportExporter(HtmlReportRenderer()),
            shutdownCancellation = ShutdownCancellation { AutoCloseable {} },
        )
        val output = temporary.resolve(name)
        pipeline.run(input, output, context)
        assertTrue(connection.isClosed)
        assertEquals(countersBeforeReport, context.statistics(), "Export must not issue another EXPLAIN")
        assertEquals(context.statistics().completedEvaluations * input.queries.size,
            context.statistics().explainAttempts, "Profiles must share a single complete cost vector")
        return Run(requireNotNull(report), context, output, evaluatedSql)
    }

    private fun assertArtifacts(run: Run) {
        val report = run.report
        val selectedIds = selected(run).evaluation.candidateIds
        val json = mapper.readTree(Files.readString(run.output.resolve("report.json")))
        val html = Files.readString(run.output.resolve("report.html"))
        val sql = Files.readString(run.output.resolve("indexes.sql"))
        assertEquals(report.runId, json["run_id"].stringValue())
        assertEquals(report.inputHash, json["input_hash"].stringValue())
        assertEquals(selectedIds, json["selected"]["candidate_ids"].toList().map { it.stringValue() })
        assertEquals("evaluated_sets", json["comparison_scope"].stringValue())
        assertTrue(html.contains(report.runId))
        assertTrue(html.contains(requireNotNull(report.inputHash)))
        assertTrue(sql.contains("-- run_id: ${report.runId}"))
        assertTrue(sql.contains("-- input_hash: ${report.inputHash}"))
        for (candidate in report.candidates.candidates) {
            assertEquals(candidate.id in selectedIds, sql.contains(PostgresDdlRenderer().render(candidate.definition)))
        }
        assertFalse(html.contains("Actual Total Time"))
        assertFalse(html.contains("jdbc:postgresql:"))
    }

    private fun selected(run: Run) = requireNotNull(run.report.result.selected)

    private fun workload(queries: List<QueryInput>) = WorkloadInput(
        queries, listOf(
            Profile("day", 3.0, queries.associate { it.id to 1000.0 }),
            Profile("rare", 1.0, queries.associate { it.id to 1.0 }),
        ), Constraints(64L * 1024 * 1024, null, 1),
    )

    private fun realIndexCount(): Int = admin.prepareStatement(
        "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = ?",
    ).use { statement ->
        statement.setString(1, schema)
        statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
    }

    private fun execute(sql: String) = admin.createStatement().use { it.execute(sql) }

    private fun connect(): Connection = DriverManager.getConnection(
        System.getenv("INDEXBUDGET_HYPOPG_TEST_URL"), System.getenv("INDEXBUDGET_HYPOPG_TEST_USER"),
        System.getenv("INDEXBUDGET_HYPOPG_TEST_PASSWORD"),
    )

    private data class Run(val report: AnalysisReport, val context: RunContext, val output: Path,
                           val evaluatedSql: List<List<String>>)
}
