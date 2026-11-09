package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.Objective
import tools.jackson.core.JacksonException
import java.nio.file.Files
import java.nio.file.Path

class WorkloadReaderTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `public example retains one query list and two frequency profiles`() {
        val workload = WorkloadReader().read(Path.of("examples/workload-v2.json"))
        assertEquals(2, workload.schemaVersion)
        assertEquals(Objective.ROBUST, workload.objective)
        assertEquals(2, workload.profiles.size)
        val queryIds = workload.queries.map { it.id }.toSet()
        workload.profiles.forEach { assertEquals(queryIds, it.callsPerHour.keys) }
    }

    @Test
    fun `disabled write model retains unknown values`() {
        val workload = WorkloadReader().read(Path.of("examples/workload-v2-no-write.json"))
        assertEquals(Objective.MEAN, workload.objective)
        assertNull(workload.tableWriteRates)
        assertNull(workload.constraints.writeBudgetUnitsPerHour)
    }

    @Test
    fun `oversized input is rejected before decoding`() {
        val input = directory.resolve("oversized.json")
        Files.writeString(input, " ".repeat(1024 * 1024 + 1))
        val failure = assertThrows(AnalysisException::class.java) { WorkloadReader().read(input) }
        assertEquals(ErrorCode.INPUT_LIMIT_EXCEEDED, failure.error.code)
    }

    @Test
    fun `missing write budget is not silently converted into explicit null`() {
        val input = directory.resolve("missing-write-budget.json")
        Files.writeString(input, """
            {"schema_version":2,"queries":[],"profiles":[],"constraints":{"storage_budget_bytes":1024}}
        """.trimIndent())
        val failure = assertThrows(AnalysisException::class.java) { WorkloadReader().read(input) }
        assertEquals(ErrorCode.INVALID_INPUT, failure.error.code)
    }

    @Test
    fun `integer fields reject fractions and numeric strings rather than coercing them`() {
        val input = directory.resolve("invalid-integer.json")
        for ((storage, maxIndexes) in listOf("1.9" to "5", "1024" to "2.7", "\"1024\"" to "5")) {
            Files.writeString(input, """
                {"schema_version":2,"queries":[],"profiles":[],"constraints":{
                  "storage_budget_bytes":$storage,"write_budget_units_per_hour":null,"max_indexes":$maxIndexes
                }}
            """.trimIndent())
            assertThrows(JacksonException::class.java) { WorkloadReader().read(input) }
        }
    }
}
