package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.indexbudget.application.ResolvedWorkloadValidator
import ru.itmo.indexbudget.fixtures.ContractFixtures

class BudgetAssessmentTest {
    private val metrics = MetricsCalculatorImpl()
    private val records = TableRef("public", "records")
    private val events = TableRef("audit", "events")
    private val rates = mapOf(
        "public.records" to TableWriteRates(100.0, 3000.0, 1000.0),
        "audit.events" to TableWriteRates(10.0, 20.0, 30.0),
    )

    @Test
    fun `payload is charged once per column and changes exact write admissibility`() {
        val plain = candidate("plain", 10)
        val covering = plain.copy(id = "covering", definition = plain.definition.copy(
            includedColumns = listOf(ColumnRef(records, "payload"), ColumnRef(records, "extra"))))
        val budget = workload(storage = 12, write = 12300.0, profile = rates)
        assertEquals(4100.0, metrics.assessBudget(listOf(plain), budget).usage.writeUnitsPerHour)
        assertEquals(BudgetUsage(10, 12, 12300.0, 1), metrics.assessBudget(listOf(covering), budget).usage)
        assertTrue(metrics.assessBudget(listOf(covering), budget).admissible)
        assertEquals(setOf(BudgetViolation.WRITE_BUDGET), metrics.assessBudget(listOf(covering),
            budget.copy(constraints = budget.constraints.copy(writeBudgetUnitsPerHour = 12299.0))).violations)
        assertNull(metrics.assessBudget(listOf(covering), workload()).usage.writeUnitsPerHour)
        assertTrue(metrics.assessBudget(listOf(covering), workload(write = 0.0,
            profile = mapOf("public.records" to TableWriteRates(0.0, 0.0, 0.0)))).admissible)
    }

    @Test
    fun `reserve is rounded per new index before summing`() {
        val candidates = listOf(candidate("a", 1), candidate("b", 1))
        val exact = metrics.assessBudget(candidates, workload(storage = 4))
        assertEquals(BudgetUsage(2, 4, null, 2), exact.usage)
        assertTrue(exact.admissible)
        // ceil(1.2 * (1 + 1)) would incorrectly accept this budget.
        assertEquals(setOf(BudgetViolation.STORAGE_BUDGET),
            metrics.assessBudget(candidates, workload(storage = 3)).violations)
    }

    @ParameterizedTest
    @CsvSource(
        "0,0", "1,2", "4,5", "5,6", "6,8", "262144,314573",
        "9007199254740993,10808639105689192",
        "7686143364045646505,9223372036854775806",
    )
    fun `storage equality and one byte excess are exact across Long range`(estimated: Long, budgeted: Long) {
        val candidates = listOf(candidate("a", estimated))
        val assessment = metrics.assessBudget(candidates, workload(storage = budgeted))
        assertEquals(estimated, assessment.usage.estimatedBytes)
        assertEquals(budgeted, assessment.usage.budgetedBytes)
        assertTrue(assessment.admissible)
        if (budgeted > 0) {
            assertEquals(setOf(BudgetViolation.STORAGE_BUDGET),
                metrics.assessBudget(candidates, workload(storage = budgeted - 1)).violations)
        }
    }

    @Test
    fun `all three limits report separate violations and allow exact equality`() {
        val candidates = listOf(candidate("a", 5), candidate("b", 10, keys = 2))
        val exact = workload(storage = 18, write = 12300.0, maxIndexes = 2, profile = rates)
        assertEquals(BudgetUsage(15, 18, 12300.0, 2), metrics.assessBudget(candidates, exact).usage)
        assertTrue(metrics.assessBudget(candidates, exact).admissible)
        val constraints = exact.constraints
        for ((limit, violation) in listOf(
            constraints.copy(storageBudgetBytes = 17) to BudgetViolation.STORAGE_BUDGET,
            constraints.copy(writeBudgetUnitsPerHour = 12299.0) to BudgetViolation.WRITE_BUDGET,
            constraints.copy(maxIndexes = 1) to BudgetViolation.MAX_INDEXES,
        )) {
            val rejected = metrics.assessBudget(candidates, exact.copy(constraints = limit))
            assertFalse(rejected.admissible)
            assertEquals(setOf(violation), rejected.violations)
        }
        assertEquals(BudgetViolation.entries.toSet(), metrics.assessBudget(candidates,
            workload(storage = 17, write = 12299.0, maxIndexes = 1, profile = rates)).violations)
    }

    @Test
    fun `two tables and multiple indexes charge all updates and each key column`() {
        val candidates = listOf(
            candidate("records_one", 5), candidate("records_two", 10, keys = 2),
            candidate("events_two", 15, table = events, keys = 2),
        )
        // 4100 + 2*4100 + 2*60, independent of read profile frequencies and weights.
        val assessment = metrics.assessBudget(candidates, workload(profile = rates, write = 12420.0))
        assertEquals(BudgetUsage(30, 36, 12420.0, 3), assessment.usage)
        assertTrue(assessment.admissible)
        assertEquals(assessment.usage,
            metrics.assessBudget(candidates, workload(profile = rates, write = null)).usage)
    }

    @Test
    fun `fractional writes retain exact representable budget boundary`() {
        val profile = mapOf("public.records" to TableWriteRates(0.25, 0.5, 0.125))
        val candidates = listOf(candidate("a", 0, keys = 2))
        assertTrue(metrics.assessBudget(candidates, workload(profile = profile, write = 1.75)).admissible)
        assertEquals(setOf(BudgetViolation.WRITE_BUDGET),
            metrics.assessBudget(candidates, workload(profile = profile, write = 1.5)).violations)
    }

    @Test
    fun `empty set only charges new indexes and keeps unknown writes distinct from zero`() {
        // The fixture catalog has a real primary key; it remains outside the candidate set.
        assertTrue(ContractFixtures.catalog().tables.single().existingIndexes.isNotEmpty())
        for (profile in listOf(null, rates)) {
            val input = workload(storage = 0, maxIndexes = 0, profile = profile)
            val assessment = metrics.assessBudget(emptyList(), input)
            assertEquals(BudgetUsage(0, 0, if (profile == null) null else 0.0, 0), assessment.usage)
            assertTrue(assessment.admissible)
        }
        assertNull(metrics.assessBudget(listOf(candidate("a", 1)), workload()).usage.writeUnitsPerHour)
    }

    @Test
    fun `zero limits allow empty set and zero write budget only allows zero writes`() {
        val zeroRates = mapOf("public.records" to TableWriteRates(0.0, 0.0, 0.0))
        val zero = workload(storage = 0, write = 0.0, maxIndexes = 0, profile = zeroRates)
        assertTrue(metrics.assessBudget(emptyList(), zero).admissible)
        assertEquals(setOf(BudgetViolation.STORAGE_BUDGET, BudgetViolation.MAX_INDEXES),
            metrics.assessBudget(listOf(candidate("a", 1)), zero).violations)
        assertTrue(metrics.assessBudget(listOf(candidate("a", 0)),
            zero.copy(constraints = zero.constraints.copy(maxIndexes = 1))).admissible)
        assertEquals(setOf(BudgetViolation.WRITE_BUDGET), metrics.assessBudget(
            listOf(candidate("a", 0)), workload(write = 0.0, profile = rates)).violations)
        assertEquals(setOf(BudgetViolation.WRITE_BUDGET), metrics.assessBudget(
            listOf(candidate("a", 0)), workload(write = 0.0,
                profile = mapOf("public.records" to TableWriteRates(Double.MIN_VALUE, 0.0, 0.0)))).violations)
    }

    @Test
    fun `cached candidate expenses never apply the reserve twice or override the profile`() {
        val prepared = candidate("a", 5).copy(budgetedBytes = 6, writeUnitsPerHour = 999.0)
        val stale = prepared.copy(budgetedBytes = Long.MAX_VALUE, writeUnitsPerHour = Double.NaN)
        val input = workload(storage = 6, profile = rates)
        for (candidate in listOf(prepared, stale)) {
            assertEquals(BudgetUsage(5, 6, 4100.0, 1), metrics.assessBudget(listOf(candidate), input).usage)
        }
        assertNull(metrics.assessBudget(listOf(prepared), workload()).usage.writeUnitsPerHour)
        assertEquals(5L, prepared.estimatedBytes)
    }

    @Test
    fun `unknown estimate cannot become zero even if cached budgeted bytes exist`() {
        val unknown = candidate("unknown", null).copy(budgetedBytes = 0)
        val error = failure(ErrorCode.HYPOTHETICAL_INDEX_ERROR) {
            metrics.assessBudget(listOf(unknown), workload())
        }
        assertEquals("unknown", error.details["candidate_id"])
    }

    @ParameterizedTest
    @ValueSource(longs = [-1L, Long.MIN_VALUE, 7686143364045646506L, Long.MAX_VALUE])
    fun `negative or individually overflowing sizes fail explicitly`(bytes: Long) {
        failure(ErrorCode.NUMERIC_ERROR) { metrics.assessBudget(listOf(candidate("a", bytes)), workload()) }
    }

    @Test
    fun `sum overflow is detected even when every individual size fits`() {
        for (bytes in listOf(4_000_000_000_000_000_000L, 5_000_000_000_000_000_000L)) {
            failure(ErrorCode.NUMERIC_ERROR) {
                metrics.assessBudget(listOf(candidate("a", bytes), candidate("b", bytes)), workload())
            }
        }
    }

    @Test
    fun `missing and partial profiles fail even for disabled write limit`() {
        for (write in listOf(0.0, 100.0)) {
            failure(ErrorCode.INVALID_WRITE_PROFILE) { metrics.assessBudget(emptyList(), workload(write = write)) }
        }
        for (write in listOf(null, 0.0, 100.0)) {
            failure(ErrorCode.INVALID_WRITE_PROFILE) {
                metrics.assessBudget(emptyList(), workload(write = write, profile = emptyMap()))
            }
            failure(ErrorCode.INVALID_WRITE_PROFILE) {
                metrics.assessBudget(listOf(candidate("a", 1, table = events)),
                    workload(write = write, profile = rates.filterKeys { it == "public.records" }))
            }
        }
    }

    @Test
    fun `profile uses physical case sensitive schema and table names`() {
        val table = TableRef("Audit", "Event.Log")
        val candidates = listOf(candidate("a", 1, table = table))
        val rate = TableWriteRates(1.0, 2.0, 3.0)
        assertEquals(6.0, metrics.assessBudget(candidates,
            workload(profile = mapOf("Audit.Event.Log" to rate))).usage.writeUnitsPerHour)
        failure(ErrorCode.INVALID_WRITE_PROFILE) {
            metrics.assessBudget(candidates, workload(profile = mapOf("audit.event.log" to rate)))
        }
    }

    @Test
    fun `resolved validation rejects missing noncandidate tables and unknown profile tables before assessment`() {
        val resolved = ContractFixtures.resolvedWorkload()
        val withTwoTables = resolved.queries.map { it.copy(tables = it.tables + events) }
        for (write in listOf(null, 100.0)) {
            for (profile in listOf(
                rates.filterKeys { it == "public.records" },
                rates + ("public.unknown" to TableWriteRates(0.0, 0.0, 0.0)),
            )) {
                val invalid = resolved.copy(input = workload(write = write, profile = profile), queries = withTwoTables)
                failure(ErrorCode.INVALID_WRITE_PROFILE) { ResolvedWorkloadValidator().validate(invalid, RunContext()) }
            }
            val valid = resolved.copy(input = workload(write = write, profile = rates), queries = withTwoTables)
            ResolvedWorkloadValidator().validate(valid, RunContext())
            assertTrue(metrics.assessBudget(emptyList(), valid.input).admissible)
        }
    }

    @Test
    fun `invalid rates are rejected even without a write limit or selected indexes`() {
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (rate in listOf(TableWriteRates(value, 0.0, 0.0), TableWriteRates(0.0, value, 0.0),
                TableWriteRates(0.0, 0.0, value))) {
                failure(ErrorCode.INVALID_WRITE_PROFILE) {
                    metrics.assessBudget(emptyList(), workload(profile = mapOf("public.records" to rate)))
                }
            }
        }
    }

    @Test
    fun `write overflow in row changes key multiplication and set sum is a numeric error`() {
        val cases = listOf(
            TableWriteRates(Double.MAX_VALUE, Double.MAX_VALUE, 0.0) to listOf(candidate("a", 1)),
            TableWriteRates(Double.MAX_VALUE, 0.0, 0.0) to listOf(candidate("a", 1, keys = 2)),
            TableWriteRates(Double.MAX_VALUE, 0.0, 0.0) to listOf(candidate("a", 1), candidate("b", 1)),
        )
        for ((rate, candidates) in cases) {
            failure(ErrorCode.NUMERIC_ERROR) {
                metrics.assessBudget(candidates, workload(profile = mapOf("public.records" to rate)))
            }
        }
    }

    @Test
    fun `write sum retains small terms and checks budgets across candidate permutations`() {
        val candidates = listOf(candidate("a", 1), candidate("b", 1, events), candidate("c", 1, events))
        val profile = mapOf(
            "public.records" to TableWriteRates(1e16, 0.0, 0.0),
            "audit.events" to TableWriteRates(1.0, 0.0, 0.0),
        )
        val expected = BudgetUsage(3, 6, 10_000_000_000_000_002.0, 3)
        for (order in listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0),
            listOf(2, 0, 1), listOf(2, 1, 0))) {
            val selected = order.map { candidates[it] }
            val exceeded = metrics.assessBudget(selected, workload(profile = profile, write = 1e16))
            assertEquals(expected, exceeded.usage)
            assertEquals(setOf(BudgetViolation.WRITE_BUDGET), exceeded.violations)
            val equal = metrics.assessBudget(selected, workload(profile = profile, write = 10_000_000_000_000_002.0))
            assertEquals(expected, equal.usage)
            assertTrue(equal.admissible)
            assertEquals(expected, metrics.assessBudget(selected, workload(profile = profile)).usage)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `table row changes retain small terms before multiplying by key count`(keys: Int) {
        val expected = if (keys == 1) 10_000_000_000_000_002.0 else 20_000_000_000_000_004.0
        val candidates = listOf(candidate("a", 1, keys = keys))
        for (rate in listOf(TableWriteRates(1e16, 1.0, 1.0), TableWriteRates(1.0, 1e16, 1.0),
            TableWriteRates(1.0, 1.0, 1e16))) {
            val profile = mapOf("public.records" to rate)
            val exceeded = metrics.assessBudget(candidates, workload(profile = profile, write = 1e16 * keys))
            assertEquals(expected, exceeded.usage.writeUnitsPerHour)
            assertEquals(setOf(BudgetViolation.WRITE_BUDGET), exceeded.violations)
            val equal = metrics.assessBudget(candidates, workload(profile = profile, write = expected))
            assertEquals(expected, equal.usage.writeUnitsPerHour)
            assertTrue(equal.admissible)
        }
    }

    @Test
    fun `budget comparison precedes rounding the final write usage to Double`() {
        val cases = listOf(
            listOf(candidate("a", 1)) to mapOf("public.records" to TableWriteRates(1e16, 1.0, 0.0)),
            listOf(candidate("a", 1), candidate("b", 1, events)) to mapOf(
                "public.records" to TableWriteRates(1e16, 0.0, 0.0),
                "audit.events" to TableWriteRates(1.0, 0.0, 0.0),
            ),
        )
        for ((candidates, profile) in cases) {
            // 1e16 + 1 is not representable as Double, but still exceeds the exactly represented limit.
            val exceeded = metrics.assessBudget(candidates, workload(profile = profile, write = 1e16))
            assertEquals(setOf(BudgetViolation.WRITE_BUDGET), exceeded.violations)
            assertTrue(metrics.assessBudget(candidates,
                workload(profile = profile, write = 10_000_000_000_000_002.0)).admissible)
        }
    }

    @Test
    fun `write sum above Double range cannot round back to maximum finite value`() {
        val profile = mapOf("public.records" to TableWriteRates(Double.MAX_VALUE, 1.0, 0.0))
        failure(ErrorCode.NUMERIC_ERROR) {
            metrics.assessBudget(listOf(candidate("a", 1)), workload(profile = profile))
        }
        val exact = mapOf("public.records" to TableWriteRates(Double.MAX_VALUE, 0.0, 0.0))
        val assessment = metrics.assessBudget(listOf(candidate("a", 1)),
            workload(profile = exact, write = Double.MAX_VALUE))
        assertTrue(assessment.admissible)
        assertEquals(Double.MAX_VALUE, assessment.usage.writeUnitsPerHour)
    }

    @Test
    fun `same candidate cannot be charged twice in a set`() {
        val candidate = candidate("a", 1)
        assertThrows(IllegalArgumentException::class.java) {
            metrics.assessBudget(listOf(candidate, candidate), workload())
        }
    }

    @Test
    fun `profile metrics retain the budget assessment unchanged`() {
        val input = ContractFixtures.workload()
        val assessment = metrics.assessBudget(ContractFixtures.candidates(), input)
        assertEquals(BudgetUsage(786432, 943719, 12300.0, 2), assessment.usage)
        val result = metrics.calculate(input, ContractFixtures.baselineEvaluation(),
            ContractFixtures.robustEvaluation(), assessment.usage)
        assertSame(assessment.usage, result.budgetUsage)
    }

    private fun candidate(id: String, bytes: Long?, table: TableRef = records, keys: Int = 1) = IndexCandidate(
        id, IndexDefinition(table, (1..keys).map { IndexKey(ColumnRef(table, "c$it")) }),
        setOf("q"), setOf(GenerationReason.SINGLE_COLUMN), estimatedBytes = bytes,
    )

    private fun workload(
        storage: Long = Long.MAX_VALUE,
        write: Double? = null,
        maxIndexes: Int = 5,
        profile: Map<String, TableWriteRates>? = null,
    ) = ContractFixtures.workload().copy(
        constraints = Constraints(storage, write, maxIndexes), tableWriteRates = profile,
    )

    private fun failure(code: ErrorCode, operation: () -> Unit): AnalysisError {
        val error = assertThrows(AnalysisException::class.java, operation).error
        assertEquals(code, error.code)
        return error
    }
}
