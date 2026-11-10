package ru.itmo.indexbudget.sql

import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.AllTableColumns
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
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

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("unsupportedSql")
    fun `unsupported SQL is rejected`(sql: String) {
        assertFailure(sql, ErrorCode.UNSUPPORTED_SQL)
    }

    @ParameterizedTest
    @ValueSource(strings = ["CAST('1' AS integer)", "'1'::integer", "CAST('1'::integer AS bigint)"])
    fun `PostgreSQL literal casts are supported`(literal: String) {
        val sql = "SELECT id FROM records WHERE id = $literal"
        assertEquals(sql, analyzer.parse(workload(sql), RunContext()).input.queries.single().sql)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "9223372036854775807"])
    fun `limit accepts the non-negative bigint boundaries`(limit: String) {
        val sql = "SELECT id FROM records LIMIT $limit"
        assertEquals(sql, analyzer.parse(workload(sql), RunContext()).input.queries.single().sql)
    }

    @ParameterizedTest
    @MethodSource("quotedTableNames")
    fun `quoted dots and escaped quotes retain PostgreSQL table identity`(sql: String, expected: TableName) {
        val parsed = analyzer.parse(workload(sql), RunContext()) as JSqlParserParsedWorkload

        assertEquals(setOf(expected), parsed.referencedTables)
        assertEquals(sql, parsed.input.queries.single().sql)
        val table = parsed.queriesById.getValue("query").fromItem as Table
        assertEquals(expected.name, table.name)
        assertEquals(expected.schema, table.schemaName)
    }

    @Test
    fun `quoted names are retained in projection conditions ordering and join ASTs`() {
        val sql = """
            SELECT "a.b"."c.d", "g.h".*
            FROM "a.b"
            INNER JOIN "other.table" AS "g.h" ON "a.b"."c.d" = "g.h"."x.y"
            WHERE "a.b"."c.d" = 1
            ORDER BY "a.b"."c.d"
        """.trimIndent()
        val parsed = analyzer.parse(workload(sql), RunContext()) as JSqlParserParsedWorkload
        val select = parsed.queriesById.getValue("query")

        assertEquals(setOf(TableName("\"a.b\""), TableName("\"other.table\"")), parsed.referencedTables)
        val columns = listOf(
            select.selectItems[0].expression as Column,
            (select.where as EqualsTo).leftExpression as Column,
            select.orderByElements.single().expression as Column,
            select.joins.single().onExpressions.single().let { (it as EqualsTo).leftExpression as Column },
        )
        columns.forEach { column ->
            assertEquals("\"c.d\"", column.columnName)
            assertEquals("\"a.b\"", column.table.name)
            assertNull(column.table.schemaName)
        }
        val star = select.selectItems[1].expression as AllTableColumns
        assertEquals("\"g.h\"", star.table.name)
        assertNull(star.table.schemaName)
        val right = (select.joins.single().onExpressions.single() as EqualsTo).rightExpression as Column
        assertEquals("\"x.y\"", right.columnName)
        assertEquals("\"g.h\"", right.table.name)
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
    fun `comparison limit combines WHERE and JOIN conditions`() {
        fun sql(whereCount: Int) = "SELECT r.id FROM records r INNER JOIN groups g ON r.group_id = g.id WHERE " +
            (1..whereCount).joinToString(" AND ") { "r.c$it = $it" }

        analyzer.parse(workload(sql(255)), RunContext())
        assertFailure(sql(256), ErrorCode.INPUT_LIMIT_EXCEEDED)
    }

    @Test
    fun `wide condition list is validated without truncating the sql`() {
        val sql = comparisons(100)
        val parsed = analyzer.parse(workload(sql), RunContext())

        assertEquals(sql, parsed.input.queries.single().sql)
    }

    @ParameterizedTest
    @ValueSource(strings = ["x", "я", "😀"])
    fun `sql byte limit is checked before parsing`(character: String) {
        val prefix = "SELECT id FROM records -- "
        val remainingBytes = 64 * 1024 - prefix.toByteArray(StandardCharsets.UTF_8).size
        val characterBytes = character.toByteArray(StandardCharsets.UTF_8).size
        val exactLimit = prefix + character.repeat(remainingBytes / characterBytes) +
            " ".repeat(remainingBytes % characterBytes)
        assertEquals(64 * 1024, exactLimit.toByteArray(StandardCharsets.UTF_8).size)
        analyzer.parse(workload(exactLimit), RunContext())

        val error = assertFailure(exactLimit + character, ErrorCode.INPUT_LIMIT_EXCEEDED)
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
        assertTrue(error.details.values.none { secret in it })
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

    private companion object {
        @JvmStatic
        fun unsupportedSql(): List<String> = listOf(
            "SELECT id FROM records; SELECT value FROM records",
            "UPDATE records SET value = 1",
            "SELECT id FROM records UNION SELECT id FROM archived_records",
            "WITH recent AS (SELECT id FROM records) SELECT id FROM recent",
            "SELECT lower(id) FROM records",
            "SELECT id FROM records WHERE value = 1 OR group_id = 2",
            "SELECT id FROM records WHERE EXISTS (SELECT 1 FROM groups)",
            "SELECT id FROM (SELECT id FROM records) nested",
            "SELECT g.id FROM (SELECT id FROM records) r INNER JOIN groups g ON r.id = g.id",
            "SELECT group_id, count(*) FROM records GROUP BY group_id",
            "SELECT id, row_number() OVER () FROM records",
            "SELECT * EXCEPT (value) FROM records",
            "SELECT id FROM records ORDER BY lower(id)",
            "SELECT id FROM records ORDER BY id NULLS FIRST",
            "SELECT id FROM records LIMIT 10 OFFSET 1",
            "SELECT id FROM records LIMIT ?",
            "SELECT r.id FROM records r LEFT JOIN groups g ON r.group_id = g.id",
            "SELECT r.id FROM records r CROSS JOIN groups g",
            "SELECT r.id FROM records r INNER JOIN groups g ON r.group_id > g.id",
            "SELECT r.id FROM records r INNER JOIN groups g ON r.group_id = 1",
            "SELECT r.id FROM records r INNER JOIN groups g USING (group_id)",
            "SELECT id FROM records WHERE id = TRY_CAST('1' AS integer)",
            "SELECT id FROM records WHERE id = SAFE_CAST('1' AS integer)",
            "SELECT id FROM records WHERE id = CAST(TRY_CAST('1' AS integer) AS bigint)",
            "SELECT id FROM records WHERE id = CAST(value AS integer)",
            "SELECT vals[abs(1)] FROM records",
            "SELECT id FROM records WHERE vals[abs(1)] = 1",
            "SELECT id FROM records ORDER BY vals[abs(1)]",
            "SELECT r.id FROM records r INNER JOIN groups g ON r.vals[abs(1)] = g.id",
            "SELECT (make_record()).* FROM records",
            "SELECT r.id FROM records r INNER JOIN (SELECT id FROM groups) g ON r.id = g.id",
            "SELECT id FROM records LIMIT 9223372036854775808",
            "SELECT id FROM records LIMIT -1",
            "SELECT id FROM records LIMIT 1.5",
        )

        @JvmStatic
        fun quotedTableNames(): List<Arguments> = listOf(
            Arguments.of("SELECT id FROM \"a.b\"", TableName("\"a.b\"")),
            Arguments.of("SELECT id FROM \"a.b.c.d\"", TableName("\"a.b.c.d\"")),
            Arguments.of("SELECT id FROM \"s.c\".\"a.b\"", TableName("\"a.b\"", "\"s.c\"")),
            Arguments.of("SELECT id FROM \"a\"\"b.c\"", TableName("\"a\"\"b.c\"")),
        )
    }
}
