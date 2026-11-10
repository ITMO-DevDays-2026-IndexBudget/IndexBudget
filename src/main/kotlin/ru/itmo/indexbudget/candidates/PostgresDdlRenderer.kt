package ru.itmo.indexbudget.candidates

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import org.postgresql.core.Utils
import ru.itmo.indexbudget.domain.IndexDefinition

/** Renders a catalog-resolved definition; never opens a connection or executes SQL. */
class PostgresDdlRenderer : DdlRenderer {
    override fun render(definition: IndexDefinition): String {
        val canonical = CanonicalIndex.normalize(definition)
        val name = "ib_" + CanonicalIndex.id(canonical).take(60)
        val table = canonical.table
        val keys = canonical.keys.joinToString(", ") { key ->
            buildString {
                append(quoteIdentifier(key.column.name))
                key.collation?.let { append(" COLLATE ").append(it) }
                append(' ').append(key.operatorClass)
                append(' ').append(key.direction.name)
                append(" NULLS ").append(key.nulls.name)
            }
        }
        return "CREATE INDEX ${quoteIdentifier(name)} ON ${quoteIdentifier(table.schema)}." +
            "${quoteIdentifier(table.name)} USING btree ($keys);"
    }
}

/** The same normalized structure supplies IDs, comparison keys and DDL. */
internal object CanonicalIndex {
    fun normalize(definition: IndexDefinition): IndexDefinition {
        require(definition.method == "btree" && !definition.unique) { "Expected a nonunique B-tree candidate" }
        require(definition.keys.size in 1..2) { "Expected one or two physical keys" }
        require(definition.keys.map { it.column }.distinct().size == definition.keys.size) { "Repeated index key" }
        quoteIdentifier(definition.table.schema)
        quoteIdentifier(definition.table.name)
        return definition.copy(keys = definition.keys.map { key ->
            require(key.column.table == definition.table) { "Index keys must belong to its table" }
            quoteIdentifier(key.column.name)
            key.copy(
                collation = key.collation?.let(::qualifiedIdentifier),
                operatorClass = qualifiedIdentifier(requireNotNull(key.operatorClass) { "Resolve catalog defaults first" }),
            )
        })
    }

    fun id(definition: IndexDefinition): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun field(value: String?) {
                val encoded = value?.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded?.size ?: -1)
                if (encoded != null) output.write(encoded)
            }
            field(definition.table.schema)
            field(definition.table.name)
            field(definition.method)
            output.writeInt(definition.keys.size)
            for (key in definition.keys) {
                field(key.column.name)
                field(key.direction.name)
                field(key.nulls.name)
                field(key.collation)
                field(key.operatorClass)
            }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
    }
}

internal fun quoteIdentifier(value: String): String {
    require(value.isNotEmpty() && '\u0000' !in value) { "Invalid PostgreSQL identifier" }
    return Utils.escapeIdentifier(null, value).toString()
}

/** Decode exactly schema.name from pg_catalog format('%I.%I'), then quote both parts. */
internal fun qualifiedIdentifier(value: String): String {
    var position = 0
    fun part(): String {
        require(position < value.length) { "Expected a qualified catalog identifier" }
        if (value[position] == '"') {
            position++
            val result = StringBuilder()
            while (position < value.length) {
                val character = value[position++]
                if (character != '"') result.append(character)
                else if (position < value.length && value[position] == '"') {
                    result.append('"')
                    position++
                } else return result.toString()
            }
            throw IllegalArgumentException("Unterminated catalog identifier")
        }
        val start = position
        while (position < value.length && value[position] != '.') position++
        val text = value.substring(start, position)
        require(text.isNotEmpty() && text.withIndex().all { (index, character) ->
            character == '_' || character in 'a'..'z' || character in 'A'..'Z' || character.code >= 128 ||
                (index > 0 && (character in '0'..'9' || character == '$'))
        }) { "Invalid catalog identifier" }
        return text.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")
    }
    val schema = part()
    require(position < value.length && value[position++] == '.') { "Catalog identifier must be schema-qualified" }
    val name = part()
    require(position == value.length) { "Unexpected catalog identifier suffix" }
    return "${quoteIdentifier(schema)}.${quoteIdentifier(name)}"
}
