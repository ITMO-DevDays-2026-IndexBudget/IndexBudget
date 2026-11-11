package ru.itmo.indexbudget.sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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

    @Test
    fun `visible custom equality is rejected before producing a false equality feature`() {
        sql("CREATE OPERATOR $schema.= (LEFTARG = integer, RIGHTARG = integer, FUNCTION = pg_catalog.int4ne)")
        sql("INSERT INTO records (id, value) VALUES (1, 1), (2, 2)")
        val text = "SELECT id FROM records WHERE value = 1"
        connection.createStatement().use { statement -> statement.executeQuery(text).use {
            assertTrue(it.next())
            assertEquals(2, it.getInt(1)) // PostgreSQL actually selected our not-equal implementation.
            assertFalse(it.next())
        } }
        failure(text, ErrorCode.UNSUPPORTED_SQL)
        // Schema presence alone must not cause blanket rejection of every comparison.
        resolve("SELECT id FROM records WHERE value > 1")
    }

    @Test
    fun `domain shadowing int4 is rejected while system syntax and qualification retain builtin identity`() {
        sql("CREATE DOMAIN $schema.int4 AS pg_catalog.int4")
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT n.nspname FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace WHERE t.oid = pg_catalog.pg_typeof('1'::int4)::oid").use {
                assertTrue(it.next())
                assertEquals(schema, it.getString(1))
            }
        }
        failure("SELECT id FROM records WHERE value = '1'::int4", ErrorCode.UNSUPPORTED_SQL)
        resolve("SELECT id FROM records WHERE value = '1'::pg_catalog.int4")
        resolve("SELECT id FROM records WHERE value = CAST('1' AS integer)")
    }

    @Test
    fun `operator identity depends on both operand types not only symbol or schema presence`() {
        sql("CREATE OPERATOR $schema.= (LEFTARG = integer, RIGHTARG = bigint, FUNCTION = pg_catalog.int48ne)")
        resolve("SELECT id FROM records WHERE value = 1")
        failure("SELECT id FROM records WHERE value = 2147483648", ErrorCode.UNSUPPORTED_SQL)
        // Unknown strings use the known argument's type for exact matching.
        resolve("SELECT id FROM records WHERE value = '1'")
        sql("EXPLAIN SELECT id FROM records WHERE value = '1'")
    }

    @Test
    fun `implicit coercion to text cannot hide a custom equality operator`() {
        sql("CREATE TABLE strings (value varchar(20))")
        sql("CREATE OPERATOR $schema.= (LEFTARG = text, RIGHTARG = text, FUNCTION = pg_catalog.textne)")
        failure("SELECT value FROM strings WHERE value = 'x'", ErrorCode.UNSUPPORTED_SQL)
    }

    @ParameterizedTest
    @ValueSource(strings = ["date", "timestamptz"])
    fun `generic datetime literals respect domain shadowing`(typeName: String) {
        sql("CREATE TABLE dates (value pg_catalog.$typeName)")
        sql("CREATE DOMAIN $schema.$typeName AS pg_catalog.$typeName")
        val literal = "$typeName '2026-01-01'"
        connection.createStatement().use { statement ->
            statement.executeQuery("""
                SELECT n.nspname FROM pg_catalog.pg_type t
                JOIN pg_catalog.pg_namespace n ON n.oid OPERATOR(pg_catalog.=) t.typnamespace
                WHERE t.oid OPERATOR(pg_catalog.=) pg_catalog.pg_typeof($literal)::pg_catalog.oid
            """.trimIndent()).use {
                assertTrue(it.next())
                assertEquals(schema, it.getString(1))
            }
        }
        failure("SELECT value FROM dates WHERE value = $literal", ErrorCode.UNSUPPORTED_SQL)
        resolve("SELECT value FROM dates WHERE value = CAST('2026-01-01' AS pg_catalog.$typeName)")
        resolve("SELECT value FROM dates WHERE value = TIMESTAMP WITH TIME ZONE '2026-01-01'")
    }

    @ParameterizedTest
    @ValueSource(strings = ["varchar", "text", "nested_varchar"])
    fun `implicit coercion to a domain preserves the custom operator candidate`(baseType: String) {
        sql("CREATE TABLE pairs (id integer, a varchar, b varchar)")
        sql("INSERT INTO pairs VALUES (1, 'left', 'right')")
        if (baseType == "nested_varchar") sql("CREATE DOMAIN $schema.nested_varchar AS pg_catalog.varchar")
        val qualifiedBase = if (baseType == "nested_varchar") "$schema.$baseType" else "pg_catalog.$baseType"
        sql("CREATE DOMAIN $schema.target_domain AS $qualifiedBase")
        sql("CREATE FUNCTION $schema.compare(pg_catalog.varchar, $schema.target_domain) RETURNS pg_catalog.bool LANGUAGE sql IMMUTABLE AS 'SELECT true'")
        sql("CREATE OPERATOR $schema.= (LEFTARG = pg_catalog.varchar, RIGHTARG = $schema.target_domain, FUNCTION = $schema.compare)")
        val text = "SELECT id FROM pairs WHERE a = b"
        assertEquals(listOf(1), queryIds(text)) // Built-in equality would reject these unequal strings.
        failure(text, ErrorCode.UNSUPPORTED_SQL)
    }

    @Test
    fun `binary bit strings cannot hide a custom text bit operator`() {
        sql("INSERT INTO groups VALUES (1, '1'), (2, '2')")
        sql("CREATE FUNCTION $schema.compare(pg_catalog.text, pg_catalog.bit) RETURNS pg_catalog.bool LANGUAGE sql IMMUTABLE AS 'SELECT true'")
        sql("CREATE OPERATOR $schema.= (LEFTARG = pg_catalog.text, RIGHTARG = pg_catalog.bit, FUNCTION = $schema.compare)")
        assertEquals(listOf(1560), queryIds("SELECT pg_catalog.pg_typeof(B'1')::pg_catalog.oid"))
        val text = "SELECT id FROM groups WHERE label = B'1'"
        assertEquals(listOf(1, 2), queryIds("$text ORDER BY id"))
        failure(text, ErrorCode.UNSUPPORTED_SQL)
        val ordinaryString = "SELECT id FROM groups WHERE label = '1'"
        assertEquals(listOf(1), queryIds(ordinaryString))
        resolve(ordinaryString)
    }

    @Test
    fun `hexadecimal integer ranges select the same operator signatures as PostgreSQL`() {
        val types = mapOf("0x7fffffff" to 23, "0x80000000" to 20,
            "0x7fffffffffffffff" to 20, "0x8000000000000000" to 1700)
        types.forEach { (literal, oid) ->
            assertEquals(listOf(oid), queryIds("SELECT pg_catalog.pg_typeof($literal)::pg_catalog.oid"))
        }
        sql("CREATE OPERATOR $schema.= (LEFTARG = integer, RIGHTARG = bigint, FUNCTION = pg_catalog.int48ne)")
        sql("CREATE FUNCTION $schema.compare(pg_catalog.int4, pg_catalog.numeric) RETURNS pg_catalog.bool LANGUAGE sql IMMUTABLE AS 'SELECT true'")
        sql("CREATE OPERATOR $schema.= (LEFTARG = integer, RIGHTARG = numeric, FUNCTION = $schema.compare)")
        resolve("SELECT id FROM records WHERE value = 0x7fffffff")
        types.keys.drop(1).forEach { literal ->
            failure("SELECT id FROM records WHERE value = $literal", ErrorCode.UNSUPPORTED_SQL)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["WITH  TIME ZONE", "WITH\tTIME ZONE", "WITH\nTIME ZONE"])
    fun `formatting of composed builtin types does not affect resolution`(zone: String) {
        val text = "SELECT id FROM records WHERE created_at > CAST('2026-01-01' AS timestamp $zone)"
        assertEquals(text, resolve(text).queries.single().sql)
        sql("EXPLAIN $text")
    }

    @Test
    fun `builtin types outside the former manual whitelist are supported from catalog facts`() {
        sql("CREATE TABLE refs (target regclass, typ regtype, document tsvector, query tsquery)")
        val queries = listOf(
            "SELECT target FROM refs WHERE target = 'refs'::pg_catalog.regclass",
            "SELECT typ FROM refs WHERE typ = 'integer'::pg_catalog.regtype",
            "SELECT document FROM refs WHERE document = 'example'::pg_catalog.tsvector",
            "SELECT query FROM refs WHERE query = 'example'::pg_catalog.tsquery",
        )
        queries.forEach { text ->
            assertEquals(1, resolve(text).queries.single().predicates.size)
            sql("EXPLAIN $text")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["a", "Ж", "😀"])
    fun `identifier byte clipping agrees with PostgreSQL for tables columns and aliases`(character: String) {
        val supplied = character.repeat(64)
        val expected = truncatePostgresIdentifier(supplied, 63)
        sql("CREATE TABLE \"$supplied\" (\"$supplied\" integer)")
        for (quoted in listOf(true, false)) {
            if (!quoted && character == "😀") continue
            val name = if (quoted) "\"$supplied\"" else supplied
            val text = "SELECT $name.$name FROM $name AS $name ORDER BY $name.$name"
            val query = resolve(text).queries.single()
            val folded = if (quoted) expected else expected.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")
            assertEquals(setOf(ColumnRef(TableRef(schema, folded), folded)), query.referencedColumns)
            assertEquals(text, query.sql)
            sql("EXPLAIN $text")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["foo", "'10'", "(10)"])
    fun `separate AST typmod arguments must be integer constants`(argument: String) {
        failure("SELECT id FROM groups WHERE label = CAST('x' AS \"varchar\"($argument))", ErrorCode.UNSUPPORTED_SQL)
    }

    @Test
    fun `quoted type with integer typmod remains supported`() {
        val text = "SELECT id FROM groups WHERE label = CAST('x' AS \"varchar\"(20))"
        resolve(text)
        sql("EXPLAIN $text")
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

    @ParameterizedTest
    @ValueSource(strings = ["id IN (1, '2'::integer, NULL)", "value BETWEEN 1 AND CAST(3 AS integer)",
        "value IS NULL", "value IS NOT NULL", "id IN (1, 2147483648)", "id IN (1, 2.5)"])
    fun `scalar predicates resolve from real types and reach full EXPLAIN`(condition: String) {
        val text = "SELECT r.id FROM records r WHERE $condition"
        val workload = resolve(text)
        assertEquals(text, workload.queries.single().sql)
        assertEquals(1, workload.queries.single().predicates.size)
        val scopes = mutableListOf<CandidateScope>()
        BtreeCandidateEnumerator().forEachScope(workload, RunContext(), scopes::add)
        assertTrue(scopes.single().variants.isNotEmpty())
        sql("EXPLAIN (FORMAT JSON) $text")
    }

    @Test
    fun `json null tests keep integer candidates with a real catalog`() {
        sql("ALTER TABLE records ADD COLUMN payload json")
        sql("INSERT INTO records(id, payload) VALUES (1, NULL), (2, '{}'::json)")
        for (nullTest in listOf("IS NULL", "IS NOT NULL")) {
            val text = "SELECT id FROM records WHERE payload $nullTest AND id = 1"
            val workload = resolve(text)
            val context = RunContext()
            val snapshot = JdbcCatalogReader(connection).readCatalog(setOf(TableName("records")), context)
            assertNull(snapshot.tables.single().columns.single { it.name == "payload" }.defaultOperatorClass)
            val batch = ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator().generate(workload, snapshot, context)
            assertEquals(listOf("id"), batch.candidates.single().definition.keys.map { it.column.name })
            assertEquals(CandidateReason.NO_BTREE_OPERATOR_CLASS, batch.diagnostics.decisions.single().reason)
            assertEquals(text, workload.queries.single().sql)
            sql("EXPLAIN (FORMAT JSON) $text")
        }
    }

    @Test
    fun `null predicates preserve PostgreSQL three valued semantics`() {
        sql("INSERT INTO records(id, value) VALUES (1, NULL), (2, 2), (3, 3)")
        for ((condition, expected) in listOf(
            "value IN (2, NULL)" to listOf(2), "value BETWEEN NULL AND 3" to emptyList(),
            "value IS NULL" to listOf(1), "value IS NOT NULL" to listOf(2, 3),
        )) {
            val text = "SELECT id FROM records WHERE $condition ORDER BY id"
            assertEquals(text, resolve(text).queries.single().sql)
            assertEquals(expected, queryIds(text))
        }
    }

    @Test
    fun `IN common RHS type keeps original left type when choosing visible operator`() {
        sql("CREATE OPERATOR $schema.= (LEFTARG = integer, RIGHTARG = bigint, PROCEDURE = pg_catalog.int48ne)")
        val text = "SELECT id FROM records WHERE id IN (1, 2147483648)"
        sql("EXPLAIN $text")
        failure(text, ErrorCode.UNSUPPORTED_SQL)
        resolve("SELECT id FROM records WHERE id IN (1, 2)")
    }

    @Test
    fun `IN must not coerce left type to common RHS type before choosing operator`() {
        sql("CREATE OPERATOR $schema.= (LEFTARG = bigint, RIGHTARG = bigint, PROCEDURE = pg_catalog.int8ne)")
        val text = "SELECT id FROM records WHERE id IN (1, 2147483648)"
        resolve(text) // int4 = int8 remains builtin; int8 = int8 is irrelevant here.
        sql("EXPLAIN $text")
    }

    @Test
    fun `BETWEEN upper boundary cannot hide a custom operator`() {
        sql("CREATE OPERATOR $schema.<= (LEFTARG = integer, RIGHTARG = integer, PROCEDURE = pg_catalog.int4gt)")
        val text = "SELECT id FROM records WHERE id BETWEEN 1 AND 10"
        sql("EXPLAIN $text")
        failure(text, ErrorCode.UNSUPPORTED_SQL)
    }

    @Test
    fun `IN common numeric array does not check discarded int4 equality signatures`() {
        sql("CREATE OPERATOR $schema.= (LEFTARG = integer, RIGHTARG = integer, PROCEDURE = pg_catalog.int4ne)")
        val text = "SELECT id FROM records WHERE id IN (1, 2.5)"
        resolve(text)
        sql("EXPLAIN $text")
        failure("SELECT id FROM records WHERE id IN (1)", ErrorCode.UNSUPPORTED_SQL)
    }

    private fun failure(text: String, code: ErrorCode) {
        val error = assertThrows(AnalysisException::class.java) { resolve(text) }.error
        assertEquals(code, error.code)
        assertEquals("q", error.queryId)
    }

    private fun sql(text: String) = connection.createStatement().use { it.execute(text) }

    private fun queryIds(text: String): List<Int> = connection.createStatement().use { statement ->
        statement.executeQuery(text).use { rows -> buildList {
            while (rows.next()) add(rows.getInt(1))
        } }
    }
}
