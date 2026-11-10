package ru.itmo.indexbudget.postgres

import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executor
import org.postgresql.ds.PGSimpleDataSource
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.RunContext

/** Opens a dedicated, unpooled connection only when the pipeline starts introspection. */
class JdbcAnalysisSessionFactory(
    private val databaseUrl: () -> String? = { System.getenv("INDEXBUDGET_DATABASE_URL") },
    private val connect: (PGSimpleDataSource) -> Connection = { it.connection },
) : AnalysisSessionFactory {
    override fun open(context: RunContext): AnalysisSession {
        context.checkDeadline()
        val url = databaseUrl()?.takeIf { it.startsWith("jdbc:postgresql:") }
            ?: fail("INDEXBUDGET_DATABASE_URL must contain a PostgreSQL JDBC URL")
        val source = try {
            PGSimpleDataSource().apply {
                setURL(url)
                val limit = context.connectionTimeLimit()
                // Set after parsing the URL so URL parameters cannot disable the run's limits.
                connectTimeout = ((limit.toMillis() + 999) / 1000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                // pgJDBC's login timeout covers the entire connection attempt, including multi-host URLs.
                setProperty("loginTimeout", (limit.toMillis().coerceAtLeast(1) / 1000.0).toString())
                socketTimeout = ((context.remainingTime().toMillis() + 999) / 1000)
                    .coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                cancelSignalTimeout = connectTimeout
                logServerErrorDetail = false
            }
        } catch (_: IllegalArgumentException) {
            fail("Invalid PostgreSQL JDBC connection settings")
        } catch (error: SQLException) {
            fail("Invalid PostgreSQL JDBC connection settings", error.sqlState)
        }
        context.checkDeadline()
        val connection = try {
            connect(source)
        } catch (error: SQLException) {
            context.checkDeadline()
            fail("Unable to open the PostgreSQL analysis session", error.sqlState)
        }
        try {
            context.checkDeadline()
            return JdbcAnalysisSession(connection)
        } catch (error: Throwable) {
            // A connection obtained after the budget expired still belongs to this invocation.
            try {
                connection.close()
            } catch (_: SQLException) {
                try { connection.abort(Executor { it.run() }) } catch (_: SQLException) {
                    // Preserve the primary safe error; never attach a driver exception with credentials.
                }
            }
            throw error
        }
    }

    private fun fail(message: String, sqlState: String? = null): Nothing = throw AnalysisException(
        AnalysisError(ErrorCode.DB_CONNECTION_ERROR, message, details =
            sqlState?.takeIf { it.matches(Regex("[A-Z0-9]{5}")) }?.let { mapOf("sql_state" to it) } ?: emptyMap()),
    )
}
