package ru.itmo.indexbudget.postgres

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*

class JdbcCatalogReaderTest {
    @Test
    fun `expired deadline does not touch JDBC`() {
        var now = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofNanos(1)), clock = NanoClock { now })
        now = 2
        val connection = connection { throw AssertionError("JDBC must not be called") }
        val error = assertThrows(AnalysisException::class.java) {
            JdbcCatalogReader(connection).readCatalog(setOf(TableName("records")), context)
        }
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.error.code)
    }

    @Test
    fun `driver secrets are not retained in errors or causes`() {
        val connection = connection { throw SQLException("jdbc:postgresql://secret-host/db?password=secret", "08006") }
        val error = assertThrows(AnalysisException::class.java) {
            JdbcCatalogReader(connection).readCatalog(emptySet(), RunContext())
        }
        assertEquals(ErrorCode.CATALOG_ERROR, error.error.code)
        assertEquals(mapOf("sql_state" to "08006"), error.error.details)
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains("jdbc:postgresql://secret-host"))
    }

    @Test
    fun `deadline during rows closes statements and results but not the caller connection`() {
        var now = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofNanos(2)), clock = NanoClock { now })
        var resultClosed = false
        var statementClosed = false
        var timeoutSeconds = 0
        val result = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSet::class.java)) { _, method, _ ->
            when (method.name) {
                "next" -> { now = 3; true }
                "close" -> { resultClosed = true; null }
                else -> throw AssertionError("Unexpected ResultSet call: " + method.name)
            }
        } as ResultSet
        val statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, args ->
            when (method.name) {
                "setQueryTimeout" -> { timeoutSeconds = args[0] as Int; null }
                "executeQuery" -> result
                "close" -> { statementClosed = true; null }
                else -> throw AssertionError("Unexpected Statement call: " + method.name)
            }
        } as PreparedStatement
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            if (method.name != "prepareStatement") throw AssertionError("Reader changed connection: " + method.name)
            statement
        } as Connection
        val error = assertThrows(AnalysisException::class.java) {
            JdbcCatalogReader(connection).readCatalog(emptySet(), context)
        }
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.error.code)
        assertEquals(1, timeoutSeconds)
        assertTrue(resultClosed)
        assertTrue(statementClosed)
    }

    @Test
    fun `deadline expiring in prepare closes statement without executing SQL`() {
        var now = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofNanos(2)), clock = NanoClock { now })
        var closed = false
        val statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, _ ->
            if (method.name != "close") throw AssertionError("Expired statement was used: " + method.name)
            closed = true
            null
        } as PreparedStatement
        val connection = connection { now = 2; statement }
        val error = assertThrows(AnalysisException::class.java) {
            JdbcCatalogReader(connection).readCatalog(emptySet(), context)
        }
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.error.code)
        assertTrue(closed)
    }

    private fun connection(action: () -> Any?): Connection = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(Connection::class.java), { _, _, _ -> action() },
    ) as Connection
}
