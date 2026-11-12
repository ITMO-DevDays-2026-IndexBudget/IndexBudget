package ru.itmo.indexbudget.statistics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import ru.itmo.indexbudget.domain.RunContext
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_TABLE_STATS_TEST_URL", matches = ".+")
class TableStatisticsIntegrationTest {
    private val source = UUID.fromString("4ada107c-d989-4e80-9836-7fc1d14182cf")
    private fun connection() = DriverManager.getConnection(System.getenv("INDEXBUDGET_TABLE_STATS_TEST_URL"), "postgres", "")
    private fun capture(connect: () -> Connection = ::connection, limits: SnapshotLimits = SnapshotLimits()) =
        JdbcTableStatisticsCollector({ connect() }).collect(source, limits, RunContext())

    @Test fun `published counters retain updates including HOT with original relation identity`() {
        val schema = "tbl_" + UUID.randomUUID().toString().replace("-", "")
        connection().use { c -> c.createStatement().use {
            it.execute("CREATE SCHEMA $schema")
            it.execute("CREATE TABLE $schema.probe (id integer, payload integer) WITH (fillfactor=50)")
        } }
        try {
            val before = capture()
            connection().use { c -> c.createStatement().use {
                it.executeUpdate("INSERT INTO $schema.probe SELECT i, 0 FROM generate_series(1,100) i")
                it.executeUpdate("UPDATE $schema.probe SET payload=1 WHERE id<=40")
                it.executeUpdate("DELETE FROM $schema.probe WHERE id<=10")
                it.execute("SELECT pg_catalog.pg_stat_force_next_flush()")
            } }
            // Publication is asynchronous even after the writer requests a flush. Observe it with
            // fresh collection transactions rather than treating an early zero as a final counter.
            var after = capture()
            val waitUntil = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos()
            while (after.entries.single { it.schema == schema }.nTupIns != 100L && System.nanoTime() < waitUntil) {
                Thread.sleep(50)
                after = capture()
            }
            val a = before.entries.single { it.schema == schema }
            val b = after.entries.single { it.schema == schema }
            assertTrue(after.complete)
            assertEquals(a.relationOid, b.relationOid)
            assertEquals(source, after.source.sourceId)
            assertEquals(before.source.databaseOid, after.source.databaseOid)
            assertEquals(100L, b.nTupIns!! - a.nTupIns!!)
            assertEquals(40L, b.nTupUpd!! - a.nTupUpd!!)
            assertEquals(10L, b.nTupDel!! - a.nTupDel!!)
            assertTrue(b.nTupHotUpd!! >= a.nTupHotUpd!!)
            assertTrue(b.nTupHotUpd!! <= b.nTupUpd!!)
            connection().use { c -> c.createStatement().use { statement ->
                statement.executeQuery("SELECT n_tup_hot_upd FROM pg_catalog.pg_stat_user_tables WHERE relid='$schema.probe'::regclass").use { rows ->
                    assertTrue(rows.next())
                    val publishedHot = rows.getLong(1)
                    assertTrue(publishedHot > 0, "Fixture must publish real HOT updates")
                    assertEquals(publishedHot, b.nTupHotUpd)
                }
            } }
        } finally { connection().use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } } }
    }

    @Test fun `ordinary unprivileged role can read public counters without table SELECT and closes connection`() {
        val role = "reader_" + UUID.randomUUID().toString().replace("-", "")
        connection().use { c -> c.createStatement().use {
            it.execute("CREATE ROLE $role")
            it.execute("CREATE TABLE public.$role (id integer)")
        } }
        var opened: Connection? = null
        try {
            val result = capture(connect = {
                connection().also { c -> opened = c; c.createStatement().use { it.execute("SET ROLE $role") } }
            })
            val entry = result.entries.single { it.name == role }
            assertFalse(entry.selectAccess)
            assertNotNull(entry.nTupIns, "No SELECT grant is required to read public table counters")
            assertTrue(opened!!.isClosed)
        } finally { connection().use { c -> c.createStatement().use {
            it.execute("DROP TABLE public.$role"); it.execute("DROP ROLE $role")
        } } }
    }

    @Test fun `track counts off preserves unknown counters instead of reporting zeros`() {
        val name = "off_" + UUID.randomUUID().toString().replace("-", "")
        connection().use { c -> c.createStatement().use { it.execute("CREATE TABLE public.$name(id integer)") } }
        try {
            val result = capture(connect = { connection().also { c -> c.createStatement().use { it.execute("SET track_counts=off") } } })
            assertFalse(result.source.trackCounts)
            assertTrue(TableSnapshotReason.TRACK_COUNTS_OFF in result.reasons)
            val entry = result.entries.single { it.name == name }
            assertNull(entry.nTupIns); assertNull(entry.nTupUpd); assertNull(entry.nTupDel); assertNull(entry.nTupHotUpd)
        } finally { connection().use { c -> c.createStatement().use { it.execute("DROP TABLE public.$name") } } }
    }

    @Test fun `disappearance and detectable counter reset are explicit diagnostics`() {
        val name = "gone_" + UUID.randomUUID().toString().replace("-", "")
        connection().use { c -> c.createStatement().use {
            it.execute("CREATE TABLE public.$name(id integer)")
            it.execute("INSERT INTO public.$name VALUES (1),(2)")
            it.execute("SELECT pg_catalog.pg_stat_force_next_flush()")
        } }
        val before = capture()
        try {
            connection().use { c -> c.createStatement().use {
                // Deliberate administrative test operation, never called by the collector.
                it.execute("SELECT pg_catalog.pg_stat_reset_single_table_counters('public.$name'::regclass)")
            } }
            val reset = capture()
            assertTrue(compareTableSnapshots(listOf(before, reset)).any { it["code"] == "COUNTERS_DECREASED" })
        } finally { connection().use { c -> c.createStatement().use { it.execute("DROP TABLE public.$name") } } }
        val after = capture()
        assertTrue(compareTableSnapshots(listOf(before, after)).any { it["code"] == "RELATION_DISAPPEARED" })
    }

    @Test fun `database reset between metadata reads marks the single snapshot incomplete`() {
        connection().use { admin ->
            fun resetAndRead(): String {
                admin.createStatement().use { it.execute("SELECT pg_catalog.pg_stat_reset()") }
                return admin.createStatement().use { statement ->
                    statement.executeQuery("SELECT stats_reset FROM pg_catalog.pg_stat_database WHERE datname=pg_catalog.current_database()").use {
                        assertTrue(it.next()); it.getTimestamp(1).toInstant().toString()
                    }
                }
            }
            val initial = resetAndRead()
            var expectedAfter: String? = null
            var metadataReads = 0
            val raw = connection()
            val wrapped = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "prepareStatement" && (args!![0] as String).contains("FROM pg_catalog.pg_database")) {
                    metadataReads++
                    if (metadataReads == 2) {
                        Thread.sleep(10) // Distinct published reset timestamps, including on coarse clocks.
                        expectedAfter = resetAndRead()
                    }
                }
                try { method.invoke(raw, *(args ?: emptyArray())) }
                catch (error: InvocationTargetException) { throw error.targetException }
            } as Connection
            try {
                val result = capture(connect = { wrapped })
                assertEquals(2, metadataReads)
                assertEquals(initial, result.resetBefore)
                assertNotNull(expectedAfter)
                assertNotEquals(initial, expectedAfter)
                assertEquals(expectedAfter, result.resetAfter)
                assertTrue(TableSnapshotReason.RESET_DETECTED in result.reasons)
                assertFalse(result.complete)
                assertTrue(raw.isClosed)
            } finally { raw.close() }
        }
    }

    @Test fun `row limit retains prefix and physical connection closes`() {
        connection().use { c -> c.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS public.limit_a(id integer)")
            it.execute("CREATE TABLE IF NOT EXISTS public.limit_b(id integer)")
        } }
        var opened: Connection? = null
        try {
            val result = capture(connect = { connection().also { opened = it } }, limits = SnapshotLimits(maxRows = 1))
            assertEquals(1, result.entries.size)
            assertTrue(TableSnapshotReason.ROW_LIMIT in result.reasons)
            assertTrue(opened!!.isClosed)
        } finally { connection().use { c -> c.createStatement().use { it.execute("DROP TABLE public.limit_a, public.limit_b") } } }
    }
}
