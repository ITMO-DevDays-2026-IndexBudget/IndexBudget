package ru.itmo.indexbudget.sql

import net.sf.jsqlparser.expression.*
import net.sf.jsqlparser.statement.create.table.ColDataType
import ru.itmo.indexbudget.domain.*
import java.math.BigInteger

/** SQL spelling/typmods belong to the adapter; actual identities come only from the server. */
internal class PostgresLiteralTypes(private val catalog: CatalogSnapshot, private val context: RunContext) {
    private fun facts() = catalog.semantics
        ?: fail(ErrorCode.CATALOG_ERROR, "Catalog is missing PostgreSQL type/operator facts.")
    private val byOid by lazy { facts().types.associate { context.checkDeadline(); it.oid to it } }
    private val byName by lazy { byOid.values.associate { context.checkDeadline(); (it.schema to it.name) to it } }

    fun columnType(column: CatalogColumn): CatalogType = byOid[column.typeOid]
        ?: fail(ErrorCode.CATALOG_ERROR, "Catalog is missing a referenced column type identity.")

    fun requireAssignment(column: CatalogColumn, expression: Expression) {
        val target = columnType(column)
        if (!target.isBuiltin || target.kind != BASE_TYPE_KIND || target.category == ARRAY_TYPE_CATEGORY) {
            unsupported("SET requires a built-in scalar target type.")
        }
        // Unknown literals and cast value/typmod validation are checked by the original EXPLAIN.
        val source = infer(expression)
        if (source == null || source.oid == target.oid || target.category == STRING_TYPE_CATEGORY) return
        if (!hasAssignmentCast(source, target)) {
            unsupported("SET literal cannot be assignment-coerced to the target column type.")
        }
    }

    private fun hasAssignmentCast(source: CatalogType, target: CatalogType): Boolean =
        facts().implicitCasts.any { cast ->
            context.checkDeadline()
            cast.sourceTypeOid == source.oid && cast.targetTypeOid == target.oid
        }

    fun infer(expression: Expression): CatalogType? {
        context.checkDeadline()
        return when (expression) {
            is CastExpression -> {
                infer(expression.leftExpression)
                castType(expression.colDataType)
            }
            is StringValue -> if (expression.prefix.equals("B", ignoreCase = true)) builtin("bit") else null
            is NullValue -> null // PostgreSQL unknown, not text.
            is BooleanValue -> builtin("bool")
            is LongValue -> integerType(expression.bigIntegerValue)
            is DoubleValue -> builtin("numeric")
            is SignedExpression -> {
                val inner = expression.expression
                if (expression.sign != '+' && expression.sign != '-') unsupported("Unsupported numeric literal sign.")
                if (inner is LongValue) integerType(if (expression.sign == '-') -inner.bigIntegerValue else inner.bigIntegerValue)
                else infer(inner)
            }
            is DateValue -> builtin("date")
            is TimeValue -> builtin("time")
            is TimestampValue -> builtin("timestamp")
            is DateTimeLiteralExpression -> castType(ColDataType(expression.type.name))
            is HexValue -> if (expression.value.startsWith("0x", ignoreCase = true)) {
                integerType(BigInteger(expression.value.substring(2), 16))
            } else {
                builtin("bit")
            }
            else -> unsupported("Unsupported comparison literal.")
        }
    }

    fun castType(type: ColDataType): CatalogType {
        context.checkDeadline()
        if (!type.argumentsStringList.orEmpty().all { it.trim().toIntOrNull() != null }) {
            unsupported("Literal cast type modifiers must be integer constants.")
        }
        if (!type.arrayData.isNullOrEmpty() || type.characterSet != null || type.signedness != null || type.isZerofill ||
            !type.typeModifiers.isNullOrEmpty() || type.isNational || type.xmlTypeModifier != null) {
            unsupported("Unsupported literal cast modifiers.")
        }
        val (spelling, modifiers) = normalizeTypeSpelling(type.dataType)
        val standard = STANDARD_TYPE_NAMES[spelling]
        if (standard != null) {
            val target = if (spelling == "float" && modifiers.firstOrNull()?.let { it in 1..24 } == true) "float4" else standard
            return builtin(target)
        }
        val parts = identifierParts(spelling)
        if (parts.size !in 1..2) unsupported("Unsupported type qualification.")
        val name = parts.last()
        val schema = if (parts.size == 2) parts.first() else catalog.searchPath.firstOrNull { schema ->
            byName.containsKey(schema to name)
        }
        val found = byName[schema to name]
            ?: unsupported("Literal cast type was not found in the catalog.")
        if (!found.isBuiltin || found.kind == "p" || found.category == ARRAY_TYPE_CATEGORY) unsupported("User-defined or nonscalar cast types are not supported.")
        return found
    }

    private fun builtin(name: String): CatalogType = byName["pg_catalog" to name]?.takeIf { it.isBuiltin }
        ?: fail(ErrorCode.CATALOG_ERROR, "Catalog is missing a required PostgreSQL built-in type.")

    private fun integerType(value: BigInteger): CatalogType = builtin(when {
        value >= BigInteger.valueOf(Int.MIN_VALUE.toLong()) && value <= BigInteger.valueOf(Int.MAX_VALUE.toLong()) -> "int4"
        value >= BigInteger.valueOf(Long.MIN_VALUE) && value <= BigInteger.valueOf(Long.MAX_VALUE) -> "int8"
        else -> "numeric"
    })

    /** Normalize only unquoted separators; do not change quoted identifier contents. */
    private fun normalizeTypeSpelling(raw: String): Pair<String, List<Int>> {
        val name = StringBuilder()
        val modifiers = mutableListOf<Int>()
        var quoted = false
        var index = 0
        while (index < raw.length) {
            context.checkDeadline()
            val ch = raw[index]
            when {
                ch == '"' -> {
                    name.append(ch)
                    if (quoted && raw.getOrNull(index + 1) == '"') { name.append('"'); index++ }
                    else quoted = !quoted
                }
                !quoted && ch == '(' -> {
                    val end = raw.indexOf(')', index + 1)
                    if (end < 0) unsupported("Invalid literal cast type modifier.")
                    raw.substring(index + 1, end).split(',').forEach {
                        modifiers += it.trim().toIntOrNull() ?: unsupported("Literal cast type modifiers must be integer constants.")
                    }
                    index = end
                }
                !quoted && ch.isWhitespace() -> if (name.isNotEmpty() && name.last() != ' ') name.append(' ')
                !quoted && ch in 'A'..'Z' -> name.append(ch.lowercaseChar())
                else -> name.append(ch)
            }
            index++
        }
        if (quoted) unsupported("Invalid quoted type name.")
        return name.toString().trim() to modifiers
    }

    private fun identifierParts(spelling: String): List<String> {
        val parts = mutableListOf<String>()
        var quoted = false
        var start = 0
        var index = 0
        while (index < spelling.length) {
            val ch = spelling[index]
            if (ch == '"') {
                if (quoted && spelling.getOrNull(index + 1) == '"') index++ else quoted = !quoted
            } else if (ch == '.' && !quoted) {
                parts += spelling.substring(start, index).trim()
                start = index + 1
            }
            index++
        }
        parts += spelling.substring(start).trim()
        return parts.map { truncatePostgresIdentifier(decodePostgresIdentifier(it), catalog.maxIdentifierLength) }
    }

    private fun unsupported(message: String): Nothing = fail(ErrorCode.UNSUPPORTED_SQL, message)
    private fun fail(code: ErrorCode, message: String): Nothing = throw AnalysisException(AnalysisError(code, message))

    private companion object {
        // pg_type.typtype and typcategory codes.
        const val BASE_TYPE_KIND = "b"
        const val ARRAY_TYPE_CATEGORY = "A"
        const val STRING_TYPE_CATEGORY = "S"

        // PostgreSQL gram.y SystemTypeName aliases, not a list of supported types.
        // Generic names (int4, regclass, tsvector, quoted names, etc.) use search_path.
        val STANDARD_TYPE_NAMES = mapOf(
            "int" to "int4", "integer" to "int4", "smallint" to "int2", "bigint" to "int8",
            "real" to "float4", "double precision" to "float8", "float" to "float8",
            "decimal" to "numeric", "dec" to "numeric", "numeric" to "numeric", "boolean" to "bool",
            "char" to "bpchar", "character" to "bpchar", "varchar" to "varchar", "character varying" to "varchar",
            "char varying" to "varchar", "bit" to "bit", "bit varying" to "varbit",
            "timestamp" to "timestamp", "timestamp without time zone" to "timestamp", "timestamp with time zone" to "timestamptz",
            "time" to "time", "time without time zone" to "time", "time with time zone" to "timetz", "interval" to "interval",
        )
    }
}
