package ru.itmo.indexbudget.sql

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.indexbudget.candidates.BtreeCandidateEnumerator
import ru.itmo.indexbudget.candidates.CandidateScope
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.CandidateGenerationContract
import ru.itmo.indexbudget.fixtures.CandidateGenerationFixtures
import ru.itmo.indexbudget.fixtures.CandidateGenerationScenario
import java.time.Duration

class JSqlParserNameResolverTest {
    private val analyzer = JSqlParserSqlAnalyzer()
    private val records = TableRef("public", "records")
    private val groups = TableRef("public", "groups")
    private val catalog = CatalogSnapshot(listOf(
        CatalogTable(records, listOf("id", "group_id", "value").map { CatalogColumn(it, "integer") } +
            CatalogColumn("created_at", "timestamp without time zone")),
        CatalogTable(groups, listOf(CatalogColumn("id", "integer"), CatalogColumn("label", "text"))),
    ), searchPath = listOf("pg_catalog", "public"))

    @Test
    fun `all physical features and profiles survive resolution without duplicating SQL`() {
        val sql = """
            SELECT r.id, g.label FROM records r
            INNER JOIN groups g ON r.group_id = g.id
            WHERE r.value = 1 AND 5 < r.value AND r.value = 2
            ORDER BY g.label DESC, r.id ASC LIMIT 20
        """.trimIndent()
        val input = input(sql).let { it.copy(profiles = listOf(
            Profile("day", 2.0, mapOf("q" to 30.0)), Profile("night", 1.0, mapOf("q" to 2.0)),
        )) }
        val parsed = analyzer.parse(input, RunContext())
        val result = analyzer.resolve(parsed, catalog, RunContext())
        val query = result.queries.single()

        assertSame(input, result.input)
        assertEquals(sql, query.sql)
        assertEquals(setOf(records, groups), query.tables)
        assertEquals(setOf(col("id"), ColumnRef(groups, "label")), query.projectedColumns)
        assertEquals(listOf(
            ResolvedPredicate(col("value"), ComparisonOperator.EQUAL, PredicateSource.WHERE),
            ResolvedPredicate(col("value"), ComparisonOperator.GREATER_THAN, PredicateSource.WHERE),
            ResolvedPredicate(col("value"), ComparisonOperator.EQUAL, PredicateSource.WHERE),
            ResolvedPredicate(col("group_id"), ComparisonOperator.EQUAL, PredicateSource.JOIN, ColumnRef(groups, "id")),
        ), query.predicates)
        assertEquals(listOf(OrderByColumn(ColumnRef(groups, "label"), SortDirection.DESC), OrderByColumn(col("id"))), query.orderBy)
        assertEquals(setOf(col("id"), col("value"), col("group_id"), ColumnRef(groups, "id"), ColumnRef(groups, "label")), query.referencedColumns)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("generationScenarios")
    fun `actual SQL resolution satisfies shared candidate generation contracts`(name: String, scenario: CandidateGenerationScenario) {
        val parsed = analyzer.parse(scenario.workload.input, RunContext())
        val resolved = analyzer.resolve(parsed, scenario.catalog, RunContext())
        val actual = mutableListOf<CandidateScope>()
        BtreeCandidateEnumerator().forEachScope(resolved, RunContext(), actual::add)
        CandidateGenerationContract.assertScopes(scenario, actual)
        assertEquals(scenario.workload.input.queries.map { it.sql }, resolved.queries.map { it.sql }, name)
        assertSame(scenario.workload.input, resolved.input)
    }

    @Test
    fun `effective search path chooses the first relation not catalog list order`() {
        val privateRecords = catalog.tables.first().copy(ref = TableRef("private", "records"))
        val snapshot = catalog.copy(tables = catalog.tables + privateRecords, searchPath = listOf("pg_catalog", "private", "public"))
        assertEquals(setOf(privateRecords.ref), resolve("SELECT id FROM records", snapshot).tables)
        assertEquals(setOf(records), resolve("SELECT id FROM public.records", snapshot).tables)
        failure("SELECT id FROM records", ErrorCode.CATALOG_ERROR, snapshot.copy(searchPath = emptyList()))
        failure("SELECT id FROM records", ErrorCode.UNKNOWN_TABLE, snapshot.copy(searchPath = listOf("absent")))
    }

    @Test
    fun `quoted table alias column and output alias retain case dots and quotes`() {
        val table = CatalogTable(TableRef("Odd.Schema", "Records.Table"), listOf(CatalogColumn("Id\".Mixed", "integer")))
        val sql = "SELECT \"R.x\".\"Id\"\".Mixed\" AS \"Out.Name\" FROM \"Odd.Schema\".\"Records.Table\" AS \"R.x\" ORDER BY \"Out.Name\" DESC"
        val query = resolve(sql, CatalogSnapshot(listOf(table)))
        val ref = ColumnRef(table.ref, "Id\".Mixed")
        assertEquals(setOf(ref), query.referencedColumns)
        assertEquals(listOf(OrderByColumn(ref, SortDirection.DESC)), query.orderBy)
    }

    @Test
    fun `unquoted identifiers fold ASCII but not arbitrary unicode`() {
        val table = CatalogTable(TableRef("public", "Ärecords"), listOf(CatalogColumn("Äid", "integer")))
        val snapshot = CatalogSnapshot(listOf(table), searchPath = listOf("public"))
        assertEquals(setOf(ColumnRef(table.ref, "Äid")), resolve("SELECT ÄID FROM ÄRECORDS", snapshot).referencedColumns)
        failure("SELECT äid FROM Ärecords", ErrorCode.UNKNOWN_COLUMN, snapshot)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT missing FROM records",
        "SELECT id FROM records WHERE missing = 1",
        "SELECT id FROM records ORDER BY missing",
        "SELECT r.id FROM records r INNER JOIN groups g ON r.missing = g.id",
    ])
    fun `every column position is resolved before candidate selection`(sql: String) {
        failure(sql, ErrorCode.UNKNOWN_COLUMN)
    }

    @Test
    fun `unknown table keeps the failing query id even when another table resolved`() {
        val snapshot = catalog.copy(unresolvedTables = listOf(TableName("missing")))
        failure("SELECT id FROM missing", ErrorCode.UNKNOWN_TABLE, snapshot)
    }

    @Test
    fun `unqualified names stay ambiguous across two aliases of one physical table`() {
        failure("SELECT id FROM records a INNER JOIN records b ON a.id = b.id", ErrorCode.AMBIGUOUS_COLUMN)
        val query = resolve("SELECT a.id FROM records a INNER JOIN records b ON a.id = b.id")
        assertEquals(setOf(records), query.tables)
        assertEquals(setOf(col("id")), query.referencedColumns)
        assertEquals(col("id"), query.predicates.single().rightColumn)
    }

    @Test
    fun `aliases hide original names and ON cannot reference a future table`() {
        failure("SELECT records.id FROM records r", ErrorCode.UNKNOWN_TABLE)
        failure("SELECT public.records.id FROM records r", ErrorCode.UNKNOWN_TABLE)
        failure("SELECT r.id FROM records r INNER JOIN groups g ON later.id = g.id INNER JOIN records later ON later.id = r.id", ErrorCode.UNKNOWN_TABLE)
        failure("SELECT r.id FROM records r INNER JOIN groups r ON r.id = r.id", ErrorCode.UNSUPPORTED_SQL)
    }

    @Test
    fun `ORDER BY output aliases take precedence but do not leak into WHERE`() {
        val query = resolve("SELECT value AS id FROM records ORDER BY id")
        assertEquals(listOf(OrderByColumn(col("value"))), query.orderBy)
        failure("SELECT value AS out_value FROM records WHERE out_value = 1", ErrorCode.UNKNOWN_COLUMN)
        failure("SELECT id AS x, value AS x FROM records ORDER BY x", ErrorCode.AMBIGUOUS_COLUMN)
        val join = resolve("SELECT r.id FROM records r INNER JOIN groups g ON r.id = g.id ORDER BY id")
        assertEquals(listOf(OrderByColumn(col("id"))), join.orderBy)
    }

    @Test
    fun `wide star expands all columns but never creates candidate features by itself`() {
        val wide = CatalogTable(TableRef("public", "wide"), (1..100).map { CatalogColumn("c$it", "integer") })
        val snapshot = CatalogSnapshot(listOf(wide), searchPath = listOf("public"))
        val query = resolve("SELECT * FROM wide WHERE c1 = 1 ORDER BY c100", snapshot)
        assertEquals(100, query.projectedColumns.size)
        assertEquals(100, query.referencedColumns.size)
        assertTrue(query.selectsAllColumns)
        assertEquals(listOf(ColumnRef(wide.ref, "c1")), query.predicates.map { it.leftColumn })
        failure("SELECT * FROM wide WHERE c1 = 1 AND c101 = 2", ErrorCode.UNKNOWN_COLUMN, snapshot)
    }

    @ParameterizedTest
    @EnumSource(value = TableKind::class, names = ["VIEW", "PARTITIONED", "FOREIGN", "OTHER"])
    fun `nonordinary relations are rejected`(kind: TableKind) {
        failure("SELECT id FROM records", ErrorCode.UNSUPPORTED_SQL,
            catalog.copy(tables = listOf(catalog.tables.first().copy(kind = kind))))
    }

    @Test
    fun `user-defined column types are checked wherever referenced including star`() {
        val snapshot = catalog.copy(tables = listOf(catalog.tables.first().copy(columns =
            catalog.tables.first().columns + CatalogColumn("custom", "app.custom_type", isBuiltinType = false))))
        resolve("SELECT id FROM records", snapshot)
        failure("SELECT custom FROM records", ErrorCode.UNSUPPORTED_SQL, snapshot)
        failure("SELECT * FROM records", ErrorCode.UNSUPPORTED_SQL, snapshot)
        failure("SELECT id FROM records WHERE custom = 1", ErrorCode.UNSUPPORTED_SQL, snapshot)
        failure("SELECT id FROM records ORDER BY custom", ErrorCode.UNSUPPORTED_SQL, snapshot)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT id FROM records WHERE value = CAST('1' AS integer)",
        "SELECT id FROM records WHERE value = '1'::pg_catalog.int4",
        "SELECT id FROM records WHERE value = '1'::PG_CATALOG.INT4",
        "SELECT id FROM records WHERE created_at > CAST('2026-01-01' AS timestamp)",
        "SELECT id FROM groups WHERE label = CAST('x' AS varchar(20))",
    ])
    fun `builtin literal casts are supported`(sql: String) {
        resolve(sql)
    }

    @ParameterizedTest
    @ValueSource(strings = ["'1'::app.int4", "CAST('1' AS custom_type)", "CAST('1' AS app.custom_type)", "CAST('1'::custom_type AS integer)"])
    fun `user-defined casts are rejected including nested casts`(literal: String) {
        failure("SELECT id FROM records WHERE value = $literal", ErrorCode.UNSUPPORTED_SQL)
    }

    @Test
    fun `deadline during resolution retains query id and returns no result`() {
        val parsed = analyzer.parse(input("SELECT id FROM records"), RunContext())
        var now = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofNanos(3)), clock = NanoClock { now++ })
        val error = assertThrows(AnalysisException::class.java) { analyzer.resolve(parsed, catalog, context) }.error
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.code)
        assertEquals("q", error.queryId)
    }

    private fun col(name: String) = ColumnRef(records, name)
    private fun input(sql: String) = WorkloadInput(listOf(QueryInput("q", sql)),
        listOf(Profile("p", 1.0, mapOf("q" to 1.0))), Constraints(1024, null))
    private fun resolve(sql: String, snapshot: CatalogSnapshot = catalog) =
        analyzer.resolve(analyzer.parse(input(sql), RunContext()), snapshot, RunContext()).queries.single()
    private fun failure(sql: String, code: ErrorCode, snapshot: CatalogSnapshot = catalog) {
        val error = assertThrows(AnalysisException::class.java) { resolve(sql, snapshot) }.error
        assertEquals(code, error.code, sql)
        assertEquals("q", error.queryId, sql)
    }

    private companion object {
        @JvmStatic
        fun generationScenarios(): List<Arguments> = CandidateGenerationFixtures.all().map { Arguments.of(it.name, it) }
    }
}
