package ru.itmo.indexbudget.domain

enum class PredicateSource { WHERE, JOIN }
enum class StatementKind { SELECT, UPDATE, DELETE }

/** Leaf indices refer to the complete, ordered ResolvedQuery.predicates vector. No DNF expansion. */
sealed interface ResolvedCondition {
    data class Predicate(val index: Int) : ResolvedCondition
    data class And(val left: ResolvedCondition, val right: ResolvedCondition) : ResolvedCondition
    data class Or(val left: ResolvedCondition, val right: ResolvedCondition) : ResolvedCondition
}

object ConditionLimits {
    const val MAX_COMPLEXITY = 256
    const val MAX_NODES = 1024
    const val MAX_DEPTH = 512
}
enum class ComparisonOperator {
    EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL,
    IN, BETWEEN, IS_NULL, IS_NOT_NULL;

    val equalityFeature: Boolean get() = this == EQUAL || this == IN || this == IS_NULL
}

/** Binary literals and new scalar forms have no [rightColumn]; exact values remain in original SQL. */
data class ResolvedPredicate(
    val leftColumn: ColumnRef,
    val operator: ComparisonOperator,
    val source: PredicateSource,
    val rightColumn: ColumnRef? = null,
    /** New scalar forms keep one feature, not an expansion into fictitious AND comparisons. */
    val literalCount: Int? = null,
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
    /** Null means the legacy conjunctive vector (or no conditions). Production preserves the tree. */
    val condition: ResolvedCondition? = null,
    val statementKind: StatementKind = StatementKind.SELECT,
    val modifiedColumns: Set<ColumnRef> = emptySet(),
)

data class ResolvedWorkload(
    val input: WorkloadInput,
    val queries: List<ResolvedQuery>,
)
