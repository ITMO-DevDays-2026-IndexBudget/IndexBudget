package ru.itmo.indexbudget.postgres

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import ru.itmo.indexbudget.candidates.BtreeCandidateCanonicalizer
import ru.itmo.indexbudget.candidates.CandidateScope
import ru.itmo.indexbudget.candidates.CandidateVariant
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.application.AnalysisPipeline
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.report.AtomicReportExporter
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.HtmlReportRenderer
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import tools.jackson.databind.json.JsonMapper

/** Explicit opt-in to a disposable PostgreSQL 16 + HypoPG database, never the shared stand. */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_HYPOPG_TEST_URL", matches = ".+")
class JdbcAnalysisSessionIntegrationTest {
    private lateinit var admin: Connection
    private lateinit var connection: Connection
    private lateinit var session: JdbcAnalysisSession
    private lateinit var schema: String
    private lateinit var role: String
    private lateinit var workload: ResolvedWorkload
    private lateinit var candidates: List<IndexCandidate>
    private val mapper = JsonMapper.builder().build()

    @BeforeEach
    fun setup() {
        admin = connect()
        sql(admin, "CREATE EXTENSION IF NOT EXISTS hypopg WITH SCHEMA public")
        schema = "hypopg_" + UUID.randomUUID().toString().replace("-", "")
        role = schema + "_reader"
        sql(admin, "CREATE SCHEMA $schema")
        sql(admin, "CREATE ROLE $role")
        sql(admin, "CREATE TABLE $schema.records (id integer PRIMARY KEY, a integer, b integer, payload text)")
        sql(admin, "INSERT INTO $schema.records SELECT i, i, 50001 - i, repeat('x', 80) FROM generate_series(1, 50000) i")
        sql(admin, "ANALYZE $schema.records") // Explicit physical fixture preparation, outside the adapter.
        sql(admin, "GRANT USAGE ON SCHEMA $schema TO $role")
        sql(admin, "GRANT SELECT ON ALL TABLES IN SCHEMA $schema TO $role")
        connection = connect()
        sql(connection, "SET search_path TO $schema, pg_catalog") // HypoPG's schema is deliberately absent.
        sql(connection, "SET ROLE $role")
        session = JdbcAnalysisSession(connection)
        val input = input()
        val context = RunContext()
        val analyzer = JSqlParserSqlAnalyzer()
        val parsed = analyzer.parse(input, context)
        val catalog = session.readCatalog(parsed.referencedTables, context)
        workload = analyzer.resolve(parsed, catalog, context)
        candidates = listOf("a", "b").map { column ->
            val table = TableRef(schema, "records")
            val definition = IndexDefinition(table, listOf(IndexKey(ColumnRef(table, column))))
            BtreeCandidateCanonicalizer(catalog, context).canonicalize(CandidateScope(
                "q_a", table, 1, listOf(ColumnRef(table, column)), emptyList(),
                listOf(CandidateVariant(definition, setOf("q_a"), setOf(GenerationReason.SINGLE_COLUMN))),
                emptyList(), emptyList(),
            )).candidates.single()
        }
    }

    @AfterEach
    fun cleanup() {
        if (::session.isInitialized) session.close()
        if (::connection.isInitialized && !connection.isClosed) connection.close()
        if (::admin.isInitialized) admin.use {
            if (::schema.isInitialized) sql(it, "DROP SCHEMA IF EXISTS $schema CASCADE")
            if (::role.isInitialized) sql(it, "DROP ROLE IF EXISTS $role")
        }
    }

    @Test
    fun `generated INCLUDE uses real sizes costs budgets catalog and retains no benefit case`() {
        // Visibility preparation belongs to the disposable fixture, never to analyze.
        sql(admin, "VACUUM (ANALYZE) $schema.records")
        val input = WorkloadInput(listOf(
            QueryInput("positive", "SELECT b FROM $schema.records WHERE a = 314"),
            QueryInput("negative", "SELECT payload FROM $schema.records WHERE a > 0"),
        ), listOf(Profile("p", 1.0, mapOf("positive" to 1.0, "negative" to 1.0))),
            Constraints(100_000_000, 300.0), tableWriteRates = mapOf(
                "$schema.records" to TableWriteRates(10.0, 20.0, 30.0)))
        val context = RunContext().apply { startSearch() }
        val analyzer = JSqlParserSqlAnalyzer()
        val parsed = analyzer.parse(input, context)
        val catalog = session.readCatalog(parsed.referencedTables, context)
        val resolved = analyzer.resolve(parsed, catalog, context)
        val generated = ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator().generate(resolved, catalog, context)
        val covering = generated.candidates.filter { it.definition.includedColumns.isNotEmpty() }
        assertEquals(2, covering.size)
        val baseline = session.evaluate(emptyList(), resolved, context)
        val metrics = MetricsCalculatorImpl()
        val samples = covering.sortedBy { it.sourceQueryIds.single() }.map { candidate ->
            val size = session.estimateSize(candidate, context)
            val measured = candidate.copy(estimatedBytes = size)
            val assessment = metrics.assessBudget(listOf(measured), input)
            assertTrue(assessment.admissible)
            assertEquals(120.0, assessment.usage.writeUnitsPerHour)
            assertEquals(setOf(BudgetViolation.WRITE_BUDGET), metrics.assessBudget(listOf(measured),
                input.copy(constraints = input.constraints.copy(writeBudgetUnitsPerHour = 119.0))).violations)
            assertEquals(setOf(BudgetViolation.STORAGE_BUDGET), metrics.assessBudget(listOf(measured),
                input.copy(constraints = input.constraints.copy(storageBudgetBytes = assessment.usage.budgetedBytes - 1))).violations)
            val evaluated = session.evaluate(listOf(measured), resolved, context)
            val source = candidate.sourceQueryIds.single()
            val cost = evaluated.costByQueryId.getValue(source)
            if (source == "positive") {
                assertTrue(cost < baseline.costByQueryId.getValue(source))
                assertTrue(evaluated.plansByQueryId.getValue(source).json.contains("Index Only Scan"))
            } else {
                assertEquals(baseline.costByQueryId.getValue(source), cost)
            }
            assertTrue(size > 0)
            assertEquals(0, hypothesisCount(connection))
            mapOf("query_id" to source, "ddl" to candidate.ddl!!.replace(schema, "include_demo"),
                "estimated_bytes" to size, "budgeted_bytes" to assessment.usage.budgetedBytes,
                "write_units_per_hour" to assessment.usage.writeUnitsPerHour,
                "storage_budget_bytes" to input.constraints.storageBudgetBytes,
                "write_budget_units_per_hour" to input.constraints.writeBudgetUnitsPerHour,
                "baseline_cost" to baseline.costByQueryId.getValue(source), "covering_cost" to cost)
        }
        // Real catalog distinguishes included positions from search keys, then exact dedup consumes it.
        val positive = covering.single { "positive" in it.sourceQueryIds }
        sql(admin, positive.ddl!!)
        val after = session.readCatalog(parsed.referencedTables, context)
        assertTrue(after.tables.single().existingIndexes.any {
            it.includedColumns == positive.definition.includedColumns &&
                it.keys.map { key -> key.column } == positive.definition.keys.map { key -> key.column }
        })
        val deduped = ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator().generate(resolved, after, context)
        assertFalse(deduped.candidates.any { it.id == positive.id })
        assertTrue(deduped.candidates.any { it.definition.includedColumns.isEmpty() })
        fun version(sql: String) = connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
        }
        val sample = mapOf("postgres_version" to version("SHOW server_version"),
            "hypopg_version" to version("SELECT extversion FROM pg_extension WHERE extname = 'hypopg'"),
            "fixture_rows" to 50000, "samples" to samples)
        val output = java.nio.file.Path.of("build", "include-demo", "real-costs.json")
        java.nio.file.Files.createDirectories(output.parent)
        java.nio.file.Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sample) + "\n")
    }

    @Test
    fun `OR workload completes real search with exact EXPLAIN and branch origins in artifacts`(@TempDir directory: Path) {
        val text = """
            {"schema_version":2,"queries":[
              {"id":"alternatives","sql":"SELECT id FROM records WHERE a IN (1, 2) OR b BETWEEN 49999 AND 50000"},
              {"id":"mixed","sql":"SELECT id FROM records WHERE (a = 1 AND b > 1) OR (a > 2 AND b = 3)"}],
             "profiles":[
              {"id":"day","weight":2,"calls_per_hour":{"alternatives":10,"mixed":1}},
              {"id":"night","weight":1,"calls_per_hour":{"alternatives":1,"mixed":10}}],
             "constraints":{"storage_budget_bytes":8388608,"write_budget_units_per_hour":null,"max_indexes":3}}
        """.trimIndent()
        val path = directory.resolve("workload.json")
        Files.writeString(path, text)
        val context = RunContext()
        val input = WorkloadReader().read(path, context)
        val observed = mutableListOf<ExplainObservation>()
        session = JdbcAnalysisSession(observingConnection(observed))
        val pipeline = AnalysisPipeline(JSqlParserSqlAnalyzer(), object : AnalysisSessionFactory {
            override fun open(context: RunContext): AnalysisSession = session
        }, ProfileAwareCandidateGenerator(), MetricsCalculatorImpl(), ObjectiveComparatorImpl(),
            LimitedRepairOptimizer(), DefaultReportBuilder(), AtomicReportExporter(HtmlReportRenderer()))
        val output = directory.resolve("result")
        val result = pipeline.run(input, output, context)
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(setOf("alternatives", "mixed"), result.baseline?.costByQueryId?.keys)
        assertTrue(observed.isNotEmpty())
        assertTrue(observed.all { event -> input.queries.any { event.sql.endsWith(it.sql) } })
        assertEquals(2, result.diagnostics.warnings.count { it.code == WarningCode.OR_BRANCH_AWARE_GENERATION })
        val json = Files.readString(output.resolve("report.json"))
        assertTrue(json.contains("source_branches"))
        assertTrue(json.contains("condition/L"))
        assertTrue(json.contains("dnf_expanded"))
        val html = Files.readString(output.resolve("report.html"))
        assertTrue(html.contains("Ветви условий"))
        assertTrue(html.contains("condition/L"))
        for (name in listOf("report.json", "report.html", "indexes.sql")) {
            assertTrue(Files.readString(output.resolve(name)).contains(context.runId))
        }
    }

    @Test
    fun `json null tests do not fail real pipeline or suppress integer search`(@TempDir directory: Path) {
        sql(admin, "ALTER TABLE $schema.records ADD COLUMN nullable_json json")
        sql(admin, "UPDATE $schema.records SET nullable_json = '{}'::json WHERE id % 2 = 0")
        val text = """
            {"schema_version":2,"queries":[
              {"id":"null","sql":"SELECT id FROM records WHERE nullable_json IS NULL AND a = 1"},
              {"id":"not_null","sql":"SELECT id FROM records WHERE nullable_json IS NOT NULL AND a = 2"}],
             "profiles":[{"id":"observed","weight":1,"calls_per_hour":{"null":1,"not_null":1}}],
             "constraints":{"storage_budget_bytes":8388608,"write_budget_units_per_hour":null,"max_indexes":3}}
        """.trimIndent()
        val path = directory.resolve("workload.json")
        Files.writeString(path, text)
        val context = RunContext()
        val input = WorkloadReader().read(path, context)
        val pipeline = AnalysisPipeline(JSqlParserSqlAnalyzer(), object : AnalysisSessionFactory {
            override fun open(context: RunContext): AnalysisSession = session
        }, ProfileAwareCandidateGenerator(), MetricsCalculatorImpl(), ObjectiveComparatorImpl(),
            LimitedRepairOptimizer(), DefaultReportBuilder(), AtomicReportExporter(HtmlReportRenderer()))
        val output = directory.resolve("result")
        val result = pipeline.run(input, output, context)
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(setOf("null", "not_null"), result.baseline?.costByQueryId?.keys)
        assertTrue(result.selected?.evaluation?.candidateIds.orEmpty().isNotEmpty(), "Integer index must still reach search")
        assertEquals(2, result.diagnostics.candidateDecisions.count { it.reason == CandidateReason.NO_BTREE_OPERATOR_CLASS })
        val json = Files.readString(output.resolve("report.json"))
        assertTrue(json.contains("NO_BTREE_OPERATOR_CLASS"))
        assertTrue(json.contains("nullable_json IS NULL AND a = 1"))
        assertTrue(json.contains("nullable_json IS NOT NULL AND a = 2"))
        assertTrue(Files.readString(output.resolve("report.html")).contains("Нет подходящего B-tree opclass"))
        assertFalse(Files.readString(output.resolve("indexes.sql")).contains("nullable_json"))
    }

    @Test
    fun `new scalar forms complete real analysis and export consistent artifacts`(@TempDir directory: Path) {
        val source = """
            {"schema_version":2,"queries":[
              {"id":"in","sql":"SELECT id FROM records WHERE a IN (1, 2, NULL)"},
              {"id":"between","sql":"SELECT id FROM records WHERE b BETWEEN 100 AND 200"},
              {"id":"null","sql":"SELECT id FROM records WHERE payload IS NULL"},
              {"id":"not_null","sql":"SELECT id FROM records WHERE payload IS NOT NULL"}],
             "profiles":[{"id":"observed","weight":1,"calls_per_hour":{"in":10,"between":10,"null":1,"not_null":1}}],
             "constraints":{"storage_budget_bytes":8388608,"write_budget_units_per_hour":null,"max_indexes":3}}
        """.trimIndent()
        val path = directory.resolve("workload.json")
        Files.writeString(path, source)
        val context = RunContext()
        val input = WorkloadReader().read(path, context)
        val pipeline = AnalysisPipeline(JSqlParserSqlAnalyzer(), object : AnalysisSessionFactory {
            override fun open(context: RunContext): AnalysisSession = session
        }, ProfileAwareCandidateGenerator(), MetricsCalculatorImpl(), ObjectiveComparatorImpl(),
            LimitedRepairOptimizer(), DefaultReportBuilder(), AtomicReportExporter(HtmlReportRenderer()))
        val output = directory.resolve("result")
        val result = pipeline.run(input, output, context)
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(setOf("in", "between", "null", "not_null"), result.baseline?.costByQueryId?.keys)
        assertTrue(connection.isClosed)
        for (name in listOf("report.json", "report.html", "indexes.sql")) {
            assertTrue(Files.isRegularFile(output.resolve(name)), name)
            assertTrue(Files.readString(output.resolve(name)).contains(context.runId), name)
        }
        val json = Files.readString(output.resolve("report.json"))
        for (predicate in listOf("IN (1, 2, NULL)", "BETWEEN 100 AND 200", "IS NULL", "IS NOT NULL")) {
            assertTrue(json.contains(predicate), predicate)
        }
    }

    @Test
    fun `exact sets shared query vector real baseline indexes and settings use one backend`() {
        val pid = scalar(connection, "SELECT pg_backend_pid()")
        val settings = settings()
        val observed = mutableListOf<ExplainObservation>()
        session = JdbcAnalysisSession(observingConnection(observed))
        val context = RunContext().apply { startSearch() }
        // A stale hypothesis must not enter the baseline or any later exact set.
        createHypothesis(connection, "CREATE INDEX ON $schema.records (b)")
        val baseline = session.evaluate(emptyList(), workload, context)
        val a = session.evaluate(listOf(candidates[0]), workload, context)
        val both = session.evaluate(candidates.reversed(), workload, context)
        val repeated = session.evaluate(emptyList(), workload, context)

        assertEquals(baseline.costByQueryId, repeated.costByQueryId)
        assertTrue(baseline.plansByQueryId.getValue("q_id").json.contains("records_pkey"))
        assertTrue(a.costByQueryId.getValue("q_a") < baseline.costByQueryId.getValue("q_a"))
        assertEquals(candidates.map { it.id }.sorted(), both.candidateIds)
        assertEquals(List(4) { listOf("q_a", "q_b", "q_id") }.flatten(), observed.map { event ->
            workload.queries.single { event.sql.endsWith(it.sql) }.id
        })
        assertEquals(List(3) { emptyList<String>() } + List(3) { listOf("2") } +
            List(3) { listOf("2", "3") } + List(3) { emptyList<String>() }, observed.map { it.keys })
        assertEquals(setOf(pid), observed.map { it.pid }.toSet())
        for (evaluation in listOf(baseline, a, both, repeated)) {
            assertEquals(setOf("q_a", "q_b", "q_id"), evaluation.costByQueryId.keys)
            assertEquals(evaluation.costByQueryId.keys, evaluation.plansByQueryId.keys)
            for ((queryId, plan) in evaluation.plansByQueryId) {
                val rootCost = mapper.readTree(plan.json)[0]["Plan"]["Total Cost"].doubleValue()
                assertEquals(rootCost, evaluation.costByQueryId.getValue(queryId))
                assertFalse(plan.json.contains("Actual Total Time"))
            }
        }
        assertEquals(12, context.statistics().explainAttempts)
        assertEquals(4, context.statistics().completedEvaluations)
        assertEquals(0, hypothesisCount(connection))
        assertEquals(settings, settings())
        assertEquals(1, scalar(connection,
            "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = '$schema'"))
        assertEquals(pid, scalar(connection, "SELECT pg_backend_pid()"))
        val catalog = session.readCatalog(setOf(TableName("records")), context)
        assertEquals(TableRef(schema, "records"), catalog.tables.single().ref)
        assertEquals(pid, scalar(connection, "SELECT pg_backend_pid()"))
        session.close()
        session.close()
        assertTrue(connection.isClosed)
    }

    @Test
    fun `changing only profiles does not multiply EXPLAIN and saved plans need no connection`() {
        val context = RunContext()
        val baseline = session.evaluate(emptyList(), workload, context)
        val result = session.evaluate(candidates, workload, context)
        val metrics = MetricsCalculatorImpl()
        val usage = BudgetUsage(0, 0, null, 2)
        val original = metrics.calculate(workload.input, baseline, result, usage)
        val changedInput = workload.input.copy(profiles = listOf(
            Profile("changed", 1.0, mapOf("q_a" to 10.0, "q_b" to 1.0, "q_id" to 1.0)),
            Profile("another", 2.0, mapOf("q_a" to 1.0, "q_b" to 10.0, "q_id" to 2.0)),
            Profile("third", 3.0, mapOf("q_a" to 2.0, "q_b" to 3.0, "q_id" to 4.0)),
        ))
        val second = session.evaluate(candidates, workload.copy(input = changedInput), context)
        assertEquals(result.costByQueryId, second.costByQueryId)
        assertEquals(9, context.statistics().explainAttempts)
        session.close()
        val changed = metrics.calculate(changedInput, baseline, result, usage)
        assertEquals(2, original.profiles.size)
        assertEquals(3, changed.profiles.size)
        assertEquals(9, context.statistics().explainAttempts)
        assertTrue(result.plansByQueryId.values.all { mapper.readTree(it.json)[0]["Plan"].isObject })
    }

    @Test
    fun `size is raw HypoPG estimate and estimation cleans hypotheses without EXPLAIN`() {
        val candidate = candidates.first()
        val oid = createHypothesis(connection, requireNotNull(candidate.ddl))
        val expected = longScalar(connection, "SELECT public.hypopg_relation_size($oid::oid)")
        val context = RunContext()
        val estimated = session.estimateSize(candidate, context)
        assertEquals(expected, estimated)
        assertTrue(estimated > 0)
        assertEquals(0, hypothesisCount(connection))
        assertEquals(RunStatistics(), context.statistics())
        assertEquals(estimated, session.estimateSize(candidate, context))
        assertEquals(0, hypothesisCount(connection))
    }

    @Test
    fun `another backend hypotheses survive evaluation cleanup and close`() {
        connect().use { other ->
            val oid = createHypothesis(other, requireNotNull(candidates.first().ddl))
            assertEquals(1, hypothesisCount(other))
            assertEquals(0, hypothesisCount(connection))
            session.evaluate(candidates, workload, RunContext())
            session.estimateSize(candidates.last(), RunContext())
            assertEquals(0, hypothesisCount(connection))
            assertEquals(1, hypothesisCount(other))
            session.close()
            assertTrue(connection.isClosed)
            assertEquals(1, hypothesisCount(other))
            assertTrue(longScalar(other, "SELECT public.hypopg_relation_size($oid::oid)") > 0)
        }
        connect().use { fresh -> assertEquals(0, hypothesisCount(fresh)) }
    }

    @Test
    fun `failed EXPLAIN rolls back aborted transaction cleans exact set and allows retry`() {
        connection.autoCommit = false
        val context = RunContext()
        // Resolve first; a subsequent privilege change fails at the second query in ID order.
        sql(admin, "REVOKE SELECT ON $schema.records FROM $role")
        sql(admin, "GRANT SELECT (id, a, payload) ON $schema.records TO $role")
        val failure = assertThrows(AnalysisException::class.java) {
            session.evaluate(candidates, workload, context)
        }
        assertEquals(ErrorCode.EXPLAIN_FAILED, failure.error.code)
        assertEquals("q_b", failure.error.queryId)
        assertNull(failure.cause)
        assertEquals(2, context.statistics().explainAttempts)
        assertEquals(0, context.statistics().completedEvaluations)
        assertEquals(0, hypothesisCount(connection)) // Fails with 25P02 without rollback.
        assertFalse(connection.isClosed)
        sql(admin, "GRANT SELECT ON $schema.records TO $role")
        val result = session.evaluate(candidates, workload, context)
        assertEquals(3, result.costByQueryId.size)
        assertEquals(5, context.statistics().explainAttempts)
        assertEquals(2, context.statistics().evaluationAttempts)
        assertEquals(1, context.statistics().completedEvaluations)
        assertEquals(0, hypothesisCount(connection))
    }

    @Test
    fun `failed hypothesis creation cleans earlier indexes and preserves usable session`() {
        val ordered = candidates.sortedBy { it.id }
        val bad = ordered.last().let { candidate ->
            candidate.copy(definition = candidate.definition.copy(keys = listOf(
                candidate.definition.keys.single().copy(column = ColumnRef(TableRef(schema, "records"), "missing")),
            )))
        }
        connection.autoCommit = false
        val context = RunContext()
        val failure = assertThrows(AnalysisException::class.java) {
            session.evaluate(listOf(ordered.first(), bad), workload, context)
        }
        assertEquals(ErrorCode.HYPOTHETICAL_INDEX_ERROR, failure.error.code)
        assertEquals(0, context.statistics().explainAttempts)
        assertEquals(0, hypothesisCount(connection))
        assertNull(failure.cause)
        assertEquals(3, session.evaluate(emptyList(), workload, context).costByQueryId.size)
    }

    @Test
    fun `baseline EXPLAIN timeout rolls back and permits a complete retry`() {
        connect().use { blocker ->
            blocker.autoCommit = false
            sql(blocker, "LOCK TABLE $schema.records IN ACCESS EXCLUSIVE MODE")
            connection.autoCommit = false
            try {
                val context = RunContext(limits = RunLimits(explainTimeout = Duration.ofMillis(150)))
                val failure = assertThrows(AnalysisException::class.java) {
                    session.evaluate(emptyList(), workload, context)
                }
                assertEquals(ErrorCode.EXPLAIN_FAILED, failure.error.code)
                assertEquals("q_a", failure.error.queryId)
                assertEquals("57014", failure.error.details["sql_state"])
                assertEquals(1, context.statistics().explainAttempts)
                assertEquals(0, context.statistics().completedEvaluations)
                assertFalse(context.remainingTime().isZero)
                assertFalse(connection.isClosed)
                assertEquals(0, hypothesisCount(connection))
            } finally {
                blocker.rollback()
            }
        }
        assertEquals(3, session.evaluate(emptyList(), workload, RunContext()).costByQueryId.size)
    }

    @Test
    fun `timeout in the middle rejects the configuration and preserves the complete baseline`() {
        sql(admin, "CREATE TABLE $schema.blocked_records (b integer, payload text)")
        sql(admin, "GRANT SELECT ON $schema.blocked_records TO $role")
        val input = workload.input.copy(queries = workload.input.queries.map { query ->
            if (query.id == "q_b") query.copy(sql = "SELECT payload FROM blocked_records WHERE b = 314") else query
        })
        val analyzer = JSqlParserSqlAnalyzer()
        val context = RunContext(limits = RunLimits(explainTimeout = Duration.ofMillis(150)))
        val parsed = analyzer.parse(input, context)
        val changed = analyzer.resolve(parsed, session.readCatalog(parsed.referencedTables, context), context)
        val baseline = session.evaluate(emptyList(), changed, context)
        val savedCosts = baseline.costByQueryId.toMap()
        val observed = mutableListOf<ExplainObservation>()
        session = JdbcAnalysisSession(observingConnection(observed))
        connect().use { blocker ->
            blocker.autoCommit = false
            sql(blocker, "LOCK TABLE $schema.blocked_records IN ACCESS EXCLUSIVE MODE")
            connection.autoCommit = false
            try {
                val failure = assertThrows(AnalysisException::class.java) {
                    session.evaluate(candidates, changed, context)
                }
                assertEquals(ErrorCode.EXPLAIN_FAILED, failure.error.code)
                assertEquals("q_b", failure.error.queryId)
                assertEquals("57014", failure.error.details["sql_state"])
                assertEquals(2, observed.size)
                assertEquals(5, context.statistics().explainAttempts)
                assertEquals(1, context.statistics().completedEvaluations)
                assertEquals(savedCosts, baseline.costByQueryId)
                assertEquals(0, hypothesisCount(connection))
                assertFalse(connection.isClosed)
            } finally {
                blocker.rollback()
            }
        }
        assertEquals(3, session.evaluate(candidates, changed, context).costByQueryId.size)
        assertEquals(8, context.statistics().explainAttempts)
        assertEquals(2, context.statistics().completedEvaluations)
        assertEquals(0, hypothesisCount(connection))
    }

    @Test
    fun `shared EXPLAIN limit includes baseline and prevents an incomplete set becoming successful`() {
        val observed = mutableListOf<ExplainObservation>()
        session = JdbcAnalysisSession(observingConnection(observed))
        val context = RunContext(limits = RunLimits(maxExplainAttempts = 4))
        val baseline = session.evaluate(emptyList(), workload, context)
        val failure = assertThrows(AnalysisException::class.java) {
            session.evaluate(candidates, workload, context)
        }
        assertEquals(ErrorCode.EXPLAIN_LIMIT, failure.error.code)
        assertEquals(4, observed.size)
        assertEquals(4, context.statistics().explainAttempts)
        assertEquals(2, context.statistics().evaluationAttempts)
        assertEquals(1, context.statistics().completedEvaluations)
        assertEquals(3, baseline.costByQueryId.size)
        assertEquals(0, hypothesisCount(connection))
        assertEquals(ErrorCode.EXPLAIN_LIMIT, assertThrows(AnalysisException::class.java) {
            session.evaluate(emptyList(), workload, context)
        }.error.code)
        assertEquals(4, observed.size)
        assertEquals(3, session.evaluate(emptyList(), workload, RunContext()).costByQueryId.size)
    }

    @Test
    fun `terminated backend is a session failure and a fresh run remains isolated`() {
        val pid = scalar(connection, "SELECT pg_backend_pid()")
        val observed = mutableListOf<ExplainObservation>()
        session = JdbcAnalysisSession(observingConnection(observed, beforeExplain = {
            if (observed.size == 2) {
                assertEquals(1, scalar(admin, "SELECT pg_catalog.pg_terminate_backend($pid)::integer"))
            }
        }))
        val context = RunContext()
        val failure = assertThrows(AnalysisException::class.java) {
            session.evaluate(candidates, workload, context)
        }
        assertEquals(ErrorCode.DB_CONNECTION_ERROR, failure.error.code)
        assertEquals("q_b", failure.error.queryId)
        assertEquals(2, context.statistics().explainAttempts)
        assertEquals(0, context.statistics().completedEvaluations)
        assertTrue(connection.isClosed)
        assertNull(failure.cause)
        connect().use { fresh ->
            sql(fresh, "SET search_path TO $schema, pg_catalog")
            sql(fresh, "SET ROLE $role")
            assertEquals(0, hypothesisCount(fresh))
            JdbcAnalysisSession(fresh).use { restarted ->
                assertEquals(3, restarted.evaluate(emptyList(), workload, RunContext()).costByQueryId.size)
            }
        }
    }

    @Test
    fun `effective search deadline closes blocked SQL without starting rollback or reset`() {
        val now = AtomicLong()
        val context = RunContext(
            limits = RunLimits(searchTimeout = Duration.ofMillis(150)),
            clock = NanoClock(now::get),
        ).apply { startSearch() }
        val observed = mutableListOf<ExplainObservation>()
        val events = mutableListOf<String>()
        session = JdbcAnalysisSession(observingConnection(observed,
            beforeExplain = { now.set(Duration.ofMillis(150).toNanos()) },
            onSql = { events += it },
        ))
        connect().use { blocker ->
            blocker.autoCommit = false
            sql(blocker, "LOCK TABLE $schema.records IN ACCESS EXCLUSIVE MODE")
            connection.autoCommit = false
            try {
                val failure = assertThrows(AnalysisException::class.java) {
                    session.evaluate(emptyList(), workload, context)
                }
                assertEquals(ErrorCode.DEADLINE_EXCEEDED, failure.error.code)
                assertFalse(context.remainingCommandTime().isZero)
                assertEquals(1, context.statistics().explainAttempts)
                assertEquals(0, context.statistics().completedEvaluations)
                assertTrue(connection.isClosed)
                val explain = events.indexOfFirst { it.startsWith("EXPLAIN ") }
                assertTrue(explain >= 0)
                assertTrue(events.drop(explain + 1).isEmpty(), "No cleanup SQL may start after deadline")
            } finally {
                blocker.rollback()
            }
        }
    }

    @Test
    fun `cancellation from another thread releases a blocked physical connection`() {
        val pid = scalar(connection, "SELECT pg_backend_pid()")
        connect().use { blocker ->
            blocker.autoCommit = false
            sql(blocker, "LOCK TABLE $schema.records IN ACCESS EXCLUSIVE MODE")
            val context = RunContext(limits = RunLimits(explainTimeout = Duration.ofSeconds(5)))
            val result = CompletableFuture.supplyAsync {
                assertThrows(AnalysisException::class.java) { session.evaluate(emptyList(), workload, context) }
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (scalar(admin, "SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE pid = $pid AND wait_event_type = 'Lock'") == 0) {
                    check(System.nanoTime() < deadline) { "EXPLAIN did not reach the lock wait" }
                    Thread.sleep(10)
                }
                context.cancel()
                session.cancel()
                val failure = result.get(5, TimeUnit.SECONDS)
                assertEquals(ErrorCode.CANCELLED, failure.error.code)
                assertTrue(connection.isClosed)
                assertEquals(0, context.statistics().completedEvaluations)
                session.cancel()
                session.close()
            } finally {
                blocker.rollback()
                session.cancel()
            }
        }
    }

    private fun input(): WorkloadInput {
        val queries = listOf(
            QueryInput("q_id", "SELECT payload FROM records WHERE id = 314"),
            QueryInput("q_b", "SELECT payload FROM records WHERE b = 314"),
            QueryInput("q_a", "SELECT payload FROM records WHERE a = 314 ORDER BY id LIMIT 10"),
        )
        return WorkloadInput(queries, listOf(
            Profile("day", 1.0, mapOf("q_a" to 10.0, "q_b" to 1.0, "q_id" to 2.0)),
            Profile("night", 2.0, mapOf("q_a" to 1.0, "q_b" to 10.0, "q_id" to 2.0)),
        ), Constraints(1_000_000_000, null))
    }

    private fun connect(): Connection = DriverManager.getConnection(
        System.getenv("INDEXBUDGET_HYPOPG_TEST_URL"), System.getenv("INDEXBUDGET_HYPOPG_TEST_USER"),
        System.getenv("INDEXBUDGET_HYPOPG_TEST_PASSWORD"),
    )

    private fun sql(target: Connection, text: String) = target.createStatement().use { it.execute(text) }
    private fun scalar(target: Connection, text: String) = longScalar(target, text).toInt()
    private fun longScalar(target: Connection, text: String): Long = target.createStatement().use { statement ->
        statement.executeQuery(text).use { rows -> check(rows.next()); rows.getLong(1) }
    }
    private fun hypothesisCount(target: Connection) = scalar(target, "SELECT count(*) FROM public.hypopg()")
    private fun createHypothesis(target: Connection, ddl: String): Long =
        target.prepareStatement("SELECT indexrelid FROM public.hypopg_create_index(?)").use { statement ->
            statement.setString(1, ddl)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
        }

    private fun settings(): List<String> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT name || '=' || setting FROM pg_catalog.pg_settings " +
            "WHERE name IN ('search_path', 'enable_seqscan', 'enable_indexscan', 'enable_bitmapscan', 'random_page_cost') ORDER BY name").use { rows ->
            buildList { while (rows.next()) add(rows.getString(1)) }
        }
    }

    private data class ExplainObservation(val sql: String, val keys: List<String>, val pid: Int)

    /** Delegates to real JDBC; observes HypoPG on that same backend immediately before EXPLAIN. */
    private fun observingConnection(
        observed: MutableList<ExplainObservation>,
        beforeExplain: (String) -> Unit = {},
        onSql: (String) -> Unit = {},
    ): Connection =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "rollback") onSql("ROLLBACK")
            val value = invoke(connection, method, args)
            if (value is Statement && method.name in setOf("createStatement", "prepareStatement")) {
                val preparedSql = args?.firstOrNull() as? String
                val type = if (value is PreparedStatement) PreparedStatement::class.java else Statement::class.java
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, statementMethod, statementArgs ->
                    val sql = statementArgs?.firstOrNull() as? String ?: preparedSql
                    if (statementMethod.name in setOf("execute", "executeQuery") && sql != null) onSql(sql)
                    if (statementMethod.name in setOf("execute", "executeQuery") && sql?.startsWith("EXPLAIN ") == true) {
                        val keys = connection.createStatement().use { query ->
                            query.executeQuery("SELECT indkey::text FROM public.hypopg() ORDER BY indkey::text").use { rows ->
                                buildList { while (rows.next()) add(rows.getString(1)) }
                            }
                        }
                        observed += ExplainObservation(sql, keys, scalar(connection, "SELECT pg_backend_pid()"))
                        beforeExplain(sql)
                    }
                    invoke(value, statementMethod, statementArgs)
                }
            } else value
        } as Connection

    private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
}
