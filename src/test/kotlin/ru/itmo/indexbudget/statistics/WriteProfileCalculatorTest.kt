package ru.itmo.indexbudget.statistics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.TableWriteRates
import ru.itmo.indexbudget.domain.WriteRateSource
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class WriteProfileCalculatorTest {
    private val calculator = WriteProfileCalculator()

    @Test
    fun `known increments on different intervals give consistent rows per hour`() {
        // One hour window: +10 inserts, +5 updates, +2 deletes, +20 HOT updates.
        val hour = calculator.calculate(
            snapshot(Instant.parse("2026-11-12T10:00:00Z"), inserts = 100, updates = 50, deletes = 10),
            snapshot(Instant.parse("2026-11-12T11:00:00Z"), inserts = 110, updates = 55, deletes = 12, hot = 20),
        )
        // Half an hour window: +5 inserts and +1 delete are the same per-hour rate.
        val half = calculator.calculate(
            snapshot(Instant.parse("2026-11-12T10:00:00Z"), inserts = 100, deletes = 10),
            snapshot(Instant.parse("2026-11-12T10:30:00Z"), inserts = 105, deletes = 11),
        )

        for (profile in listOf(hour, half)) {
            assertTrue(profile.complete)
            assertTrue(profile.reasons.isEmpty())
            assertEquals(10.0, profile.rates.getValue("public.records").insertsPerHour, 1e-9)
            assertEquals(2.0, profile.rates.getValue("public.records").deletesPerHour, 1e-9)
            assertEquals(WriteRateSource.OBSERVED, profile.rates.getValue("public.records").source)
        }
        assertEquals(3600.0, hour.periodSeconds, 1e-9)
        assertEquals(1800.0, half.periodSeconds, 1e-9)
        assertEquals(5.0, hour.rates.getValue("public.records").updatesPerHour, 1e-9)
        // HOT updates stay a historical diagnostic and never enter the rates.
        assertEquals(20.0, hour.hotUpdatesPerHour.getValue("public.records"), 1e-9)
        assertEquals(0.0, hour.hotUpdatesPerHour.getValue("public.records") - 20.0, 1e-9)
    }

    @Test
    fun `zero deltas are valid zero rates and fractional rates survive`() {
        val idle = calculator.calculate(
            snapshot(Instant.parse("2026-11-12T10:00:00Z")),
            snapshot(Instant.parse("2026-11-12T12:00:00Z")),
        )
        assertTrue(idle.complete)
        val rates = idle.rates.getValue("public.records")
        assertEquals(0.0, rates.insertsPerHour)
        assertEquals(0.0, rates.updatesPerHour)
        assertEquals(0.0, rates.deletesPerHour)

        // One changed row over two hours is a fractional half-row per hour.
        val fractional = calculator.calculate(
            snapshot(Instant.parse("2026-11-12T10:00:00Z"), deletes = 0),
            snapshot(Instant.parse("2026-11-12T12:00:00Z"), deletes = 1),
        )
        assertEquals(0.5, fractional.rates.getValue("public.records").deletesPerHour, 1e-12)
    }

    @Test
    fun `a decreased counter keeps the table faulty instead of inventing a negative rate`() {
        val profile = calculator.calculate(
            snapshot(Instant.parse("2026-11-12T10:00:00Z"), inserts = 100),
            snapshot(Instant.parse("2026-11-12T11:00:00Z"), inserts = 60),
        )

        assertFalse(profile.complete)
        assertTrue(WriteProfileReason.COUNTER_DECREASE in profile.reasons)
        assertEquals(WriteProfileReason.COUNTER_DECREASE, profile.tableFaults.getValue("public.records"))
        assertNull(profile.rates["public.records"])
        assertNull(profile.hotUpdatesPerHour["public.records"])
    }

    @Test
    fun `a missing table on either side is unknown, not zero`() {
        val before = snapshot(Instant.parse("2026-11-12T10:00:00Z"))
            .let { it.copy(entries = it.entries + entry(16386, "public", "orders")) }
        val after = snapshot(Instant.parse("2026-11-12T11:00:00Z"))
            .let { it.copy(entries = it.entries + entry(16387, "public", "billing", inserts = 5)) }
        val profile = calculator.calculate(before, after)

        assertFalse(profile.complete)
        assertTrue(WriteProfileReason.TABLE_MISSING in profile.reasons)
        assertEquals(WriteProfileReason.TABLE_MISSING, profile.tableFaults.getValue("public.orders"))
        assertEquals(WriteProfileReason.TABLE_MISSING, profile.tableFaults.getValue("public.billing"))
        // The sound table keeps its rate; the missing ones never become zero.
        assertEquals(0.0, profile.rates.getValue("public.records").insertsPerHour)
        assertNull(profile.rates["public.orders"])
    }

    @Test
    fun `a changed shared reset epoch invalidates the whole window`() {
        val before = snapshot(Instant.parse("2026-11-12T10:00:00Z"), inserts = 100)
        val after = snapshot(Instant.parse("2026-11-12T11:00:00Z"), inserts = 130, statsReset = "2026-11-12T10:30:00Z")

        val profile = calculator.calculate(before, after)

        assertFalse(profile.complete)
        assertTrue(WriteProfileReason.STATS_RESET in profile.reasons)
        assertEquals(WriteProfileReason.STATS_RESET, profile.tableFaults.getValue("public.records"))
        assertTrue(profile.rates.isEmpty())
    }

    @Test
    fun `incompatible pairs and tracking failures are rejected as input errors`() {
        val base = snapshot(Instant.parse("2026-11-12T10:00:00Z"))
        val later = snapshot(Instant.parse("2026-11-12T11:00:00Z"))
        val differentDatabase = later.copy(source = later.source.copy(databaseOid = 99999))
        val differentReader = later.copy(source = later.source.copy(readerUserid = 99))
        val trackingOff = later.copy(source = later.source.copy(trackCounts = false))
        val overlapping = snapshot(Instant.parse("2026-11-12T09:30:00Z"))
        val sameInstant = snapshot(Instant.parse("2026-11-12T10:00:00Z"))

        for (after in listOf(base, differentDatabase, differentReader, trackingOff, overlapping, sameInstant)) {
            val error = assertThrows(AnalysisException::class.java) {
                calculator.calculate(base, after)
            }
            assertEquals(ErrorCode.INVALID_INPUT, error.error.code, after.toString())
        }
    }

    @Test
    fun `snapshot json roundtrips a versioned table write snapshot`() {
        val snapshot = snapshot(Instant.parse("2026-11-12T10:00:00Z"), inserts = 3, hot = 7).let {
            it.copy(entries = it.entries + entry(16386, "public", "orders", updates = 4))
        }
        val path = Path.of(java.nio.file.Files.createTempDirectory("ib-write-snapshot").toString(), "snapshot.json")
        java.nio.file.Files.write(path, TableWriteSnapshotJson.render(snapshot))

        val parsed = TableWriteSnapshotJson.read(path)

        assertEquals(snapshot, parsed)
        assertEquals("pg_stat_user_tables_snapshot", String(TableWriteSnapshotJson.render(parsed))
            .substringAfter("\"kind\":\"").substringBefore('"'))
    }

    @Test
    fun `snapshot json rejects disabled tracking and inconsistent metadata`() {
        fun write(name: String, mutate: (String) -> String): Path {
            val base = String(TableWriteSnapshotJson.render(snapshot(Instant.parse("2026-11-12T10:00:00Z"))))
            val path = Path.of(java.nio.file.Files.createTempDirectory("ib-write-snapshot").toString(), name)
            java.nio.file.Files.write(path, mutate(base).toByteArray())
            return path
        }

        val trackingOff = write("off.json") { it.replace("\"track_counts\":true", "\"track_counts\":false") }
        val wrongKind = write("kind.json") { it.replace("pg_stat_user_tables_snapshot", "pg_stat_statements_snapshot") }
        val wrongCount = write("count.json") { it.replace("\"row_count\":1", "\"row_count\":2") }
        for (path in listOf(trackingOff, wrongKind, wrongCount)) {
            val error = assertThrows(AnalysisException::class.java) { TableWriteSnapshotJson.read(path) }
            assertEquals(ErrorCode.INVALID_INPUT, error.error.code)
        }
    }

    @Test
    fun `identical manual and observed rates give identical budget decisions`() {
        val metrics = ru.itmo.indexbudget.domain.MetricsCalculatorImpl()
        val table = ru.itmo.indexbudget.domain.TableRef("public", "records")
        val candidate = ru.itmo.indexbudget.domain.IndexCandidate("idx",
            ru.itmo.indexbudget.domain.IndexDefinition(table,
                listOf(ru.itmo.indexbudget.domain.IndexKey(
                    ru.itmo.indexbudget.domain.ColumnRef(table, "value"),
                    operatorClass = "pg_catalog.int4_ops"))),
            emptySet(), setOf(ru.itmo.indexbudget.domain.GenerationReason.SINGLE_COLUMN), estimatedBytes = 100)
        fun input(source: ru.itmo.indexbudget.domain.WriteRateSource,
            origin: ru.itmo.indexbudget.domain.WriteRatesOrigin?) =
            ru.itmo.indexbudget.domain.WorkloadInput(
                listOf(ru.itmo.indexbudget.domain.QueryInput("q1", "SELECT 1")),
                listOf(ru.itmo.indexbudget.domain.Profile("only", 1.0, mapOf("q1" to 1.0))),
                ru.itmo.indexbudget.domain.Constraints(10_000, 100.0, 2),
                tableWriteRates = mapOf("public.records" to TableWriteRates(10.0, 5.0, 2.0, source)),
                tableWriteRatesOrigin = origin,
            )
        val observedOrigin = ru.itmo.indexbudget.domain.WriteRatesOrigin(
            from = "2026-11-12T10:00:00Z", to = "2026-11-12T11:00:00Z", periodSeconds = 3600.0,
            complete = true, reasons = emptyList(), databaseOid = 16384, postgresVersion = "16.8")

        val manual = metrics.assessBudget(listOf(candidate), input(WriteRateSource.MANUAL, null))
        val observed = metrics.assessBudget(listOf(candidate),
            input(WriteRateSource.OBSERVED, observedOrigin))

        assertEquals(manual, observed)
        assertEquals(manual.admissible, observed.admissible)
        assertEquals(manual.usage.budgetedBytes, observed.usage.budgetedBytes)
        assertEquals(manual.usage.writeUnitsPerHour, observed.usage.writeUnitsPerHour)
    }

    private fun snapshot(
        at: Instant,
        inserts: Long = 0,
        updates: Long = 0,
        deletes: Long = 0,
        hot: Long? = 0,
        statsReset: String? = null,
    ) = TableWriteSnapshot(
        snapshotId = UUID.nameUUIDFromBytes((at.toString() + inserts + updates + deletes + statsReset).toByteArray()),
        source = TableWriteSource(UUID.fromString("00000000-0000-0000-0000-000000000001"),
            16384, 10, trackCounts = true, postgresVersion = "16.8", statsReset = statsReset),
        startedAt = at.toString(),
        finishedAt = at.toString(),
        entries = listOf(entry(16385, "public", "records", inserts, updates, deletes, hot)),
        complete = true,
    )

    private fun entry(
        tableOid: Long,
        schema: String,
        table: String,
        inserts: Long = 0,
        updates: Long = 0,
        deletes: Long = 0,
        hot: Long? = 0,
    ) = TableWriteEntry(tableOid, schema, table, inserts, updates, deletes, hot)
}
