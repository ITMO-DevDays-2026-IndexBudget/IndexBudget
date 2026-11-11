package ru.itmo.indexbudget.sql

import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.CCJSqlParser
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.Statements
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
import java.util.function.Consumer

@Component
class JSqlParserSqlAnalyzer internal constructor(
    private val statementParser: (String, Consumer<CCJSqlParser>) -> Statements,
) : SqlAnalyzer {
    constructor() : this(CCJSqlParserUtil::parseStatements)

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
    ): ResolvedWorkload {
        context.checkDeadline()
        if (parsed !is JSqlParserParsedWorkload) {
            throw AnalysisException(AnalysisError(ErrorCode.INVALID_INPUT, "Workload was not parsed by this SQL analyzer."))
        }
        return JSqlParserNameResolver(catalog, context).resolve(parsed)
    }

    private fun parseAndValidateQuery(query: QueryInput, context: RunContext): Pair<PlainSelect, List<TableName>> =
        try {
            context.checkDeadline()
            validateSqlSize(query)
            val tokens = PostgresParserTokens(query.sql, context)
            val select = parseSingleSelect(query, context, tokens)
            val tables = JSqlParserAstValidator(query.id, context, tokens::restoreIdentifier).validate(select)
            select to tables.map { TableName(name = decodePostgresIdentifier(it.postgresName), schema = it.schemaName?.let(::decodePostgresIdentifier)) }
        } catch (error: AnalysisException) {
            if (error.error.queryId != null) throw error
            throw AnalysisException(error.error.copy(queryId = query.id), error)
        }

    private fun validateSqlSize(query: QueryInput) {
        if (query.sql.toByteArray(StandardCharsets.UTF_8).size > MAX_SQL_BYTES) {
            fail(ErrorCode.INPUT_LIMIT_EXCEEDED, query.id, "SQL must not exceed 64 KiB UTF-8.")
        }
    }

    private fun parseSingleSelect(query: QueryInput, context: RunContext, tokens: PostgresParserTokens): PlainSelect {
        context.checkDeadline()
        val statements = try {
            statementParser(query.sql, Consumer(tokens::configure))
        } catch (_: JSQLParserException) {
            fail(ErrorCode.UNSUPPORTED_SQL, query.id, "SQL cannot be parsed as a supported SELECT.")
        } finally {
            context.checkDeadline()
        }
        tokens.restoreImages(context)

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

/** Decode AST tokens at the SQL -> domain boundary; keep the AST and original SQL unchanged.
 * PostgreSQL's UTF-8 scanner folds ASCII A-Z in unquoted identifiers, not arbitrary Unicode.
 */
internal fun decodePostgresIdentifier(token: String): String =
    if (token.startsWith('"') && token.endsWith('"')) {
        token.substring(1, token.length - 1).replace("\"\"", "\"")
    } else {
        token.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")
    }
