package ru.itmo.indexbudget.candidates

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import ru.itmo.indexbudget.domain.*

class PostgresDdlRendererTest {
    private val renderer = PostgresDdlRenderer()
    private val table = TableRef("Odd.Schema", "Table\"; --")
    private val key = IndexKey(ColumnRef(table, "Column\".Name"), SortDirection.DESC, NullsOrder.LAST,
        "\"My.Schema\".\"Coll\"\"ation\"", "\"My.Schema\".\"Op.Class\"")
    private val definition = IndexDefinition(table, listOf(key))

    @Test
    fun `quotes every identifier component and renders every key option`() {
        val ddl = renderer.render(definition)
        val name = ddl.substringAfter("CREATE INDEX \"").substringBefore('"')
        assertTrue(name.matches(Regex("ib_[0-9a-f]{60}")))
        assertEquals(63, name.toByteArray().size)
        assertEquals("CREATE INDEX \"$name\" ON \"Odd.Schema\".\"Table\"\"; --\" USING btree " +
            "(\"Column\"\".Name\" COLLATE \"My.Schema\".\"Coll\"\"ation\" \"My.Schema\".\"Op.Class\" DESC NULLS LAST);", ddl)
        assertEquals(ddl, renderer.render(definition))
    }

    @Test
    fun `noncollatable keys omit COLLATE and unquoted catalog names normalize`() {
        val definition = definition.copy(keys = listOf(key.copy(collation = null, operatorClass = "PG_CATALOG.INT4_OPS")))
        val ddl = renderer.render(definition)
        assertFalse("COLLATE" in ddl)
        assertTrue("\"pg_catalog\".\"int4_ops\" DESC NULLS LAST" in ddl)
        assertEquals(ddl, renderer.render(definition.copy(keys = listOf(definition.keys.single().copy(
            operatorClass = "\"pg_catalog\".\"int4_ops\"",
        )))))
    }

    @TestFactory
    fun `raw SQL and ambiguous names are rejected in qualified catalog fields`() = listOf(
        "text_ops", "pg_catalog.text_ops; DROP TABLE records", "pg_catalog.text_ops --",
        "pg_catalog.text_ops.extra", "pg_catalog.\"broken", "\"\".text_ops", "pg_catalog.\"\"",
        "pg_catalog.text_ops\u0000", "pg_catalog.text_ops /* comment */",
    ).map { value -> dynamicTest(value.replace('\u0000', '?')) {
        assertThrows(IllegalArgumentException::class.java) {
            renderer.render(definition.copy(keys = listOf(key.copy(operatorClass = value))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            renderer.render(definition.copy(keys = listOf(key.copy(collation = value))))
        }
    } }

    @TestFactory
    fun `unsupported candidate shapes and unresolved defaults are rejected`() = listOf(
        definition.copy(unique = true), definition.copy(method = "hash"), definition.copy(keys = emptyList()),
        definition.copy(keys = listOf(key, key)), definition.copy(keys = listOf(key.copy(operatorClass = null))),
        definition.copy(keys = listOf(key.copy(column = ColumnRef(TableRef("other", "table"), "a")))),
        definition.copy(keys = listOf(key.copy(column = ColumnRef(table, "bad\u0000name")))),
    ).mapIndexed { index, definition -> dynamicTest("invalid shape $index") {
        assertThrows(IllegalArgumentException::class.java) { renderer.render(definition) }
    } }
}
