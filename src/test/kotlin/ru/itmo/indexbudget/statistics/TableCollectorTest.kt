package ru.itmo.indexbudget.statistics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import java.lang.reflect.Proxy
import java.sql.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class TableCollectorTest {
    @Test fun `timeout while processing a row retains prefix closes physical connection and starts no more reads`() {
        val now = AtomicLong()
        val db = Database(onCounter = { now.set(100_000_000_000L) })
        val result = JdbcTableStatisticsCollector({ db.connection }).collect(UUID.randomUUID(), SnapshotLimits(), RunContext(clock = NanoClock(now::get)))
        assertTrue(TableSnapshotReason.DEADLINE_EXCEEDED in result.reasons)
        assertEquals(1, result.entries.size)
        assertEquals(1, db.dataReads)
        assertTrue(db.closed)
    }

    @Test fun `byte cap marks incomplete output without inventing missing rows`() {
        val db = Database(count = 1000)
        val result = JdbcTableStatisticsCollector({ db.connection }).collect(UUID.randomUUID(), SnapshotLimits(maxBytes = 32768), RunContext())
        assertTrue(TableSnapshotReason.BYTE_LIMIT in result.reasons)
        assertTrue(result.entries.size < 1000)
        assertTrue(TableSnapshotJson().render(result).size <= 32768)
        assertTrue(db.closed)
    }

    @Test fun `metadata permission failure has no driver secrets and closes connection`() {
        val db = Database(deny = true)
        val error = assertThrows(AnalysisException::class.java) {
            JdbcTableStatisticsCollector({ db.connection }).collect(UUID.randomUUID(), SnapshotLimits(), RunContext())
        }
        assertEquals(ErrorCode.STATISTICS_PERMISSION_DENIED, error.error.code)
        assertFalse(error.toString().contains("secret"))
        assertNull(error.cause)
        assertTrue(db.closed)
    }

    @Test fun `JDBC mapping retains each distinct counter including nonzero HOT`() {
        val db = Database(count = 1)
        val entry = JdbcTableStatisticsCollector({ db.connection }).collect(UUID.randomUUID(), SnapshotLimits(), RunContext()).entries.single()
        assertEquals(101L, entry.nTupIns)
        assertEquals(37L, entry.nTupUpd)
        assertEquals(9L, entry.nTupDel)
        assertEquals(23L, entry.nTupHotUpd)
    }

    @Test fun `null statistics never become zero and no modifying SQL is sent`() {
        val db = Database(missing = true)
        val result = JdbcTableStatisticsCollector({ db.connection }).collect(UUID.randomUUID(), SnapshotLimits(), RunContext())
        assertNull(result.entries.single().nTupIns)
        assertTrue(TableSnapshotReason.COUNTERS_UNAVAILABLE in result.reasons)
        assertTrue(db.sql.all { it.trimStart().startsWith("SELECT") })
        assertTrue(db.closed)
    }

    private class Database(val count: Int = 2, val onCounter: () -> Unit = {}, val deny: Boolean = false, val missing: Boolean = false) {
        @Volatile var closed = false
        var dataReads = 0
        val sql = mutableListOf<String>()
        val connection = proxy(Connection::class.java) { method, args -> when (method) {
            "abort", "close" -> { closed = true; null }
            "prepareStatement" -> {
                val query = checkNotNull(args) { "Expected args to be available" }[0] as String; sql += query
                proxy(PreparedStatement::class.java) { name, _ -> when (name) {
                    "executeQuery" -> {
                        if (deny) throw SQLException("secret DSN password", "42501")
                        val metadata = query.contains("FROM pg_catalog.pg_database")
                        var index = -1; var wasNull = false
                        proxy(ResultSet::class.java) { op, values -> when (op) {
                            "next" -> { index++; if (!metadata) dataReads++; index < if (metadata) 1 else if (missing) 1 else count }
                            "close" -> null
                            "wasNull" -> wasNull
                            else -> {
                                val key = checkNotNull(values) { "Expected values to be available" }[0].toString()
                                wasNull = missing && key.startsWith("n_tup")
                                when (op) {
                                    "getInt" -> 160015
                                    "getBoolean" -> true
                                    "getTimestamp" -> Timestamp.valueOf("2026-11-12 00:00:00")
                                    "getString" -> when (key) { "schema_name" -> "public"; "table_name" -> "t$index"; else -> "16.15" }
                                    "getLong" -> { if (key == "n_tup_hot_upd") onCounter(); if (wasNull) 0L else when (key) {
                                        "n_tup_ins" -> 101L; "n_tup_upd" -> 37L; "n_tup_del" -> 9L; "n_tup_hot_upd" -> 23L
                                        else -> index.toLong() + 10
                                    } }
                                    else -> null
                                }
                            }
                        } }
                    }
                    else -> null
                } }
            }
            else -> null
        } }
        companion object {
            private fun <T> proxy(type: Class<T>, block: (String, Array<out Any?>?) -> Any?): T = type.cast(
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> block(method.name, args) })
        }
    }
}
