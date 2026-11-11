package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.domain.*
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class BudgetSeriesReaderTest {
    @TempDir lateinit var directory: Path
    private val reader = BudgetSeriesReader()

    @Test
    fun `storage values preserve input order and exact Long boundaries`() {
        val request = read("""{"schema_version":1,"budget":"storage_budget_bytes","values":[9223372036854775807,0,9007199254740993]}""")
        assertEquals(SeriesBudget.STORAGE, request.budget)
        assertEquals(listOf("9223372036854775807", "0", "9007199254740993").map(::BigDecimal), request.values)
        assertEquals(600L, request.timeoutSeconds)
    }

    @Test
    fun `write values accept fractions and five point series with explicit timeout`() {
        val request = read("""{"schema_version":1,"budget":"write_budget_units_per_hour","values":[0,1.25,2.5,5,10],"timeout_seconds":86400}""")
        assertEquals(SeriesBudget.WRITE, request.budget)
        assertEquals(listOf(0.0, 1.25, 2.5, 5.0, 10.0), request.values.map { it.toDouble() })
        assertEquals(86400L, request.timeoutSeconds)
    }

    @Test
    fun `invalid series shapes and budgets are rejected without source excerpts`() {
        val invalid = listOf(
            "{}", "[]", "null",
            """{"schema_version":2,"budget":"storage_budget_bytes","values":[1,2,3]}""",
            """{"schema_version":1,"budget":"storage_budget_bytes","values":[1,2]}""",
            """{"schema_version":1,"budget":"storage_budget_bytes","values":[1,2,3,4,5,6]}""",
            """{"schema_version":1,"budget":"storage_budget_bytes","values":[0,0,3]}""",
            """{"schema_version":1,"budget":"storage_budget_bytes","values":[0,1.5,3]}""",
            """{"schema_version":1,"budget":"storage_budget_bytes","values":[0,1.0,3]}""",
            """{"schema_version":1,"budget":"storage_budget_bytes","values":[0,1,9223372036854775808]}""",
            """{"schema_version":1,"budget":"write_budget_units_per_hour","values":[0,1,1.0]}""",
            """{"schema_version":1,"budget":"write_budget_units_per_hour","values":[0,9007199254740992,9007199254740993]}""",
            """{"schema_version":1,"budget":"write_budget_units_per_hour","values":[0,1,1e400]}""",
            """{"schema_version":1,"budget":"storage_budget_bytes","values":[-1,1,2]}""",
            """{"schema_version":1,"budget":"private-password","values":[0,1,2]}""",
            """{"schema_version":1,"budget":"storage_budget_bytes","values":[0,1,2],"unknown":"private-password"}""",
            """{"schema_version":1,"budget":"storage_budget_bytes","values":[0,1,2],"values":[3,4,5]}""",
            """{"schema_version":1,"budget":"storage_budget_bytes","values":[0,1,2]} private-password""",
        )
        for (json in invalid) {
            val failure = assertThrows(AnalysisException::class.java) { read(json) }
            assertFalse(failure.error.message.contains("private-password"))
        }
        for (timeout in listOf("null", "0", "-1", "86401", "1.1", "\"1\"")) {
            assertThrows(AnalysisException::class.java) {
                read("""{"schema_version":1,"budget":"storage_budget_bytes","values":[0,1,2],"timeout_seconds":$timeout}""")
            }
        }
    }

    @Test
    fun `positive write underflow stays an explicit numeric error`() {
        val failure = assertThrows(AnalysisException::class.java) {
            read("""{"schema_version":1,"budget":"write_budget_units_per_hour","values":[0,1e-400,1]}""")
        }
        assertEquals(ErrorCode.NUMERIC_ERROR, failure.error.code)
        assertEquals("values[1]", failure.error.details["field"])
    }

    @Test
    fun `invalid UTF8 and oversized series are rejected`() {
        val path = directory.resolve("series.json")
        Files.write(path, byteArrayOf(0xc3.toByte(), 0x28))
        assertEquals(ErrorCode.INVALID_INPUT, assertThrows(AnalysisException::class.java) { reader.read(path) }.error.code)
        Files.writeString(path, " ".repeat(64 * 1024 + 1))
        assertEquals(ErrorCode.INPUT_LIMIT_EXCEEDED, assertThrows(AnalysisException::class.java) { reader.read(path) }.error.code)
    }

    private fun read(json: String): BudgetSeriesRequest = reader.read(Files.writeString(directory.resolve("series.json"), json))
}
