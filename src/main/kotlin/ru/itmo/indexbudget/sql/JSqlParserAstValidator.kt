package ru.itmo.indexbudget.sql

import net.sf.jsqlparser.expression.BooleanValue
import net.sf.jsqlparser.expression.CastExpression
import net.sf.jsqlparser.expression.DateTimeLiteralExpression
import net.sf.jsqlparser.expression.DateValue
import net.sf.jsqlparser.expression.DoubleValue
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.HexValue
import net.sf.jsqlparser.expression.LongValue
import net.sf.jsqlparser.expression.NullValue
import net.sf.jsqlparser.expression.SignedExpression
import net.sf.jsqlparser.expression.StringValue
import net.sf.jsqlparser.expression.TimeValue
import net.sf.jsqlparser.expression.TimestampValue
import net.sf.jsqlparser.expression.operators.conditional.AndExpression
import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.expression.operators.relational.GreaterThan
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals
import net.sf.jsqlparser.expression.operators.relational.MinorThan
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList
import net.sf.jsqlparser.parser.Node
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.AllColumns
import net.sf.jsqlparser.statement.select.AllTableColumns
import net.sf.jsqlparser.statement.select.FromItem
import net.sf.jsqlparser.statement.select.Join
import net.sf.jsqlparser.statement.select.PlainSelect
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.RunContext

internal class JSqlParserAstValidator(private val queryId: String, private val context: RunContext) {
    fun validate(select: PlainSelect): List<Table> {
        validateStructureClauses(select)
        validateRowSelectionClauses(select)
        validateLockingClauses(select)
        validateDialectSources(select)
        validateDialectHints(select)
        validateDialectOutput(select)
        val tables = validateTables(select)
        validateProjection(select)
        validateConditions(select)
        validateOrderBy(select)
        validateLimit(select)
        return tables
    }

    private fun validateTables(select: PlainSelect): List<Table> {
        context.checkDeadline()
        val tables = buildList {
            add(requireSupportedTable(select.fromItem))
            select.joins.orEmpty().forEach { add(requireSupportedTable(it.fromItem)) }
        }
        if (tables.size > MAX_TABLES) {
            fail(ErrorCode.UNSUPPORTED_SQL, "SELECT must reference between one and three ordinary tables.")
        }
        return tables
    }

    private fun validateProjection(select: PlainSelect) {
        val selectItems = select.selectItems.orEmpty()
        if (selectItems.isEmpty()) fail(ErrorCode.UNSUPPORTED_SQL, "SELECT list must not be empty.")
        selectItems.forEach { item ->
            context.checkDeadline()
            when (val expression = item.expression) {
                is Column -> validateColumn(expression)
                is AllColumns -> validateAllColumns(expression)
                else -> fail(ErrorCode.UNSUPPORTED_SQL, "SELECT supports only columns and * projections.")
            }
        }
    }

    private fun validateConditions(select: PlainSelect) {
        var comparisonCount = 0
        select.where?.let {
            comparisonCount += validateCondition(it, joinCondition = false)
        }
        select.joins.orEmpty().forEach { join ->
            comparisonCount += validateJoin(join)
        }
        if (comparisonCount > MAX_COMPARISONS) {
            fail(
                ErrorCode.INPUT_LIMIT_EXCEEDED,
                "WHERE and JOIN conditions must contain at most 256 comparisons.",
            )
        }
    }

    private fun validateOrderBy(select: PlainSelect) {
        val orderBy = select.orderByElements.orEmpty()
        if (orderBy.size > MAX_ORDER_BY_COLUMNS) {
            fail(ErrorCode.UNSUPPORTED_SQL, "ORDER BY supports at most two columns.")
        }
        orderBy.forEach { element ->
            context.checkDeadline()
            val column = element.expression
            if (column !is Column || element.nullOrdering != null || element.withFill != null ||
                element.isMysqlWithRollup
            ) {
                fail(ErrorCode.UNSUPPORTED_SQL, "ORDER BY supports only columns with ASC or DESC.")
            }
            validateColumn(column)
        }
    }

    private fun validateLimit(select: PlainSelect) {
        select.limit?.let { limit ->
            context.checkDeadline()
            val value = (limit.rowCount as? LongValue)?.stringValue?.toLongOrNull()
            if (value == null || value < 0 || limit.offset != null ||
                limit.byExpressions?.isNotEmpty() == true
            ) {
                fail(ErrorCode.UNSUPPORTED_SQL, "LIMIT must be a non-negative integer literal within the bigint range.")
            }
        }
    }

    private fun validateAllColumns(expression: AllColumns) {
        if (expression.javaClass != AllColumns::class.java && expression.javaClass != AllTableColumns::class.java) {
            fail(ErrorCode.UNSUPPORTED_SQL, "SELECT supports only * and table.* wildcards.")
        }
        if (expression.exceptColumns?.isNotEmpty() == true || expression.replaceExpressions?.isNotEmpty() == true) {
            fail(ErrorCode.UNSUPPORTED_SQL, "SELECT * modifiers are not supported.")
        }
        if (expression is AllTableColumns) {
            val table = expression.table
            restoreTableName(table)
            if (table.name.isNullOrBlank() || table.catalogName != null || table.databaseName != null) {
                fail(ErrorCode.UNSUPPORTED_SQL, "Qualified * must use a table name or alias.")
            }
        }
    }

    private fun validateJoin(join: Join): Int {
        context.checkDeadline()
        val isInner = join.isInner || join.isInnerJoin
        val hasUnsupportedMode = !isInner || join.isSimple || join.isLeft || join.isRight || join.isFull ||
            join.isCross || join.isNatural || join.isOuter || join.isStraight || join.isApply || join.isSemi ||
            join.isArray || join.isAsOf || join.isWindowJoin || join.isGlobal || join.isAny || join.isAll ||
            join.isFetch || join.usingColumns?.isNotEmpty() == true || join.joinWindow != null || join.joinHint != null
        if (hasUnsupportedMode) {
            fail(ErrorCode.UNSUPPORTED_SQL, "Only INNER JOIN between ordinary tables is supported.")
        }

        val conditions = join.onExpressions.orEmpty()
        if (conditions.isEmpty()) {
            fail(ErrorCode.UNSUPPORTED_SQL, "INNER JOIN requires an ON equality between columns.")
        }
        return conditions.sumOf { validateCondition(it, joinCondition = true) }
    }

    private fun validateCondition(
        expression: Expression,
        joinCondition: Boolean,
    ): Int {
        context.checkDeadline()
        return when (expression) {
            is ParenthesedExpressionList<*> -> {
                if (expression.size != 1) {
                    fail(ErrorCode.UNSUPPORTED_SQL, "Parenthesized conditions must contain one expression.")
                }
                validateCondition(expression.single(), joinCondition)
            }
            is AndExpression -> validateCondition(expression.leftExpression, joinCondition) +
                validateCondition(expression.rightExpression, joinCondition)
            is EqualsTo, is MinorThan, is MinorThanEquals, is GreaterThan, is GreaterThanEquals -> {
                val comparison = expression
                if (joinCondition && comparison !is EqualsTo) {
                    fail(ErrorCode.UNSUPPORTED_SQL, "JOIN conditions support only column equality.")
                }
                validateComparison(comparison.leftExpression, comparison.rightExpression, joinCondition)
                1
            }
            else -> fail(ErrorCode.UNSUPPORTED_SQL, "Only AND-connected supported comparisons are allowed.")
        }
    }

    private fun validateComparison(
        left: Expression,
        right: Expression,
        joinCondition: Boolean,
    ) {
        val leftIsColumn = left is Column
        val rightIsColumn = right is Column
        if (joinCondition) {
            if (!leftIsColumn || !rightIsColumn) {
                fail(ErrorCode.UNSUPPORTED_SQL, "JOIN equality must compare two columns.")
            }
        } else {
            if (!leftIsColumn && !rightIsColumn) {
                fail(ErrorCode.UNSUPPORTED_SQL, "A comparison must reference at least one column.")
            }
        }
        validateOperand(left)
        validateOperand(right)
    }

    private fun validateOperand(expression: Expression) {
        if (expression is Column) {
            validateColumn(expression)
        } else if (!isSupportedLiteral(expression)) {
            fail(ErrorCode.UNSUPPORTED_SQL, "Comparison operands must be columns or supported literals.")
        }
    }

    private fun isSupportedLiteral(expression: Expression): Boolean {
        context.checkDeadline()
        return when (expression) {
            is BooleanValue, is DateTimeLiteralExpression, is DateValue, is DoubleValue, is HexValue,
            is LongValue, is NullValue, is StringValue, is TimestampValue, is TimeValue,
            -> true
            is SignedExpression -> expression.expression is LongValue || expression.expression is DoubleValue
            is CastExpression -> isSupportedCast(expression)
            else -> false
        }
    }

    private fun isSupportedCast(expression: CastExpression): Boolean =
        (expression.keyword == null || expression.keyword.equals("CAST", ignoreCase = true)) &&
            expression.format == null && expression.columnDefinitions.isNullOrEmpty() &&
            isSupportedLiteral(expression.leftExpression)

    private fun validateColumn(column: Column) {
        context.checkDeadline()
        if (column.arrayConstructor != null) fail(ErrorCode.UNSUPPORTED_SQL, "Array expressions are not supported.")
        val parts = identifierParts(column.astNode)
        if (parts.isNotEmpty()) {
            column.setName(parts.last(), false)
            column.table = if (parts.size == 1) null else Table().apply {
                nameParts = parts.dropLast(1).asReversed()
            }
        }
    }

    private fun requireSupportedTable(fromItem: FromItem?): Table {
        context.checkDeadline()
        if (fromItem !is Table) fail(ErrorCode.UNSUPPORTED_SQL, "FROM and JOIN support only ordinary tables.")
        restoreTableName(fromItem)
        if (fromItem.name.isNullOrBlank() || fromItem.catalogName != null || fromItem.databaseName != null ||
            fromItem.isTableVariable || fromItem.pivot != null || fromItem.unPivot != null || fromItem.indexHint != null ||
            fromItem.sqlServerHints != null || fromItem.sampleClause != null || fromItem.timeTravel != null ||
            fromItem.timeTravelStrAfterAlias != null
        ) {
            fail(ErrorCode.UNSUPPORTED_SQL, "Only ordinary schema-qualified or unqualified tables are supported.")
        }
        return fromItem
    }

    private fun restoreTableName(table: Table) {
        val parts = identifierParts(table.astNode)
        if (parts.isNotEmpty()) table.nameParts = parts.asReversed()
    }

    // JSqlParser 5.4 splits dots even inside quoted names. Restore the exact identifier tokens
    // in our AST instead of rewriting SQL or changing the JVM-wide SPLIT_NAMES_ON_DELIMITER property.
    private fun identifierParts(node: Node?): List<String> {
        var token = node?.jjtGetFirstToken() ?: return emptyList()
        val parts = mutableListOf(token.image)
        while (token !== node.jjtGetLastToken() && token.next?.image == ".") {
            context.checkDeadline()
            val nextPart = token.next?.next ?: break
            if (nextPart.image == "*") break
            parts += nextPart.image
            token = nextPart
        }
        return parts
    }

    private fun validateStructureClauses(select: PlainSelect) {
        context.checkDeadline()
        if (select.withItemsList?.isNotEmpty() == true || select.distinct != null || select.groupBy != null ||
            select.having != null || select.qualify != null || select.windowDefinitions?.isNotEmpty() == true ||
            select.intoTables?.isNotEmpty() == true || select.intoTempTable != null || select.alias != null
        ) {
            fail(ErrorCode.UNSUPPORTED_SQL, "SELECT contains an unsupported structural clause.")
        }
    }

    private fun validateRowSelectionClauses(select: PlainSelect) {
        if (select.offset != null || select.fetch != null || select.limitBy != null ||
            select.top != null || select.skip != null || select.first != null
        ) {
            fail(ErrorCode.UNSUPPORTED_SQL, "SELECT contains an unsupported row selection clause.")
        }
    }

    private fun validateLockingClauses(select: PlainSelect) {
        if (select.forClause != null || select.forMode != null || select.forUpdate != null ||
            select.forUpdateTable != null || select.forUpdateTables?.isNotEmpty() == true ||
            select.wait != null || select.isSkipLocked || select.isNoWait || select.isForUpdateBeforeOrderBy ||
            select.isolation != null
        ) {
            fail(ErrorCode.UNSUPPORTED_SQL, "SELECT locking clauses are not supported.")
        }
    }

    private fun validateDialectSources(select: PlainSelect) {
        if (select.oracleHierarchical != null || select.preWhere != null || select.lateralViews?.isNotEmpty() == true ||
            select.pivot != null || select.unPivot != null || select.sampleClause != null ||
            select.isUsingOnly || select.isUsingFinal || select.ksqlWindow != null
        ) {
            fail(ErrorCode.UNSUPPORTED_SQL, "SELECT contains an unsupported dialect-specific source clause.")
        }
    }

    private fun validateDialectHints(select: PlainSelect) {
        if (select.isOracleSiblings || select.option != null || select.settings?.isNotEmpty() == true ||
            select.optimizeFor != null || select.getMySqlHintStraightJoin() || select.getMySqlSqlCalcFoundRows() ||
            select.mySqlSqlCacheFlag != null || select.oracleHint != null || select.isUseWithNoLog ||
            select.preferringClause != null
        ) {
            fail(ErrorCode.UNSUPPORTED_SQL, "SELECT dialect-specific hints are not supported.")
        }
    }

    private fun validateDialectOutput(select: PlainSelect) {
        if (select.mySqlProcedureAnalyse != null || select.interpolate?.isNotEmpty() == true || select.isEmitChanges ||
            select.mySqlSelectIntoClause != null || select.bigQuerySelectQualifier != null ||
            select.forXmlPath != null || select.emitMode != PlainSelect.EmitMode.NONE
        ) {
            fail(ErrorCode.UNSUPPORTED_SQL, "SELECT contains an unsupported dialect-specific output clause.")
        }
    }

    private fun fail(code: ErrorCode, message: String): Nothing =
        throw AnalysisException(AnalysisError(code, message, queryId = queryId))

    private companion object {
        const val MAX_COMPARISONS = 256
        const val MAX_TABLES = 3
        const val MAX_ORDER_BY_COLUMNS = 2
    }
}
