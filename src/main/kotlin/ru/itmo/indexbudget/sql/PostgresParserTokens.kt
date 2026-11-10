package ru.itmo.indexbudget.sql

import net.sf.jsqlparser.parser.CCJSqlParser
import net.sf.jsqlparser.parser.CCJSqlParserTokenManager
import net.sf.jsqlparser.parser.SimpleCharStream
import net.sf.jsqlparser.parser.StringProvider
import net.sf.jsqlparser.parser.Token
import net.sf.jsqlparser.schema.Table
import ru.itmo.indexbudget.domain.RunContext
import java.security.MessageDigest
import java.util.HexFormat

internal val Table.postgresName: String
    get() = nameParts.first()

/**
 * JSqlParser 5.4 splits quoted identifiers containing dots while building RelObjectNames.
 * The adapter therefore replaces every dotted quoted token with a short unique placeholder
 * for the duration of parsing and restores the original images afterwards.
 *
 * The protected representation stays bounded: each placeholder has a fixed short length
 * derived from the SQL digest, and a restoration table maps placeholders back to originals.
 * Embedding a colliding literal would require a fixed point of the digest over the SQL.
 * The total protected size is O(number of dotted quoted tokens), never O(sql length squared).
 */
internal class PostgresParserTokens(sql: String, context: RunContext? = null) {
    private val prefix = "ib_q" + run {
        context?.checkDeadline()
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(sql.toByteArray(Charsets.UTF_8)).copyOfRange(0, 6),
        )
    }
    private val originals = HashMap<String, String>()
    private val tokens = mutableListOf<Token>()

    fun configure(parser: CCJSqlParser) {
        val source = parser.token_source
        parser.token_source = object : CCJSqlParserTokenManager(SimpleCharStream(StringProvider(""))) {
            init {
                configuration = source.configuration
            }

            override fun getNextToken(): Token = source.nextToken.also { token ->
                if (token.kind == S_QUOTED_IDENTIFIER && token.image.startsWith('"') && '.' in token.image) {
                    val placeholder = "\"" + prefix + originals.size + "\""
                    originals[placeholder] = token.image
                    tokens += token
                    token.image = placeholder
                }
            }
        }
    }

    fun restoreImages(context: RunContext) {
        tokens.forEach {
            context.checkDeadline()
            it.image = restoreIdentifier(it.image)
        }
    }

    /** Exact placeholder lookup: string literals and untouched identifiers are never rewritten. */
    fun restoreIdentifier(identifier: String): String = originals[identifier] ?: identifier
}
