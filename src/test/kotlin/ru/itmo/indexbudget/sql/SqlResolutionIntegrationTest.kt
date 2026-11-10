package ru.itmo.indexbudget.sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import ru.itmo.indexbudget.candidates.BtreeCandidateEnumerator
import ru.itmo.indexbudget.candidates.CandidateScope
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.postgres.JdbcCatalogReader
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Same disposable-DB opt-in as the catalog tests; never targets the shared stand. */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_CATALOG_TEST_URL", matches = ".+")
class SqlResolutionIntegrationTest {
    private lateinit var connection: Connection
    private lateinit var schema: String
    private lateinit var secondSchema: String
    private val analyzer = JSqlParserSqlAnalyzer()

    @BeforeEach
    fun setup() {
        connection = DriverManager.getConnection(System.getenv("INDEXBUDGET_CATALOG_TEST_URL"),
            System.getenv("INDEXBUDGET_CATALOG_TEST_USER"), System.getenv("INDEXBUDGET_CATALOG_TEST_PASSWORD"))
        schema = "resolution_" + UUID.randomUUID().toString().replace("-", "")
        secondSchema = schema + "_second"
        sql("CREATE SCHEMA $schema")
        sql("CREATE SCHEMA $secondSchema")
        sql("SET search_path TO $schema, $secondSchema, pg_catalog")
        sql("CREATE TABLE records (id integer, value integer, created_at timestamp)")
        sql("CREATE TABLE groups (id integer, label text)")
        sql("CREATE TABLE $secondSchema.records (second_id integer)")
    }

    @AfterEach
    fun cleanup() {
        if (::connection.isInitialized) connection.use {
            it.autoCommit = true
            if (::schema.isInitialized) sql("DROP SCHEMA IF EXISTS $schema CASCADE")
            if (::secondSchema.isInitialized) sql("DROP SCHEMA IF EXISTS $secondSchema CASCADE")
        }
    }

    @Test
    fun `real catalog search path aliases casts and candidate handoff agree with PostgreSQL`() {
        val text = """
            SELECT r.id, g.label AS result_label FROM records r
            INNER JOIN groups g ON r.id = g.id
            WHERE r.value >= '1'::integer AND r.created_at < CAST('2027-01-01' AS timestamp)
            ORDER BY result_label DESC, r.value ASC LIMIT 10
        """.trimIndent()
        val resolved = resolve(text)
        val query = resolved.queries.single()
        assertEquals(setOf(TableRef(schema, "records"), TableRef(schema, "groups")), query.tables)
        assertEquals(listOf("label", "value"), query.orderBy.map { it.column.name })
        assertEquals(3, query.predicates.size)
        val scopes = mutableListOf<CandidateScope>()
        BtreeCandidateEnumerator().forEachScope(resolved, RunContext(), scopes::add)
        assertEquals(2, scopes.size)
        assertTrue(scopes.all { it.variants.isNotEmpty() })
        // EXPLAIN is only a test oracle; no ANALYZE and no physical indexes.
        sql("EXPLAIN $text")
        assertEquals(setOf(TableRef(secondSchema, "records")), resolve("SELECT second_id FROM $secondSchema.records").queries.single().tables)
    }

    @Test
    fun `all one hundred real columns resolve before generation truncates features`() {
        sql("CREATE TABLE wide (" + (1..100).joinToString(",") { "c$it integer" } + ")")
        val text = "SELECT * FROM wide WHERE " + (1..100).joinToString(" AND ") { "c$it = $it" }
        val resolved = resolve(text)
        val query = resolved.queries.single()
        assertEquals(100, query.referencedColumns.size)
        assertEquals(100, query.predicates.size)
        assertEquals(text, query.sql)
        val scopes = mutableListOf<CandidateScope>()
        BtreeCandidateEnumerator().forEachScope(resolved, RunContext(), scopes::add)
        assertEquals(16, scopes.single().retainedColumns.size)
        assertEquals(84, scopes.single().excludedColumns.size)
        sql("EXPLAIN $text")
        failure("SELECT * FROM wide WHERE c1 = 1 AND missing = 2", ErrorCode.UNKNOWN_COLUMN)
    }

    @Test
    fun `real quoted identifiers preserve physical identity through parsing catalog and resolution`() {
        sql("CREATE TABLE \"Odd.Table\" (\"Id\"\".Mixed\" integer)")
        val text = "SELECT \"R.x\".\"Id\"\".Mixed\" FROM \"Odd.Table\" AS \"R.x\" ORDER BY \"R.x\".\"Id\"\".Mixed\""
        val query = resolve(text).queries.single()
        assertEquals(setOf(ColumnRef(TableRef(schema, "Odd.Table"), "Id\".Mixed")), query.referencedColumns)
        sql("EXPLAIN $text")
    }

    @Test
    fun `unknown ambiguous relations and user types are rejected using real snapshot facts`() {
        sql("CREATE VIEW visible_records AS SELECT id FROM records")
        sql("CREATE TABLE partitioned_records (id integer) PARTITION BY RANGE (id)")
        sql("CREATE DOMAIN custom_integer AS integer")
        sql("CREATE TABLE custom_records (id integer, custom custom_integer)")
        failure("SELECT id FROM missing", ErrorCode.UNKNOWN_TABLE)
        failure("SELECT missing FROM records", ErrorCode.UNKNOWN_COLUMN)
        failure("SELECT id FROM records r INNER JOIN groups g ON r.id = g.id", ErrorCode.AMBIGUOUS_COLUMN)
        failure("SELECT id FROM visible_records", ErrorCode.UNSUPPORTED_SQL)
        failure("SELECT id FROM partitioned_records", ErrorCode.UNSUPPORTED_SQL)
        failure("SELECT * FROM custom_records", ErrorCode.UNSUPPORTED_SQL)
        failure("SELECT id FROM records WHERE value = '1'::custom_integer", ErrorCode.UNSUPPORTED_SQL)
        resolve("SELECT id FROM custom_records")
    }

    private fun resolve(text: String): ResolvedWorkload {
        val input = WorkloadInput(listOf(QueryInput("q", text)), listOf(
            Profile("day", 2.0, mapOf("q" to 100.0)), Profile("night", 1.0, mapOf("q" to 5.0)),
        ), Constraints(1024, null))
        val context = RunContext()
        val parsed = analyzer.parse(input, context)
        val snapshot = JdbcCatalogReader(connection).readCatalog(parsed.referencedTables, context)
        return analyzer.resolve(parsed, snapshot, context)
    }

    private fun failure(text: String, code: ErrorCode) {
        val error = assertThrows(AnalysisException::class.java) { resolve(text) }.error
        assertEquals(code, error.code)
        assertEquals("q", error.queryId)
    }

    private fun sql(text: String) = connection.createStatement().use { it.execute(text) }
}
