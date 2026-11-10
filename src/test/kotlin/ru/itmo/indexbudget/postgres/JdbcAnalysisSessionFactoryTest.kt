package ru.itmo.indexbudget.postgres

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.Banner
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Configuration
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.NanoClock
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.RunLimits

class JdbcAnalysisSessionFactoryTest {
    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `application logging suppresses driver warnings containing malformed credentials`(output: CapturedOutput) {
        SpringApplicationBuilder(LoggingConfiguration::class.java)
            .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).run().use {
                val factory = JdbcAnalysisSessionFactory({
                    "jdbc:postgresql://private-host/db/extra?password=private-password"
                })
                val failure = assertThrows(AnalysisException::class.java) { factory.open(RunContext()) }
                assertEquals(ErrorCode.DB_CONNECTION_ERROR, failure.error.code)
                assertFalse(output.all.contains("private-host"))
                assertFalse(output.all.contains("private-password"))
            }
    }

    @Configuration(proxyBeanMethods = false)
    class LoggingConfiguration

    @Test
    fun `missing or non JDBC configuration fails without connecting`() {
        for (url in listOf(null, "", "postgresql://secret:password@localhost/db")) {
            val factory = JdbcAnalysisSessionFactory({ url }, { error("Must not connect") })
            val error = assertThrows(AnalysisException::class.java) { factory.open(RunContext()) }
            assertEquals(ErrorCode.DB_CONNECTION_ERROR, error.error.code)
            assertFalse(error.toString().contains("password"))
            assertNull(error.cause)
        }
    }

    @Test
    fun `run limits override URL timeouts without changing planner options`() {
        val connection = ConnectionStub()
        val factory = JdbcAnalysisSessionFactory(
            { "jdbc:postgresql://localhost/db?connectTimeout=0&loginTimeout=0&socketTimeout=0&currentSchema=workload" },
            { source ->
                assertEquals(1, source.connectTimeout)
                assertEquals("0.25", source.getProperty("loginTimeout"))
                assertEquals(1, source.socketTimeout)
                assertEquals("workload", source.currentSchema)
                assertFalse(source.logServerErrorDetail)
                connection.proxy
            },
        )
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofMillis(250)), clock = NanoClock { 0 })
        factory.open(context).close()
        assertTrue(connection.closed)
    }

    @Test
    fun `driver connection error is sanitized`() {
        val factory = JdbcAnalysisSessionFactory({ "jdbc:postgresql://localhost/db?password=secret" }, {
            throw SQLException("jdbc:postgresql://secret-host/db?password=secret", "08001")
        })
        val error = assertThrows(AnalysisException::class.java) { factory.open(RunContext()) }
        assertEquals(ErrorCode.DB_CONNECTION_ERROR, error.error.code)
        assertEquals(mapOf("sql_state" to "08001"), error.error.details)
        assertFalse(error.error.toString().contains("secret"))
        assertNull(error.cause)
    }

    @Test
    fun `late connection is closed and deadline wins over success`() {
        var nanos = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofMillis(10)), clock = NanoClock { nanos })
        val connection = ConnectionStub()
        val factory = JdbcAnalysisSessionFactory({ "jdbc:postgresql://localhost/db" }, {
            nanos = Duration.ofMillis(11).toNanos()
            connection.proxy
        })
        val error = assertThrows(AnalysisException::class.java) { factory.open(context) }
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.error.code)
        assertTrue(connection.closed)
    }

    @Test
    fun `cancelled context never opens a connection`() {
        val context = RunContext().also { it.cancel() }
        val factory = JdbcAnalysisSessionFactory({ error("Must not read settings") })
        val error = assertThrows(AnalysisException::class.java) { factory.open(context) }
        assertEquals(ErrorCode.CANCELLED, error.error.code)
    }

    @Test
    fun `late connection aborts when close fails`() {
        var nanos = 0L
        val context = RunContext(limits = RunLimits(commandTimeout = Duration.ofMillis(10)), clock = NanoClock { nanos })
        val connection = ConnectionStub(failClose = true)
        val factory = JdbcAnalysisSessionFactory({ "jdbc:postgresql://localhost/db" }, {
            nanos = Duration.ofMillis(11).toNanos()
            connection.proxy
        })
        val error = assertThrows(AnalysisException::class.java) { factory.open(context) }
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.error.code)
        assertTrue(connection.closed)
        assertNull(error.cause)
    }

    private class ConnectionStub(private val failClose: Boolean = false) {
        var closed = false
        val proxy = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "close" -> {
                    if (failClose) throw SQLException("private-host private-password")
                    closed = true
                    null
                }
                "abort" -> { closed = true; null }
                "isClosed" -> closed
                "getAutoCommit" -> true
                else -> error("Unexpected JDBC method: ${method.name}")
            }
        } as Connection
    }
}
