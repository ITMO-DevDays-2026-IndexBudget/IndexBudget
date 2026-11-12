package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.candidates.CandidateGenerator
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.optimizer.GreedyOptimizer
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.postgres.AnalysisSessionFactory
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.ReportExporter
import ru.itmo.indexbudget.report.JsonReportWriter
import ru.itmo.indexbudget.report.HtmlReportRenderer
import tools.jackson.databind.json.JsonMapper
import ru.itmo.indexbudget.sql.ParsedWorkload
import ru.itmo.indexbudget.sql.SqlAnalyzer
import java.nio.file.Path
import java.time.Duration

/** Synthetic size/cost observations exercise the real preparation, metrics and search together. */
class CandidateRefillPipelineTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `reserve exposes affordable alternatives after fourteen initial storage rejections`() {
        val original = Harness(withReserve = false)
        val refilled = Harness(withReserve = true)

        val originalResult = original.execute()
        val refilledResult = refilled.execute()

        assertEquals(RunStatus.COMPLETED, originalResult.status)
        assertEquals(RunStatus.COMPLETED, refilledResult.status)
        assertEquals(original.pool.take(16).map { it.id }, refilled.pool.take(16).map { it.id })
        assertEquals(16, original.sizeCalls.size)
        assertEquals(18, refilled.sizeCalls.size)
        assertEquals(listOf("c16"), originalResult.selected?.evaluation?.candidateIds)
        assertEquals(listOf("c17"), refilledResult.selected?.evaluation?.candidateIds)
        assertEquals(70.0, originalResult.selected?.evaluation?.costByQueryId?.get("wide"))
        assertEquals(40.0, refilledResult.selected?.evaluation?.costByQueryId?.get("wide"))
        assertEquals(3, original.evaluatedSets.size)
        assertEquals(5, refilled.evaluatedSets.size)
        assertTrue(refilled.evaluatedSets.drop(1).all { ids -> ids.single() in refilled.pool.drop(14).map { it.id } })
        val preparation = checkNotNull(refilled.report.candidates.diagnostics.preparation)
        assertEquals(14, preparation.budgetRejectedCandidates)
        assertEquals(2, preparation.refilledCandidates)
        assertEquals(4, preparation.finalShortlistedCandidates)
        assertEquals(18, preparation.sizeAttempts)
        assertEquals(CandidatePreparationStopReason.RESERVE_EXHAUSTED, preparation.stopReason)
        assertEquals(18, refilled.report.candidates.candidates.size)
        assertEquals(1, original.closeCalls)
        assertEquals(1, refilled.closeCalls)
    }

    @Test
    fun `local size error retains unknown estimate and continues through reserve`() {
        val run = Harness(withReserve = true)
        val rejectedId = run.pool[14].id
        run.onSize = { candidate ->
            if (candidate.id == rejectedId) problem(ErrorCode.HYPOTHETICAL_INDEX_ERROR)
        }

        val result = run.execute()

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf("c17"), result.selected?.evaluation?.candidateIds)
        assertEquals(18, run.sizeCalls.size)
        val preparation = checkNotNull(run.report.candidates.diagnostics.preparation)
        assertEquals(1, preparation.candidateErrors)
        assertEquals(2, preparation.refilledCandidates)
        assertEquals(3, preparation.finalShortlistedCandidates)
        val rejected = run.report.candidates.candidates.single { it.id == rejectedId }
        assertNull(rejected.estimatedBytes)
        assertNull(rejected.budgetedBytes)
        assertTrue(run.report.candidates.diagnostics.decisions.any {
            it.candidateId == rejectedId && it.reason == CandidateReason.HYPOTHETICAL_INDEX_ERROR
        })
        assertFalse(run.evaluatedSets.any { rejectedId in it })
        assertEquals(1, run.closeCalls)
    }

    @Test
    fun `deadline during reserve size exports full baseline preparation facts and closes session`() {
        val run = Harness(withReserve = true)
        run.onSize = { candidate ->
            if (candidate.id == run.pool[16].id) {
                run.expireSearch()
                run.context.checkDeadline()
            }
        }

        val result = run.execute()

        assertInterruptedPreparation(run, result, StopReason.DEADLINE_EXCEEDED,
            CandidatePreparationStopReason.DEADLINE_EXCEEDED)
    }

    @Test
    fun `connection loss during reserve size cannot become a local candidate rejection`() {
        val run = Harness(withReserve = true)
        run.onSize = { candidate ->
            if (candidate.id == run.pool[16].id) problem(ErrorCode.DB_CONNECTION_ERROR)
        }

        val result = run.execute()

        assertInterruptedPreparation(run, result, StopReason.CONNECTION_LOST,
            CandidatePreparationStopReason.CONNECTION_LOST)
        assertEquals(0, run.report.candidates.diagnostics.preparation?.candidateErrors)
        assertFalse(run.report.candidates.diagnostics.decisions.any {
            it.candidateId == run.pool[16].id && it.reason == CandidateReason.HYPOTHETICAL_INDEX_ERROR
        })
    }

    @Test
    fun `zero storage point keeps the common report pool without generating or measuring candidates`() {
        val preparation = Harness(withReserve = true)
        val pool = preparation.prepareFixedPool()
        val point = Harness(withReserve = true)
        val zeroBudget = point.input.copy(constraints = point.input.constraints.copy(storageBudgetBytes = 0))

        val result = point.execute(input = zeroBudget, fixedPool = pool)

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf(StopReason.ZERO_STORAGE_BUDGET), result.diagnostics.stopReasons)
        assertEquals(emptyList<String>(), result.selected?.evaluation?.candidateIds)
        assertEquals(1, point.openCalls)
        assertEquals(1, point.closeCalls)
        assertEquals(1, point.evaluatedSets.size)
        assertEquals(0, point.generateCalls)
        assertTrue(point.sizeCalls.isEmpty())
        assertEquals(18, point.report.candidates.candidates.size)
        assertEquals(pool.reportBatch.candidates, point.report.candidates.candidates)
        assertEquals(pool.reportBatch.diagnostics.preparation, point.report.candidates.diagnostics.preparation)
        val diagnostics = point.report.candidates.diagnostics
        assertEquals(CandidatePreparationScope.COMMON_POOL, diagnostics.preparationScope)
        assertEquals(FixedPoolSelectionStatistics(4, 0, 4), diagnostics.fixedPoolSelection)
        val json = JsonMapper.builder().build().readTree(JsonReportWriter().render(point.report))["candidate_diagnostics"]
        assertEquals("common_pool", json["preparation_scope"].stringValue())
        assertEquals(18, json["preparation"]["size_attempts"].intValue())
        assertEquals(0, json["fixed_pool_selection"]["size_attempts"].intValue())
        assertEquals(0, json["fixed_pool_selection"]["admissible_candidates"].intValue())
        assertEquals(4, json["fixed_pool_selection"]["budget_rejected_candidates"].intValue())
        val html = HtmlReportRenderer().render(point.report)
        assertTrue(html.contains("Общая подготовка пула"))
        assertTrue(html.contains("Текущая бюджетная точка"))
        assertTrue(html.contains("<h3>Итоговый пул кандидатов</h3><p>0 / 16</p>"))
    }

    @Test
    fun `positive fixed pool point filters candidates and reaches optimizer without new sizes`() {
        val preparation = Harness(withReserve = true)
        preparation.candidateSizes = mapOf("c15" to 100L, "c16" to 90L)
        val pool = preparation.prepareFixedPool()
        val point = Harness(withReserve = true)
        val input = point.input.copy(constraints = point.input.constraints.copy(storageBudgetBytes = 60))

        val result = point.execute(input, pool)

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf("c17"), result.selected?.evaluation?.candidateIds)
        assertEquals(100.0, result.baseline?.costByQueryId?.get("wide"))
        assertEquals(listOf(emptyList(), listOf("c17"), listOf("c18")), point.evaluatedSets)
        assertEquals(0, point.generateCalls)
        assertTrue(point.sizeCalls.isEmpty())
        assertEquals(1, point.closeCalls)
        assertEquals(FixedPoolSelectionStatistics(4, 2, 2), point.report.candidates.diagnostics.fixedPoolSelection)
    }

    @Test
    fun `incompatible fixed pool input is rejected before opening even with zero storage`() {
        val preparation = Harness(withReserve = true)
        val pool = preparation.prepareFixedPool()
        for (changeQueries in listOf(false, true)) {
            val point = Harness(withReserve = true)
            val zeroBudget = point.input.copy(constraints = point.input.constraints.copy(storageBudgetBytes = 0))
            val incompatible = if (changeQueries) zeroBudget.copy(queries = zeroBudget.queries.map {
                it.copy(sql = it.sql + " LIMIT 10")
            }) else zeroBudget.copy(objective = Objective.MEAN)

            val result = point.execute(input = incompatible, fixedPool = pool)

            assertEquals(RunStatus.FAILED, result.status)
            assertEquals(ErrorCode.INVALID_INPUT, (result as SearchResult.Failed).error.code)
            assertEquals(0, point.openCalls)
            assertEquals(0, point.closeCalls)
            assertEquals(0, point.generateCalls)
            assertTrue(point.sizeCalls.isEmpty())
            assertTrue(point.evaluatedSets.isEmpty())
            assertNull(result.baseline)
        }
    }

    private fun assertInterruptedPreparation(
        run: Harness,
        result: SearchResult,
        stopReason: StopReason,
        preparationReason: CandidatePreparationStopReason,
    ) {
        assertEquals(RunStatus.PARTIAL, result.status)
        assertTrue(stopReason in result.diagnostics.stopReasons)
        assertEquals(emptyList<String>(), result.selected?.evaluation?.candidateIds)
        assertEquals(100.0, result.baseline?.costByQueryId?.get("wide"))
        assertEquals(1, run.evaluatedSets.size, "Preparation must not evaluate incomplete candidate sets")
        assertEquals(17, run.sizeCalls.size)
        val preparation = checkNotNull(run.report.candidates.diagnostics.preparation)
        assertEquals(17, preparation.sizeAttempts)
        assertEquals(2, preparation.finalShortlistedCandidates)
        assertEquals(preparationReason, preparation.stopReason)
        assertNull(run.report.candidates.candidates.single { it.id == run.pool[16].id }.estimatedBytes)
        assertNull(run.report.candidates.candidates.single { it.id == run.pool[17].id }.estimatedBytes)
        assertEquals(1, run.closeCalls)
        assertSame(result, run.report.result)
    }

    private inner class Harness(private val withReserve: Boolean) {
        private var now = 0L
        val context = RunContext(clock = NanoClock { now })
            .apply { recordInputHash("b".repeat(64)) }
        private val table = ContractFixtures.wideTable
        private val columns = (1..16).map { ColumnRef(table, "c$it") }
        val input = WorkloadInput(
            queries = listOf(QueryInput("wide", "SELECT * FROM public.wide_records WHERE " +
                columns.joinToString(" AND ") { "${it.name} = 1" })),
            profiles = listOf(Profile("profile", 1.0, mapOf("wide" to 1.0))),
            constraints = Constraints(storageBudgetBytes = 120, writeBudgetUnitsPerHour = null, maxIndexes = 1),
        )
        private val resolved = ResolvedWorkload(input, listOf(ResolvedQuery(
            id = "wide", sql = input.queries.single().sql, tables = setOf(table),
            referencedColumns = columns.toSet(),
            predicates = columns.map { ResolvedPredicate(it, ComparisonOperator.EQUAL, PredicateSource.WHERE) },
            orderBy = emptyList(), selectsAllColumns = true,
        )))
        private val catalog = ContractFixtures.wideCatalog()
        private val generated = ProfileAwareCandidateGenerator().generate(resolved, catalog, RunContext(clock = NanoClock { 0 }))
        // Stable readable fixture IDs retain the production generator's definitions and queue order.
        val pool = (generated.candidates + generated.reserve).take(18)
            .mapIndexed { index, candidate -> candidate.copy(id = "c${(index + 1).toString().padStart(2, '0')}") }
            .also { require(it.size == 18) }
        val sizeCalls = mutableListOf<String>()
        val evaluatedSets = mutableListOf<List<String>>()
        var candidateSizes: Map<String, Long> = emptyMap()
        var onSize: (IndexCandidate) -> Unit = {}
        var closeCalls = 0
        var openCalls = 0
        var generateCalls = 0
        private var closed = false
        lateinit var report: AnalysisReport

        fun expireSearch() { now = Duration.ofSeconds(60).toNanos() }

        private val session = object : AnalysisSession {
            override fun readCatalog(tables: Set<TableName>, context: RunContext): CatalogSnapshot = catalog

            override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long {
                assertFalse(closed)
                sizeCalls += candidate.id
                onSize(candidate)
                return candidateSizes[candidate.id] ?: if (pool.indexOfFirst { it.id == candidate.id } < 14) 1000 else 50
            }

            override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload,
                                  context: RunContext): EvaluationResult {
                assertFalse(closed)
                context.reserveEvaluationAttempt()
                context.reserveExplainAttempt()
                val ids = candidates.map { it.id }.sorted()
                evaluatedSets += ids
                val cost = if (candidates.isEmpty()) 100.0 else when (pool.indexOfFirst { it.id == ids.single() }) {
                    14 -> 80.0
                    15 -> 70.0
                    16 -> 40.0
                    17 -> 50.0
                    else -> error("An over-budget candidate reached EXPLAIN")
                }
                context.recordCompletedEvaluation()
                return EvaluationResult(ids, mapOf("wide" to cost),
                    mapOf("wide" to ExplainPlan("""[{"Plan":{"Total Cost":$cost}}]""")))
            }

            override fun cancel() = Unit
            override fun close() { closeCalls++; closed = true }
        }

        private fun batch(): CandidateBatch {
            val reserve = if (withReserve) pool.drop(16) else emptyList()
            return generated.copy(candidates = pool.take(16), reserve = reserve,
                diagnostics = generated.diagnostics.copy(initialShortlistedCandidates = 16,
                    reserveCandidates = reserve.size, shortlistedCandidates = 16))
        }

        fun prepareFixedPool(): PreparedCandidatePool = try {
            CandidatePreparation(MetricsCalculatorImpl()).prepareFixedPool(batch(), session, input, context)
        } finally {
            session.close()
        }

        fun execute(input: WorkloadInput = this.input, fixedPool: PreparedCandidatePool? = null): SearchResult {
            val pipeline = AnalysisPipeline(
                sqlAnalyzer = object : SqlAnalyzer {
                    override fun parse(input: WorkloadInput, context: RunContext): ParsedWorkload {
                        val source = input
                        return object : ParsedWorkload {
                            override val input = source
                            override val referencedTables = setOf(TableName(table.name, table.schema))
                        }
                    }
                    override fun resolve(parsed: ParsedWorkload, catalog: CatalogSnapshot, context: RunContext) =
                        resolved.copy(input = parsed.input)
                },
                sessionFactory = object : AnalysisSessionFactory {
                    override fun open(context: RunContext): AnalysisSession { openCalls++; return session }
                },
                candidateGenerator = object : CandidateGenerator {
                    override fun generate(workload: ResolvedWorkload, catalog: CatalogSnapshot, context: RunContext): CandidateBatch {
                        generateCalls++
                        return batch()
                    }
                },
                metrics = MetricsCalculatorImpl(), comparator = ObjectiveComparatorImpl(), optimizer = GreedyOptimizer(),
                reportBuilder = DefaultReportBuilder(environment = EnvironmentInfo("synthetic", "synthetic")),
                reportExporter = ReportExporter { report, _, _ ->
                    assertTrue(closed || openCalls == 0, "Export must use saved observations after closing the session")
                    this.report = report
                },
                shutdownCancellation = ShutdownCancellation { AutoCloseable {} },
            )
            val output = directory.resolve(if (withReserve) "refilled" else "original")
            return if (fixedPool == null) pipeline.run(input, output, context)
                else pipeline.runWithFixedPool(input, output, context, fixedPool)
        }
    }

    companion object {
        private fun problem(code: ErrorCode): Nothing = throw AnalysisException(AnalysisError(code, "Synthetic size failure"))
    }
}
