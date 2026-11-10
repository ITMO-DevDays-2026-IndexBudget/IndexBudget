package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.Objective
import ru.itmo.indexbudget.domain.RunContext
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
        assertEquals(5, read(json(constraints = constraintsJson(maxIndexes = null))).constraints.maxIndexes)
        for (objective in listOf("\"other\"", "\"ROBUST\"", "null", "42", "true", "\"\"")) {
            assertEquals("objective", failure(json(objective = objective)).details["field"])
        }
        failure(json(constraints = constraintsJson(maxIndexes = "null")))
    }

    @Test
    fun `legacy version includes migration guidance and other schema versions are rejected`() {
        for (version in listOf("1", "3", "2.0", "\"2\"", "null", "2147483648")) {
            val error = failure(json(schemaVersion = version),
                ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
            assertEquals("schema_version", error.details["field"])
            assertTrue(error.message.contains("queries"))
            assertTrue(error.message.contains("profiles"))
        }
        failure("""{"schema_version":1}""", ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
        failure("""{"schema_version":1,"calls_per_hour":100,"queries":[]}""", ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
        failure(json(schemaVersion = null), ErrorCode.UNSUPPORTED_SCHEMA_VERSION)
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
        assertEquals("profiles[0].calls_per_hour", missing.details["field"])
        val unknown = failure(json(profiles = listOf(
            profile(),
            profile("campaign", calls = """{"q1":1,"q2":1,"extra":1,"another":1}"""),
        )))
        assertEquals("campaign", unknown.profileId)
        assertEquals("extra", unknown.queryId)
        assertEquals("profiles[1].calls_per_hour", unknown.details["field"])
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
        val budget = failure(json(constraints = constraintsJson(writeBudget = "1e-400")),
            ErrorCode.NUMERIC_ERROR)
        assertEquals("constraints.write_budget_units_per_hour", budget.details["field"])
        val rates = failure(json(writeRates = ratesJson(inserts = "1e-400")), ErrorCode.NUMERIC_ERROR)
        assertEquals("table_write_rates[0].inserts_per_hour", rates.details["field"])
    }

    @Test
    fun `integer budgets preserve zero and the largest supported storage value`() {
        val zero = read(json(constraints = constraintsJson(storage = "0", maxIndexes = "0")))
        assertEquals(0L, zero.constraints.storageBudgetBytes)
        assertEquals(0, zero.constraints.maxIndexes)
        assertEquals(Long.MAX_VALUE, read(json(constraints = constraintsJson(storage = "9223372036854775807")))
            .constraints.storageBudgetBytes)
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidConstraints")
    fun `constraints reject invalid or missing values`(field: String, description: String, value: String?) {
        val constraints = when (field) {
            "storage_budget_bytes" -> constraintsJson(storage = value)
            "write_budget_units_per_hour" -> constraintsJson(writeBudget = value)
            "max_indexes" -> constraintsJson(maxIndexes = value)
            else -> error("Unexpected constraint: $field")
        }
        val error = failure(json(constraints = constraints), context = "$field = $description")
        assertEquals("constraints.$field", error.details["field"])
    }

    @Test
    fun `write budget must be explicit and zero requires a profile`() {
        assertNull(read(json()).constraints.writeBudgetUnitsPerHour)
        assertNull(read(json(writeRates = "null")).tableWriteRates)
        assertTrue(requireNotNull(read(json(writeRates = ratesJson())).tableWriteRates).isNotEmpty())
        for (budget in listOf("0", "100")) {
            val withBudget = json(constraints = constraintsJson(writeBudget = budget))
            failure(withBudget, ErrorCode.INVALID_WRITE_PROFILE)
            val withRates = json(writeRates = ratesJson(), constraints = constraintsJson(writeBudget = budget))
            assertEquals(budget.toDouble(), read(withRates).constraints.writeBudgetUnitsPerHour)
        }
        failure(json(writeRates = "{}"), ErrorCode.INVALID_WRITE_PROFILE)
    }

    @Test
    fun `write source is manual by default and rates retain their distinct values`() {
        for (source in listOf("\"manual\"", null)) {
            val workload = read(json(writeRates = ratesJson(inserts = "10", updates = "20", deletes = "30", source = source)))
            val rates = requireNotNull(workload.tableWriteRates).getValue("public.records")
            assertEquals(10.0, rates.insertsPerHour)
            assertEquals(20.0, rates.updatesPerHour)
            assertEquals(30.0, rates.deletesPerHour)
            assertEquals(WriteRateSource.MANUAL, rates.source)
        }
        for (source in listOf("\"auto\"", "\"MANUAL\"", "null", "1")) {
            failure(json(writeRates = ratesJson(source = source)),
                ErrorCode.INVALID_WRITE_PROFILE, context = "source = $source")
        }
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidWriteRates")
    fun `write rates must be present finite nonnegative numbers`(field: String, description: String, value: String?) {
        val rates = when (field) {
            "inserts_per_hour" -> ratesJson(inserts = value)
            "updates_per_hour" -> ratesJson(updates = value)
            "deletes_per_hour" -> ratesJson(deletes = value)
            else -> error("Unexpected write rate: $field")
        }
        val error = failure(json(writeRates = rates), ErrorCode.INVALID_WRITE_PROFILE, "$field = $description")
        assertEquals("table_write_rates[0].$field", error.details["field"])
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
        failure("""
            {"schema_version":2,"schema_version":2,
             "queries":[{"id":"q1","sql":"SELECT id FROM public.records"}],
             "profiles":[{"id":"normal","weight":1,"calls_per_hour":{"q1":1}}],
             "constraints":{"storage_budget_bytes":1024,"write_budget_units_per_hour":null}}
        """.trimIndent())
        failure(json(profiles = listOf(profile(calls = """{"q1":1,"q1":2,"q2":1}"""))))
        failure(json() + " {}")
        failure("{private-sql-and-password}")
        failure(json(queries = listOf("""{"id":"q1","sql":"\uD800"}""", query("q2"))))
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), json().toByteArray(Charsets.UTF_16))) {
            val path = directory.resolve("encoding.json")
            Files.write(path, bytes)
            assertEquals(ErrorCode.INVALID_INPUT, assertThrows(AnalysisException::class.java) { reader.read(path) }.error.code)
        }
    }

    @Test
    fun `unknown fields and wrong structures do not expose raw input`() {
        val unknownField = """
            {"schema_version":2,"secret-password":1,
             "queries":[{"id":"q1","sql":"SELECT id FROM public.records"}],
             "profiles":[{"id":"normal","weight":1,"calls_per_hour":{"q1":1}}],
             "constraints":{"storage_budget_bytes":1024,"write_budget_units_per_hour":null}}
        """.trimIndent()
        for (bad in listOf("null", "[]", unknownField,
            json(queries = listOf("""{"id":42,"sql":"SELECT id FROM public.records"}""", query("q2"))))) {
            val error = failure(bad)
            assertFalse(error.message.contains("secret-password"))
        }
    }

    @Test
    fun `input fingerprint hashes recursively sorted compact source JSON`() {
        val expected = "eab14c75b8de97286b475d312c503023120ad6d6df926d7fe0c9cb388cf2c0d6"
        // Independently fixed SHA-256 for this JSON with sorted keys at every object level.
        val compact = json(queries = listOf(query("q1")),
            profiles = listOf(profile(calls = """{"q1":1}""")))
        val reordered = """
            {
              "profiles": [{"weight":1, "id":"normal", "calls_per_hour":{"q1":1}}],
              "queries": [{"sql":"SELECT id FROM public.records", "id":"q1"}],
              "constraints": {
                "write_budget_units_per_hour": null,
                "storage_budget_bytes": 1024,
                "max_indexes": 5
              },
              "schema_version": 2
            }
        """.trimIndent()

        assertEquals(expected, fingerprint(compact))
        assertEquals(expected, fingerprint(reordered))
    }

    @Test
    fun `fingerprint preserves SQL string contents but ignores JSON escape spelling`() {
        val sql = "SELECT id FROM public.records WHERE value = 'я😀'"
        val source = json(queries = listOf(query("q1", sql), query("q2")))
        val sameSql = source.replace("SELECT", "\\u0053ELECT")
        assertEquals(fingerprint(source), fingerprint(sameSql))
        assertNotEquals(fingerprint(source),
            fingerprint(json(queries = listOf(query("q1", " $sql\n"), query("q2")))))

        val context = RunContext()
        val path = directory.resolve("sql-hash.json")
        Files.writeString(path, source)
        assertEquals(sql, reader.read(path, context).queries.first().sql)
        assertEquals(fingerprint(source), context.inputHash)
    }

    @Test
    fun `fingerprint preserves both query and profile array order`() {
        val original = fingerprint(json())
        assertNotEquals(original, fingerprint(json(queries = listOf(query("q2"), query("q1")))))
        assertNotEquals(original, fingerprint(json(profiles = listOf(
            profile("campaign", "1", """{"q1":0,"q2":10}"""),
            profile("normal", "9", """{"q1":10,"q2":0}"""),
        ))))
        // Object order inside calls_per_hour has no effect, unlike the surrounding array order.
        assertEquals(original, fingerprint(json(profiles = listOf(
            profile("normal", "9", """{"q2":0,"q1":10}"""),
            profile("campaign", "1", """{"q2":10,"q1":0}"""),
        ))))
    }

    @Test
    fun `fingerprint distinguishes omitted fields from equivalent explicit defaults`() {
        for ((omitted, explicit) in listOf(
            json() to json(objective = "\"robust\""),
            json(constraints = constraintsJson(maxIndexes = null)) to json(),
            json() to json(writeRates = "null"),
        )) {
            assertEquals(read(omitted), read(explicit))
            assertNotEquals(fingerprint(omitted), fingerprint(explicit))
        }
    }

    @Test
    fun `invalid workload never records an input fingerprint`() {
        for (source in listOf("{", json(schemaVersion = "1"), json(objective = "\"invalid\""),
            json(writeRates = "{}"))) {
            val context = RunContext()
            val path = directory.resolve("invalid-hash.json")
            Files.writeString(path, source)
            assertThrows(AnalysisException::class.java) { reader.read(path, context) }
            assertNull(context.inputHash)
        }
    }

    @Test
    fun `one run cannot replace its input fingerprint`() {
        val context = RunContext()
        val path = directory.resolve("single-input.json")
        Files.writeString(path, json())
        reader.read(path, context)
        val original = context.inputHash
        Files.writeString(path, json(objective = "\"mean\""))
        assertThrows(IllegalStateException::class.java) { reader.read(path, context) }
        assertEquals(original, context.inputHash)
    }

    private fun fingerprint(json: String): String {
        val context = RunContext()
        val path = directory.resolve("fingerprint.json")
        Files.writeString(path, json)
        reader.read(path, context)
        return requireNotNull(context.inputHash)
    }

    private fun json(
        queries: List<String> = listOf(query("q1"), query("q2")),
        profiles: List<String> = listOf(
            profile("normal", "9", """{"q1":10,"q2":0}"""),
            profile("campaign", "1", """{"q1":0,"q2":10}"""),
        ),
        objective: String? = null,
        writeRates: String? = null,
        constraints: String = constraintsJson(),
        schemaVersion: String? = "2",
    ): String = listOfNotNull(
        schemaVersion?.let { "\"schema_version\":$it" },
        objective?.let { "\"objective\":$it" },
        "\"queries\":[${queries.joinToString(",")} ]",
        "\"profiles\":[${profiles.joinToString(",")} ]",
        "\"constraints\":$constraints",
        writeRates?.let { "\"table_write_rates\":$it" },
    ).joinToString(prefix = "{", postfix = "}")

    private fun query(id: String, sql: String = "SELECT id FROM public.records"): String =
        """{"id":${quote(id)},"sql":${quote(sql)}}"""

    private fun profile(id: String = "normal", weight: String = "1", calls: String = """{"q1":1,"q2":1}"""): String =
        """{"id":${quote(id)},"weight":$weight,"calls_per_hour":$calls}"""

    // null опускает поле, строка "null" передаёт явный JSON null; остальные значения — сырые JSON-токены.
    private fun constraintsJson(
        storage: String? = "1024",
        writeBudget: String? = "null",
        maxIndexes: String? = "5",
    ): String = listOfNotNull(
        storage?.let { "\"storage_budget_bytes\":$it" },
        writeBudget?.let { "\"write_budget_units_per_hour\":$it" },
        maxIndexes?.let { "\"max_indexes\":$it" },
    ).joinToString(prefix = "{", postfix = "}")

    private fun ratesJson(
        inserts: String? = "0",
        updates: String? = "0",
        deletes: String? = "0",
        source: String? = "\"manual\"",
    ): String = listOfNotNull(
        inserts?.let { "\"inserts_per_hour\":$it" },
        updates?.let { "\"updates_per_hour\":$it" },
        deletes?.let { "\"deletes_per_hour\":$it" },
        source?.let { "\"source\":$it" },
    ).joinToString(prefix = "{\"public.records\":{", postfix = "}}")

    private fun quote(value: String): String = mapper.writeValueAsString(value)

    private fun read(json: String): WorkloadInput {
        val path = directory.resolve("workload.json")
        Files.writeString(path, json)
        return reader.read(path)
    }

    private fun failure(
        json: String,
        code: ErrorCode = ErrorCode.INVALID_INPUT,
        context: String = "Input: ${json.take(512)}",
    ): AnalysisError {
        val error = assertThrows(AnalysisException::class.java, { read(json) }, context).error
        assertEquals(code, error.code, context)
        assertTrue(error.details.containsKey("field"), context)
        return error
    }

    companion object {
        @JvmStatic
        fun invalidConstraints(): List<Arguments> = listOf(
            "storage_budget_bytes" to listOf(null, "-1", "1.5", "\"1024\"", "null", "true", "9223372036854775808"),
            "max_indexes" to listOf("-1", "6", "2.7", "\"5\"", "null", "false", "9223372036854775808"),
            "write_budget_units_per_hour" to listOf(null, "-1", "\"0\"", "false", "1e400"),
        ).flatMap { (field, values) ->
            values.map { value -> Arguments.of(field, value ?: "absent", value) }
        }

        @JvmStatic
        fun invalidWriteRates(): List<Arguments> = listOf("inserts_per_hour", "updates_per_hour", "deletes_per_hour")
            .flatMap { field ->
                listOf(null, "-1", "null", "\"0\"", "false", "1e400").map { value ->
                    Arguments.of(field, value ?: "absent", value)
                }
            }
    }
}
