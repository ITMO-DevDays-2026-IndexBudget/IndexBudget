package ru.itmo.indexbudget.statistics

import net.sf.jsqlparser.expression.*
import net.sf.jsqlparser.expression.operators.relational.*
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.schema.Table
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.sql.decodePostgresIdentifier
import ru.itmo.indexbudget.sql.JSqlParserAstValidator
import ru.itmo.indexbudget.sql.PostgresParserTokens
import ru.itmo.indexbudget.sql.postgresName
import ru.itmo.indexbudget.sql.ValidatedSql
import net.sf.jsqlparser.statement.update.Update
import net.sf.jsqlparser.statement.delete.Delete

/** Conservative AST substitution: only parameter leaves change; the entire resulting AST must match. */
internal class SqlTemplateMatcher(private val context: RunContext) {
    fun referencedTables(sql: String): Set<TableName> = parse(sql).let { validated ->
        validated.tables.map {
            TableName(decodePostgresIdentifier(it.postgresName), it.schemaName?.let(::decodePostgresIdentifier))
        }.toSet()
    }

    fun match(template: String, example: String, catalog: CatalogSnapshot): Boolean {
        val source = parse(template)
        val exampleAst = parse(example)
        if (source.kind != exampleAst.kind) return false
        qualify(source.scope, catalog)
        qualify(exampleAst.scope, catalog)

        // All clauses share one binding table, including repeated parameters in SET and WHERE.
        val binder = ParameterBinder()
        bindWhere(source.scope, exampleAst.scope, binder)
        if (!bindJoins(source.scope, exampleAst.scope, binder)) return false
        bindLimit(source.scope, exampleAst.scope, binder)
        if (!bindDml(source, exampleAst, binder)) return false

        if (source.statement.toString() != exampleAst.statement.toString()) return false
        // Validate source-only dialect flags too: canonical SQL text is not itself a whitelist.
        JSqlParserAstValidator("template", context).validateStatement(source.statement)
        context.checkDeadline()
        return true
    }

    private fun bindWhere(source: PlainSelect, example: PlainSelect, binder: ParameterBinder) {
        source.where = binder.bind(source.where, example.where)
    }

    private fun bindJoins(source: PlainSelect, example: PlainSelect, binder: ParameterBinder): Boolean {
        val sourceJoins = source.joins.orEmpty()
        val exampleJoins = example.joins.orEmpty()
        if (sourceJoins.size != exampleJoins.size) return false
        sourceJoins.zip(exampleJoins).forEach { (sourceJoin, exampleJoin) ->
            if (sourceJoin.onExpressions.size != exampleJoin.onExpressions.size) return false
            sourceJoin.setOnExpressions(sourceJoin.onExpressions.zip(exampleJoin.onExpressions).map { (left, right) ->
                binder.bind(left, right)
            })
        }
        return true
    }

    private fun bindLimit(source: PlainSelect, example: PlainSelect, binder: ParameterBinder) {
        if (source.limit != null && example.limit != null) {
            source.limit.rowCount = binder.bind(source.limit.rowCount, example.limit.rowCount)
        }
    }

    private fun bindDml(source: ValidatedSql, example: ValidatedSql, binder: ParameterBinder): Boolean {
        if (source.statement is Update && example.statement is Update) {
            if (!bindUpdateAssignments(source.statement, example.statement, binder)) return false
            source.statement.where = source.scope.where
        } else if (source.statement is Delete) {
            source.statement.where = source.scope.where
        }
        return true
    }

    private fun bindUpdateAssignments(source: Update, example: Update, binder: ParameterBinder): Boolean {
        val sourceAssignments = source.updateSets
        val exampleAssignments = example.updateSets
        if (sourceAssignments.size != exampleAssignments.size) return false
        for (index in sourceAssignments.indices) {
            val assignment = sourceAssignments[index]
            val bound = requireNotNull(binder.bind(assignment.values[0], exampleAssignments[index].values[0]))
            // The validator admits exactly one scalar value per SET assignment.
            assignment.setValues(ExpressionList<Expression>(bound))
        }
        return true
    }

    private inner class ParameterBinder {
        private val literalsByParameter = mutableMapOf<String, String>()

        fun bind(left: Expression?, right: Expression?): Expression? {
            context.checkDeadline()
            if (left == null || right == null) return left
            if (left is JdbcParameter) {
                if (!scalar(right)) return left
                val key = left.toString()
                val previous = literalsByParameter.putIfAbsent(key, right.toString())
                if (previous != null && previous != right.toString()) return left
                return right
            }
            if (left.javaClass != right.javaClass) return left
            when (left) {
                is BinaryExpression -> {
                    right as BinaryExpression
                    left.leftExpression = bind(left.leftExpression, right.leftExpression)
                    left.rightExpression = bind(left.rightExpression, right.rightExpression)
                }
                is ParenthesedExpressionList<*> -> {
                    return bindExpressionList(left, right as ParenthesedExpressionList<*>)
                }
                is InExpression -> {
                    right as InExpression
                    left.leftExpression = bind(left.leftExpression, right.leftExpression)
                    left.rightExpression = bind(left.rightExpression, right.rightExpression)
                }
                is Between -> {
                    right as Between
                    left.leftExpression = bind(left.leftExpression, right.leftExpression)
                    left.betweenExpressionStart = bind(left.betweenExpressionStart, right.betweenExpressionStart)
                    left.betweenExpressionEnd = bind(left.betweenExpressionEnd, right.betweenExpressionEnd)
                }
                is IsNullExpression -> left.leftExpression = bind(left.leftExpression, (right as IsNullExpression).leftExpression)
                is CastExpression -> left.leftExpression = bind(left.leftExpression, (right as CastExpression).leftExpression)
                is SignedExpression -> left.expression = bind(left.expression, (right as SignedExpression).expression)
            }
            return left
        }

        private fun bindExpressionList(
            source: ParenthesedExpressionList<*>,
            example: ParenthesedExpressionList<*>,
        ): ParenthesedExpressionList<*> {
            if (source.size != example.size) return source
            val expressions = source.mapIndexed { index, expression ->
                requireNotNull(bind(expression, example[index]))
            }
            return ParenthesedExpressionList<Expression>(expressions).apply { astNode = source.astNode }
        }
    }

    /** Freeze table resolution in exported SQL; never carry an implicit historical search_path. */
    fun qualifiedExample(sql: String, catalog: CatalogSnapshot): String {
        val example = parse(sql)
        qualify(example.scope, catalog)
        return example.statement.toString()
    }

    private fun qualify(select: PlainSelect, catalog: CatalogSnapshot) {
        val tables = listOf(select.fromItem) + select.joins.orEmpty().map { it.fromItem }
        for (item in tables) {
            context.checkDeadline()
            val table = item as? Table ?: ImportJson.fail("Template requires ordinary tables.")
            val name = decodePostgresIdentifier(table.postgresName)
            val schema = table.schemaName?.let(::decodePostgresIdentifier)
            val physical = if (schema != null) catalog.tables.firstOrNull { it.ref == TableRef(schema, name) }
                else catalog.searchPath.firstNotNullOfOrNull { path -> catalog.tables.firstOrNull { it.ref == TableRef(path, name) } }
            val ref = physical?.ref ?: ImportJson.fail("Template table cannot be resolved in the explicit context.")
            table.nameParts = table.nameParts.toMutableList()
            table.schemaName = quote(ref.schema)
            // Keep the original table spelling so unaliased qualifiers keep their identity.
        }
    }

    private fun scalar(expression: Expression): Boolean = when (expression) {
        is LongValue, is DoubleValue, is StringValue, is NullValue, is BooleanValue,
        is DateValue, is TimeValue, is TimestampValue, is DateTimeLiteralExpression, is HexValue -> true
        is CastExpression -> scalar(expression.leftExpression)
        is SignedExpression -> scalar(expression.expression)
        else -> false
    }

    private fun parse(sql: String): ValidatedSql {
        if (sql.toByteArray(Charsets.UTF_8).size > 64 * 1024) ImportJson.fail("Template SQL exceeds 64 KiB.")
        context.checkDeadline()
        val tokens = PostgresParserTokens(sql, context)
        val statements = try { CCJSqlParserUtil.parseStatements(sql, java.util.function.Consumer(tokens::configure)) }
            catch (_: net.sf.jsqlparser.JSQLParserException) { ImportJson.fail("Template cannot be parsed as one supported statement.") }
        finally { context.checkDeadline() }
        tokens.restoreImages(context)
        if (statements.size != 1) ImportJson.fail("Template must contain one supported statement.")
        return JSqlParserAstValidator("template", context, tokens::restoreIdentifier).validateStatement(statements[0], template = true)
    }
    private fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
}
