package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KnownBudgetAssessmentTest {
    private val metrics = MetricsCalculatorImpl()
    private val table = TableRef("public", "records")

    @Test
    fun `known write rejection uses the exact boundary before rounding its reported usage`() {
        val unknown = candidate("A")
        val profile = mapOf("public.records" to TableWriteRates(1e16, 1.0, 0.0))
        val input = input(write = 1e16, profile = profile)

        val known = metrics.assessKnownBudget(listOf(unknown), input)
        val measured = metrics.assessBudget(listOf(unknown.copy(estimatedBytes = 10L)), input)

        // The exact expense exceeds the limit although its Double representation rounds back to it.
        assertEquals(1e16, known.writeUnitsPerHour)
        assertEquals(setOf(BudgetViolation.WRITE_BUDGET), known.violations)
        assertFalse(known.admissible)
        assertEquals(known.writeUnitsPerHour, measured.usage.writeUnitsPerHour)
        assertEquals(known.violations, measured.violations)
        val larger = input.copy(constraints = input.constraints.copy(writeBudgetUnitsPerHour = 10_000_000_000_000_002.0))
        assertTrue(metrics.assessKnownBudget(listOf(unknown), larger).admissible)
        assertTrue(metrics.assessBudget(listOf(unknown.copy(estimatedBytes = 10L)), larger).admissible)
        assertNull(unknown.estimatedBytes)
        assertNull(unknown.budgetedBytes)
        assertNull(unknown.writeUnitsPerHour)
    }

    @Test
    fun `known and complete checks agree at an exactly representable fractional write limit`() {
        val unknown = candidate("pair", keys = 2)
        val profile = mapOf("public.records" to TableWriteRates(0.25, 0.5, 0.125))
        val equal = input(write = 1.75, profile = profile)
        val below = equal.copy(constraints = equal.constraints.copy(writeBudgetUnitsPerHour = Math.nextDown(1.75)))

        val knownEqual = metrics.assessKnownBudget(listOf(unknown), equal)
        assertEquals(1.75, knownEqual.writeUnitsPerHour)
        assertTrue(knownEqual.admissible)
        for (workload in listOf(equal, below)) {
            val known = metrics.assessKnownBudget(listOf(unknown), workload)
            val complete = metrics.assessBudget(listOf(unknown.copy(estimatedBytes = 10L)), workload)
            assertEquals(known.writeUnitsPerHour, complete.usage.writeUnitsPerHour)
            assertEquals(known.violations, complete.violations)
        }
        assertEquals(setOf(BudgetViolation.WRITE_BUDGET), metrics.assessKnownBudget(listOf(unknown), below).violations)
    }

    @Test
    fun `known checks report index and write limits without requiring or inventing sizes`() {
        val unknown = listOf(candidate("single"), candidate("pair", keys = 2))
        val profile = mapOf("public.records" to TableWriteRates(1.0, 2.0, 3.0))
        val exact = input(write = 18.0, maxIndexes = 2, profile = profile)
        val known = metrics.assessKnownBudget(unknown, exact)
        assertEquals(18.0, known.writeUnitsPerHour)
        assertTrue(known.admissible)
        val complete = metrics.assessBudget(unknown.map { it.copy(estimatedBytes = 10L) }, exact)
        assertEquals(known.violations, complete.violations)
        assertEquals(known.writeUnitsPerHour, complete.usage.writeUnitsPerHour)

        val fewer = exact.copy(constraints = exact.constraints.copy(maxIndexes = 1))
        assertEquals(setOf(BudgetViolation.MAX_INDEXES), metrics.assessKnownBudget(unknown, fewer).violations)
        val both = fewer.copy(constraints = fewer.constraints.copy(writeBudgetUnitsPerHour = 17.0))
        assertEquals(setOf(BudgetViolation.MAX_INDEXES, BudgetViolation.WRITE_BUDGET),
            metrics.assessKnownBudget(unknown, both).violations)
        assertTrue(unknown.all { it.estimatedBytes == null && it.budgetedBytes == null && it.writeUnitsPerHour == null })
        val error = assertThrows(AnalysisException::class.java) { metrics.assessBudget(unknown, exact) }
        assertEquals(ErrorCode.HYPOTHETICAL_INDEX_ERROR, error.error.code)
    }

    @Test
    fun `unknown writes stay distinct from a known zero and cached candidate expenses are ignored`() {
        val unknown = candidate("A")
        val stale = unknown.copy(writeUnitsPerHour = 999.0, budgetedBytes = 999L)
        assertNull(metrics.assessKnownBudget(listOf(stale), input()).writeUnitsPerHour)
        assertNull(metrics.assessKnownBudget(emptyList(), input()).writeUnitsPerHour)
        val quiet = input(write = 0.0,
            profile = mapOf("public.records" to TableWriteRates(0.0, 0.0, 0.0)))
        val known = metrics.assessKnownBudget(listOf(stale), quiet)
        assertEquals(0.0, known.writeUnitsPerHour)
        assertTrue(known.admissible)
        assertEquals(0.0, metrics.assessKnownBudget(emptyList(), quiet).writeUnitsPerHour)
        assertNull(unknown.estimatedBytes)
        assertNull(unknown.budgetedBytes)
        assertNull(unknown.writeUnitsPerHour)
    }

    @Test
    fun `missing incomplete and invalid write profiles fail before size preparation`() {
        val invalid = listOf(
            input(write = 1.0),
            input(profile = emptyMap()),
            input(profile = mapOf("public.other" to TableWriteRates(0.0, 0.0, 0.0))),
            input(profile = mapOf("public.records" to TableWriteRates(-1.0, 0.0, 0.0))),
            input(profile = mapOf("public.records" to TableWriteRates(0.0, Double.NaN, 0.0))),
            input(profile = mapOf("public.records" to TableWriteRates(0.0, 0.0, Double.POSITIVE_INFINITY))),
        )
        for (workload in invalid) {
            val unknown = candidate("A")
            val error = assertThrows(AnalysisException::class.java) {
                metrics.assessKnownBudget(listOf(unknown), workload)
            }
            assertEquals(ErrorCode.INVALID_WRITE_PROFILE, error.error.code)
            assertNull(unknown.estimatedBytes)
            assertNull(unknown.budgetedBytes)
        }
    }

    @Test
    fun `known write budget counts included columns before any size call`() {
        val base = candidate("covering")
        val covering = base.copy(definition = base.definition.copy(
            includedColumns = listOf(ColumnRef(table, "payload"), ColumnRef(table, "other_payload"))))
        val workload = input(write = 2.0, profile = mapOf("public.records" to TableWriteRates(1.0, 0.0, 0.0)))

        assertTrue(metrics.assessKnownBudget(listOf(base), workload).admissible)
        val known = metrics.assessKnownBudget(listOf(covering), workload)
        assertEquals(3.0, known.writeUnitsPerHour)
        assertEquals(setOf(BudgetViolation.WRITE_BUDGET), known.violations)
        assertEquals(known.violations,
            metrics.assessBudget(listOf(covering.copy(estimatedBytes = 10L)), workload).violations)
        assertNull(covering.estimatedBytes)
    }

    private fun candidate(id: String, keys: Int = 1) = IndexCandidate(
        id, IndexDefinition(table, (1..keys).map { IndexKey(ColumnRef(table, "c$it")) }),
        setOf("q"), setOf(if (keys == 1) GenerationReason.SINGLE_COLUMN else GenerationReason.TWO_EQUALITIES),
    )

    private fun input(write: Double? = null, maxIndexes: Int = 5,
        profile: Map<String, TableWriteRates>? = null) = WorkloadInput(
        listOf(QueryInput("q", "SELECT * FROM public.records")),
        listOf(Profile("profile", 1.0, mapOf("q" to 1.0))),
        Constraints(100, write, maxIndexes), tableWriteRates = profile,
    )
}
