package ru.itmo.indexbudget.postgres

import java.lang.reflect.Proxy
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.sql.Connection
import java.sql.SQLException
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException
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
        assertEquals(ErrorCode.DB_CONNECTION_ERROR, error.error.code)
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
        var guardActive = false
        var guardClosed = false
        var timeoutSeconds = 0
        val result = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSet::class.java)) { _, method, _ ->
            when (method.name) {
                "next" -> { assertTrue(guardActive); now = 3; true }
                "close" -> { assertTrue(guardActive); resultClosed = true; null }
                else -> throw AssertionError("Unexpected ResultSet call: " + method.name)
            }
        } as ResultSet
        val statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, args ->
            when (method.name) {
                "setQueryTimeout" -> { timeoutSeconds = args[0] as Int; null }
                "executeQuery" -> { assertTrue(guardActive); result }
                "close" -> { assertFalse(guardActive); statementClosed = true; null }
                else -> throw AssertionError("Unexpected Statement call: " + method.name)
            }
        } as PreparedStatement
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            if (method.name != "prepareStatement") throw AssertionError("Reader changed connection: " + method.name)
            statement
        } as Connection
        val error = assertThrows(AnalysisException::class.java) {
            JdbcCatalogReader(connection, beforeExecute = { active, run ->
                assertSame(statement, active)
                assertSame(context, run)
                guardActive = true
                AutoCloseable { guardActive = false; guardClosed = true }
            }).readCatalog(emptySet(), context)
        }
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.error.code)
        assertEquals(1, timeoutSeconds)
        assertTrue(resultClosed)
        assertTrue(statementClosed)
        assertTrue(guardClosed)
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

    @Test
    fun `deadline expiring while registering operation closes guard without starting SQL`() {
        var now = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofNanos(2)), clock = NanoClock { now })
        var statementClosed = false
        var guardClosed = false
        val statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, _ ->
            when (method.name) {
                "setQueryTimeout" -> null
                "close" -> { statementClosed = true; null }
                else -> throw AssertionError("Expired statement was used: " + method.name)
            }
        } as PreparedStatement
        val error = assertThrows(AnalysisException::class.java) {
            JdbcCatalogReader(connection { statement }, beforeExecute = { _, _ ->
                now = 2
                AutoCloseable { guardClosed = true }
            }).readCatalog(emptySet(), context)
        }
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.error.code)
        assertTrue(statementClosed)
        assertTrue(guardClosed)
        assertEquals(0, context.statistics().explainAttempts)
    }

    @Test
    fun `catalog SQL failure closes operation guard before statement and exposes no driver secrets`() {
        var guardActive = false
        var statementClosed = false
        val statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, _ ->
            when (method.name) {
                "setQueryTimeout" -> null
                "executeQuery" -> {
                    assertTrue(guardActive)
                    throw SQLException("jdbc:postgresql://secret-host/db?password=secret", "57014")
                }
                "close" -> { assertFalse(guardActive); statementClosed = true; null }
                else -> throw AssertionError("Unexpected statement call: " + method.name)
            }
        } as PreparedStatement
        val error = assertThrows(AnalysisException::class.java) {
            JdbcCatalogReader(connection { statement }, beforeExecute = { _, _ ->
                guardActive = true
                AutoCloseable { guardActive = false }
            }).readCatalog(emptySet(), RunContext())
        }
        assertEquals(ErrorCode.CATALOG_ERROR, error.error.code)
        assertEquals(mapOf("sql_state" to "57014"), error.error.details)
        assertTrue(statementClosed)
        assertFalse(guardActive)
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains("secret-host"))
    }

    @Test
    fun `nested and typed driver failures retain connection failure classification after sanitization`() {
        val failures = listOf(
            SQLException("secret driver wrapper", "HY000").also {
                it.setNextException(SQLException("secret backend failure", "08006"))
            },
            SQLException("secret wrapper", SocketException("secret socket")),
            SQLException("secret wrapper", SocketTimeoutException("secret timeout")),
            SQLException("secret wrapper", EOFException("secret EOF")),
            SQLException("secret wrapper", "HY000").also {
                it.addSuppressed(SQLException("secret close failure", "57P01"))
            },
            SQLNonTransientConnectionException("secret connection failure"),
            SQLTransientConnectionException("secret connection failure"),
            SQLRecoverableException("secret recoverable failure"),
        )
        for (failure in failures) {
            val error = assertThrows(AnalysisException::class.java) {
                JdbcCatalogReader(connection { throw failure }).readCatalog(emptySet(), RunContext())
            }
            assertEquals(ErrorCode.DB_CONNECTION_ERROR, error.error.code, failure.javaClass.simpleName)
            if (failure.nextException?.sqlState == "08006") {
                assertEquals("08006", error.error.details["sql_state"])
            }
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            assertFalse(error.stackTraceToString().contains("secret"))
        }
    }

    @Test
    fun `connection failure inspection handles cyclic chains and limits its traversal`() {
        val first = SQLException("first", "57014")
        val second = SQLException("second", "42601")
        first.initCause(second)
        second.initCause(first)
        first.addSuppressed(second)
        assertNull(jdbcConnectionFailure(first))
        val connectionLost = SQLException("connection lost", "08006")
        second.setNextException(connectionLost)
        assertSame(connectionLost, jdbcConnectionFailure(first))

        val failures = List(33) { SQLException("wrapper", "HY000") }
        for (index in 0 until failures.lastIndex) failures[index].initCause(failures[index + 1])
        failures.last().initCause(SocketException("outside bounded inspection"))
        assertNull(jdbcConnectionFailure(failures.first()))
    }

    private fun connection(action: () -> Any?): Connection = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(Connection::class.java), { _, _, _ -> action() },
    ) as Connection
}
