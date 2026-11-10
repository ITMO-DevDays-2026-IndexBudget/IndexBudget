package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.NanoClock
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.RunLimits
import ru.itmo.indexbudget.domain.TableRef
import ru.itmo.indexbudget.domain.TableWriteRates
import ru.itmo.indexbudget.fixtures.ContractFixtures
import java.time.Duration

class ResolvedWorkloadValidatorTest {
    private val validator = ResolvedWorkloadValidator()
    private val records = ContractFixtures.recordsTable
    private val events = TableRef("analytics", "events")
    private val rates = TableWriteRates(0.0, 10.0, 0.0)

    @Test
    fun `full profile covers union of tables across shared SQL and repeated references`() {
        for (budget in listOf(null, 0.0, 100.0)) {
            val workload = workload(
                budget,
                mapOf("public.records" to rates, "analytics.events" to rates),
                listOf(setOf(records, events), setOf(records)),
            )
            assertDoesNotThrow { validator.validate(workload, RunContext()) }
        }
    }

    @Test
    fun `missing profile is valid only with disabled write budget`() {
        assertDoesNotThrow { validator.validate(workload(null, null), RunContext()) }
        for (budget in listOf(0.0, 100.0)) {
            assertInvalid(workload(budget, null))
        }
    }

    @Test
    fun `partial supplied profile fails even if limit is disabled`() {
        for (budget in listOf(null, 0.0, 100.0)) {
            assertInvalid(workload(
                budget,
                mapOf("public.records" to rates),
                listOf(setOf(records), setOf(events)),
            ))
        }
    }

    @Test
    fun `empty supplied profile does not mean absent data`() {
        assertInvalid(workload(null, emptyMap()))
    }

    @Test
    fun `extra table fails without copying arbitrary key into diagnostic`() {
        val secret = "postgresql://user:password@host/database\nextra"
        val error = assertInvalid(workload(null, linkedMapOf("public.records" to rates, secret to rates)))
        assertEquals("table_write_rates[1]", error.details["field"])
        assertFalse(error.toString().contains(secret))
        assertFalse(error.toString().contains("password"))
    }

    @Test
    fun `profile keys use resolved physical names with exact case and no SQL aliases`() {
        val table = TableRef("CaseSensitive", "Order Items")
        val tables = listOf(setOf(table), setOf(table))
        assertDoesNotThrow {
            validator.validate(workload(null, mapOf("CaseSensitive.Order Items" to rates), tables), RunContext())
        }
        for (key in listOf("casesensitive.order items", "o", "Order Items")) {
            assertInvalid(workload(null, mapOf(key to rates), tables))
        }
    }

    @Test
    fun `distinct physical tables cannot silently share one write profile entry`() {
        assertInvalid(workload(
            null,
            mapOf("a.b.c" to rates),
            listOf(setOf(TableRef("a.b", "c")), setOf(TableRef("a", "b.c"))),
        ))
    }

    @Test
    fun `validation checks deadline while traversing resolved tables`() {
        var ticks = 0L
        val context = RunContext(
            limits = RunLimits(commandTimeout = Duration.ofNanos(5)),
            clock = NanoClock { ticks++ },
        )
        val failure = assertThrows(AnalysisException::class.java) {
            validator.validate(workload(null, mapOf("public.records" to rates)), context)
        }
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, failure.error.code)
    }

    private fun workload(
        budget: Double?,
        profile: Map<String, TableWriteRates>?,
        tables: List<Set<TableRef>> = listOf(setOf(records), setOf(records)),
    ): ResolvedWorkload {
        val fixture = ContractFixtures.resolvedWorkload()
        return fixture.copy(
            input = fixture.input.copy(
                constraints = fixture.input.constraints.copy(writeBudgetUnitsPerHour = budget),
                tableWriteRates = profile,
            ),
            queries = fixture.queries.mapIndexed { index, query -> query.copy(tables = tables[index]) },
        )
    }

    private fun assertInvalid(workload: ResolvedWorkload) = assertThrows(AnalysisException::class.java) {
        validator.validate(workload, RunContext())
    }.error.also { assertEquals(ErrorCode.INVALID_WRITE_PROFILE, it.code) }
}
