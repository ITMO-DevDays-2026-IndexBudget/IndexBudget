package ru.itmo.indexbudget.domain

enum class PredicateSource { WHERE, JOIN }
enum class ComparisonOperator { EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL }

/** A null [rightColumn] denotes a literal; its exact text remains in the original SQL. */
data class ResolvedPredicate(
    val leftColumn: ColumnRef,
    val operator: ComparisonOperator,
    val source: PredicateSource,
    val rightColumn: ColumnRef? = null,
)

data class OrderByColumn(
    val column: ColumnRef,
    val direction: SortDirection = SortDirection.ASC,
    val nulls: NullsOrder = if (direction == SortDirection.DESC) NullsOrder.FIRST else NullsOrder.LAST,
)

/** All references are resolved before any generator shortlist. SQL is preserved verbatim. */
data class ResolvedQuery(
    val id: String,
    val sql: String,
    val tables: Set<TableRef>,
    val referencedColumns: Set<ColumnRef>,
    val predicates: List<ResolvedPredicate>,
    val orderBy: List<OrderByColumn>,
    val projectedColumns: Set<ColumnRef> = emptySet(),
    val selectsAllColumns: Boolean = false,
)

data class ResolvedWorkload(
    val input: WorkloadInput,
    val queries: List<ResolvedQuery>,
)
