package ru.itmo.indexbudget.sql

import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.operators.conditional.AndExpression
import net.sf.jsqlparser.expression.operators.conditional.OrExpression
import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.expression.operators.relational.GreaterThan
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals
import net.sf.jsqlparser.expression.operators.relational.MinorThan
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList
import net.sf.jsqlparser.expression.operators.relational.InExpression
import net.sf.jsqlparser.expression.operators.relational.Between
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.AllColumns
import net.sf.jsqlparser.statement.select.AllTableColumns
import net.sf.jsqlparser.statement.select.PlainSelect
import ru.itmo.indexbudget.domain.*

/** A fresh resolver per workload; no JDBC, candidate caps or parser objects in the result. */
internal class JSqlParserNameResolver(private val catalog: CatalogSnapshot, private val context: RunContext) {
    private val literalTypes = PostgresLiteralTypes(catalog, context)
    private val operators = PostgresOperatorResolver(catalog, context)

    private fun identifier(token: String): String =
        truncatePostgresIdentifier(decodePostgresIdentifier(token), catalog.maxIdentifierLength)

    fun resolve(parsed: JSqlParserParsedWorkload): ResolvedWorkload {
        val queries = parsed.input.queries.map { query ->
            try {
                context.checkDeadline()
                val select = parsed.queriesById[query.id]
                    ?: fail(ErrorCode.INVALID_INPUT, "Parsed workload does not cover every input query.")
                QueryScope(query, select).resolve()
            } catch (error: AnalysisException) {
                if (error.error.queryId != null) throw error
                throw AnalysisException(error.error.copy(queryId = query.id), error)
            }
        }
        context.checkDeadline()
        return ResolvedWorkload(parsed.input, queries)
    }

    private inner class QueryScope(private val query: QueryInput, private val select: PlainSelect) {
        private val bindings = mutableListOf<Binding>()
        private val referenced = linkedSetOf<ColumnRef>()
        private val projected = linkedSetOf<ColumnRef>()
        private val outputNames = linkedMapOf<String, MutableList<BoundColumn>>()
        private var selectsAll = false

        fun resolve(): ResolvedQuery {
            bindTables()
            resolveProjection()
            val predicates = mutableListOf<ResolvedPredicate>()
            val conditions = buildList {
                select.where?.let { add(resolveCondition(it, PredicateSource.WHERE, bindings, predicates)) }
                select.joins.orEmpty().forEachIndexed { index, join ->
                    // An ON clause cannot see tables introduced by a later JOIN.
                    join.onExpressions.orEmpty().forEach {
                        add(resolveCondition(it, PredicateSource.JOIN, bindings.take(index + 2), predicates))
                    }
                }
            }
            val order = select.orderByElements.orEmpty().map { element ->
                context.checkDeadline()
                val column = element.expression as Column
                val bound = if (column.table == null) {
                    val outputs = outputNames[identifier(column.columnName)].orEmpty().distinct()
                    if (outputs.size > 1) fail(ErrorCode.AMBIGUOUS_COLUMN, "ORDER BY output name is ambiguous.")
                    outputs.singleOrNull() ?: resolveColumn(column)
                } else {
                    resolveColumn(column)
                }
                referenced += bound.ref
                OrderByColumn(bound.ref, if (element.isAsc) SortDirection.ASC else SortDirection.DESC)
            }
            context.checkDeadline()
            return ResolvedQuery(query.id, query.sql, bindings.mapTo(linkedSetOf()) { it.table.ref },
                referenced, predicates, order, projected, selectsAll,
                conditions.reduceOrNull { left, right -> ResolvedCondition.And(left, right) })
        }

        private fun bindTables() {
            val tables = listOf(select.fromItem as Table) + select.joins.orEmpty().map { it.fromItem as Table }
            for (table in tables) {
                context.checkDeadline()
                if (!table.alias?.aliasColumns.isNullOrEmpty()) {
                    fail(ErrorCode.UNSUPPORTED_SQL, "Table column alias lists are not supported.")
                }
                val name = TableName(identifier(table.postgresName), table.schemaName?.let(::identifier))
                val physical = resolveTable(name)
                if (physical.kind != TableKind.ORDINARY) {
                    fail(ErrorCode.UNSUPPORTED_SQL, "Only ordinary PostgreSQL tables are supported.")
                }
                val alias = table.alias?.name?.let(::identifier)
                val binding = Binding(bindings.size, physical, alias)
                if (bindings.any { it.visibleName == binding.visibleName &&
                        (it.alias != null || alias != null || it.table.ref == physical.ref) }) {
                    fail(ErrorCode.UNSUPPORTED_SQL, "A table name or alias is specified more than once.")
                }
                bindings += binding
            }
        }

        private fun resolveProjection() {
            for (item in select.selectItems) {
                context.checkDeadline()
                val alias = item.alias?.name?.let(::identifier)
                val columns = when (val expression = item.expression) {
                    is Column -> listOf(resolveColumn(expression))
                    is AllTableColumns -> {
                        if (alias != null) fail(ErrorCode.UNSUPPORTED_SQL, "A wildcard cannot have an output alias.")
                        selectsAll = true
                        expand(listOf(resolveQualifier(expression.table, bindings)))
                    }
                    is AllColumns -> {
                        if (alias != null) fail(ErrorCode.UNSUPPORTED_SQL, "A wildcard cannot have an output alias.")
                        selectsAll = true
                        expand(bindings)
                    }
                    else -> fail(ErrorCode.UNSUPPORTED_SQL, "Unsupported projection expression.")
                }
                for (column in columns) {
                    projected += column.ref
                    referenced += column.ref
                    outputNames.getOrPut(alias ?: column.column.name) { mutableListOf() } += column
                }
            }
        }

        private fun expand(sources: List<Binding>): List<BoundColumn> = sources.flatMap { binding ->
            binding.table.columns.map { column -> checkedColumn(binding, column) }
        }

        private fun resolveCondition(
            expression: Expression,
            source: PredicateSource,
            visible: List<Binding>,
            result: MutableList<ResolvedPredicate>,
        ): ResolvedCondition {
            context.checkDeadline()
            when (expression) {
                is AndExpression -> {
                    return ResolvedCondition.And(resolveCondition(expression.leftExpression, source, visible, result),
                        resolveCondition(expression.rightExpression, source, visible, result))
                }
                is OrExpression -> {
                    return ResolvedCondition.Or(resolveCondition(expression.leftExpression, source, visible, result),
                        resolveCondition(expression.rightExpression, source, visible, result))
                }
                is ParenthesedExpressionList<*> -> return resolveCondition(expression.single(), source, visible, result)
                is InExpression -> {
                    val column = requireNotNull(resolveOperand(expression.leftExpression, visible))
                    val values = (expression.rightExpression as ParenthesedExpressionList<*>).map {
                        context.checkDeadline()
                        literalTypes.infer(it)
                    }
                    operators.requireBuiltinIn(literalTypes.columnType(column.column), values)
                    result += ResolvedPredicate(column.ref, ComparisonOperator.IN, source, literalCount = values.size)
                }
                is Between -> {
                    val column = requireNotNull(resolveOperand(expression.leftExpression, visible))
                    val type = literalTypes.columnType(column.column)
                    operators.requireBuiltin(">=", type, literalTypes.infer(expression.betweenExpressionStart))
                    operators.requireBuiltin("<=", type, literalTypes.infer(expression.betweenExpressionEnd))
                    result += ResolvedPredicate(column.ref, ComparisonOperator.BETWEEN, source, literalCount = 2)
                }
                is IsNullExpression -> {
                    val column = requireNotNull(resolveOperand(expression.leftExpression, visible))
                    val operator = if (expression.isNot) ComparisonOperator.IS_NOT_NULL else ComparisonOperator.IS_NULL
                    result += ResolvedPredicate(column.ref, operator, source, literalCount = 0)
                }
                is EqualsTo, is MinorThan, is MinorThanEquals, is GreaterThan, is GreaterThanEquals -> {
                    val left = expression.leftExpression
                    val right = expression.rightExpression
                    val operator = when (expression) {
                        is EqualsTo -> ComparisonOperator.EQUAL
                        is MinorThan -> ComparisonOperator.LESS_THAN
                        is MinorThanEquals -> ComparisonOperator.LESS_THAN_OR_EQUAL
                        is GreaterThan -> ComparisonOperator.GREATER_THAN
                        else -> ComparisonOperator.GREATER_THAN_OR_EQUAL
                    }
                    val leftColumn = resolveOperand(left, visible)
                    val rightColumn = resolveOperand(right, visible)
                    val leftType = leftColumn?.let { literalTypes.columnType(it.column) } ?: literalTypes.infer(left)
                    val rightType = rightColumn?.let { literalTypes.columnType(it.column) } ?: literalTypes.infer(right)
                    operators.requireBuiltin(expression.stringExpression, leftType, rightType)
                    if (leftColumn == null && rightColumn == null) {
                        fail(ErrorCode.UNSUPPORTED_SQL, "A comparison must reference a column.")
                    }
                    if (leftColumn != null) {
                        result += ResolvedPredicate(leftColumn.ref, operator, source, rightColumn?.ref)
                    } else {
                        result += ResolvedPredicate(requireNotNull(rightColumn).ref, reverse(operator), source)
                    }
                }
                else -> fail(ErrorCode.UNSUPPORTED_SQL, "Unsupported condition expression.")
            }
            return ResolvedCondition.Predicate(result.lastIndex)
        }

        private fun resolveOperand(expression: Expression, visible: List<Binding>): BoundColumn? {
            context.checkDeadline()
            if (expression is Column) {
                return resolveColumn(expression, visible).also { referenced += it.ref }
            }
            return null
        }

        private fun resolveColumn(column: Column, visible: List<Binding> = bindings): BoundColumn {
            context.checkDeadline()
            val name = identifier(column.columnName)
            val sources = column.table?.let { listOf(resolveQualifier(it, visible)) } ?: visible
            val matches = sources.flatMap { binding ->
                binding.table.columns.filter { it.name == name }.map { checkedColumn(binding, it) }
            }
            if (matches.isEmpty()) fail(ErrorCode.UNKNOWN_COLUMN, "Column does not exist in the visible query tables.")
            if (matches.size > 1) fail(ErrorCode.AMBIGUOUS_COLUMN, "Column reference is ambiguous.")
            return matches.single()
        }

        private fun resolveQualifier(table: Table, visible: List<Binding>): Binding {
            context.checkDeadline()
            if (table.databaseName != null || table.catalogName != null) {
                fail(ErrorCode.UNSUPPORTED_SQL, "Cross-database column references are not supported.")
            }
            val name = identifier(table.postgresName)
            val schema = table.schemaName?.let(::identifier)
            val matches = visible.filter {
                if (schema == null) it.visibleName == name
                else it.alias == null && it.table.ref == TableRef(schema, name)
            }
            if (matches.isEmpty()) fail(ErrorCode.UNKNOWN_TABLE, "Table or alias is not visible in this expression.")
            if (matches.size > 1) fail(ErrorCode.AMBIGUOUS_COLUMN, "Table qualifier is ambiguous; use a distinct alias.")
            return matches.single()
        }
    }

    private fun resolveTable(name: TableName): CatalogTable {
        context.checkDeadline()
        if (name in catalog.unresolvedTables) fail(ErrorCode.UNKNOWN_TABLE, "Workload table was not found in PostgreSQL.")
        val schema = name.schema ?: run {
            if (catalog.searchPath.isEmpty()) fail(ErrorCode.CATALOG_ERROR, "Catalog is missing the effective PostgreSQL search_path.")
            catalog.searchPath.firstOrNull { schema -> catalog.tables.any { it.ref == TableRef(schema, name.name) } }
                ?: fail(ErrorCode.UNKNOWN_TABLE, "Workload table was not found on the effective search_path.")
        }
        val matches = catalog.tables.filter { it.ref == TableRef(schema, name.name) }
        if (matches.size > 1) fail(ErrorCode.CATALOG_ERROR, "Catalog contains duplicate physical table identities.")
        return matches.singleOrNull() ?: fail(ErrorCode.UNKNOWN_TABLE, "Workload table was not found in the catalog.")
    }

    private fun checkedColumn(binding: Binding, column: CatalogColumn): BoundColumn {
        context.checkDeadline()
        if (!column.isBuiltinType) fail(ErrorCode.UNSUPPORTED_SQL, "User-defined column types are not supported.")
        return BoundColumn(binding, column)
    }

    private fun reverse(operator: ComparisonOperator): ComparisonOperator = when (operator) {
        ComparisonOperator.EQUAL -> operator
        ComparisonOperator.LESS_THAN -> ComparisonOperator.GREATER_THAN
        ComparisonOperator.LESS_THAN_OR_EQUAL -> ComparisonOperator.GREATER_THAN_OR_EQUAL
        ComparisonOperator.GREATER_THAN -> ComparisonOperator.LESS_THAN
        ComparisonOperator.GREATER_THAN_OR_EQUAL -> ComparisonOperator.LESS_THAN_OR_EQUAL
        else -> error("Only binary comparisons can be reversed")
    }

    private data class Binding(val position: Int, val table: CatalogTable, val alias: String?) {
        val visibleName: String get() = alias ?: table.ref.name
    }

    private data class BoundColumn(val binding: Binding, val column: CatalogColumn) {
        val ref: ColumnRef get() = ColumnRef(binding.table.ref, column.name)
    }

    private fun fail(code: ErrorCode, message: String): Nothing =
        throw AnalysisException(AnalysisError(code, message))

}
