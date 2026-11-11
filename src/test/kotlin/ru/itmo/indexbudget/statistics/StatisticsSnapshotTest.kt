package ru.itmo.indexbudget.statistics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.ShutdownCancellation
import ru.itmo.indexbudget.cli.StatisticsCommand
import ru.itmo.indexbudget.domain.*
import tools.jackson.databind.json.JsonMapper
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

class StatisticsSnapshotTest {
    @TempDir lateinit var directory: Path
    private val source = UUID.fromString("d0d60c19-eb9d-42ee-9e01-935560eb7e20")

    @Test fun `row cap retains a prefix and marks it partial instead of top N`() {
        val db = FakeDatabase(List(3) { row(it.toLong()) })
        val result = JdbcStatisticsCollector({ db.connection }).collect(source, SnapshotLimits(maxRows = 2), RunContext())
        assertEquals(2, result.entries.size)
        assertEquals(listOf("ROW_LIMIT"), result.reasons)
        assertFalse(result.complete)
        assertTrue(db.closed)
        assertTrue(db.readOnly)
        assertTrue(db.sql.all { !it.contains("reset(") && !it.contains("ORDER BY") })
        assertEquals(0, db.commits)
    }

    @Test fun `restricted identity and large text are explicit and never replaced with fabricated SQL`() {
        val db = FakeDatabase(listOf(row(1).plus(mapOf("queryid" to null, "query" to "<insufficient privilege>")),
            row(2).plus(mapOf("query" to null, "text_too_large" to true))))
        val result = JdbcStatisticsCollector({ db.connection }).collect(source, SnapshotLimits(), RunContext())
        assertNull(result.entries[0].queryid)
        assertNull(result.entries[0].query)
        assertEquals("restricted", result.entries[0].textStatus)
        assertEquals("size_limit", result.entries[1].textStatus)
        assertTrue("QUERY_TEXT_LIMIT" in result.reasons)
    }

    @Test fun `byte cap counts JSON escaped UTF8 and keeps bounded valid output`() {
        val text = "\"\\\n😀".repeat(1000)
        val db = FakeDatabase(List(10) { row(it.toLong()).plus("query" to text) })
        val result = JdbcStatisticsCollector({ db.connection }).collect(source, SnapshotLimits(maxBytes = 32768), RunContext())
        assertTrue("BYTE_LIMIT" in result.reasons)
        val bytes = SnapshotJson().render(result)
        assertTrue(bytes.size <= 32768)
        val json = JsonMapper().readTree(bytes)
        assertEquals("partial", json["status"].stringValue())
        if (result.entries.isNotEmpty()) assertEquals(text, json["entries"][0]["query"].stringValue())
    }

    @Test fun `reset during capture is visible and bigint query ID is lossless`() {
        val db = FakeDatabase(listOf(row(Long.MIN_VALUE)), changedEpoch = true)
        val result = JdbcStatisticsCollector({ db.connection }).collect(source, SnapshotLimits(), RunContext())
        assertTrue("STATS_RESET_DURING_READ" in result.reasons)
        val json = JsonMapper().readTree(SnapshotJson().render(result))
        assertEquals(Long.MIN_VALUE.toString(), json["entries"][0]["queryid"].stringValue())
        assertEquals(source.toString(), json["source_id"].stringValue())
    }

    @Test fun `permission failure exposes no driver message and closes physical connection`() {
        val db = FakeDatabase(emptyList(), failure = SQLException("jdbc:postgresql://secret/db?password=private", "42501"))
        val error = assertThrows(AnalysisException::class.java) {
            JdbcStatisticsCollector({ db.connection }).collect(source, SnapshotLimits(), RunContext())
        }
        assertEquals(ErrorCode.STATISTICS_PERMISSION_DENIED, error.error.code)
        assertFalse(error.toString().contains("private"))
        assertNull(error.cause)
        assertTrue(db.closed)
    }

    @Test fun `deadline expiring during preparation prevents execute and closes statement and connection`() {
        var now = 0L
        val context = RunContext(clock = NanoClock { now })
        val db = FakeDatabase(emptyList(), onPrepare = { now = 100_000_000_000L })
        val error = assertThrows(AnalysisException::class.java) {
            JdbcStatisticsCollector({ db.connection }).collect(source, SnapshotLimits(), context)
        }
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.error.code)
        assertEquals(0, db.executions)
        assertTrue(db.statementClosed)
        assertTrue(db.closed)
    }

    @Test fun `pair interruption preserves first artifact and uses a separate collection context`() {
        val first = JdbcStatisticsCollector({ FakeDatabase(listOf(row(1))).connection })
        var collections = 0
        val command = StatisticsCommand(StatisticsCollector { id, limits, context ->
            collections++
            assertEquals(limits.timeout, context.limits.commandTimeout)
            first.collect(id, limits, context)
        }, ShutdownCancellation { AutoCloseable {} }, { throw InterruptedException() })
        val output = directory.resolve("pair")
        try {
            val result = command.execute(arrayOf("stats", "pair", "--source-id", source.toString(),
                "--output-dir", output.toString(), "--interval-seconds", "1"), PrintWriter(StringWriter()), PrintWriter(StringWriter()))
            assertEquals(3, result)
            assertEquals(1, collections)
            assertTrue(Files.exists(output.resolve("snapshot-1.json")))
            assertFalse(Files.exists(output.resolve("snapshot-2.json")))
            assertTrue(Files.readString(output.resolve("capture.json")).contains("CANCELLED"))
        } finally { Thread.interrupted() }
    }

    @Test fun `invalid options never open a connection or create output`() {
        val command = StatisticsCommand(StatisticsCollector { _, _, _ -> error("Must not collect") })
        for (option in listOf("0", "100001", "not-an-integer")) {
            assertEquals(2, command.execute(arrayOf("stats", "snapshot", "--source-id", source.toString(),
                "--output-dir", directory.resolve(option).toString(), "--max-rows", option),
                PrintWriter(StringWriter()), PrintWriter(StringWriter())))
        }
        assertEquals(0L, Files.list(directory).use { it.count() })
    }

    @Test fun `successful pair uses independent contexts and publishes both snapshots`() {
        val contexts = mutableListOf<RunContext>()
        val databases = mutableListOf<FakeDatabase>()
        val command = StatisticsCommand(StatisticsCollector { id, limits, context ->
            assertTrue(databases.all { it.closed }, "Previous collection must be closed before the next one")
            contexts += context
            val db = FakeDatabase(listOf(row(1)))
            databases += db
            JdbcStatisticsCollector({ db.connection }).collect(id, limits, context)
        }, ShutdownCancellation { AutoCloseable {} })
        val output = directory.resolve("success")
        assertEquals(0, command.execute(arrayOf("stats", "pair", "--source-id", source.toString(),
            "--output-dir", output.toString(), "--interval-seconds", "1"),
            PrintWriter(StringWriter()), PrintWriter(StringWriter())))
        assertEquals(2, contexts.size)
        assertNotSame(contexts[0], contexts[1])
        assertTrue(databases.all { it.closed })
        val manifest = JsonMapper().readTree(Files.readString(output.resolve("capture.json")))
        assertEquals(2, manifest["snapshots"].size())
        assertTrue(manifest["conditions_match"].booleanValue())
        assertTrue(Files.exists(output.resolve("snapshot-2.json")))
    }

    private fun row(id: Long): Map<String, Any?> = mapOf("dbid" to 10L, "userid" to 20L, "queryid" to id,
        "toplevel" to true, "query" to "SELECT 1", "text_too_large" to false,
        "calls" to 2L, "total_exec_time" to 1.25, "rows" to 2L)

    private class FakeDatabase(val rows: List<Map<String, Any?>>, val changedEpoch: Boolean = false,
        val failure: SQLException? = null, val onPrepare: () -> Unit = {}) {
        var closed = false
        var readOnly = false
        var commits = 0
        var executions = 0
        var epochs = 0
        var statementClosed = false
        val sql = mutableListOf<String>()
        val connection = proxy(Connection::class.java) { name, args -> when (name) {
            "setReadOnly" -> { readOnly = args!![0] as Boolean; null }
            "close", "abort" -> { closed = true; null }
            "commit" -> { commits++; null }
            "prepareStatement" -> {
                val query = args!![0] as String
                sql += query
                onPrepare()
                proxy(PreparedStatement::class.java) { method, _ -> when (method) {
                    "close" -> { statementClosed = true; null }
                    "executeQuery" -> {
                        executions++
                        failure?.let { throw it }
                        val values = when {
                            query.contains("FROM pg_catalog.pg_extension") -> listOf(mapOf(
                                "extension_schema" to "public", "dbid" to 10L, "userid" to 20L, "visible" to false,
                                "version_number" to 160015, "version" to "16.15", "extversion" to "1.10", "track" to "top"))
                            query.contains("pg_stat_statements_info") -> {
                                epochs++
                                listOf(mapOf("stats_reset" to java.sql.Timestamp.valueOf(
                                    if (changedEpoch && epochs > 1) "2026-11-11 01:00:00" else "2026-11-11 00:00:00"), "dealloc" to 0L))
                            }
                            else -> rows
                        }
                        result(values)
                    }
                    else -> null
                } }
            }
            else -> null
        } }

        companion object {
            private fun result(rows: List<Map<String, Any?>>): ResultSet {
                var index = -1
                var wasNull = false
                return proxy(ResultSet::class.java) { name, args -> when (name) {
                    "next" -> { index++; index < rows.size }
                    "wasNull" -> wasNull
                    "close" -> null
                    else -> {
                        val value = rows[index][args!![0].toString()]
                        wasNull = value == null
                        when (name) {
                            "getLong" -> (value as? Number)?.toLong() ?: 0L
                            "getInt" -> (value as? Number)?.toInt() ?: 0
                            "getDouble" -> (value as? Number)?.toDouble() ?: 0.0
                            "getBoolean" -> value as? Boolean ?: false
                            else -> value
                        }
                    }
                } }
            }
            private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T =
                type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> call(method.name, args) })
        }
    }
}
