package ru.itmo.indexbudget.domain

data class TableRef(val schema: String, val name: String)

/** Physical identifier values: SQL quotes decoded, unquoted ASCII folded by the parser.
 * Schema may be absent until PostgreSQL search_path resolution has been applied. */
data class TableName(val name: String, val schema: String? = null)

data class ColumnRef(val table: TableRef, val name: String)

enum class TableKind { ORDINARY, VIEW, PARTITIONED, FOREIGN, OTHER }

data class CatalogSnapshot(
    val tables: List<CatalogTable>,
    /** Effective PostgreSQL path, including implicit pg_catalog/temporary schemas. */
    val searchPath: List<String> = emptyList(),
    val unresolvedTables: List<TableName> = emptyList(),
    val warnings: List<AnalysisWarning> = emptyList(),
    val maxIdentifierLength: Int = 63,
    /** Server facts for SQL type/operator resolution; absent in non-SQL fixtures. */
    val semantics: CatalogSemantics? = null,
)

/** All relevant columns remain available even when candidate generation later shortlists them. */
data class CatalogTable(
    val ref: TableRef,
    val columns: List<CatalogColumn>,
    val estimatedRows: Double? = null,
    val statisticsAvailable: Boolean = false,
    val existingIndexes: List<ExistingIndex> = emptyList(),
    val kind: TableKind = TableKind.ORDINARY,
)

data class CatalogColumn(
    val name: String,
    val typeName: String,
    val nullable: Boolean = true,
    val collation: String? = null,
    val defaultOperatorClass: String? = null,
    val isBuiltinType: Boolean = true,
    val typeOid: Long? = null,
)

data class CatalogType(
    val oid: Long,
    val schema: String,
    val name: String,
    val category: String,
    val preferred: Boolean = false,
    val isBuiltin: Boolean = true,
    val kind: String = "b",
    val elementTypeOid: Long = 0,
    /** Immediate base type of a domain (pg_type.typbasetype); zero for other types. */
    val baseTypeOid: Long = 0,
)

/** Only binary comparison operators visible according to PostgreSQL are included. */
data class CatalogOperator(val oid: Long, val schema: String, val name: String,
    val leftTypeOid: Long, val rightTypeOid: Long, val isBuiltin: Boolean = true)

data class CatalogImplicitCast(val sourceTypeOid: Long, val targetTypeOid: Long)

data class CatalogSemantics(val types: List<CatalogType>, val operators: List<CatalogOperator>,
    val implicitCasts: List<CatalogImplicitCast> = emptyList())

/** PostgreSQL clips identifiers by bytes, never in the middle of a UTF-8 character. */
fun truncatePostgresIdentifier(value: String, maxBytes: Int): String {
    require(maxBytes > 0)
    var bytes = 0
    var end = 0
    while (end < value.length) {
        val codePoint = value.codePointAt(end)
        val width = when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }
        if (bytes + width > maxBytes) break
        bytes += width
        end += Character.charCount(codePoint)
    }
    return value.substring(0, end)
}

enum class SortDirection { ASC, DESC }
enum class NullsOrder { FIRST, LAST }

data class IndexKey(
    val column: ColumnRef,
    val direction: SortDirection = SortDirection.ASC,
    val nulls: NullsOrder = if (direction == SortDirection.DESC) NullsOrder.FIRST else NullsOrder.LAST,
    val collation: String? = null,
    val operatorClass: String? = null,
)

/** Candidate definitions are nonunique B-trees with one or two physical columns. */
data class IndexDefinition(
    val table: TableRef,
    val keys: List<IndexKey>,
    val method: String = "btree",
    val unique: Boolean = false,
)

/** Nonordinary indexes stay in the catalog and must not be mistaken for exact candidate duplicates. */
data class ExistingIndex(
    val name: String,
    val table: TableRef,
    val method: String,
    val keys: List<IndexKey>,
    val valid: Boolean = true,
    val ready: Boolean = true,
    val accessible: Boolean = true,
    val unique: Boolean = false,
    val predicate: String? = null,
    val expressions: List<String> = emptyList(),
    val includedColumns: List<ColumnRef> = emptyList(),
)
