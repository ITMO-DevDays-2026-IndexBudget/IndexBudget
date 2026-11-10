package ru.itmo.indexbudget.postgres

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import ru.itmo.indexbudget.domain.*

/** Explicit opt-in: requires a disposable PostgreSQL 16 database, never the shared stand. */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_CATALOG_TEST_URL", matches = ".+")
class JdbcCatalogReaderIntegrationTest {
    private lateinit var connection: Connection
    private lateinit var schema: String
    private lateinit var role: String
    private lateinit var reader: JdbcCatalogReader

    @BeforeEach
    fun setup() {
        connection = DriverManager.getConnection(System.getenv("INDEXBUDGET_CATALOG_TEST_URL"),
            System.getenv("INDEXBUDGET_CATALOG_TEST_USER"), System.getenv("INDEXBUDGET_CATALOG_TEST_PASSWORD"))
        schema = "catalog_" + UUID.randomUUID().toString().replace("-", "")
        role = schema + "_reader"
        sql("CREATE SCHEMA $schema")
        sql("CREATE ROLE $role")
        sql("SET search_path TO $schema, pg_catalog")
        sql("CREATE TABLE records (id integer PRIMARY KEY, a integer NOT NULL, b integer, label varchar(50))")
        sql("INSERT INTO records SELECT i, i % 10, i % 7, 'item' || i FROM generate_series(1, 100) i")
        sql("CREATE INDEX single_idx ON records (a)")
        sql("CREATE INDEX pair_idx ON records (a DESC NULLS LAST, b ASC NULLS FIRST)")
        sql("CREATE INDEX partial_idx ON records (a) WHERE b > 0")
        sql("CREATE INDEX include_idx ON records (a) INCLUDE (label)")
        sql("CREATE INDEX expression_idx ON records (lower(label))")
        sql("CREATE INDEX pattern_idx ON records (label COLLATE \"C\" varchar_pattern_ops)")
        sql("CREATE INDEX hash_idx ON records USING hash (b)")
        // A failed concurrent unique build leaves a real invalid index behind.
        assertThrows(SQLException::class.java) { sql("CREATE UNIQUE INDEX CONCURRENTLY invalid_idx ON records (a)") }
        sql("CREATE TABLE wide (" + (1..100).joinToString(",") { "c$it integer" } + ")")
        sql("INSERT INTO wide (c1,c100) VALUES (1,100)")
        sql("CREATE TABLE no_stats (id integer)")
        sql("CREATE TYPE custom_enum AS ENUM ('one', 'two')")
        sql("CREATE TABLE custom_types (value custom_enum)")
        sql("CREATE VIEW demo_view AS SELECT a FROM records")
        sql("CREATE TABLE partitioned (id integer) PARTITION BY RANGE (id)")
        sql("CREATE TABLE \"Odd.Table\" (\"Mixed\"\"Column\" integer, discarded integer)")
        sql("ALTER TABLE \"Odd.Table\" DROP COLUMN discarded")
        sql("ANALYZE records")
        sql("ANALYZE wide")
        sql("GRANT USAGE ON SCHEMA $schema TO $role")
        sql("GRANT SELECT ON ALL TABLES IN SCHEMA $schema TO $role")
        sql("SET ROLE $role")
        reader = JdbcCatalogReader(connection)
    }

    @AfterEach
    fun cleanup() {
        if (::connection.isInitialized) connection.use {
            it.autoCommit = true
            sql("RESET ROLE")
            if (::schema.isInitialized) sql("DROP SCHEMA IF EXISTS $schema CASCADE")
            if (::role.isInitialized) sql("DROP ROLE IF EXISTS $role")
        }
    }

    @Test
    fun `all one hundred columns survive and only requested relations are read`() {
        val context = RunContext()
        val snapshot = reader.readCatalog(setOf(TableName("wide"), TableName("wide", schema)), context)
        assertEquals(listOf(TableRef(schema, "wide")), snapshot.tables.map { it.ref })
        assertEquals((1..100).map { "c$it" }, snapshot.tables.single().columns.map { it.name })
        assertTrue(snapshot.tables.single().statisticsAvailable)
        assertEquals(1.0, snapshot.tables.single().estimatedRows)
        assertEquals(listOf(schema, "pg_catalog"), snapshot.searchPath)
        assertTrue(snapshot.warnings.isEmpty())
        assertEquals(0, context.statistics().explainAttempts)
    }

    @Test
    fun `index metadata distinguishes exact keys from partial include expression and invalid indexes`() {
        val table = reader.readCatalog(setOf(TableName("records")), RunContext()).tables.single()
        val indexes = table.existingIndexes.associateBy { it.name }
        assertEquals(listOf("a"), indexes.getValue("single_idx").keys.map { it.column.name })
        val pair = indexes.getValue("pair_idx").keys
        assertEquals(listOf("a", "b"), pair.map { it.column.name })
        assertEquals(listOf(SortDirection.DESC, SortDirection.ASC), pair.map { it.direction })
        assertEquals(listOf(NullsOrder.LAST, NullsOrder.FIRST), pair.map { it.nulls })
        assertNotNull(indexes.getValue("partial_idx").predicate)
        assertEquals(listOf("label"), indexes.getValue("include_idx").includedColumns.map { it.name })
        assertEquals(1, indexes.getValue("include_idx").keys.size)
        assertTrue(indexes.getValue("expression_idx").expressions.single().contains("lower"))
        assertTrue(indexes.getValue("expression_idx").keys.isEmpty())
        assertFalse(indexes.getValue("invalid_idx").valid)
        assertTrue(indexes.getValue("records_pkey").unique)
        assertEquals("hash", indexes.getValue("hash_idx").method)
        assertEquals("pg_catalog.\"C\"", indexes.getValue("pattern_idx").keys.single().collation)
        assertEquals("pg_catalog.varchar_pattern_ops", indexes.getValue("pattern_idx").keys.single().operatorClass)
        val a = table.columns.single { it.name == "a" }
        assertEquals("integer", a.typeName)
        assertFalse(a.nullable)
        assertEquals("pg_catalog.int4_ops", a.defaultOperatorClass)
        assertEquals("pg_catalog.text_ops", table.columns.single { it.name == "label" }.defaultOperatorClass)
    }

    @Test
    fun `quoted names dropped attributes and unknown names are preserved safely`() {
        val missing = TableName("records\"; DROP TABLE records; --", schema)
        val snapshot = reader.readCatalog(setOf(TableName("Odd.Table"), missing), RunContext())
        assertEquals(listOf("Mixed\"Column"), snapshot.tables.single().columns.map { it.name })
        assertEquals(listOf(missing), snapshot.unresolvedTables)
        assertEquals(100, scalar("SELECT count(*) FROM records"))
    }

    @Test
    fun `unsupported relations and types remain visible to SQL resolution`() {
        val tables = reader.readCatalog(setOf(TableName("demo_view"), TableName("partitioned"),
            TableName("custom_types")), RunContext()).tables.associateBy { it.ref.name }
        assertEquals(TableKind.VIEW, tables.getValue("demo_view").kind)
        assertEquals(TableKind.PARTITIONED, tables.getValue("partitioned").kind)
        assertFalse(tables.getValue("custom_types").columns.single().isBuiltinType)
    }

    @Test
    fun `missing statistics yields warning without silently analyzing`() {
        val snapshot = reader.readCatalog(setOf(TableName("no_stats")), RunContext())
        assertFalse(snapshot.tables.single().statisticsAvailable)
        assertNull(snapshot.tables.single().estimatedRows)
        assertEquals(listOf(WarningCode.MISSING_STATISTICS), snapshot.warnings.map { it.code })
        assertEquals(snapshot, reader.readCatalog(setOf(TableName("no_stats")), RunContext()))
    }

    @Test
    fun `reader reuses connection and leaves caller transaction intact`() {
        val pid = scalar("SELECT pg_backend_pid()")
        connection.autoCommit = false
        sql("SAVEPOINT before_catalog")
        reader.readCatalog(setOf(TableName("records")), RunContext())
        assertFalse(connection.isClosed)
        assertFalse(connection.autoCommit)
        assertEquals(pid, scalar("SELECT pg_backend_pid()"))
        sql("ROLLBACK TO SAVEPOINT before_catalog")
        connection.rollback()
    }

    @Test
    fun `permission errors do not expose driver diagnostics`() {
        sql("RESET ROLE")
        sql("REVOKE SELECT ON records FROM $role")
        sql("SET ROLE $role")
        val error = assertThrows(AnalysisException::class.java) {
            reader.readCatalog(setOf(TableName("records")), RunContext())
        }
        assertEquals(ErrorCode.CATALOG_ERROR, error.error.code)
        assertNull(error.cause)
    }

    @Test
    fun `effective path resolves shadowed names and quoted schemas`() {
        val other = schema + ".Mixed"
        sql("RESET ROLE")
        sql("CREATE SCHEMA \"$other\"")
        try {
            sql("CREATE TABLE \"$other\".records (shadow integer)")
            sql("GRANT USAGE ON SCHEMA \"$other\" TO $role")
            sql("GRANT SELECT ON \"$other\".records TO $role")
            sql("SET search_path TO \"$other\", $schema, pg_catalog")
            sql("SET ROLE $role")
            val snapshot = reader.readCatalog(setOf(TableName("records"), TableName("records", schema)), RunContext())
            assertEquals(listOf(other, schema, "pg_catalog"), snapshot.searchPath)
            assertEquals(2, snapshot.tables.size)
            assertEquals(listOf("shadow"), snapshot.tables.single { it.ref.schema == other }.columns.map { it.name })
            assertEquals(4, snapshot.tables.single { it.ref.schema == schema }.columns.size)
            assertTrue(snapshot.unresolvedTables.isEmpty())
        } finally {
            sql("RESET ROLE")
            sql("DROP SCHEMA \"$other\" CASCADE")
        }
    }

    private fun sql(text: String) = connection.createStatement().use { it.execute(text) }
    private fun scalar(text: String): Int = connection.createStatement().use { s ->
        s.executeQuery(text).use { it.next(); it.getInt(1) }
    }
}
