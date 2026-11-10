package ru.itmo.indexbudget.sql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.Constraints
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.NanoClock
import ru.itmo.indexbudget.domain.Profile
import ru.itmo.indexbudget.domain.QueryInput
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.RunLimits
import ru.itmo.indexbudget.domain.TableName
import ru.itmo.indexbudget.domain.WorkloadInput
import java.nio.charset.StandardCharsets
import java.time.Duration

class JSqlParserSqlAnalyzerTest {
    private val analyzer = JSqlParserSqlAnalyzer()

    @Test
    fun `supported select retains shared input and referenced tables`() {
        val sql = """
            SELECT r.id, g.*
            FROM public.records r
            INNER JOIN public.groups g ON r.group_id = g.id
            WHERE r.value >= CAST('-1' AS integer)
              AND r.value <= 100
              AND r.id > 0
              AND r.created_at < TIMESTAMP '2026-01-01 00:00:00'
            ORDER BY r.created_at DESC, r.id ASC
            LIMIT 20;
        """.trimIndent()
        val input = workload(sql)

        val parsed = analyzer.parse(input, RunContext())

        assertEquals(input, parsed.input)
        assertEquals(
            setOf(TableName("records", "public"), TableName("groups", "public")),
            parsed.referencedTables,
        )
    }

    @Test
    fun `quoted identifiers are retained for catalog resolution`() {
        val parsed = analyzer.parse(
            workload("SELECT r.\"Id\" FROM \"Custom\".\"Records\" AS r WHERE r.\"Id\" = 1"),
            RunContext(),
        )

        assertEquals(setOf(TableName("\"Records\"", "\"Custom\"")), parsed.referencedTables)
    }

    @Test
    fun `frequency profiles do not create duplicate asts`() {
        val queries = listOf(
            QueryInput("first", "SELECT id FROM records WHERE value = 1"),
            QueryInput("second", "SELECT id FROM records WHERE value = 2"),
        )
        val profiles = (1..3).map { number ->
            Profile("profile-$number", number.toDouble(), mapOf("first" to 1.0, "second" to 2.0))
        }
        val input = workload(queries.first().sql).copy(queries = queries, profiles = profiles)

        val parsed = analyzer.parse(input, RunContext()) as JSqlParserParsedWorkload

        assertEquals(2, parsed.queriesById.size)
        assertEquals(3, parsed.input.profiles.size)
    }

    @Test
    fun `exactly one plain select is required`() {
        val invalid = listOf(
            "SELECT id FROM records; SELECT value FROM records",
            "UPDATE records SET value = 1",
            "SELECT id FROM records UNION SELECT id FROM archived_records",
            "WITH recent AS (SELECT id FROM records) SELECT id FROM recent",
        )

        invalid.forEach { sql ->
            assertFailure(sql, ErrorCode.UNSUPPORTED_SQL)
        }
    }

    @Test
    fun `unsupported clauses and nested expressions are rejected`() {
        val invalid = listOf(
            "SELECT lower(id) FROM records",
            "SELECT id FROM records WHERE value = 1 OR group_id = 2",
            "SELECT id FROM records WHERE EXISTS (SELECT 1 FROM groups)",
            "SELECT id FROM (SELECT id FROM records) nested",
            "SELECT group_id, count(*) FROM records GROUP BY group_id",
            "SELECT id, row_number() OVER () FROM records",
            "SELECT * EXCEPT (value) FROM records",
            "SELECT id FROM records ORDER BY lower(id)",
            "SELECT id FROM records ORDER BY id NULLS FIRST",
            "SELECT id FROM records LIMIT 10 OFFSET 1",
            "SELECT id FROM records LIMIT ?",
        )

        invalid.forEach { sql ->
            assertFailure(sql, ErrorCode.UNSUPPORTED_SQL)
        }
    }

    @Test
    fun `only inner joins with column equality are accepted`() {
        val invalid = listOf(
            "SELECT r.id FROM records r LEFT JOIN groups g ON r.group_id = g.id",
            "SELECT r.id FROM records r CROSS JOIN groups g",
            "SELECT r.id FROM records r INNER JOIN groups g ON r.group_id > g.id",
            "SELECT r.id FROM records r INNER JOIN groups g ON r.group_id = 1",
            "SELECT r.id FROM records r INNER JOIN groups g USING (group_id)",
        )

        invalid.forEach { sql ->
            assertFailure(sql, ErrorCode.UNSUPPORTED_SQL)
        }
    }

    @Test
    fun `no more than three tables are accepted`() {
        val sql = """
            SELECT a.id
            FROM a
            INNER JOIN b ON a.id = b.a_id
            INNER JOIN c ON b.id = c.b_id
            INNER JOIN d ON c.id = d.c_id
        """.trimIndent()

        assertFailure(sql, ErrorCode.UNSUPPORTED_SQL)
    }

    @Test
    fun `comparison limit includes the entire where tree`() {
        val accepted = comparisons(256)
        analyzer.parse(workload(accepted), RunContext())

        val error = assertFailure(comparisons(257), ErrorCode.INPUT_LIMIT_EXCEEDED)
        assertEquals("query", error.queryId)
    }

    @Test
    fun `wide condition list is validated without truncating the sql`() {
        val sql = comparisons(100)
        val parsed = analyzer.parse(workload(sql), RunContext())

        assertEquals(sql, parsed.input.queries.single().sql)
    }

    @Test
    fun `sql byte limit is checked before parsing`() {
        val prefix = "SELECT id FROM records"
        val exactLimit = prefix + " ".repeat(64 * 1024 - prefix.toByteArray(StandardCharsets.UTF_8).size)
        analyzer.parse(workload(exactLimit), RunContext())

        val error = assertFailure("$exactLimit ", ErrorCode.INPUT_LIMIT_EXCEEDED)
        assertEquals("query", error.queryId)
    }

    @Test
    fun `deadline is checked after the external parser returns`() {
        var now = 0L
        val context = RunContext(
            limits = RunLimits(commandTimeout = Duration.ofNanos(5), searchTimeout = Duration.ofSeconds(60)),
            clock = NanoClock { now++ },
        )

        val error = assertThrows(AnalysisException::class.java) {
            analyzer.parse(workload("SELECT id FROM records"), context)
        }.error

        assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.code)
        assertEquals("query", error.queryId)
    }

    @Test
    fun `parse error does not expose sql text in its public diagnostic`() {
        val secret = "private_password_value"
        val error = assertFailure("SELECT $secret(", ErrorCode.UNSUPPORTED_SQL)

        assertTrue(error.message.isNotBlank())
        assertTrue(secret !in error.message)
        assertTrue(secret !in error.details.values)
    }

    private fun assertFailure(sql: String, expectedCode: ErrorCode): AnalysisError {
        val failure = assertThrows(AnalysisException::class.java) {
            analyzer.parse(workload(sql), RunContext())
        }
        assertEquals(expectedCode, failure.error.code, sql)
        assertEquals("query", failure.error.queryId)
        return failure.error
    }

    private fun comparisons(count: Int): String =
        "SELECT * FROM wide_records WHERE " + (1..count).joinToString(" AND ") { "c$it = $it" }

    private fun workload(sql: String): WorkloadInput = WorkloadInput(
        queries = listOf(QueryInput("query", sql)),
        profiles = listOf(Profile("profile", 1.0, mapOf("query" to 1.0))),
        constraints = Constraints(
            storageBudgetBytes = 1024,
            writeBudgetUnitsPerHour = null,
        ),
    )
}
