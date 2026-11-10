package ru.itmo.indexbudget.sql

import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.PlainSelect
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
            val (select, tables) = parseAndValidateQuery(query, context)
            parsedQueries[query.id] = select
            referencedTables += tables
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

    private fun parseAndValidateQuery(query: QueryInput, context: RunContext): Pair<PlainSelect, List<TableName>> =
        try {
            context.checkDeadline()
            validateSqlSize(query)
            val select = parseSingleSelect(query, context)
            val tables = JSqlParserAstValidator(query.id, context).validate(select)
            select to tables.map { TableName(name = it.name, schema = it.schemaName) }
        } catch (error: AnalysisException) {
            if (error.error.queryId != null) throw error
            throw AnalysisException(error.error.copy(queryId = query.id), error)
        }

    private fun validateSqlSize(query: QueryInput) {
        if (query.sql.toByteArray(StandardCharsets.UTF_8).size > MAX_SQL_BYTES) {
            fail(ErrorCode.INPUT_LIMIT_EXCEEDED, query.id, "SQL must not exceed 64 KiB UTF-8.")
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
        if (statement !is PlainSelect) {
            fail(ErrorCode.UNSUPPORTED_SQL, query.id, "Only a plain SELECT statement is supported.")
        }
        return statement
    }

    private fun fail(code: ErrorCode, queryId: String, message: String): Nothing =
        throw AnalysisException(AnalysisError(code, message, queryId = queryId))

    private companion object {
        const val MAX_SQL_BYTES = 64 * 1024
    }
}

internal data class JSqlParserParsedWorkload(
    override val input: WorkloadInput,
    override val referencedTables: Set<TableName>,
    val queriesById: Map<String, PlainSelect>,
) : ParsedWorkload
