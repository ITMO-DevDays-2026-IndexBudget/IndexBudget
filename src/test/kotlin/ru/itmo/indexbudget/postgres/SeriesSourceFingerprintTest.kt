package ru.itmo.indexbudget.postgres

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.NanoClock
import ru.itmo.indexbudget.domain.RunContext
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration

class SeriesSourceFingerprintTest {
    @Test
    fun `same multi-host URL distinguishes the actual backend database and session roles`() {
        val url = "jdbc:postgresql://primary:5432,secondary:5433/items?targetServerType=any"
        val backend = Backend()
        val first = fingerprint(url, backend)
        assertNotNull(first)
        assertTrue(checkNotNull(first).matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(first, fingerprint(url, backend.copy(address = "192.0.2.11")))
        assertNotEquals(first, fingerprint(url, backend.copy(port = 5433)))
        assertNotEquals(first, fingerprint(url, backend.copy(database = "other")))
        assertNotEquals(first, fingerprint(url, backend.copy(sessionUser = "other-reader")))
        assertNotEquals(first, fingerprint(url, backend.copy(currentUser = "limited-reader")))
    }

    @Test
    fun `same backend ignores configured password and cached getter performs no SQL`() {
        val backend = Backend()
        val first = fingerprint("jdbc:postgresql://primary,secondary/items?password=first-secret", backend)
        val jdbc = FakeJdbc("jdbc:postgresql://secondary,primary/items?password=other-secret", backend)
        JdbcAnalysisSession(jdbc.connection).use { session ->
            assertNull(session.sourceFingerprint, "The configured URL alone is not an observed source")
            assertEquals(0, jdbc.executedQueries.size)
            val versions = session.readDatabaseVersions(RunContext())
            assertEquals("16.15", versions.postgresVersion)
            assertEquals("1.4.2", versions.hypopgVersion)
            assertEquals(first, session.sourceFingerprint)
            val executed = jdbc.executedQueries.toList()
            assertEquals(first, session.sourceFingerprint)
            assertEquals(executed, jdbc.executedQueries, "Reading captured identity must not issue another query")
        }
        assertTrue(jdbc.closed)
        assertEquals(0, jdbc.openStatements)
        assertEquals(0, jdbc.openResults)
    }

    @Test
    fun `missing actual network endpoint remains unknown even with a configured TCP URL`() {
        val url = "jdbc:postgresql://primary,secondary/items"
        assertNull(fingerprint(url, Backend(address = null, port = null)))
        assertNull(fingerprint(url, Backend(address = null)))
        assertNull(fingerprint(url, Backend(port = null)))
    }

    @Test
    fun `metadata query obeys deadline and does not publish facts returned after expiry`() {
        var now = 0L
        val context = RunContext(clock = NanoClock { now })
        val jdbc = FakeJdbc("jdbc:postgresql://primary/items", Backend()) {
            now = Duration.ofSeconds(90).toNanos()
        }
        JdbcAnalysisSession(jdbc.connection).use { session ->
            val error = assertThrows(AnalysisException::class.java) { session.readDatabaseVersions(context) }
            assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.error.code)
            assertNull(session.sourceFingerprint)
        }
        assertTrue(jdbc.closed)
        assertEquals(0, jdbc.openStatements)
        assertEquals(0, jdbc.openResults)
    }

    private fun fingerprint(url: String, backend: Backend): String? {
        val jdbc = FakeJdbc(url, backend)
        return JdbcAnalysisSession(jdbc.connection).use {
            it.readDatabaseVersions(RunContext())
            it.sourceFingerprint
        }
    }

    private data class Backend(
        val address: String? = "192.0.2.10",
        val port: Int? = 5432,
        val database: String = "items",
        val sessionUser: String = "reader",
        val currentUser: String = "reader",
    )

    /** Only metadata rows vary: the configured JDBC URL intentionally need not match the backend. */
    private class FakeJdbc(url: String, backend: Backend, onFactsQuery: () -> Unit = {}) {
        var closed = false
        var openStatements = 0
        var openResults = 0
        val executedQueries = mutableListOf<String>()
        private val metadata = proxy(DatabaseMetaData::class.java) { name, _ ->
            when (name) {
                "getURL" -> url
                "getUserName" -> "configured-user"
                else -> error("Unexpected metadata operation: $name")
            }
        }
        val connection = proxy(Connection::class.java) { name, args ->
            when (name) {
                "getMetaData" -> metadata
                "prepareStatement" -> {
                    val sql = args[0] as String
                    openStatements++
                    proxy(PreparedStatement::class.java) { operation, _ ->
                        when (operation) {
                            "setQueryTimeout", "setString", "cancel" -> null
                            "executeQuery" -> {
                                executedQueries += sql
                                val values = if (sql.contains("inet_server_addr()")) {
                                    onFactsQuery()
                                    listOf("16.15", "1.4.2", backend.address, backend.port, backend.database,
                                        backend.sessionUser, backend.currentUser)
                                } else {
                                    check(sql.startsWith("SELECT pg_catalog.set_config('statement_timeout'"))
                                    listOf("90000ms")
                                }
                                openResults++
                                var row = false
                                var wasNull = false
                                proxy(ResultSet::class.java) { resultOperation, resultArgs ->
                                    when (resultOperation) {
                                        "next" -> (!row).also { row = true }
                                        "getString", "getInt" -> {
                                            val value = values[(resultArgs[0] as Int) - 1]
                                            wasNull = value == null
                                            if (resultOperation == "getString") value?.toString() else value as Int? ?: 0
                                        }
                                        "wasNull" -> wasNull
                                        "close" -> { openResults--; null }
                                        else -> error("Unexpected result operation: $resultOperation")
                                    }
                                }
                            }
                            "close" -> { openStatements--; null }
                            else -> error("Unexpected statement operation: $operation")
                        }
                    }
                }
                "setNetworkTimeout" -> null
                "close", "abort" -> { closed = true; null }
                "isClosed" -> closed
                else -> error("Unexpected connection operation: $name")
            }
        }
    }

    private companion object {
        fun <T> proxy(type: Class<T>, action: (String, Array<out Any?>) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                action(method.name, args ?: emptyArray())
            },
        )
    }
}
