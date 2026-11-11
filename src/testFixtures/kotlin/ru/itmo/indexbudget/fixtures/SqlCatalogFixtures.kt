package ru.itmo.indexbudget.fixtures

import ru.itmo.indexbudget.domain.*

/** Explicit facts for SQL unit tests, never a production fallback for a missing snapshot. */
fun CatalogSnapshot.withSqlSemantics(): CatalogSnapshot {
    val types = listOf(
        CatalogType(16, "pg_catalog", "bool", "B", true),
        CatalogType(20, "pg_catalog", "int8", "N"), CatalogType(21, "pg_catalog", "int2", "N"),
        CatalogType(23, "pg_catalog", "int4", "N"), CatalogType(25, "pg_catalog", "text", "S", true),
        CatalogType(1043, "pg_catalog", "varchar", "S"), CatalogType(1082, "pg_catalog", "date", "D"),
        CatalogType(1114, "pg_catalog", "timestamp", "D"), CatalogType(1184, "pg_catalog", "timestamptz", "D", true),
        CatalogType(1700, "pg_catalog", "numeric", "N"), CatalogType(2205, "pg_catalog", "regclass", "N"),
        CatalogType(2206, "pg_catalog", "regtype", "N"), CatalogType(3614, "pg_catalog", "tsvector", "U"),
        CatalogType(3615, "pg_catalog", "tsquery", "U"),
    ).map { it.copy(arrayTypeOid = it.oid + 100_000) }
    val arrayTypes = types.map { CatalogType(it.arrayTypeOid, "pg_catalog", "_${it.name}", "A", elementTypeOid = it.oid) }
    val aliases = mapOf("integer" to "int4", "smallint" to "int2", "bigint" to "int8",
        "timestamp without time zone" to "timestamp", "timestamp with time zone" to "timestamptz")
    val operators = buildList {
        for (type in types) for (name in listOf("=", "<", "<=", ">", ">=")) {
            add(CatalogOperator((size + 1).toLong(), "pg_catalog", name, type.oid, type.oid))
        }
        for ((left, right) in listOf(1114L to 1184L, 1184L to 1114L)) for (name in listOf("=", "<", "<=", ">", ">=")) {
            add(CatalogOperator((size + 1).toLong(), "pg_catalog", name, left, right))
        }
    }
    return copy(
        tables = tables.map { table -> table.copy(columns = table.columns.map { column ->
            val name = column.typeName.substringBefore('(').trim()
            column.copy(typeOid = types.singleOrNull { it.name == (aliases[name] ?: name) }?.oid)
        }) },
        semantics = CatalogSemantics(types + arrayTypes, operators, listOf(CatalogImplicitCast(1043, 25)) +
            listOf(21L to 23L, 21L to 20L, 21L to 1700L, 23L to 20L, 23L to 1700L, 20L to 1700L)
                .map { (source, target) -> CatalogImplicitCast(source, target) }),
    )
}
