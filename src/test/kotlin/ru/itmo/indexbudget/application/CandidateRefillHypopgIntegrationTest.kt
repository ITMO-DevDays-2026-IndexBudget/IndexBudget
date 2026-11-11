package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.candidates.CandidateGenerator
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.GreedyOptimizer
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.postgres.AnalysisSessionFactory
import ru.itmo.indexbudget.postgres.JdbcAnalysisSession
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.ReportExporter
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Opt-in physical fixtures in a disposable test database; never run against the shared demo DB. */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_HYPOPG_TEST_URL", matches = ".+")
class CandidateRefillHypopgIntegrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `real HypoPG sizes refill affordable reserve and cleanup both comparison runs`() {
        connect().use { admin ->
            val schema = "refill_" + UUID.randomUUID().toString().replace("-", "")
            sql(admin, "CREATE EXTENSION IF NOT EXISTS hypopg WITH SCHEMA public")
            sql(admin, "CREATE SCHEMA $schema")
            try {
                val columns = (1..14).map { "a" + it.toString().padStart(2, '0') }
                sql(admin, "CREATE TABLE $schema.large (${columns.joinToString(", ") { "$it integer" }}, payload text)")
                sql(admin, "INSERT INTO $schema.large SELECT ${columns.joinToString(", ") { "i" }}, repeat('x', 100) " +
                    "FROM generate_series(1, 50000) i")
                sql(admin, "CREATE TABLE $schema.small (a integer, b integer, c integer, d integer, payload text)")
                sql(admin, "INSERT INTO $schema.small SELECT i, i, i, i, repeat('y', 100) FROM generate_series(1, 4000) i")
                sql(admin, "ANALYZE $schema.large")
                sql(admin, "ANALYZE $schema.small")
                val queries = listOf(QueryInput("large", "SELECT payload FROM $schema.large WHERE " +
                    columns.joinToString(" AND ") { "$it = 42" })) +
                    listOf("a", "b", "c", "d").map { QueryInput("small_$it", "SELECT payload FROM $schema.small WHERE $it = 42") }
                val source = WorkloadInput(queries,
                    listOf(Profile("profile", 1.0, queries.associate { it.id to 1.0 })),
                    Constraints(storageBudgetBytes = Long.MAX_VALUE, writeBudgetUnitsPerHour = null, maxIndexes = 1))
                val metrics = MetricsCalculatorImpl()
                val analyzer = JSqlParserSqlAnalyzer()
                val referenceContext = RunContext()
                val measured = JdbcAnalysisSession(connect()).use { session ->
                    val parsed = analyzer.parse(source, referenceContext)
                    val catalog = session.readCatalog(parsed.referencedTables, referenceContext)
                    val resolved = analyzer.resolve(parsed, catalog, referenceContext)
                    val generated = ProfileAwareCandidateGenerator().generate(resolved, catalog, referenceContext)
                    val all = generated.candidates + generated.reserve
                    val large = all.filter { it.definition.table.name == "large" && it.definition.keys.size == 1 && it.definition.includedColumns.isEmpty() }
                        .sortedBy { it.definition.keys.single().column.name }
                    val small = all.filter { it.definition.table.name == "small" && it.definition.keys.size == 1 && it.definition.includedColumns.isEmpty() }
                        .sortedBy { it.definition.keys.single().column.name }
                    assertEquals(14, large.size)
                    assertEquals(4, small.size)
                    (large + small).map { candidate ->
                        candidate.copy(estimatedBytes = session.estimateSize(candidate, referenceContext))
                    }
                }
                val smallBudget = measured.drop(14).maxOf { metrics.assessBudget(listOf(it), source).usage.budgetedBytes }
                assertTrue(measured.take(14).all { metrics.assessBudget(listOf(it), source).usage.budgetedBytes > smallBudget })
                val input = source.copy(constraints = source.constraints.copy(storageBudgetBytes = smallBudget))
                val pool = measured.map { it.copy(estimatedBytes = null) }
                val old = runPipeline(input, pool, withReserve = false)
                val refilled = runPipeline(input, pool, withReserve = true)

                assertEquals(RunStatus.COMPLETED, old.report.result.status)
                assertEquals(RunStatus.COMPLETED, refilled.report.result.status)
                assertEquals(16, old.sizeCalls)
                assertEquals(18, refilled.sizeCalls)
                assertEquals(3, old.report.result.diagnostics.statistics.completedEvaluations)
                assertEquals(5, refilled.report.result.diagnostics.statistics.completedEvaluations)
                assertEquals(2, old.report.candidates.diagnostics.preparation?.finalShortlistedCandidates)
                assertEquals(4, refilled.report.candidates.diagnostics.preparation?.finalShortlistedCandidates)
                assertEquals(14, refilled.report.candidates.diagnostics.preparation?.budgetRejectedCandidates)
                assertEquals(2, refilled.report.candidates.diagnostics.preparation?.refilledCandidates)
                assertEquals(old.report.result.baseline?.costByQueryId, refilled.report.result.baseline?.costByQueryId)
                assertTrue(checkNotNull(refilled.report.result.selected).metrics.meanCost <=
                    checkNotNull(old.report.result.selected).metrics.meanCost)
                assertTrue(old.closed)
                assertTrue(refilled.closed)
                assertEquals(0, scalar(admin, "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = '$schema'"),
                    "Ordinary analysis must not create any physical index")
            } finally {
                sql(admin, "DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }

    private data class Observation(val report: AnalysisReport, val sizeCalls: Int, val closed: Boolean)

    private fun runPipeline(input: WorkloadInput, pool: List<IndexCandidate>, withReserve: Boolean): Observation {
        val connection = connect()
        val actualSession = JdbcAnalysisSession(connection)
        var sizeCalls = 0
        var report: AnalysisReport? = null
        val observed = object : AnalysisSession by actualSession {
            override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long {
                sizeCalls++
                return actualSession.estimateSize(candidate, context)
            }
            override fun close() {
                assertEquals(0, scalar(connection, "SELECT count(*) FROM public.hypopg()"))
                actualSession.close()
            }
        }
        try {
            val pipeline = AnalysisPipeline(
                sqlAnalyzer = JSqlParserSqlAnalyzer(),
                sessionFactory = object : AnalysisSessionFactory {
                    override fun open(context: RunContext): AnalysisSession = observed
                },
                candidateGenerator = object : CandidateGenerator {
                    override fun generate(workload: ResolvedWorkload, catalog: CatalogSnapshot, context: RunContext): CandidateBatch =
                        CandidateBatch(candidates = pool.take(16), reserve = if (withReserve) pool.drop(16) else emptyList(),
                            diagnostics = CandidateDiagnostics(candidatesAfterGlobalDeduplication = pool.size))
                },
                metrics = MetricsCalculatorImpl(), comparator = ObjectiveComparatorImpl(), optimizer = GreedyOptimizer(),
                reportBuilder = DefaultReportBuilder(),
                reportExporter = ReportExporter { built, _, _ ->
                    assertTrue(connection.isClosed)
                    report = built
                },
                shutdownCancellation = ShutdownCancellation { AutoCloseable {} },
            )
            pipeline.run(input, directory.resolve(if (withReserve) "refilled" else "original"), RunContext())
            return Observation(checkNotNull(report), sizeCalls, connection.isClosed)
        } finally {
            actualSession.close()
        }
    }

    private fun connect(): Connection = DriverManager.getConnection(
        System.getenv("INDEXBUDGET_HYPOPG_TEST_URL"), System.getenv("INDEXBUDGET_HYPOPG_TEST_USER"),
        System.getenv("INDEXBUDGET_HYPOPG_TEST_PASSWORD"),
    )

    private fun sql(connection: Connection, statement: String) = connection.createStatement().use { it.execute(statement) }

    private fun scalar(connection: Connection, statement: String): Int = connection.createStatement().use {
        it.executeQuery(statement).use { rows -> check(rows.next()); rows.getInt(1) }
    }
}
