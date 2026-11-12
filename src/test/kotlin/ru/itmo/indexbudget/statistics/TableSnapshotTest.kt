package ru.itmo.indexbudget.statistics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.ShutdownCancellation
import ru.itmo.indexbudget.cli.TableStatisticsCommand
import ru.itmo.indexbudget.domain.*
import tools.jackson.databind.json.JsonMapper
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class TableSnapshotTest {
    @TempDir lateinit var directory: Path
    private val source = TableStatisticsSource(UUID.fromString("4ada107c-d989-4e80-9836-7fc1d14182cf"), 10, 20, "16.15", true)
    private fun snapshot(entries: List<TableCounters> = listOf(TableCounters(30, "public", "t", false, 10, 8, 2, 5)),
                         reasons: List<TableSnapshotReason> = emptyList()) = TableSnapshot(UUID.randomUUID(), source,
        "2026-11-12T00:00:00Z", "2026-11-12T00:00:01Z", null, null, "2026-11-12T00:00:00Z", entries, reasons, SnapshotLimits())

    @Test fun `format uses relation OIDs and retains HOT without subtracting from updates`() {
        val result = JsonMapper().readTree(TableSnapshotJson().render(snapshot()))
        assertEquals("pg_table_statistics_snapshot", result["kind"].stringValue())
        assertEquals(8, result["entries"][0]["n_tup_upd"].intValue())
        assertEquals(5, result["entries"][0]["n_tup_hot_upd"].intValue())
        assertNull(result["entries"][0]["queryid"])
        assertTrue(result["database_stats_reset_before"].isNull)
    }

    @Test fun `missing counters stay null in JSON and do not become zero rates`() {
        val value = snapshot(listOf(TableCounters(30, "public", "t", false, null, null, null, null)),
            listOf(TableSnapshotReason.TRACK_COUNTS_OFF, TableSnapshotReason.COUNTERS_UNAVAILABLE))
        val json = JsonMapper().readTree(TableSnapshotJson().render(value))
        assertEquals("partial", json["status"].stringValue())
        assertTrue(json["entries"][0]["n_tup_upd"].isNull)
    }

    @Test fun `partial snapshots never imply disappearance and source mismatch never correlates OIDs`() {
        val first = snapshot()
        val partial = snapshot(emptyList(), listOf(TableSnapshotReason.ROW_LIMIT))
        assertEquals(listOf("INCOMPLETE_SNAPSHOTS"), compareTableSnapshots(listOf(first, partial)).map { it["code"] })
        val foreign = first.copy(source = source.copy(sourceId = UUID.randomUUID()))
        assertEquals(listOf("SOURCE_OR_CONDITIONS_CHANGED"), compareTableSnapshots(listOf(first, foreign)).map { it["code"] })
    }

    @Test fun `rename reset and disappearing identities have separate diagnostics`() {
        val first = snapshot()
        val second = snapshot(listOf(first.entries.single().copy(name = "renamed", nTupIns = 1)))
            .copy(resetAfter = "2026-11-12T00:00:02Z")
        val codes = compareTableSnapshots(listOf(first, second)).map { it["code"] }
        assertTrue("RELATION_RENAMED" in codes)
        assertTrue("COUNTERS_DECREASED" in codes)
        assertTrue("RESET_METADATA_CHANGED" in codes)
        assertEquals("RELATION_DISAPPEARED", compareTableSnapshots(listOf(first, snapshot(emptyList()))).single()["code"])
    }

    @Test fun `pair interruption keeps first artifact and closes collection before waiting`() {
        var calls = 0
        val command = TableStatisticsCommand(TableStatisticsCollector { id, _, _ ->
            calls++; assertEquals(source.sourceId, id); snapshot()
        }, ShutdownCancellation { AutoCloseable {} }, { throw InterruptedException() })
        val path = directory.resolve("pair")
        try {
            val code = command.execute(arrayOf("table-stats", "pair", "--source-id", source.sourceId.toString(),
                "--output-dir", path.toString(), "--interval-seconds", "1"), PrintWriter(StringWriter()), PrintWriter(StringWriter()))
            assertEquals(3, code)
            assertEquals(1, calls)
            assertTrue(Files.exists(path.resolve("snapshot-1.json")))
            assertTrue(Files.readString(path.resolve("capture.json")).contains("CANCELLED"))
        } finally { Thread.interrupted() }
    }

    @Test fun `independent pair contexts publish diagnostics for disappeared relations`() {
        val contexts = mutableListOf<RunContext>()
        val command = TableStatisticsCommand(TableStatisticsCollector { _, _, context ->
            contexts += context
            if (contexts.size == 1) snapshot() else snapshot(emptyList())
        }, ShutdownCancellation { AutoCloseable {} })
        val path = directory.resolve("success")
        val code = command.execute(arrayOf("table-stats", "pair", "--source-id", source.sourceId.toString(),
            "--output-dir", path.toString(), "--interval-seconds", "1"), PrintWriter(StringWriter()), PrintWriter(StringWriter()))
        assertEquals(3, code)
        assertNotSame(contexts[0], contexts[1])
        val json = JsonMapper().readTree(Files.readString(path.resolve("capture.json")))
        assertEquals("pg_table_statistics_capture", json["kind"].stringValue())
        assertFalse(json["conditions_match"].booleanValue())
        assertEquals("RELATION_DISAPPEARED", json["diagnostics"][0]["code"].stringValue())
    }
}
