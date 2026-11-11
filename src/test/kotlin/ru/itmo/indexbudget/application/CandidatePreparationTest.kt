package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.GreedyOptimizer
import ru.itmo.indexbudget.postgres.AnalysisSession
import java.time.Duration

class CandidatePreparationTest {
    private val metrics = MetricsCalculatorImpl()
    private val preparation = CandidatePreparation(metrics)
    private val table = TableRef("public", "records")

    @Test
    fun `reserve recovers affordable alternatives and changes selection with explicit database costs`() {
        val candidates = (1..18).map { candidate("c${it.toString().padStart(2, '0')}") }
        val input = input(Constraints(100, null, 1))
        val costs = mapOf(emptyList<String>() to 100.0, listOf("c15") to 60.0,
            listOf("c16") to 50.0, listOf("c17") to 40.0, listOf("c18") to 20.0)
        fun session() = FakeSession(costs) { candidate, _ ->
            if (candidate.id.substring(1).toInt() <= 14) 1000L else 10L
        }
        val legacySession = session()
        val legacy = preparation.prepare(batch(candidates.take(16)), legacySession, input, RunContext())
        val refillSession = session()
        val refill = preparation.prepare(batch(candidates), refillSession, input, RunContext())

        assertEquals(listOf("c15", "c16"), legacy.searchBatch.candidates.map { it.id })
        assertEquals(listOf("c15", "c16", "c17", "c18"), refill.searchBatch.candidates.map { it.id })
        assertEquals(16, legacySession.sizeCalls.size)
        assertEquals(18, refillSession.sizeCalls.size)
        val legacySelected = checkNotNull(select(legacy.searchBatch, legacySession, input).selected)
        val refillSelected = checkNotNull(select(refill.searchBatch, refillSession, input).selected)
        assertEquals(listOf("c16"), legacySelected.evaluation.candidateIds)
        assertEquals(listOf("c18"), refillSelected.evaluation.candidateIds)
        assertEquals(3, legacySession.evaluated.size)
        assertEquals(5, refillSession.evaluated.size)
        val statistics = checkNotNull(refill.reportBatch.diagnostics.preparation)
        assertEquals(14, statistics.budgetRejectedCandidates)
        assertEquals(2, statistics.refilledCandidates)
        assertEquals(4, statistics.finalShortlistedCandidates)
        assertEquals(16, refill.reportBatch.diagnostics.shortlistedCandidates,
            "The existing report field retains its pre-preparation meaning")
        assertEquals(CandidatePreparationStopReason.RESERVE_EXHAUSTED, statistics.stopReason)
        assertEquals(18, refill.reportBatch.candidates.size)
        assertTrue(refill.reportBatch.reserve.isEmpty())
    }

    @Test
    fun `full shortlist stops sizing and retains untouched reserve with unknown estimates`() {
        val candidates = (1..64).map { candidate("c$it") }
        val session = FakeSession { _, _ -> 10L }

        val result = preparation.prepare(batch(candidates), session, input(), RunContext())

        assertEquals(candidates.take(16).map { it.id }, result.searchBatch.candidates.map { it.id })
        assertEquals(16, session.sizeCalls.size)
        assertEquals(64, result.reportBatch.candidates.size)
        assertTrue(result.reportBatch.candidates.drop(16).all {
            it.estimatedBytes == null && it.budgetedBytes == null && it.writeUnitsPerHour == null
        })
        val statistics = checkNotNull(result.reportBatch.diagnostics.preparation)
        assertEquals(CandidatePreparationStopReason.SHORTLIST_FULL, statistics.stopReason)
        assertEquals(0, statistics.refilledCandidates)
    }

    @Test
    fun `write rejection happens before size and zero write budget still admits quiet tables`() {
        val quiet = TableRef("public", "quiet")
        val busy = candidate("busy")
        val silent = candidate("silent", quiet)
        val input = input(Constraints(100, 0.0, 1)).copy(tableWriteRates = mapOf(
            "public.records" to TableWriteRates(0.0, 1.0, 0.0),
            "public.quiet" to TableWriteRates(0.0, 0.0, 0.0),
        ))
        val session = FakeSession { _, _ -> 10L }

        val result = preparation.prepare(batch(listOf(busy, silent)), session, input, RunContext())

        assertEquals(listOf("silent"), session.sizeCalls)
        assertEquals(listOf("silent"), result.searchBatch.candidates.map { it.id })
        assertEquals(0.0, result.searchBatch.candidates.single().writeUnitsPerHour)
        val rejected = result.reportBatch.candidates.single { it.id == "busy" }
        assertNull(rejected.estimatedBytes)
        assertNull(rejected.budgetedBytes)
        assertTrue(result.reportBatch.diagnostics.decisions.any {
            it.candidateId == "busy" && it.reason == CandidateReason.WRITE_BUDGET
        })
    }

    @Test
    fun `zero storage or max indexes needs no hypothetical size`() {
        val cases = listOf(Constraints(0, null, 1) to CandidateReason.STORAGE_BUDGET,
            Constraints(100, null, 0) to CandidateReason.MAX_INDEXES)
        for ((constraints, reason) in cases) {
            val session = FakeSession { _, _ -> fail("Size must not be requested") }
            val result = preparation.prepare(batch(listOf(candidate("A"))), session, input(constraints), RunContext())
            assertTrue(result.searchBatch.candidates.isEmpty())
            assertTrue(session.sizeCalls.isEmpty())
            assertNull(result.reportBatch.candidates.single().estimatedBytes)
            assertTrue(result.reportBatch.diagnostics.decisions.any { it.reason == reason })
        }
    }

    @Test
    fun `size limit bounds failed attempts and leaves the remaining pool unknown`() {
        val candidates = (1..64).map { candidate("c$it") }
        val session = FakeSession { _, _ -> throw failure(ErrorCode.HYPOTHETICAL_INDEX_ERROR) }

        val result = preparation.prepare(batch(candidates), session, input(), RunContext())

        val statistics = checkNotNull(result.reportBatch.diagnostics.preparation)
        assertEquals(32, session.sizeCalls.size)
        assertEquals(32, statistics.sizeAttempts)
        assertEquals(32, statistics.candidateErrors)
        assertEquals(CandidatePreparationStopReason.SIZE_LIMIT, statistics.stopReason)
        assertTrue(result.searchBatch.candidates.isEmpty())
        assertTrue(result.reportBatch.candidates.all { it.estimatedBytes == null && it.budgetedBytes == null })
        assertTrue(result.reportBatch.diagnostics.warnings.isNotEmpty())
        assertTrue(result.reportBatch.diagnostics.decisions.any {
            it.candidateId == "c33" && it.reason == CandidateReason.NOT_EVALUATED_DUE_TO_LIMIT
        })
    }

    @Test
    fun `cheap write exclusions remain possible after the last size attempt`() {
        val quiet = TableRef("public", "quiet")
        val candidates = (1..32).map { candidate("quiet$it", quiet) } + candidate("busy") + candidate("last", quiet)
        val input = input(Constraints(100, 0.0, 1)).copy(tableWriteRates = mapOf(
            "public.records" to TableWriteRates(1.0, 0.0, 0.0),
            "public.quiet" to TableWriteRates(0.0, 0.0, 0.0),
        ))
        val session = FakeSession { _, _ -> 1000L }

        val result = preparation.prepare(batch(candidates), session, input, RunContext())

        assertEquals(32, session.sizeCalls.size)
        assertTrue(result.reportBatch.diagnostics.decisions.any {
            it.candidateId == "busy" && it.reason == CandidateReason.WRITE_BUDGET
        })
        val statistics = checkNotNull(result.reportBatch.diagnostics.preparation)
        assertEquals(33, statistics.budgetRejectedCandidates)
        assertEquals(CandidatePreparationStopReason.SIZE_LIMIT, statistics.stopReason)
        assertNull(result.reportBatch.candidates.last().estimatedBytes)
    }

    @Test
    fun `local hypothetical error is recoverable and not counted as a budget rejection`() {
        val session = FakeSession { candidate, _ ->
            if (candidate.id == "bad") throw failure(ErrorCode.HYPOTHETICAL_INDEX_ERROR)
            10L
        }

        val result = preparation.prepare(batch(listOf(candidate("bad"), candidate("good"))),
            session, input(), RunContext())

        assertEquals(listOf("bad", "good"), session.sizeCalls)
        assertEquals(listOf("good"), result.searchBatch.candidates.map { it.id })
        assertNull(result.reportBatch.candidates.first().estimatedBytes)
        assertNull(result.reportBatch.candidates.first().budgetedBytes)
        val statistics = checkNotNull(result.reportBatch.diagnostics.preparation)
        assertEquals(1, statistics.candidateErrors)
        assertEquals(0, statistics.budgetRejectedCandidates)
        assertTrue(result.reportBatch.diagnostics.decisions.any {
            it.candidateId == "bad" && it.reason == CandidateReason.HYPOTHETICAL_INDEX_ERROR
        })
    }

    @Test
    fun `lost session publishes known sizes then stops without consuming reserve`() {
        val candidates = listOf(candidate("known"), candidate("lost"), candidate("untouched"))
        val session = FakeSession { candidate, _ ->
            if (candidate.id == "lost") throw failure(ErrorCode.DB_CONNECTION_ERROR)
            10L
        }
        var snapshot: CandidateBatch? = null

        val error = assertThrows(AnalysisException::class.java) {
            preparation.prepare(batch(candidates), session, input(), RunContext()) { snapshot = it }
        }

        assertEquals(ErrorCode.DB_CONNECTION_ERROR, error.error.code)
        assertEquals(listOf("known", "lost"), session.sizeCalls)
        val saved = checkNotNull(snapshot)
        assertEquals(10L, saved.candidates.first().estimatedBytes)
        assertTrue(saved.candidates.drop(1).all { it.estimatedBytes == null })
        assertEquals(CandidatePreparationStopReason.CONNECTION_LOST,
            checkNotNull(saved.diagnostics.preparation).stopReason)
    }

    @Test
    fun `local error with failed cleanup is propagated and does not try the next candidate`() {
        val primary = failure(ErrorCode.HYPOTHETICAL_INDEX_ERROR).also {
            it.addSuppressed(failure(ErrorCode.DB_CONNECTION_ERROR))
        }
        val session = FakeSession { _, _ -> throw primary }
        var snapshot: CandidateBatch? = null

        val error = assertThrows(AnalysisException::class.java) {
            preparation.prepare(batch(listOf(candidate("bad"), candidate("next"))),
                session, input(), RunContext()) { snapshot = it }
        }

        assertSame(primary, error)
        assertEquals(listOf("bad"), session.sizeCalls)
        val saved = checkNotNull(snapshot)
        assertNotNull(saved.diagnostics.preparation)
        assertTrue(saved.candidates.all { it.estimatedBytes == null })
    }

    @Test
    fun `negative size is fatal and cannot be replaced by a zero or reserve candidate`() {
        val session = FakeSession { _, _ -> -1L }
        var snapshot: CandidateBatch? = null

        val error = assertThrows(AnalysisException::class.java) {
            preparation.prepare(batch(listOf(candidate("bad"), candidate("next"))),
                session, input(), RunContext()) { snapshot = it }
        }

        assertEquals(ErrorCode.NUMERIC_ERROR, error.error.code)
        assertEquals(listOf("bad"), session.sizeCalls)
        val saved = checkNotNull(snapshot)
        assertEquals(CandidatePreparationStopReason.FAILED, checkNotNull(saved.diagnostics.preparation).stopReason)
        assertTrue(saved.candidates.all { it.estimatedBytes == null && it.budgetedBytes == null })
    }

    @Test
    fun `expired deadline after a successful size preserves it without starting another call`() {
        var nanos = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofMillis(10)),
            clock = NanoClock { nanos })
        val session = FakeSession { _, _ -> nanos = Duration.ofMillis(11).toNanos(); 10L }
        var snapshot: CandidateBatch? = null

        val error = assertThrows(AnalysisException::class.java) {
            preparation.prepare(batch(listOf(candidate("known"), candidate("later"))),
                session, input(), context) { snapshot = it }
        }

        assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.error.code)
        assertEquals(listOf("known"), session.sizeCalls)
        val saved = checkNotNull(snapshot)
        assertEquals(10L, saved.candidates.first().estimatedBytes)
        assertNull(saved.candidates.last().estimatedBytes)
        assertEquals(CandidatePreparationStopReason.DEADLINE_EXCEEDED,
            checkNotNull(saved.diagnostics.preparation).stopReason)
    }

    @Test
    fun `cancellation stops before the first size and publishes the untouched pool`() {
        val context = RunContext().also { it.cancel() }
        val session = FakeSession { _, _ -> fail("Cancelled preparation must not request sizes") }
        var snapshot: CandidateBatch? = null

        val error = assertThrows(AnalysisException::class.java) {
            preparation.prepare(batch(listOf(candidate("A"))), session, input(), context) { snapshot = it }
        }

        assertEquals(ErrorCode.CANCELLED, error.error.code)
        assertTrue(session.sizeCalls.isEmpty())
        val saved = checkNotNull(snapshot)
        assertNull(saved.candidates.single().estimatedBytes)
        assertEquals(CandidatePreparationStopReason.CANCELLED, checkNotNull(saved.diagnostics.preparation).stopReason)
    }

    @Test
    fun `empty pool still observes an expired deadline and publishes its stop reason`() {
        var nanos = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofMillis(10)),
            clock = NanoClock { nanos })
        nanos = Duration.ofMillis(11).toNanos()
        val session = FakeSession { _, _ -> fail("Empty preparation must not request sizes") }
        var snapshot: CandidateBatch? = null

        val error = assertThrows(AnalysisException::class.java) {
            preparation.prepare(batch(emptyList()), session, input(), context) { snapshot = it }
        }

        assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.error.code)
        assertTrue(session.sizeCalls.isEmpty())
        val saved = checkNotNull(snapshot)
        assertTrue(saved.candidates.isEmpty())
        val statistics = checkNotNull(saved.diagnostics.preparation)
        assertEquals(0, statistics.examinedCandidates)
        assertEquals(0, statistics.sizeAttempts)
        assertEquals(CandidatePreparationStopReason.DEADLINE_EXCEEDED, statistics.stopReason)
    }

    @Test
    fun `empty pool still observes cancellation and publishes its stop reason`() {
        val context = RunContext().also { it.cancel() }
        val session = FakeSession { _, _ -> fail("Empty preparation must not request sizes") }
        var snapshot: CandidateBatch? = null

        val error = assertThrows(AnalysisException::class.java) {
            preparation.prepare(batch(emptyList()), session, input(), context) { snapshot = it }
        }

        assertEquals(ErrorCode.CANCELLED, error.error.code)
        assertTrue(session.sizeCalls.isEmpty())
        val saved = checkNotNull(snapshot)
        assertTrue(saved.candidates.isEmpty())
        val statistics = checkNotNull(saved.diagnostics.preparation)
        assertEquals(0, statistics.examinedCandidates)
        assertEquals(0, statistics.sizeAttempts)
        assertEquals(CandidatePreparationStopReason.CANCELLED, statistics.stopReason)
    }

    @Test
    fun `budget series filters one prepared maximum pool without independent refill or size calls`() {
        val candidates = (1..18).map { candidate("c${it.toString().padStart(2, '0')}") }
        val maximum = input(Constraints(100, null, 1))
        val session = FakeSession { candidate, _ -> when (candidate.id) {
            "c01" -> 1000L
            "c02", "c03" -> 50L
            else -> 10L
        } }
        val pool = preparation.prepareFixedPool(batch(candidates), session, maximum, RunContext())
        val preparationCalls = session.sizeCalls.toList()

        val narrow = preparation.selectFixedPool(pool, maximum.copy(constraints = Constraints(20, null, 1)), RunContext())
        val wide = preparation.selectFixedPool(pool, maximum, RunContext())

        assertEquals(17, preparationCalls.size)
        assertEquals(preparationCalls, session.sizeCalls)
        assertEquals((2..17).map { "c${it.toString().padStart(2, '0')}" }, wide.searchBatch.candidates.map { it.id })
        assertEquals((4..17).map { "c${it.toString().padStart(2, '0')}" }, narrow.searchBatch.candidates.map { it.id })
        assertFalse(narrow.searchBatch.candidates.any { it.id == "c18" })
        assertNull(narrow.reportBatch.candidates.single { it.id == "c18" }.estimatedBytes)
        assertEquals(pool.reportBatch.diagnostics.preparation, narrow.reportBatch.diagnostics.preparation)
        assertEquals(pool.reportBatch.diagnostics.preparation, wide.reportBatch.diagnostics.preparation)
        assertTrue(narrow.reportBatch.diagnostics.decisions.any {
            it.candidateId == "c02" && it.reason == CandidateReason.STORAGE_BUDGET
        })
    }

    @Test
    fun `fixed pool rejects changes to nonbudget inputs and budgets above prepared maximum`() {
        val maximum = input(Constraints(100, null, 1))
        val session = FakeSession { _, _ -> 10L }
        val pool = preparation.prepareFixedPool(batch(listOf(candidate("A"))), session, maximum, RunContext())
        val invalid = listOf(
            maximum.copy(objective = Objective.MEAN),
            maximum.copy(queries = listOf(QueryInput("q", "SELECT 2"))),
            maximum.copy(profiles = listOf(Profile("other", 1.0, mapOf("q" to 1.0)))),
            maximum.copy(constraints = Constraints(101, null, 1)),
        )
        for (changed in invalid) {
            val error = assertThrows(AnalysisException::class.java) {
                preparation.selectFixedPool(pool, changed, RunContext())
            }
            assertEquals(ErrorCode.INVALID_INPUT, error.error.code)
        }
        assertEquals(1, session.sizeCalls.size)
    }

    private fun select(candidates: CandidateBatch, session: FakeSession, input: WorkloadInput): SearchResult {
        val context = RunContext()
        context.startSearch()
        val resolved = ResolvedWorkload(input, listOf(ResolvedQuery("q", input.queries.single().sql,
            setOf(table), emptySet(), emptyList(), emptyList())))
        val baseline = session.evaluate(emptyList(), resolved, context)
        return GreedyOptimizer().optimize(resolved, candidates, baseline, session, metrics,
            ObjectiveComparatorImpl(), context)
    }

    private fun input(constraints: Constraints = Constraints(100, null, 5)) = WorkloadInput(
        queries = listOf(QueryInput("q", "SELECT * FROM public.records")),
        profiles = listOf(Profile("profile", 1.0, mapOf("q" to 1.0))), constraints = constraints,
    )

    private fun candidate(id: String, table: TableRef = this.table) = IndexCandidate(
        id, IndexDefinition(table, listOf(IndexKey(ColumnRef(table, "key_$id")))),
        setOf("q"), setOf(GenerationReason.SINGLE_COLUMN),
    )

    private fun batch(candidates: List<IndexCandidate>) = CandidateBatch(
        candidates.take(16), CandidateDiagnostics(
            candidatesBeforeGlobalDeduplication = candidates.size,
            candidatesAfterGlobalDeduplication = candidates.size,
            shortlistedCandidates = minOf(16, candidates.size),
            initialShortlistedCandidates = minOf(16, candidates.size),
            reserveCandidates = (candidates.size - 16).coerceAtLeast(0),
        ), candidates.drop(16),
    )

    private fun failure(code: ErrorCode) = AnalysisException(AnalysisError(code, "Safe test failure"))

    private class FakeSession(
        private val costs: Map<List<String>, Double> = emptyMap(),
        private val size: (IndexCandidate, RunContext) -> Long,
    ) : AnalysisSession {
        val sizeCalls = mutableListOf<String>()
        val evaluated = mutableListOf<List<String>>()
        override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long {
            sizeCalls += candidate.id
            return size(candidate, context)
        }
        override fun readCatalog(tables: Set<TableName>, context: RunContext): CatalogSnapshot =
            error("Preparation does not read the catalog")

        override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload,
            context: RunContext): EvaluationResult {
            context.reserveEvaluationAttempt()
            context.reserveExplainAttempt()
            val ids = candidates.map { it.id }.sorted()
            val cost = costs.getValue(ids)
            evaluated += ids
            context.recordCompletedEvaluation()
            return EvaluationResult(ids, mapOf("q" to cost),
                mapOf("q" to ExplainPlan("{\"Plan\":{\"Total Cost\":$cost}}")))
        }
        override fun cancel() = Unit
        override fun close() = Unit
    }
}
