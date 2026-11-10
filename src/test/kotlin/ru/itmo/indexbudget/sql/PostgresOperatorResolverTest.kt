package ru.itmo.indexbudget.sql

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.CatalogImplicitCast
import ru.itmo.indexbudget.domain.CatalogOperator
import ru.itmo.indexbudget.domain.CatalogSemantics
import ru.itmo.indexbudget.domain.CatalogSnapshot
import ru.itmo.indexbudget.domain.CatalogType
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.RunContext

class PostgresOperatorResolverTest {
    private val varchar = CatalogType(1043, "pg_catalog", "varchar", "S")
    private val text = CatalogType(25, "pg_catalog", "text", "S", preferred = true)
    private val integer = CatalogType(23, "pg_catalog", "int4", "N")
    private val varcharDomain = domain(50_001, "dvarchar", varchar)
    private val textEquality = CatalogOperator(98, "pg_catalog", "=", text.oid, text.oid)

    @Test
    fun `domain coercion preserves a custom operator with a better exact argument match`() {
        // PostgreSQL can coerce the second varchar to its domain without a pg_cast row.
        // (varchar, domain) has one exact argument; built-in (text, text) has none.
        assertCustomOperatorRejected(resolver(listOf(varcharDomain), customEquality(varchar, varcharDomain)))
    }

    @Test
    fun `nested domains resolve through every base type before checking coercion`() {
        val nestedDomain = domain(50_002, "nested_varchar", varcharDomain)
        assertCustomOperatorRejected(resolver(listOf(varcharDomain, nestedDomain), customEquality(varchar, nestedDomain)))
    }

    @Test
    fun `implicit cast to a domain base type also preserves the custom candidate`() {
        val textDomain = domain(50_003, "dtext", text)
        // varchar -> text is implicit; wrapping the target in a domain keeps that pathway.
        assertCustomOperatorRejected(resolver(listOf(textDomain), customEquality(varchar, textDomain)))
    }

    @Test
    fun `domain targets without an implicit base cast do not displace builtin equality`() {
        val integerDomain = domain(50_004, "dinteger", integer)
        val resolver = resolver(listOf(integerDomain), customEquality(varchar, integerDomain))
        assertDoesNotThrow { resolver.requireBuiltin("=", varchar, varchar) }
    }

    @Test
    fun `builtin exact match wins over a coercible custom domain overload`() {
        val textDomain = domain(50_003, "dtext", text)
        val resolver = resolver(listOf(textDomain), customEquality(text, textDomain))
        assertDoesNotThrow { resolver.requireBuiltin("=", text, text) }
    }

    @Test
    fun `ordinary builtin varchar comparison still uses its implicit text cast`() {
        assertDoesNotThrow { resolver().requireBuiltin("=", varchar, varchar) }
    }

    @Test
    fun `a missing domain base is a catalog error`() {
        val brokenDomain = varcharDomain.copy(baseTypeOid = 99_999)
        val resolver = resolver(listOf(brokenDomain), customEquality(varchar, brokenDomain))
        val error = assertThrows(AnalysisException::class.java) { resolver.requireBuiltin("=", varchar, varchar) }.error
        assertEquals(ErrorCode.CATALOG_ERROR, error.code)
    }

    @Test
    fun `a domain cycle is rejected instead of being traversed indefinitely`() {
        val first = varcharDomain.copy(baseTypeOid = 50_002)
        val second = domain(50_002, "second_domain", first)
        val resolver = resolver(listOf(first, second), customEquality(varchar, first))
        val error = assertThrows(AnalysisException::class.java) { resolver.requireBuiltin("=", varchar, varchar) }.error
        assertEquals(ErrorCode.CATALOG_ERROR, error.code)
    }

    private fun domain(oid: Long, name: String, base: CatalogType) = CatalogType(
        oid, "app", name, base.category, isBuiltin = false, kind = "d", baseTypeOid = base.oid,
    )

    private fun customEquality(left: CatalogType, right: CatalogType) =
        CatalogOperator(60_001, "app", "=", left.oid, right.oid, isBuiltin = false)

    private fun resolver(domains: List<CatalogType> = emptyList(), customOperator: CatalogOperator? = null) =
        PostgresOperatorResolver(CatalogSnapshot(emptyList(), semantics = CatalogSemantics(
            types = listOf(varchar, text, integer) + domains,
            operators = listOfNotNull(textEquality, customOperator),
            implicitCasts = listOf(CatalogImplicitCast(varchar.oid, text.oid)),
        )), RunContext())

    private fun assertCustomOperatorRejected(resolver: PostgresOperatorResolver) {
        val error = assertThrows(AnalysisException::class.java) { resolver.requireBuiltin("=", varchar, varchar) }.error
        assertEquals(ErrorCode.UNSUPPORTED_SQL, error.code)
        assertEquals("User-defined comparison operators are not supported.", error.message)
    }
}
