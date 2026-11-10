package ru.itmo.indexbudget.sql

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.CatalogSemantics
import ru.itmo.indexbudget.domain.CatalogSnapshot
import ru.itmo.indexbudget.domain.CatalogType
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.RunContext

class PostgresLiteralTypesTest {
    private val types = listOf(
        CatalogType(23, "pg_catalog", "int4", "N"),
        CatalogType(20, "pg_catalog", "int8", "N"),
        CatalogType(1700, "pg_catalog", "numeric", "N"),
        CatalogType(1560, "pg_catalog", "bit", "V"),
        CatalogType(1082, "pg_catalog", "date", "D"),
        CatalogType(1083, "pg_catalog", "time", "D"),
        CatalogType(1114, "pg_catalog", "timestamp", "D"),
        CatalogType(1184, "pg_catalog", "timestamptz", "D"),
    )
    private val catalog = CatalogSnapshot(
        tables = emptyList(),
        searchPath = listOf("app", "pg_catalog"),
        semantics = CatalogSemantics(types, emptyList()),
    )

    @ParameterizedTest
    @CsvSource(
        "0x0, int4",
        "0X10, int4",
        "0x7fffffff, int4",
        "0x80000000, int8",
        "0x7fffffffffffffff, int8",
        "0x8000000000000000, numeric",
        "0xFFFFFFFFFFFFFFFFFFFFFFFF, numeric",
    )
    fun `hexadecimal integers use PostgreSQL numeric range boundaries`(literal: String, expected: String) {
        assertEquals(expected, infer(literal)?.name)
    }

    @ParameterizedTest
    @ValueSource(strings = ["B'1'", "b'0101'", "B''", "X'10'", "x'ff'", "X''"])
    fun `binary and hexadecimal bit strings have a known bit type`(literal: String) {
        assertEquals("bit", infer(literal)?.name)
    }

    @ParameterizedTest
    @ValueSource(strings = ["'0101'", "E'0101'", "NULL"])
    fun `ordinary string and null literals keep the unknown type`(literal: String) {
        assertNull(infer(literal))
    }

    @ParameterizedTest
    @ValueSource(strings = ["date", "timestamptz"])
    fun `generic datetime literals reject the first user type on search path`(typeName: String) {
        val shadow = CatalogType(20_000, "app", typeName, "D", isBuiltin = false, kind = "d")
        val snapshot = catalog.copy(semantics = CatalogSemantics(types + shadow, emptyList()))
        val literal = "$typeName '2026-01-01'"

        val error = assertThrows(AnalysisException::class.java) { infer(literal, snapshot) }.error
        assertEquals(ErrorCode.UNSUPPORTED_SQL, error.code)
        assertEquals(typeName, infer(literal, snapshot.copy(searchPath = listOf("pg_catalog", "app")))?.name)
        assertEquals(typeName, infer("'2026-01-01'::pg_catalog.$typeName", snapshot)?.name)
    }

    @Test
    fun `standard datetime syntax still selects system types before generic shadows`() {
        val shadows = listOf("timestamp", "time").mapIndexed { index, name ->
            CatalogType(20_000L + index, "app", name, "D", isBuiltin = false, kind = "d")
        }
        val snapshot = catalog.copy(semantics = CatalogSemantics(types + shadows, emptyList()))

        assertEquals("timestamp", infer("TIMESTAMP '2026-01-01'", snapshot)?.name)
        assertEquals("time", infer("TIME '12:00:00'", snapshot)?.name)
        assertEquals("timestamptz", infer("TIMESTAMP WITH TIME ZONE '2026-01-01'", snapshot)?.name)
    }

    private fun infer(literal: String, snapshot: CatalogSnapshot = catalog): CatalogType? =
        PostgresLiteralTypes(snapshot, RunContext()).infer(CCJSqlParserUtil.parseExpression(literal))
}
