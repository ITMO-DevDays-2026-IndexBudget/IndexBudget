package ru.itmo.indexbudget.sql

import net.sf.jsqlparser.expression.CastExpression
import net.sf.jsqlparser.expression.StringValue
import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.AllTableColumns
import net.sf.jsqlparser.statement.select.PlainSelect
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.withSqlSemantics

class JSqlParserEdgeCasesTest {
    private val analyzer = JSqlParserSqlAnalyzer()

    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT id FROM db..records",
        "SELECT id FROM db...records",
        "SELECT id FROM public:records",
        "SELECT id FROM records@remote",
        "SELECT db..records.id FROM records",
        "SELECT db...records.id FROM records",
        "SELECT public:records.id FROM records",
        "SELECT db..records.* FROM records",
        "SELECT db...records.* FROM records",
        "SELECT public:records.* FROM records",
        "SELECT id FROM records WHERE id = parent_id(+)",
        "SELECT id FROM records WHERE id(+) = parent_id",
        "SELECT id FROM records WHERE PRIOR id = parent_id",
        "SELECT id FROM records WHERE id = PRIOR parent_id",
        "SELECT id(+) FROM records",
        "SELECT id FROM records ORDER BY id(+)",
        "SELECT r.id FROM records r INNER JOIN groups g ON r.id = g.id(+)",
        "SELECT id FROM records WHERE id = ~1",
        "SELECT id FROM records WHERE id = ~1.5",
        "SELECT id FROM records WHERE id = CAST(~1 AS integer)",
    ])
    fun `unsupported qualification and expression modifiers retain error identity`(sql: String) {
        val error = assertThrows(AnalysisException::class.java) {
            analyzer.parse(input(sql), RunContext())
        }.error
        assertEquals(ErrorCode.UNSUPPORTED_SQL, error.code, sql)
        assertEquals("edge", error.queryId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["+1", "-1", "+1.5", "-1.5", "CAST(-1 AS integer)", "'-1'::integer"])
    fun `numeric signs and literal casts remain supported`(literal: String) {
        val sql = "SELECT id FROM records WHERE id = $literal"
        assertEquals(sql, analyzer.parse(input(sql), RunContext()).input.queries.single().sql)
    }

    @ParameterizedTest
    @ValueSource(strings = ["db..records", "db...records", "public:records"])
    fun `unsupported qualification is rejected before AST name parts are changed`(name: String) {
        val select = CCJSqlParserUtil.parse("SELECT id FROM $name") as PlainSelect
        val table = select.fromItem as Table
        val original = table.nameParts.toList()
        val error = assertThrows(AnalysisException::class.java) {
            JSqlParserAstValidator("edge", RunContext()) { it }.validate(select)
        }.error
        assertEquals(ErrorCode.UNSUPPORTED_SQL, error.code)
        assertEquals("edge", error.queryId)
        assertEquals(original, table.nameParts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["a.b.c.d", "a@b", "a..b:c@d", "a\"b.c.d.e", "ib_quoted_0.a.b.c"])
    fun `quoted identifiers survive every supported position and resolve to the original catalog`(name: String) {
        val quoted = "\"${name.replace("\"", "\"\"")}\""
        val sql = """
            SELECT $quoted.$quoted AS $quoted, "g.h".* FROM $quoted.$quoted
            INNER JOIN groups AS "g.h" ON $quoted.$quoted = "g.h".id
            WHERE $quoted.$quoted = 1 ORDER BY $quoted
        """.trimIndent()
        val settingsBefore = System.getProperty("SPLIT_NAMES_ON_DELIMITER")
        val parsed = analyzer.parse(input(sql), RunContext()) as JSqlParserParsedWorkload
        val select = parsed.queriesById.getValue("edge")
        val table = select.fromItem as Table
        assertEquals(setOf(TableName(name, name), TableName("groups")), parsed.referencedTables)
        assertEquals(listOf(quoted, quoted), table.nameParts)
        assertEquals("$quoted.$quoted", table.fullyQualifiedName)
        assertEquals(quoted, select.selectItems.first().alias.name)
        val columns = listOf(
            select.selectItems.first().expression as Column,
            (select.where as EqualsTo).leftExpression as Column,
            (select.joins.single().onExpressions.single() as EqualsTo).leftExpression as Column,
            select.orderByElements.single().expression as Column,
        )
        columns.forEach { assertEquals(quoted, it.columnName) }
        assertEquals(listOf(quoted), columns.first().table.nameParts)
        assertEquals(listOf("\"g.h\""), (select.selectItems[1].expression as AllTableColumns).table.nameParts)
        assertEquals("\"g.h\"", select.joins.single().fromItem.alias.name)
        val tableRef = TableRef(name, name)
        val groupRef = TableRef("public", "groups")
        val catalog = CatalogSnapshot(listOf(
            CatalogTable(tableRef, listOf(CatalogColumn(name, "integer"))),
            CatalogTable(groupRef, listOf(CatalogColumn("id", "integer"))),
        ), searchPath = listOf("pg_catalog", "public")).withSqlSemantics()
        val resolved = analyzer.resolve(parsed, catalog, RunContext()).queries.single()
        assertEquals(setOf(tableRef, groupRef), resolved.tables)
        assertTrue(ColumnRef(tableRef, name) in resolved.projectedColumns)
        assertEquals(listOf(OrderByColumn(ColumnRef(tableRef, name))), resolved.orderBy)
        assertEquals(sql, parsed.input.queries.single().sql)
        assertEquals(sql, resolved.sql)
        assertEquals(settingsBefore, System.getProperty("SPLIT_NAMES_ON_DELIMITER"))
    }

    @Test
    fun `unqualified quoted table containing at sign preserves identity`() {
        val parsed = analyzer.parse(input("SELECT id FROM \"a@b\""), RunContext()) as JSqlParserParsedWorkload
        assertEquals(setOf(TableName("a@b")), parsed.referencedTables)
        assertEquals("\"a@b\"", (parsed.queriesById.getValue("edge").fromItem as Table).fullyQualifiedName)
    }

    @Test
    fun `token protection restores cast type names and leaves string literals intact`() {
        val sql = """SELECT id FROM records WHERE id = CAST('ib_quoted_.a.b' AS "type.with.dots")"""
        val parsed = analyzer.parse(input(sql), RunContext()) as JSqlParserParsedWorkload
        val cast = (parsed.queriesById.getValue("edge").where as EqualsTo).rightExpression as CastExpression
        assertEquals("\"type.with.dots\"", cast.colDataType.dataType)
        assertEquals("ib_quoted_.a.b", (cast.leftExpression as StringValue).value)
        assertEquals(sql, parsed.input.queries.single().sql)
    }

    @Test
    fun `dotted quoted tokens keep a bounded protected representation under marker collisions`() {
        // A long collision sequence and thirty thousand dots used to inflate the protected
        // token to hundreds of millions of characters; placeholders must stay short.
        val dots = ".".repeat(30_000)
        val sql = "SELECT \"$dots\" FROM records -- ib_quoted_" + "_".repeat(30_000)
        assertTrue(sql.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        val context = RunContext()

        val protection = PostgresParserTokens(sql, context)
        val statements = CCJSqlParserUtil.parseStatements(sql, java.util.function.Consumer(protection::configure))
        val select = statements[0] as PlainSelect
        val column = select.selectItems.single().expression as Column
        val protectedName = requireNotNull(column.columnName)
        assertTrue(protectedName.length <= 64, "protected representation must stay bounded: ${protectedName.length}")
        assertTrue(protectedName.startsWith("\"ib_q"))

        protection.restoreImages(context)
        JSqlParserAstValidator("edge", context, protection::restoreIdentifier).validate(select)
        assertEquals("\"$dots\"", column.columnName)
    }

    private fun input(sql: String) = WorkloadInput(
        queries = listOf(QueryInput("edge", sql)),
        profiles = listOf(Profile("profile", 1.0, mapOf("edge" to 1.0))),
        constraints = Constraints(storageBudgetBytes = 1024, writeBudgetUnitsPerHour = null),
    )
}
