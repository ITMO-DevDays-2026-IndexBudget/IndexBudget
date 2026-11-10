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
)

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
