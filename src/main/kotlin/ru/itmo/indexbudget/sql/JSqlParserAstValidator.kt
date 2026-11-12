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
import net.sf.jsqlparser.expression.operators.relational.SupportsOldOracleJoinSyntax
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
import ru.itmo.indexbudget.domain.ConditionLimits
import ru.itmo.indexbudget.domain.StatementKind
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.update.Update
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.select.SelectItem

internal class JSqlParserAstValidator(
    private val queryId: String,
    private val context: RunContext,
    private val restoreIdentifier: (String) -> String = { it },
) {
    private var allowTemplateParameters = false
    private var conditionNodes = 0

    fun validateStatement(statement: Statement, template: Boolean = false): ValidatedSql {
        allowTemplateParameters = template
        try {
            if (statement is PlainSelect) return ValidatedSql(statement, statement, validate(statement))
            val target: Table
            val where: Expression?
            val assignments: List<Pair<Column, Expression>>
            val kind: StatementKind
            when (statement) {
                is Update -> {
                    if (!statement.withItemsList.isNullOrEmpty() || statement.fromItem != null || !statement.joins.isNullOrEmpty() ||
                        !statement.startJoins.isNullOrEmpty() || statement.returningClause != null || statement.outputClause != null ||
                        statement.select != null || statement.isUseSelect || statement.isFromBeforeSet ||
                        !statement.orderByElements.isNullOrEmpty() || statement.limit != null || statement.option != null ||
                        statement.forPortionClause != null || statement.preferringClause != null || statement.oracleHint != null ||
                        statement.modifierPriority != null || statement.isModifierIgnore)
                        fail(ErrorCode.UNSUPPORTED_SQL, "Only one-table literal SET UPDATE with WHERE is supported.")
                    val sets = statement.updateSets.orEmpty()
                    if (sets.isEmpty() || sets.size > 256) fail(ErrorCode.INPUT_LIMIT_EXCEEDED, "UPDATE requires 1–256 SET assignments.")
                    assignments = sets.map {
                        context.checkDeadline()
                        // Preserve tuple shape even for a single target: SET (column) = (value).
                        if (it.columns is ParenthesedExpressionList<*> || it.columns.size != 1 || it.values.size != 1)
                            fail(ErrorCode.UNSUPPORTED_SQL, "Tuple SET assignments are unsupported.")
                        it.columns.single() to it.values.single()
                    }
                    target = statement.table
                    where = statement.where
                    kind = StatementKind.UPDATE
                }
                is Delete -> {
                    if (!statement.withItemsList.isNullOrEmpty() || !statement.tables.isNullOrEmpty() ||
                        !statement.usingFromItemList.isNullOrEmpty() || !statement.joins.isNullOrEmpty() || !statement.isHasFrom ||
                        statement.returningClause != null || statement.outputClause != null || !statement.orderByElements.isNullOrEmpty() ||
                        statement.limit != null || statement.option != null || statement.forPortionClause != null ||
                        statement.preferringClause != null || statement.oracleHint != null || statement.modifierPriority != null ||
                        statement.isModifierIgnore || statement.isModifierQuick)
                        fail(ErrorCode.UNSUPPORTED_SQL, "Only DELETE FROM one table with WHERE is supported.")
                    target = statement.table
                    where = statement.where
                    assignments = emptyList()
                    kind = StatementKind.DELETE
                }
                else -> fail(ErrorCode.UNSUPPORTED_SQL, "Only SELECT, restricted UPDATE and DELETE are supported.")
            }
            if (where == null) fail(ErrorCode.UNSUPPORTED_SQL, "UPDATE/DELETE require WHERE.")
            val scope = PlainSelect().apply {
                fromItem = target
                this.where = where
                selectItems = listOf(SelectItem(AllColumns()))
            }
            val tables = validate(scope)
            for ((column, value) in assignments) {
                validateColumn(column)
                if (column.table != null) fail(ErrorCode.UNSUPPORTED_SQL, "SET target columns must be unqualified.")
                validateScalarLiteral(value)
            }
            return ValidatedSql(statement, scope, tables, kind, assignments)
        } finally { allowTemplateParameters = false }
    }

    /** Import-only validation before literal binding; ordinary SQL validation remains strict. */
    fun validateTemplate(select: PlainSelect): List<Table> {
        allowTemplateParameters = true
        return try { validate(select) } finally { allowTemplateParameters = false }
    }

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
            item.alias?.let { it.name = restoreIdentifier(it.name) }
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
                "WHERE and JOIN exceed 256 complexity units: comparison/null-test = 1, IN = item count, BETWEEN = 2.",
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
            val parameter = allowTemplateParameters && limit.rowCount is net.sf.jsqlparser.expression.JdbcParameter
            if ((!parameter && (value == null || value < 0)) || limit.offset != null ||
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
            if (table.nameParts.firstOrNull().isNullOrBlank() || table.catalogName != null || table.databaseName != null) {
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
        val pending = ArrayDeque<Pair<Expression, Int>>()
        pending.addLast(expression to 1)
        var complexity = 0
        while (pending.isNotEmpty()) {
            context.checkDeadline()
            val (current, depth) = pending.removeLast()
            if (++conditionNodes > ConditionLimits.MAX_NODES || depth > ConditionLimits.MAX_DEPTH) {
                fail(ErrorCode.INPUT_LIMIT_EXCEEDED, "Condition tree exceeds 1024 nodes or depth 512.")
            }
            when (current) {
                is ParenthesedExpressionList<*> -> {
                    if (current.size != 1) {
                        fail(ErrorCode.UNSUPPORTED_SQL, "Parenthesized conditions must contain one expression.")
                    }
                    pending.addLast(current.single() to depth + 1)
                }
                is AndExpression, is OrExpression -> {
                    pending.addLast(current.rightExpression to depth + 1)
                    pending.addLast(current.leftExpression to depth + 1)
                }
                else -> complexity += validatePredicate(current, joinCondition)
            }
            if (complexity > MAX_COMPARISONS) fail(ErrorCode.INPUT_LIMIT_EXCEEDED, "WHERE/JOIN complexity exceeds 256 units.")
        }
        return complexity
    }

    private fun validatePredicate(expression: Expression, joinCondition: Boolean): Int =
        when (expression) {
            is InExpression -> {
                requireScalarWhere(joinCondition, expression.leftExpression, expression)
                if (expression.isNot || expression.isGlobal) {
                    fail(ErrorCode.UNSUPPORTED_SQL, "Only positive IN with a literal list is supported.")
                }
                val values = expression.rightExpression as? ParenthesedExpressionList<*>
                    ?: fail(ErrorCode.UNSUPPORTED_SQL, "IN requires a parenthesized literal list, not a subquery.")
                if (values.isEmpty()) fail(ErrorCode.UNSUPPORTED_SQL, "IN requires at least one literal.")
                if (values.size > MAX_COMPARISONS) fail(ErrorCode.INPUT_LIMIT_EXCEEDED, "IN supports at most 256 literals.")
                for (value in values) validateScalarLiteral(value)
                values.size
            }
            is Between -> {
                requireScalarWhere(joinCondition, expression.leftExpression, expression)
                if (expression.isNot || expression.isUsingSymmetric || expression.isUsingAsymmetric) {
                    fail(ErrorCode.UNSUPPORTED_SQL, "Only plain positive BETWEEN is supported.")
                }
                validateScalarLiteral(expression.betweenExpressionStart)
                validateScalarLiteral(expression.betweenExpressionEnd)
                2
            }
            is IsNullExpression -> {
                requireScalarWhere(joinCondition, expression.leftExpression, expression)
                if (expression.isUseIsNull || expression.isUseNotNull) {
                    fail(ErrorCode.UNSUPPORTED_SQL, "Use IS NULL or IS NOT NULL, not dialect null-test shortcuts.")
                }
                1
            }
            is EqualsTo, is MinorThan, is MinorThanEquals, is GreaterThan, is GreaterThanEquals -> {
                val comparison = expression
                val oracle = comparison as SupportsOldOracleJoinSyntax
                if (oracle.oldOracleJoinSyntax != SupportsOldOracleJoinSyntax.NO_ORACLE_JOIN ||
                    oracle.oraclePriorPosition != SupportsOldOracleJoinSyntax.NO_ORACLE_PRIOR
                ) {
                    fail(ErrorCode.UNSUPPORTED_SQL, "Oracle comparison modifiers are not supported.")
                }
                if (joinCondition && comparison !is EqualsTo) {
                    fail(ErrorCode.UNSUPPORTED_SQL, "JOIN conditions support only column equality.")
                }
                validateComparison(comparison.leftExpression, comparison.rightExpression, joinCondition)
                1
            }
            else -> fail(ErrorCode.UNSUPPORTED_SQL, "Only AND/OR-connected supported predicates are allowed.")
        }

    private fun requireScalarWhere(join: Boolean, left: Expression, modifiers: SupportsOldOracleJoinSyntax) {
        if (join || left !is Column) {
            fail(ErrorCode.UNSUPPORTED_SQL, "IN, BETWEEN and null tests require a column in WHERE; JOIN remains column equality.")
        }
        if (modifiers.oldOracleJoinSyntax != SupportsOldOracleJoinSyntax.NO_ORACLE_JOIN ||
            modifiers.oraclePriorPosition != SupportsOldOracleJoinSyntax.NO_ORACLE_PRIOR ||
            left.oldOracleJoinSyntax != SupportsOldOracleJoinSyntax.NO_ORACLE_JOIN) {
            fail(ErrorCode.UNSUPPORTED_SQL, "Oracle predicate modifiers are not supported.")
        }
        validateColumn(left)
    }

    private fun validateScalarLiteral(value: Expression) {
        context.checkDeadline()
        if (!isSupportedLiteral(value)) fail(ErrorCode.UNSUPPORTED_SQL, "Predicate values must be supported scalar literals.")
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
            is net.sf.jsqlparser.expression.JdbcParameter -> allowTemplateParameters
            is BooleanValue, is DateTimeLiteralExpression, is DateValue, is DoubleValue, is HexValue,
            is LongValue, is NullValue, is StringValue, is TimestampValue, is TimeValue,
            -> true
            is SignedExpression -> expression.sign in setOf('+', '-') &&
                (expression.expression is LongValue || expression.expression is DoubleValue)
            is CastExpression -> isSupportedCast(expression)
            else -> false
        }
    }

    private fun isSupportedCast(expression: CastExpression): Boolean {
        expression.colDataType?.let { it.dataType = restoreIdentifier(it.dataType) }
        return (expression.keyword == null || expression.keyword.equals("CAST", ignoreCase = true)) &&
            expression.format == null && expression.columnDefinitions.isNullOrEmpty() &&
            isSupportedLiteral(expression.leftExpression)
    }

    private fun validateColumn(column: Column) {
        context.checkDeadline()
        if (column.arrayConstructor != null) fail(ErrorCode.UNSUPPORTED_SQL, "Array expressions are not supported.")
        if (column.oldOracleJoinSyntax != SupportsOldOracleJoinSyntax.NO_ORACLE_JOIN || column.tableDelimiter != ".") {
            fail(ErrorCode.UNSUPPORTED_SQL, "Column modifiers and non-dot qualification are not supported.")
        }
        val parts = identifierParts(column.astNode)
        if (parts.size > 3) fail(ErrorCode.UNSUPPORTED_SQL, "Cross-database column references are not supported.")
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
        fromItem.alias?.let { it.name = restoreIdentifier(it.name) }
        if (fromItem.nameParts.firstOrNull().isNullOrBlank() || fromItem.catalogName != null || fromItem.databaseName != null ||
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

    private fun identifierParts(node: Node?): List<String> {
        var token = node?.jjtGetFirstToken() ?: return emptyList()
        val parts = mutableListOf(token.image)
        while (token !== node.jjtGetLastToken()) {
            context.checkDeadline()
            val separator = token.next ?: break
            // Any non-dot separator (.., ..., :, @ or an unknown token) is rejected before
            // this list is ever used to rewrite name parts in the AST.
            // Known non-dot RelObjectNames separators are rejected before this list is used to
            // rewrite name parts; any other non-dot token (e.g. AS after an aliased expression)
            // simply ends the identifier, so the loop stops without truncating a supported name.
            if (separator.image in setOf("..", "...", ":", "@")) {
                fail(ErrorCode.UNSUPPORTED_SQL, "Only dot-separated PostgreSQL identifiers are supported.")
            }
            if (separator.image != ".") break
            val nextPart = separator.next ?: break
            if (nextPart.image == "*") break
            if (nextPart.image in setOf(".", "..", "...", ":", "@")) {
                fail(ErrorCode.UNSUPPORTED_SQL, "Empty identifier parts are not supported.")
            }
            parts += nextPart.image
            token = nextPart
        }
        if (parts.any { !it.startsWith('"') && '@' in it }) {
            fail(ErrorCode.UNSUPPORTED_SQL, "Database links are not supported.")
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
        const val MAX_COMPARISONS = ConditionLimits.MAX_COMPLEXITY
        const val MAX_TABLES = 3
        const val MAX_ORDER_BY_COLUMNS = 2
    }
}
