package ru.itmo.indexbudget.postgres

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData

class SeriesSourceFingerprintTest {
    @Test
    fun `fingerprint ignores password while retaining endpoint database and actual connection user`() {
        val first = fingerprint("jdbc:postgresql://localhost:5432/items?password=first-secret", "reader")
        assertNotNull(first)
        assertTrue(checkNotNull(first).matches(Regex("[0-9a-f]{64}")))
        assertEquals(first, fingerprint("jdbc:postgresql://localhost:5432/items?password=other-secret", "reader"))
        assertNotEquals(first, fingerprint("jdbc:postgresql://localhost:5432/items", "other-reader"))
        assertNotEquals(first, fingerprint("jdbc:postgresql://localhost:5432/other", "reader"))
        assertNotEquals(first, fingerprint("jdbc:postgresql://localhost:5433/items", "reader"))
    }

    private fun fingerprint(url: String, user: String): String? {
        val metadata = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DatabaseMetaData::class.java)) { _, method, _ ->
            when (method.name) {
                "getURL" -> url
                "getUserName" -> user
                else -> error("Unexpected metadata operation: ${method.name}")
            }
        } as DatabaseMetaData
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "getMetaData" -> metadata
                "close" -> null
                "isClosed" -> false
                else -> error("Fingerprint must not issue SQL: ${method.name}")
            }
        } as Connection
        return JdbcAnalysisSession(connection).use { it.sourceFingerprint }
    }
}
