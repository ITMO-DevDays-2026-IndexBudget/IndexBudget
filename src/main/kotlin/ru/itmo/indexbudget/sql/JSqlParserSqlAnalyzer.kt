package ru.itmo.indexbudget.sql

import net.sf.jsqlparser.JSQLParserException
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
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.AllColumns
import net.sf.jsqlparser.statement.select.AllTableColumns
import net.sf.jsqlparser.statement.select.Join
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import org.springframework.stereotype.Component
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.CatalogSnapshot
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.QueryInput
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.TableName
import ru.itmo.indexbudget.domain.WorkloadInput
import java.nio.charset.StandardCharsets

@Component
class JSqlParserSqlAnalyzer : SqlAnalyzer {
    override fun parse(input: WorkloadInput, context: RunContext): ParsedWorkload {
        val parsedQueries = linkedMapOf<String, PlainSelect>()
        val referencedTables = linkedSetOf<TableName>()

        input.queries.forEach { query ->
            try {
                context.checkDeadline()
                validateSqlSize(query)
                val select = parseSingleSelect(query, context)
                validateSelect(select, query.id, context)
                parsedQueries[query.id] = select
                collectTables(select).forEach { referencedTables += it.toTableName() }
            } catch (error: AnalysisException) {
                if (error.error.queryId != null) throw error
                throw AnalysisException(error.error.copy(queryId = query.id), error)
            }
        }

        return JSqlParserParsedWorkload(input, referencedTables, parsedQueries)
    }

    override fun resolve(
        parsed: ParsedWorkload,
        catalog: CatalogSnapshot,
        context: RunContext,
    ): ResolvedWorkload = throw AnalysisException(
        AnalysisError(ErrorCode.NOT_IMPLEMENTED, "SQL name resolution is not implemented."),
    )

    private fun validateSqlSize(query: QueryInput) {
        if (query.sql.toByteArray(StandardCharsets.UTF_8).size > MAX_SQL_BYTES) {
            fail(
                ErrorCode.INPUT_LIMIT_EXCEEDED,
                query.id,
                "SQL must not exceed 64 KiB UTF-8.",
            )
        }
    }

    private fun parseSingleSelect(query: QueryInput, context: RunContext): PlainSelect {
        context.checkDeadline()
        val statements = try {
            CCJSqlParserUtil.parseStatements(query.sql)
        } catch (_: JSQLParserException) {
            fail(ErrorCode.UNSUPPORTED_SQL, query.id, "SQL cannot be parsed as a supported SELECT.")
        } finally {
            context.checkDeadline()
        }

        if (statements.size != 1) {
            fail(ErrorCode.UNSUPPORTED_SQL, query.id, "Exactly one SELECT statement is required.")
        }
        val statement = statements[0]
        if (statement !is Select || statement !is PlainSelect) {
            fail(ErrorCode.UNSUPPORTED_SQL, query.id, "Only a plain SELECT statement is supported.")
        }
        return statement
    }

    private fun validateSelect(select: PlainSelect, queryId: String, context: RunContext) {
        context.checkDeadline()
        rejectUnsupportedSelectClauses(select, queryId)

        val tables = collectTables(select)
        if (tables.isEmpty() || tables.size > MAX_TABLES) {
            fail(ErrorCode.UNSUPPORTED_SQL, queryId, "SELECT must reference between one and three ordinary tables.")
        }
        tables.forEach { validateTable(it, queryId, context) }

        val selectItems = select.selectItems.orEmpty()
        if (selectItems.isEmpty()) fail(ErrorCode.UNSUPPORTED_SQL, queryId, "SELECT list must not be empty.")
        selectItems.forEach { item ->
            context.checkDeadline()
            when (item.expression) {
                is Column -> Unit
                is AllColumns -> validateAllColumns(item.expression as AllColumns, queryId)
                else -> fail(ErrorCode.UNSUPPORTED_SQL, queryId, "SELECT supports only columns and * projections.")
            }
        }

        var comparisonCount = 0
        select.where?.let {
            comparisonCount += validateCondition(it, queryId, context, joinCondition = false)
        }
        select.joins.orEmpty().forEach { join ->
            comparisonCount += validateJoin(join, queryId, context)
        }
        if (comparisonCount > MAX_COMPARISONS) {
            fail(
                ErrorCode.INPUT_LIMIT_EXCEEDED,
                queryId,
                "WHERE and JOIN conditions must contain at most 256 comparisons.",
            )
        }

        val orderBy = select.orderByElements.orEmpty()
        if (orderBy.size > MAX_ORDER_BY_COLUMNS) {
            fail(ErrorCode.UNSUPPORTED_SQL, queryId, "ORDER BY supports at most two columns.")
        }
        orderBy.forEach { element ->
            context.checkDeadline()
            if (element.expression !is Column || element.nullOrdering != null || element.withFill != null ||
                element.isMysqlWithRollup
            ) {
                fail(ErrorCode.UNSUPPORTED_SQL, queryId, "ORDER BY supports only columns with ASC or DESC.")
            }
        }

        select.limit?.let { limit ->
            context.checkDeadline()
            val rowCount = limit.rowCount
            if (rowCount !is LongValue || rowCount.value < 0 || limit.offset != null ||
                limit.byExpressions?.isNotEmpty() == true
            ) {
                fail(ErrorCode.UNSUPPORTED_SQL, queryId, "LIMIT must be a non-negative integer literal.")
            }
        }
    }

    private fun validateAllColumns(expression: AllColumns, queryId: String) {
        if (expression.exceptColumns?.isNotEmpty() == true || expression.replaceExpressions?.isNotEmpty() == true) {
            fail(ErrorCode.UNSUPPORTED_SQL, queryId, "SELECT * modifiers are not supported.")
        }
        if (expression is AllTableColumns) {
            val table = expression.table
            if (table.name.isNullOrBlank() || table.catalogName != null || table.databaseName != null) {
                fail(ErrorCode.UNSUPPORTED_SQL, queryId, "Qualified * must use a table name or alias.")
            }
        }
    }

    private fun rejectUnsupportedSelectClauses(select: PlainSelect, queryId: String) {
        val unsupported = select.withItemsList?.isNotEmpty() == true ||
            select.distinct != null || select.groupBy != null || select.having != null || select.qualify != null ||
            select.intoTables?.isNotEmpty() == true || select.intoTempTable != null ||
            select.offset != null || select.fetch != null || select.limitBy != null ||
            select.top != null || select.skip != null || select.first != null ||
            select.oracleHierarchical != null || select.windowDefinitions?.isNotEmpty() == true ||
            select.preWhere != null || select.lateralViews?.isNotEmpty() == true ||
            select.forClause != null || select.forMode != null || select.forUpdate != null ||
            select.forUpdateTable != null || select.forUpdateTables?.isNotEmpty() == true ||
            select.wait != null || select.isSkipLocked || select.isNoWait || select.isOracleSiblings ||
            select.isForUpdateBeforeOrderBy || select.option != null || select.isolation != null ||
            select.pivot != null || select.unPivot != null || select.sampleClause != null ||
            select.settings?.isNotEmpty() == true || select.optimizeFor != null ||
            select.mySqlProcedureAnalyse != null || select.interpolate?.isNotEmpty() == true ||
            select.getMySqlHintStraightJoin() || select.getMySqlSqlCalcFoundRows() ||
            select.mySqlSqlCacheFlag != null || select.oracleHint != null || select.isEmitChanges ||
            select.isUsingOnly || select.isUsingFinal || select.isUseWithNoLog ||
            select.mySqlSelectIntoClause != null || select.bigQuerySelectQualifier != null ||
            select.preferringClause != null || select.forXmlPath != null || select.ksqlWindow != null ||
            select.emitMode != PlainSelect.EmitMode.NONE || select.alias != null

        if (unsupported) {
            fail(ErrorCode.UNSUPPORTED_SQL, queryId, "SELECT contains an unsupported clause.")
        }
    }

    private fun validateJoin(join: Join, queryId: String, context: RunContext): Int {
        context.checkDeadline()
        val isInner = join.isInner || join.isInnerJoin
        val hasUnsupportedMode = !isInner || join.isSimple || join.isLeft || join.isRight || join.isFull ||
            join.isCross || join.isNatural || join.isOuter || join.isStraight || join.isApply || join.isSemi ||
            join.isArray || join.isAsOf || join.isWindowJoin || join.isGlobal || join.isAny || join.isAll ||
            join.isFetch || join.usingColumns?.isNotEmpty() == true || join.joinWindow != null || join.joinHint != null
        if (hasUnsupportedMode || join.fromItem !is Table) {
            fail(ErrorCode.UNSUPPORTED_SQL, queryId, "Only INNER JOIN between ordinary tables is supported.")
        }

        val conditions = join.onExpressions.orEmpty()
        if (conditions.isEmpty()) {
            fail(ErrorCode.UNSUPPORTED_SQL, queryId, "INNER JOIN requires an ON equality between columns.")
        }
        return conditions.sumOf { validateCondition(it, queryId, context, joinCondition = true) }
    }

    private fun validateCondition(
        expression: Expression,
        queryId: String,
        context: RunContext,
        joinCondition: Boolean,
    ): Int {
        context.checkDeadline()
        return when (expression) {
            is ParenthesedExpressionList<*> -> {
                if (expression.size != 1) {
                    fail(ErrorCode.UNSUPPORTED_SQL, queryId, "Parenthesized conditions must contain one expression.")
                }
                validateCondition(expression.single(), queryId, context, joinCondition)
            }
            is AndExpression -> validateCondition(expression.leftExpression, queryId, context, joinCondition) +
                validateCondition(expression.rightExpression, queryId, context, joinCondition)
            is EqualsTo -> validateComparison(expression.leftExpression, expression.rightExpression, queryId, joinCondition)
            is MinorThan -> validateOrderedComparison(expression.leftExpression, expression.rightExpression, queryId, joinCondition)
            is MinorThanEquals -> validateOrderedComparison(expression.leftExpression, expression.rightExpression, queryId, joinCondition)
            is GreaterThan -> validateOrderedComparison(expression.leftExpression, expression.rightExpression, queryId, joinCondition)
            is GreaterThanEquals -> validateOrderedComparison(expression.leftExpression, expression.rightExpression, queryId, joinCondition)
            else -> fail(ErrorCode.UNSUPPORTED_SQL, queryId, "Only AND-connected supported comparisons are allowed.")
        }
    }

    private fun validateOrderedComparison(
        left: Expression,
        right: Expression,
        queryId: String,
        joinCondition: Boolean,
    ): Int {
        if (joinCondition) {
            fail(ErrorCode.UNSUPPORTED_SQL, queryId, "JOIN conditions support only column equality.")
        }
        return validateComparison(left, right, queryId, joinCondition = false)
    }

    private fun validateComparison(
        left: Expression,
        right: Expression,
        queryId: String,
        joinCondition: Boolean,
    ): Int {
        val leftIsColumn = left is Column
        val rightIsColumn = right is Column
        if (joinCondition) {
            if (!leftIsColumn || !rightIsColumn) {
                fail(ErrorCode.UNSUPPORTED_SQL, queryId, "JOIN equality must compare two columns.")
            }
        } else {
            if (!leftIsColumn && !rightIsColumn) {
                fail(ErrorCode.UNSUPPORTED_SQL, queryId, "A comparison must reference at least one column.")
            }
            if (!isSupportedOperand(left) || !isSupportedOperand(right)) {
                fail(ErrorCode.UNSUPPORTED_SQL, queryId, "Comparison operands must be columns or supported literals.")
            }
        }
        return 1
    }

    private fun isSupportedOperand(expression: Expression): Boolean = when (expression) {
        is Column,
        is BooleanValue,
        is DateTimeLiteralExpression,
        is DateValue,
        is DoubleValue,
        is HexValue,
        is LongValue,
        is NullValue,
        is StringValue,
        is TimestampValue,
        is TimeValue,
        -> true
        is SignedExpression -> expression.expression is LongValue || expression.expression is DoubleValue
        is CastExpression -> isSupportedLiteral(expression.leftExpression)
        else -> false
    }

    private fun isSupportedLiteral(expression: Expression): Boolean =
        expression !is Column && isSupportedOperand(expression)

    private fun collectTables(select: PlainSelect): List<Table> = buildList {
        (select.fromItem as? Table)?.let(::add)
        select.joins.orEmpty().mapNotNullTo(this) { it.fromItem as? Table }
    }

    private fun validateTable(table: Table, queryId: String, context: RunContext) {
        context.checkDeadline()
        if (table.name.isNullOrBlank() || table.catalogName != null || table.databaseName != null ||
            table.pivot != null || table.unPivot != null || table.indexHint != null ||
            table.sqlServerHints != null || table.sampleClause != null || table.timeTravel != null
        ) {
            fail(ErrorCode.UNSUPPORTED_SQL, queryId, "Only ordinary schema-qualified or unqualified tables are supported.")
        }
    }

    private fun Table.toTableName(): TableName = TableName(name = name, schema = schemaName)

    private fun fail(code: ErrorCode, queryId: String, message: String): Nothing =
        throw AnalysisException(AnalysisError(code, message, queryId = queryId))

    companion object {
        private const val MAX_SQL_BYTES = 64 * 1024
        private const val MAX_COMPARISONS = 256
        private const val MAX_TABLES = 3
        private const val MAX_ORDER_BY_COLUMNS = 2
    }
}

internal data class JSqlParserParsedWorkload(
    override val input: WorkloadInput,
    override val referencedTables: Set<TableName>,
    val queriesById: Map<String, PlainSelect>,
) : ParsedWorkload
