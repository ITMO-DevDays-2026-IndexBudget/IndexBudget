package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.Objective
import ru.itmo.indexbudget.domain.WorkloadInput
import ru.itmo.indexbudget.domain.WriteRateSource
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

class WorkloadReaderTest {
    @TempDir
    lateinit var directory: Path

    private val mapper = JsonMapper.builder().build()
    private val reader = WorkloadReader()

    @Test
    fun `public examples retain common SQL profiles objectives and write semantics`() {
        val workload = reader.read(Path.of("examples/workload-v2.json"))
        assertEquals(2, workload.schemaVersion)
        assertEquals(Objective.ROBUST, workload.objective)
        assertEquals(2, workload.profiles.size)
        workload.profiles.forEach { assertEquals(workload.queries.map { it.id }.toSet(), it.callsPerHour.keys) }

        val noWrite = reader.read(Path.of("examples/workload-v2-no-write.json"))
        assertEquals(Objective.MEAN, noWrite.objective)
        assertNull(noWrite.tableWriteRates)
        assertNull(noWrite.constraints.writeBudgetUnitsPerHour)

        val poc = reader.read(Path.of("demo/workload-poc.json"))
        assertEquals(3, poc.queries.size)
        assertEquals(2, poc.profiles.size)
        assertTrue(poc.constraints.storageBudgetBytes > 0)
        assertTrue(requireNotNull(poc.constraints.writeBudgetUnitsPerHour) > 0)
        assertTrue(requireNotNull(poc.tableWriteRates).isNotEmpty())
    }

    @Test
    fun `objective and max indexes use defaults only when absent`() {
        assertEquals(Objective.ROBUST, read(json()).objective)
        for ((wire, objective) in listOf("robust" to Objective.ROBUST, "mean" to Objective.MEAN)) {
            assertEquals(objective, read(json(objective = quote(wire))).objective)
        }
        assertEquals(5, read(json().replace(",\"max_indexes\":5", "")).constraints.maxIndexes)
        for (objective in listOf("\"other\"", "\"ROBUST\"", "null", "42", "true", "\"\"")) {
            assertEquals("objective", failure(json(objective = objective)).details["field"])
        }
        failure(json().replace("\"max_indexes\":5", "\"max_indexes\":null"))
    }

    @Test
    fun `legacy version includes migration guidance and other schema versions are rejected`() {
        for (version in listOf("1", "3", "2.0", "\"2\"", "null", "2147483648")) {
            val error = failure(json().replace("\"schema_version\":2", "\"schema_version\":$version"),
                ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
            assertEquals("schema_version", error.details["field"])
            assertTrue(error.message.contains("queries"))
            assertTrue(error.message.contains("profiles"))
        }
        failure("""{"schema_version":1}""", ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
        failure("""{"schema_version":1,"calls_per_hour":100,"queries":[]}""", ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
        failure(json().replace("\"schema_version\":2,", ""), ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
    }

    @Test
    fun `query and profile counts include both boundaries`() {
        for (count in listOf(1, 20)) {
            val queries = (1..count).map { query("q$it") }
            val calls = (1..count).joinToString(",") { "\"q$it\":1" }
            for (profileCount in listOf(1, 3)) {
                val profiles = (1..profileCount).map { profile("p$it", calls = "{$calls}") }
                val workload = read(json(queries = queries, profiles = profiles))
                assertEquals(count, workload.queries.size)
                assertEquals(profileCount, workload.profiles.size)
            }
        }
        failure(json(queries = emptyList()))
        failure(json(profiles = emptyList()))
        failure(json(queries = (1..21).map { query("q$it") }), ErrorCode.INPUT_LIMIT_EXCEEDED)
        failure(json(profiles = (1..4).map { profile("p$it") }), ErrorCode.INPUT_LIMIT_EXCEEDED)
    }

    @Test
    fun `duplicate IDs and blank required strings identify their location`() {
        val queryError = failure(json(queries = listOf(query("q1"), query("q1"))))
        assertEquals("q1", queryError.queryId)
        assertEquals("queries[1].id", queryError.details["field"])
        val profileError = failure(json(profiles = listOf(profile("p"), profile("p"))))
        assertEquals("p", profileError.profileId)
        assertEquals("profiles[1].id", profileError.details["field"])
        failure(json(queries = listOf(query(" "))))
        failure(json(profiles = listOf(profile(" "))))
        val sqlError = failure(json(queries = listOf(query("q1", "  "), query("q2"))))
        assertEquals("q1", sqlError.queryId)
        assertEquals("queries[0].sql", sqlError.details["field"])
    }

    @Test
    fun `each profile must contain exactly the shared query IDs`() {
        val missing = failure(json(profiles = listOf(profile(calls = """{"q1":1}"""))))
        assertEquals("normal", missing.profileId)
        assertEquals("q2", missing.queryId)
        val unknown = failure(json(profiles = listOf(profile(calls = """{"q1":1,"q2":1,"extra":1}"""))))
        assertEquals("normal", unknown.profileId)
        assertTrue(unknown.message.contains("Unknown query ID"))
        failure(json(profiles = listOf(profile(calls = "null"))))
    }

    @Test
    fun `zero frequencies are allowed but profiles and queries cannot be unused`() {
        val workload = read(json())
        assertEquals(0.0, workload.profiles.first().callsPerHour.getValue("q2"))
        val zeroProfile = failure(json(profiles = listOf(profile(calls = """{"q1":0,"q2":0}"""))))
        assertEquals("normal", zeroProfile.profileId)
        val unusedQuery = failure(json(profiles = listOf(profile(calls = """{"q1":1,"q2":0}"""))))
        assertEquals("q2", unusedQuery.queryId)
        assertEquals("profiles", unusedQuery.details["field"])
    }

    @Test
    fun `weights and frequencies require numbers with the correct sign`() {
        for (weight in listOf("0", "-1", "null", "\"1\"", "true", "1e400")) {
            val error = failure(json(profiles = listOf(profile(weight = weight))))
            assertEquals("normal", error.profileId)
            assertEquals("profiles[0].weight", error.details["field"])
        }
        for (frequency in listOf("-1", "null", "\"1\"", "false", "1e400")) {
            val error = failure(json(profiles = listOf(profile(calls = """{"q1":$frequency,"q2":1}"""))))
            assertEquals("normal", error.profileId)
            assertEquals("q1", error.queryId)
        }
    }

    @Test
    fun `nonfinite JSON numbers never become a valid workload`() {
        for (number in listOf("NaN", "Infinity", "-Infinity", "\"NaN\"", "\"Infinity\"")) {
            failure(json(profiles = listOf(profile(weight = number))))
        }
    }

    @Test
    fun `weight overflow and normalization underflow are explicit numeric failures`() {
        failure(json(profiles = listOf(profile("a", "1e308"), profile("b", "1e308"))), ErrorCode.NUMERIC_ERROR)
        val underflow = failure(json(profiles = listOf(profile("rare", "4.9e-324"), profile("usual", "2"))),
            ErrorCode.NUMERIC_ERROR)
        assertEquals("rare", underflow.profileId)
        assertEquals("profiles.weight", underflow.details["field"])
    }

    @Test
    fun `positive raw numbers cannot silently underflow to zero`() {
        val weight = failure(json(profiles = listOf(profile(weight = "1e-400"))), ErrorCode.NUMERIC_ERROR)
        assertEquals("profiles[0].weight", weight.details["field"])
        val frequency = failure(json(profiles = listOf(profile(calls = """{"q1":1e-400,"q2":1}"""))),
            ErrorCode.NUMERIC_ERROR)
        assertEquals("q1", frequency.queryId)
        val budget = failure(json().replace("\"write_budget_units_per_hour\":null", "\"write_budget_units_per_hour\":1e-400"),
            ErrorCode.NUMERIC_ERROR)
        assertEquals("constraints.write_budget_units_per_hour", budget.details["field"])
        val rates = failure(json(writeRates = rates(inserts = "1e-400")), ErrorCode.NUMERIC_ERROR)
        assertEquals("table_write_rates[0].inserts_per_hour", rates.details["field"])
    }

    @Test
    fun `budgets preserve zero and reject missing fractional coerced or out of range integers`() {
        val zero = read(json().replace("\"storage_budget_bytes\":1024", "\"storage_budget_bytes\":0")
            .replace("\"max_indexes\":5", "\"max_indexes\":0"))
        assertEquals(0L, zero.constraints.storageBudgetBytes)
        assertEquals(0, zero.constraints.maxIndexes)
        assertEquals(Long.MAX_VALUE, read(json().replace("\"storage_budget_bytes\":1024",
            "\"storage_budget_bytes\":9223372036854775807")).constraints.storageBudgetBytes)
        for (storage in listOf("-1", "1.5", "\"1024\"", "null", "true", "9223372036854775808")) {
            val error = failure(json().replace("\"storage_budget_bytes\":1024", "\"storage_budget_bytes\":$storage"))
            assertEquals("constraints.storage_budget_bytes", error.details["field"])
        }
        failure(json().replace("\"storage_budget_bytes\":1024,", ""))
        for (count in listOf("-1", "6", "2.7", "\"5\"", "null", "false", "9223372036854775808")) {
            assertEquals("constraints.max_indexes", failure(json().replace("\"max_indexes\":5",
                "\"max_indexes\":$count")).details["field"])
        }
    }

    @Test
    fun `write budget must be explicit and zero requires a profile`() {
        assertNull(read(json()).constraints.writeBudgetUnitsPerHour)
        assertNull(read(json(writeRates = "null")).tableWriteRates)
        assertTrue(requireNotNull(read(json(writeRates = rates())).tableWriteRates).isNotEmpty())
        for (budget in listOf("0", "100")) {
            val withBudget = json().replace("\"write_budget_units_per_hour\":null", "\"write_budget_units_per_hour\":$budget")
            failure(withBudget, ErrorCode.INVALID_WRITE_PROFILE)
            val withRates = json(writeRates = rates()).replace("\"write_budget_units_per_hour\":null",
                "\"write_budget_units_per_hour\":$budget")
            assertEquals(budget.toDouble(), read(withRates).constraints.writeBudgetUnitsPerHour)
        }
        failure(json().replace("\"write_budget_units_per_hour\":null,", ""))
        for (budget in listOf("-1", "\"0\"", "false", "1e400")) {
            failure(json().replace("\"write_budget_units_per_hour\":null", "\"write_budget_units_per_hour\":$budget"))
        }
        failure(json(writeRates = "{}"), ErrorCode.INVALID_WRITE_PROFILE)
    }

    @Test
    fun `all three write rates are mandatory and source is manual by default`() {
        for (field in listOf("inserts_per_hour", "updates_per_hour", "deletes_per_hour")) {
            val incomplete = rates().replace("\"$field\":0,", "")
            val error = failure(json(writeRates = incomplete), ErrorCode.INVALID_WRITE_PROFILE)
            assertEquals("table_write_rates[0].$field", error.details["field"])
        }
        assertEquals(WriteRateSource.MANUAL, read(json(writeRates = rates())).tableWriteRates?.get("public.records")?.source)
        assertEquals(WriteRateSource.MANUAL, read(json(writeRates = rates().replace(",\"source\":\"manual\"", "")))
            .tableWriteRates?.get("public.records")?.source)
        for (source in listOf("\"auto\"", "\"MANUAL\"", "null", "1")) {
            failure(json(writeRates = rates().replace("\"source\":\"manual\"", "\"source\":$source")),
                ErrorCode.INVALID_WRITE_PROFILE)
        }
    }

    @Test
    fun `write rates must be finite nonnegative numbers for each operation`() {
        for (field in listOf("inserts_per_hour", "updates_per_hour", "deletes_per_hour")) {
            for (value in listOf("-1", "null", "\"0\"", "false", "1e400")) {
                val error = failure(json(writeRates = rates().replace("\"$field\":0", "\"$field\":$value")),
                    ErrorCode.INVALID_WRITE_PROFILE)
                assertEquals("table_write_rates[0].$field", error.details["field"])
            }
        }
    }

    @Test
    fun `SQL limit measures UTF8 bytes and preserves exact content including emoji`() {
        for (character in listOf("x", "я", "😀")) {
            val overhead = "SELECT ''".toByteArray(Charsets.UTF_8).size
            val width = character.toByteArray(Charsets.UTF_8).size
            val remaining = 64 * 1024 - overhead
            val sql = "SELECT '" + character.repeat(remaining / width) + "x".repeat(remaining % width) + "'"
            assertEquals(64 * 1024, sql.toByteArray(Charsets.UTF_8).size)
            assertEquals(sql, read(json(queries = listOf(query("q1", sql), query("q2")))).queries.first().sql)
            val error = failure(json(queries = listOf(query("q1", sql + " "), query("q2"))), ErrorCode.INPUT_LIMIT_EXCEEDED)
            assertEquals("q1", error.queryId)
            assertEquals("queries[0].sql", error.details["field"])
        }
    }

    @Test
    fun `JSON limit accepts exactly one MiB and rejects larger input before decoding`() {
        val content = json()
        val exact = content + " ".repeat(1024 * 1024 - content.toByteArray(Charsets.UTF_8).size)
        assertEquals(2, read(exact).queries.size)
        failure(exact + " ", ErrorCode.INPUT_LIMIT_EXCEEDED)
        val oversizedInvalidUtf8 = ByteArray(1024 * 1024 + 1) { 0xff.toByte() }
        val path = directory.resolve("oversized.json")
        Files.write(path, oversizedInvalidUtf8)
        val error = assertThrows(AnalysisException::class.java) { reader.read(path) }.error
        assertEquals(ErrorCode.INPUT_LIMIT_EXCEEDED, error.code)
    }

    @Test
    fun `duplicate keys trailing data invalid UTF8 and invalid Unicode are rejected`() {
        failure(json().replace("\"schema_version\":2", "\"schema_version\":2,\"schema_version\":2"))
        failure(json(profiles = listOf(profile(calls = """{"q1":1,"q1":2,"q2":1}"""))))
        failure(json() + " {}")
        failure("{private-sql-and-password}")
        failure(json().replace("SELECT id FROM public.records", "\\uD800"))
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), json().toByteArray(Charsets.UTF_16))) {
            val path = directory.resolve("encoding.json")
            Files.write(path, bytes)
            assertEquals(ErrorCode.INVALID_INPUT, assertThrows(AnalysisException::class.java) { reader.read(path) }.error.code)
        }
    }

    @Test
    fun `unknown fields and wrong structures do not expose raw input`() {
        for (bad in listOf("null", "[]", json().replace("\"queries\":[", "\"secret-password\":1,\"queries\":["),
            json().replace("\"id\":\"q1\"", "\"id\":42"))) {
            val error = failure(bad)
            assertFalse(error.message.contains("secret-password"))
        }
    }

    private fun json(
        queries: List<String> = listOf(query("q1"), query("q2")),
        profiles: List<String> = listOf(
            profile("normal", "9", """{"q1":10,"q2":0}"""),
            profile("campaign", "1", """{"q1":0,"q2":10}"""),
        ),
        objective: String? = null,
        writeRates: String? = null,
    ): String = """{"schema_version":2,${objective?.let { "\"objective\":$it," } ?: ""}
        "queries":[${queries.joinToString(",")}],"profiles":[${profiles.joinToString(",")}],
        "constraints":{"storage_budget_bytes":1024,"write_budget_units_per_hour":null,"max_indexes":5}
        ${writeRates?.let { ",\"table_write_rates\":$it" } ?: ""}}
    """.trimIndent()

    private fun query(id: String, sql: String = "SELECT id FROM public.records"): String =
        """{"id":${quote(id)},"sql":${quote(sql)}}"""

    private fun profile(id: String = "normal", weight: String = "1", calls: String = """{"q1":1,"q2":1}"""): String =
        """{"id":${quote(id)},"weight":$weight,"calls_per_hour":$calls}"""

    private fun rates(inserts: String = "0"): String =
        """{"public.records":{"inserts_per_hour":$inserts,"updates_per_hour":0,"deletes_per_hour":0,"source":"manual"}}"""

    private fun quote(value: String): String = mapper.writeValueAsString(value)

    private fun read(json: String): WorkloadInput {
        val path = directory.resolve("workload.json")
        Files.writeString(path, json)
        return reader.read(path)
    }

    private fun failure(json: String, code: ErrorCode = ErrorCode.INVALID_INPUT): AnalysisError {
        val error = assertThrows(AnalysisException::class.java) { read(json) }.error
        assertEquals(code, error.code)
        assertTrue(error.details.containsKey("field"))
        return error
    }
}
