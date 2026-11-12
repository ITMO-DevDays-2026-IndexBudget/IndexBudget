package ru.itmo.indexbudget.sql

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.withSqlSemantics
import ru.itmo.indexbudget.statistics.SqlTemplateMatcher

class DmlSqlTest {
    private val analyzer = JSqlParserSqlAnalyzer()
    private val table = TableRef("public", "records")
    private val catalog = CatalogSnapshot(listOf(CatalogTable(table, listOf(
        CatalogColumn("id", "int4", defaultOperatorClass = "pg_catalog.int4_ops"),
        CatalogColumn("value", "int4", defaultOperatorClass = "pg_catalog.int4_ops"),
        CatalogColumn("Status", "text", defaultOperatorClass = "pg_catalog.text_ops"),
    ))), searchPath = listOf("pg_catalog", "public")).withSqlSemantics()

    @Test
    fun `mixed workload preserves original statements and separates modifications from projection`() {
        val sql = listOf("SELECT value FROM records WHERE id = 1",
            "UPDATE records AS r SET value = -2, \"Status\" = CAST('done' AS text) WHERE r.id IN (1, 2)",
            "DELETE FROM records r WHERE r.value BETWEEN 1 AND 2 OR r.id IS NULL")
        val input = input(sql)
        val context = RunContext()
        val resolved = analyzer.resolve(analyzer.parse(input, context), catalog, context)
        assertEquals(sql, resolved.queries.map { it.sql })
        assertEquals(StatementKind.entries, resolved.queries.map { it.statementKind })
        assertEquals(setOf("value", "Status"), resolved.queries[1].modifiedColumns.map { it.name }.toSet())
        assertTrue(resolved.queries.drop(1).all { it.projectedColumns.isEmpty() && !it.selectsAllColumns })
        assertEquals(StatementKind.UPDATE, context.statementKinds["q1"])
        val dml = resolved.copy(queries = resolved.queries.drop(1), input = input.copy(
            queries = input.queries.drop(1), profiles = input.profiles.map { it.copy(callsPerHour = it.callsPerHour.filterKeys { id -> id != "q0" }) }))
        val candidates = ProfileAwareCandidateGenerator().generate(dml, catalog, RunContext()).candidates
        assertFalse(candidates.isEmpty())
        assertTrue(candidates.all { it.definition.includedColumns.isEmpty() })
        assertFalse(candidates.any { it.definition.keys.any { key -> key.column.name == "Status" } })
    }

    @ParameterizedTest
    @ValueSource(strings = ["UPDATE records SET value = NULL WHERE id = 1",
        "UPDATE records SET value = '2'::integer WHERE id >= 1",
        "DELETE FROM public.records WHERE id = 1"])
    fun `restricted DML is supported`(sql: String) {
        assertEquals(sql, resolve(sql).sql)
    }

    @ParameterizedTest
    @ValueSource(strings = ["UPDATE records SET value = 1", "DELETE FROM records",
        "UPDATE records SET value = value + 1 WHERE id = 1",
        "UPDATE records SET value = abs(1) WHERE id = 1",
        "UPDATE records SET value = DEFAULT WHERE id = 1",
        "UPDATE records SET value = (SELECT 1) WHERE id = 1",
        "UPDATE records SET (value, id) = (1, 2) WHERE id = 1",
        "UPDATE records SET (value) = (1) WHERE id = 1",
        "UPDATE records SET value = 1 FROM records x WHERE records.id = x.id",
        "DELETE FROM records USING records x WHERE records.id = x.id",
        "DELETE FROM records USING (SELECT id FROM records) AS other WHERE records.id = other.id",
        "UPDATE records SET value = 1 WHERE id = 1 RETURNING id",
        "DELETE FROM records WHERE id = 1 RETURNING id",
        "WITH x AS (SELECT id FROM records) DELETE FROM records WHERE id = 1",
        "UPDATE records r SET r.value = 1 WHERE r.id = 1",
        "UPDATE records SET value = 1, value = 2 WHERE id = 1",
        "UPDATE records SET value = TRUE WHERE id = 1",
        "INSERT INTO records VALUES (1, 2, 'x')"])
    fun `unsupported DML is never silently approximated`(sql: String) {
        val error = assertThrows(AnalysisException::class.java) { resolve(sql) }.error
        assertEquals(ErrorCode.UNSUPPORTED_SQL, error.code, sql)
        assertEquals("q0", error.queryId)
    }

    @Test
    fun `SET and WHERE columns are resolved and complexity limits stay enforced`() {
        for (sql in listOf("UPDATE records SET missing = 1 WHERE id = 1",
            "DELETE FROM records WHERE missing = 1")) {
            assertEquals(ErrorCode.UNKNOWN_COLUMN, assertThrows(AnalysisException::class.java) { resolve(sql) }.error.code)
        }
        val list = (1..257).joinToString(",")
        assertEquals(ErrorCode.INPUT_LIMIT_EXCEEDED, assertThrows(AnalysisException::class.java) {
            resolve("DELETE FROM records WHERE id IN ($list)")
        }.error.code)
        assertEquals(ErrorCode.INPUT_LIMIT_EXCEEDED, assertThrows(AnalysisException::class.java) {
            resolve("UPDATE records SET " + (1..257).joinToString(",") { "value = $it" } + " WHERE id = 1")
        }.error.code)
    }

    @Test
    fun `assignment casts are not treated as implicit comparison casts`() {
        val snapshot = catalog.copy(semantics = checkNotNull(catalog.semantics) { "Expected semantics to be available" }.let {
            it.copy(implicitCasts = it.implicitCasts + CatalogImplicitCast(20, 23, assignmentOnly = true))
        })
        val input = input(listOf("UPDATE records SET value = 2147483648 WHERE id = 1"))
        // Value range is intentionally checked by PostgreSQL EXPLAIN, not by inventing a narrowing conversion here.
        assertEquals(StatementKind.UPDATE, analyzer.resolve(analyzer.parse(input, RunContext()), snapshot, RunContext()).queries.single().statementKind)
    }

    @Test
    fun `quoted dotted target and SET names keep their physical identities`() {
        val ref = TableRef("public", "a.b")
        val snapshot = catalog.copy(tables = listOf(CatalogTable(ref, listOf(
            CatalogColumn("x.y", "int4"), CatalogColumn("filter", "int4"))))).withSqlSemantics()
        val sql = "UPDATE public.\"a.b\" AS r SET \"x.y\" = 2 WHERE r.filter = 1"
        val input = input(listOf(sql))
        val query = analyzer.resolve(analyzer.parse(input, RunContext()), snapshot, RunContext()).queries.single()
        assertEquals(setOf(ColumnRef(ref, "x.y")), query.modifiedColumns)
        assertEquals(sql, query.sql)
    }

    @Test
    fun `documented workload passes the normal reader and resolver`() {
        val context = RunContext()
        val input = ru.itmo.indexbudget.application.WorkloadReader().read(java.nio.file.Path.of("examples/workload-v2-dml.json"), context)
        val resolved = analyzer.resolve(analyzer.parse(input, context),
            ru.itmo.indexbudget.fixtures.ContractFixtures.catalog().withSqlSemantics(), context)
        assertEquals(StatementKind.entries, resolved.queries.map { it.statementKind })
    }

    @Test
    fun `import binds SET and WHERE parameters consistently and keeps complete statement`() {
        val matcher = SqlTemplateMatcher(RunContext())
        assertTrue(matcher.match("UPDATE records SET value = $1 WHERE id = $2",
            "UPDATE records SET value = 7 WHERE id = 2", catalog))
        assertTrue(matcher.match("DELETE FROM records WHERE id IN ($1, $2)",
            "DELETE FROM records WHERE id IN (1, 2)", catalog))
        assertFalse(matcher.match("UPDATE records SET value = $1 WHERE id = $1",
            "UPDATE records SET value = 7 WHERE id = 2", catalog))
        assertFalse(matcher.match("UPDATE records SET value = $1 WHERE id = $2",
            "UPDATE records SET id = 7 WHERE id = 2", catalog))
        assertTrue(matcher.qualifiedExample("DELETE FROM records WHERE id = 1", catalog).startsWith("DELETE FROM \"public\".records"))
    }

    private fun resolve(sql: String): ResolvedQuery {
        val input = input(listOf(sql))
        return analyzer.resolve(analyzer.parse(input, RunContext()), catalog, RunContext()).queries.single()
    }
    private fun input(sql: List<String>) = WorkloadInput(sql.mapIndexed { i, s -> QueryInput("q$i", s) },
        listOf(Profile("p", 1.0, sql.indices.associate { "q$it" to 1.0 })), Constraints(1_000_000, null))
}
