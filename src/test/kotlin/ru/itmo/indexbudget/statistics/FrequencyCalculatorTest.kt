package ru.itmo.indexbudget.statistics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.indexbudget.domain.*
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class FrequencyCalculatorTest {
    private val calculator = FrequencyCalculator()
    private val source = StatisticsSource(UUID.fromString("d0d60c19-eb9d-42ee-9e01-935560eb7e20"), 42, 10, true, "16.15", "1.10", "top")
    private val epoch = StatisticsEpoch("2026-11-10T00:00:00Z", 7)
    private val origin = Instant.parse("2026-11-11T00:00:00Z")

    @Test fun `one real snapshot model pair yields frequency and separate historical milliseconds`() {
        val profile = profile(window())
        val row = profile.observations.single()
        assertEquals(60.0, profile.period.durationSeconds)
        assertEquals(58.0, profile.period.minimumDurationSeconds)
        assertEquals(62.0, profile.period.maximumDurationSeconds)
        assertEquals(origin.plusSeconds(1), profile.period.start)
        assertEquals(60L, row.deltaCalls)
        assertEquals(3600.0, row.callsPerHour)
        assertEquals(90.0, row.totalExecTimeMillis)
        assertEquals(1.5, row.meanExecTimeMillis)
        assertEquals(120L, row.deltaRows)
        assertTrue(profile.complete)
        assertEquals(ObservationState.OBSERVED, row.state)
    }

    @Test fun `windows retain their own duration traffic and normalized weights`() {
        val first = window()
        val second = window("night", 3.0).copy(after = snapshot(120, listOf(entry(160))))
        val result = calculator.calculate(listOf(first, second))
        assertEquals(listOf(3600.0, 1800.0), result.profiles.map { it.observations.single().callsPerHour })
        assertEquals(listOf(0.25, 0.75), result.profiles.map { it.normalizedWeight })
        assertEquals(1, result.commonUsableKeys.size)
        assertEquals(listOf(60.0, 120.0), result.profiles.map { it.period.durationSeconds })
    }

    @Test fun `zero is confirmed only from equal complete counters and has no mean execution time`() {
        val row = profile(window().let { it.copy(after = it.after.copy(entries = it.before.entries)) }).observations.single()
        assertEquals(ObservationState.ZERO, row.state)
        assertEquals(0.0, row.callsPerHour)
        assertEquals(0.0, row.totalExecTimeMillis)
        assertNull(row.meanExecTimeMillis)
    }

    @Test fun `full key distinguishes roles and top level without merging identical query IDs`() {
        val rows = listOf(entry(10), entry(20).copy(userid = 11), entry(30).copy(toplevel = false))
        val result = profile(window().copy(before = snapshot(0, rows),
            after = snapshot(60, rows.mapIndexed { index, row -> row.copy(calls = row.calls + index + 1) })))
        assertEquals(listOf(1L, 2L, 3L), result.observations.map { it.deltaCalls }.sortedBy { it })
        assertEquals(3, result.observations.map { it.key }.toSet().size)
    }

    @Test fun `new missing and hidden entries remain unknown rather than becoming zero`() {
        val hidden = entry(20).copy(queryid = null, query = null, textStatus = QueryTextStatus.RESTRICTED)
        val before = snapshot(0, listOf(entry(100), entry(50).copy(queryid = 2), hidden))
        val after = snapshot(60, listOf(entry(160), entry(5).copy(queryid = 3), hidden, hidden))
        val result = calculator.calculate(listOf(window().copy(before = before, after = after)))
        val profile = result.profiles.single()
        assertEquals(1, profile.unidentifiedBefore)
        assertEquals(2, profile.unidentifiedAfter)
        assertFalse(profile.complete)
        assertEquals(1, result.commonUsableKeys.size)
        assertEquals(setOf(FrequencyReason.NEW_ENTRY, FrequencyReason.MISSING_ENTRY),
            profile.observations.filter { it.state == ObservationState.UNKNOWN }.flatMap { it.reasons }.toSet())
        profile.observations.filter { it.state == ObservationState.UNKNOWN }.forEach {
            assertNull(it.callsPerHour); assertNull(it.deltaCalls); assertNull(it.totalExecTimeMillis)
        }
    }

    @Test fun `intersection does not invent a missing zero for another profile`() {
        val first = window()
        val second = window("night").copy(before = snapshot(120, listOf(entry(200), entry(5).copy(queryid = 2))),
            after = snapshot(180, listOf(entry(210), entry(8).copy(queryid = 2))))
        val result = calculator.calculate(listOf(first, second))
        assertEquals(setOf(1L), result.commonUsableKeys.map { it.queryid }.toSet())
        assertEquals(listOf(1, 2), result.profiles.map { it.observations.size })
    }

    @ParameterizedTest @ValueSource(ints = [0, 1, 2, 3])
    fun `reset at any epoch boundary invalidates all observations`(boundary: Int) {
        val original = window()
        val changed = epoch.copy(statsReset = "2026-11-10T12:00:00Z")
        val request = when (boundary) {
            0 -> original.copy(before = original.before.copy(before = changed))
            1 -> original.copy(before = original.before.copy(after = changed))
            2 -> original.copy(after = original.after.copy(before = changed))
            else -> original.copy(after = original.after.copy(after = changed))
        }
        assertUnknown(request, FrequencyReason.STATS_RESET)
    }

    @ParameterizedTest @ValueSource(longs = [6, 8])
    fun `dealloc change invalidates even matching keys with increasing counters`(value: Long) {
        val request = window().let { it.copy(after = it.after.copy(before = epoch.copy(dealloc = value), after = epoch.copy(dealloc = value))) }
        assertUnknown(request, FrequencyReason.DEALLOC_CHANGED)
    }

    @Test fun `negative dealloc counter is a numeric error like other negative counters`() {
        val request = window().let { it.copy(before = it.before.copy(after = epoch.copy(dealloc = -1))) }
        assertEquals(ErrorCode.NUMERIC_ERROR, assertThrows(AnalysisException::class.java) { profile(request) }.error.code)
    }

    @Test fun `missing ending epoch cannot establish counter continuity`() {
        assertUnknown(window().let { it.copy(before = it.before.copy(after = null)) }, FrequencyReason.MISSING_EPOCH)
    }

    @ParameterizedTest @ValueSource(strings = ["calls", "rows", "time"])
    fun `decrease in any counter invalidates the entry`(counter: String) {
        val after = when (counter) { "calls" -> entry(99); "rows" -> entry(160).copy(rows = 0); else -> entry(160).copy(totalExecTime = 0.0) }
        assertUnknown(window().copy(after = snapshot(60, listOf(after))), FrequencyReason.COUNTER_DECREASE)
    }

    @Test fun `unchanged calls with increasing rows is inconsistent not confirmed zero`() {
        assertUnknown(window().copy(after = snapshot(60, listOf(entry(100).copy(rows = 201)))), FrequencyReason.INCONSISTENT_COUNTERS)
    }

    @Test fun `changed representative text is retained with a conservative rejection`() {
        assertUnknown(window().copy(after = snapshot(60, listOf(entry(160).copy(query = "SELECT 2")))), FrequencyReason.QUERY_TEXT_CHANGED)
    }

    @Test fun `partial collection salvages matched counters but reports incomplete coverage`() {
        val request = window().let { it.copy(before = it.before.copy(reasons = listOf(SnapshotReason.ROW_LIMIT))) }
        val profile = profile(request)
        assertEquals(3600.0, profile.observations.single().callsPerHour)
        assertFalse(profile.complete)
        assertEquals(listOf(SnapshotReason.ROW_LIMIT), profile.beforeReasons)
    }

    @Test fun `missing text with known identity does not erase observed frequency`() {
        val profile = profile(window().let { it.copy(after = it.after.copy(entries = listOf(entry(160).copy(query = null, textStatus = QueryTextStatus.UNAVAILABLE)))) })
        assertEquals(3600.0, profile.observations.single().callsPerHour)
        assertNull(profile.observations.single().query)
    }

    @ParameterizedTest @ValueSource(strings = ["source", "database", "reader", "visibility", "version", "extension", "track"])
    fun `incompatible sources are rejected`(field: String) {
        val changed = when (field) {
            "source" -> source.copy(sourceId = UUID.randomUUID()); "database" -> source.copy(databaseOid = 43)
            "reader" -> source.copy(readerUserid = 11); "visibility" -> source.copy(allQueryTextsVisible = false)
            "version" -> source.copy(postgresVersion = "16.14"); "extension" -> source.copy(extensionVersion = "1.9")
            else -> source.copy(track = "all")
        }
        invalid(window().let { it.copy(after = it.after.copy(source = changed)) })
    }

    @ParameterizedTest @ValueSource(strings = ["reversed", "overlap", "zero", "invalid", "same_id"])
    fun `invalid time boundaries and repeated snapshot identity are rejected`(condition: String) {
        val request = window()
        invalid(when (condition) {
            "reversed" -> request.copy(before = request.before.copy(finishedAt = origin.minusSeconds(1).toString()))
            "overlap" -> request.copy(after = request.after.copy(startedAt = origin.plusSeconds(1).toString()))
            "zero" -> request.copy(before = request.before.copy(finishedAt = origin.toString()),
                after = request.after.copy(startedAt = origin.toString(), finishedAt = origin.toString()))
            "invalid" -> request.copy(before = request.before.copy(startedAt = "invalid"))
            else -> request.copy(after = request.after.copy(snapshotId = request.before.snapshotId))
        })
    }

    @Test fun `duplicate keys or windows and invalid profile count are rejected`() {
        invalid(window().let { it.copy(before = it.before.copy(entries = it.before.entries + it.before.entries)) })
        listOf(emptyList(), List(4) { window("profile-$it") }, listOf(window(), window("duplicate"))).forEach {
            assertEquals(ErrorCode.INVALID_INPUT, assertThrows(AnalysisException::class.java) { calculator.calculate(it) }.error.code)
        }
    }

    @Test fun `weights and invalid counters retain numeric errors`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0).forEach { weight ->
            assertEquals(ErrorCode.NUMERIC_ERROR, assertThrows(AnalysisException::class.java) { profile(window(weight = weight)) }.error.code)
        }
        val request = window().copy(after = snapshot(60, listOf(entry(160).copy(totalExecTime = Double.NaN))))
        assertEquals(ErrorCode.NUMERIC_ERROR, assertThrows(AnalysisException::class.java) { profile(request) }.error.code)
    }

    @Test fun `historical aggregate overflow fails instead of producing infinity`() {
        val before = snapshot(0, listOf(entry(0), entry(0).copy(queryid = 2)))
        val after = snapshot(60, before.entries.map { it.copy(calls = 1, totalExecTime = Double.MAX_VALUE) })
        assertEquals(ErrorCode.NUMERIC_ERROR, assertThrows(AnalysisException::class.java) {
            profile(window().copy(before = before, after = after))
        }.error.code)
    }

    @Test fun `fractional seconds and maximum bigint increments do not overflow intermediate arithmetic`() {
        val before = snapshot(0, listOf(entry(0))).copy(finishedAt = origin.toString())
        val after = snapshot(60, listOf(entry(Long.MAX_VALUE).copy(rows = 0, totalExecTime = 0.0))).copy(
            startedAt = origin.plusNanos(500_000_000).toString(), finishedAt = origin.plusNanos(500_000_000).toString())
        val row = profile(window().copy(before = before, after = after)).observations.single()
        assertEquals(Long.MAX_VALUE, row.deltaCalls)
        assertEquals(Long.MAX_VALUE.toDouble() * 7200, row.callsPerHour)
    }

    @Test fun `small increment of huge accumulated counters subtracts longs before double conversion`() {
        val before = snapshot(0, listOf(entry(1L shl 53)))
        val after = snapshot(60, listOf(entry((1L shl 53) + 1L)))
        val row = profile(window().copy(before = before, after = after)).observations.single()
        assertEquals(ObservationState.OBSERVED, row.state)
        assertEquals(1L, row.deltaCalls)
        assertEquals(2L, row.deltaRows)
        assertEquals(60.0, row.callsPerHour)
    }

    @Test fun `manifest keeps bigint IDs unknowns and historical units separate`() {
        val request = window().let { it.copy(before = it.before.copy(entries = listOf(entry(100).copy(queryid = Long.MIN_VALUE)))) }
        val result = calculator.calculate(listOf(request))
        val json = JsonMapper().readTree(FrequencyJson().render(result))
        assertEquals(1, json["schema_version"].intValue())
        assertEquals("pg_stat_statements_profiles", json["kind"].stringValue())
        assertEquals(Long.MIN_VALUE.toString(), json["profiles"][0]["observations"][0]["key"]["queryid"].stringValue())
        assertTrue(json["profiles"][0]["observations"][0]["calls_per_hour"].isNull)
        assertEquals("partial", json["profiles"][0]["status"].stringValue())
    }

    @Test fun `synthetic documented example has independently known frequency and duration`() {
        val result = calculator.calculate(listOf(window()))
        val bytes = FrequencyJson().render(result)
        val json = JsonMapper().readTree(bytes)
        assertEquals(3600.0, json["profiles"][0]["observations"][0]["calls_per_hour"].doubleValue())
        assertEquals(90.0, json["profiles"][0]["observed_total_exec_time_ms"].doubleValue())
        val output = Path.of("build/frequency-example.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json))
    }

    @Test fun `calculation honors cancellation without starting search or explain counters`() {
        val context = RunContext()
        context.cancel()
        assertEquals(ErrorCode.CANCELLED, assertThrows(AnalysisException::class.java) { calculator.calculate(listOf(window()), context) }.error.code)
    }

    @Test fun `successful calculation leaves a live context untouched and search still startable`() {
        val context = RunContext()
        val statisticsBefore = context.statistics()
        assertEquals(3600.0, calculator.calculate(listOf(window()), context).profiles.single().observations.single().callsPerHour)
        assertEquals(statisticsBefore, context.statistics())
        assertEquals(AnalysisPhase.VALIDATION, context.phase)
        context.startSearch()
    }

    private fun assertUnknown(window: FrequencyWindow, reason: FrequencyReason) {
        val result = profile(window)
        assertFalse(result.complete)
        result.observations.forEach { assertEquals(ObservationState.UNKNOWN, it.state); assertNull(it.callsPerHour); assertTrue(reason in it.reasons) }
    }
    private fun invalid(window: FrequencyWindow) = assertEquals(ErrorCode.INVALID_INPUT,
        assertThrows(AnalysisException::class.java) { profile(window) }.error.code)
    private fun profile(window: FrequencyWindow) = calculator.calculate(listOf(window)).profiles.single()
    private fun window(id: String = "day", weight: Double = 1.0) = FrequencyWindow(id, weight, snapshot(0, listOf(entry(100))), snapshot(60, listOf(entry(160))))
    private fun entry(calls: Long) = StatisticsEntry(42, 10, 1, true, "SELECT id FROM records WHERE id = $1", QueryTextStatus.AVAILABLE,
        calls, calls.toDouble() * 1.5, if (calls <= Long.MAX_VALUE / 2) calls * 2 else 0)
    private fun snapshot(second: Long, entries: List<StatisticsEntry>) = StatisticsSnapshot(UUID.nameUUIDFromBytes("snapshot-$second".toByteArray()),
        source, origin.plusSeconds(second).toString(), origin.plusSeconds(second + 2).toString(), epoch, epoch, entries, emptyList(), SnapshotLimits())
}
