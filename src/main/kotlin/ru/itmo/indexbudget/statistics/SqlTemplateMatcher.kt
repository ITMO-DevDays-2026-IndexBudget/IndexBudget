package ru.itmo.indexbudget.statistics

import net.sf.jsqlparser.expression.*
import net.sf.jsqlparser.expression.operators.relational.*
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.schema.Table
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.sql.decodePostgresIdentifier
import ru.itmo.indexbudget.sql.JSqlParserAstValidator

/** Conservative AST substitution: only parameter leaves change; the entire resulting AST must match. */
internal class SqlTemplateMatcher(private val context: RunContext) {
    fun match(template: String, example: String, catalog: CatalogSnapshot): Boolean {
        val original = parse(template)
        val sample = parse(example)
        qualify(original, catalog)
        qualify(sample, catalog)
        val values = mutableMapOf<String, String>()
        fun bind(left: Expression?, right: Expression?): Expression? {
            context.checkDeadline()
            if (left == null || right == null) return left
            if (left is JdbcParameter) {
                if (!scalar(right)) return left
                val key = left.toString()
                val previous = values.putIfAbsent(key, right.toString())
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
                    right as ParenthesedExpressionList<*>
                    if (left.size == right.size) {
                        @Suppress("UNCHECKED_CAST") val mutable = left as MutableList<Expression>
                        for (i in mutable.indices) mutable[i] = requireNotNull(bind(mutable[i], right[i]))
                    }
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
        original.where = bind(original.where, sample.where)
        val joins = original.joins.orEmpty()
        val samples = sample.joins.orEmpty()
        if (joins.size != samples.size) return false
        joins.zip(samples).forEach { (left, right) ->
            if (left.onExpressions.size != right.onExpressions.size) return false
            left.setOnExpressions(left.onExpressions.zip(right.onExpressions).map { (a, b) -> bind(a, b) })
        }
        if (original.limit != null && sample.limit != null) original.limit.rowCount = bind(original.limit.rowCount, sample.limit.rowCount)
        if (original.toString() != sample.toString()) return false
        // Validate source-only dialect flags too: canonical SQL text is not itself a whitelist.
        JSqlParserAstValidator("template", context).validate(original)
        context.checkDeadline()
        return true
    }

    /** Freeze table resolution in exported SQL; never carry an implicit historical search_path. */
    fun qualifiedExample(sql: String, catalog: CatalogSnapshot): String = parse(sql).let { qualify(it, catalog); it.toString() }

    private fun qualify(select: PlainSelect, catalog: CatalogSnapshot) {
        val tables = listOf(select.fromItem) + select.joins.orEmpty().map { it.fromItem }
        for (item in tables) {
            context.checkDeadline()
            val table = item as? Table ?: ImportJson.fail("Template requires ordinary tables.")
            val name = decodePostgresIdentifier(table.name)
            val schema = table.schemaName?.let(::decodePostgresIdentifier)
            val physical = if (schema != null) catalog.tables.firstOrNull { it.ref == TableRef(schema, name) }
                else catalog.searchPath.firstNotNullOfOrNull { path -> catalog.tables.firstOrNull { it.ref == TableRef(path, name) } }
            val ref = physical?.ref ?: ImportJson.fail("Template table cannot be resolved in the explicit context.")
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

    private fun parse(sql: String): PlainSelect {
        if (sql.toByteArray(Charsets.UTF_8).size > 64 * 1024) ImportJson.fail("Template SQL exceeds 64 KiB.")
        context.checkDeadline()
        val statements = try { CCJSqlParserUtil.parseStatements(sql) }
            catch (_: Exception) { ImportJson.fail("Template cannot be parsed as one SELECT.") }
        finally { context.checkDeadline() }
        if (statements.size != 1 || statements[0] !is PlainSelect) ImportJson.fail("Template must be one plain SELECT.")
        return statements[0] as PlainSelect
    }
    private fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
}
