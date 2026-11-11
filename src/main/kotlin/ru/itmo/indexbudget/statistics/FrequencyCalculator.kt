package ru.itmo.indexbudget.statistics

import ru.itmo.indexbudget.domain.*
import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

class FrequencyCalculator {
    fun calculate(windows: List<FrequencyWindow>, context: RunContext = RunContext()): FrequencyProfiles {
        context.checkDeadline()
        requireInput(windows.size in 1..3, "Provide one to three observation windows.")
        requireInput(windows.all { it.id.isNotBlank() } && windows.map { it.id }.distinct().size == windows.size,
            "Window IDs must be nonblank and unique.")
        requireInput(windows.map { it.before.snapshotId to it.after.snapshotId }.distinct().size == windows.size,
            "The same snapshot pair cannot be imported twice.")
        val source = windows.first().before.source
        requireInput(source.databaseOid > 0 && source.readerUserid > 0 && source.postgresVersion.startsWith("16.") &&
            source.extensionVersion.isNotBlank() && source.track in setOf("top", "all"),
            "A PostgreSQL 16 source with enabled tracking and known scope is required.")
        requireInput(windows.all { it.before.source == source && it.after.source == source },
            "Observation windows must have identical sources, scope, visibility and versions.")
        val weights = ProfileWeights.normalize(windows.map { Profile(it.id, it.weight, emptyMap()) })
        return FrequencyProfiles(source, windows.map { calculateWindow(it, weights.getValue(it.id), context) })
    }

    private fun calculateWindow(window: FrequencyWindow, normalizedWeight: Double, context: RunContext): FrequencyProfile {
        val before = window.before
        val after = window.after
        requireInput(before.snapshotId != after.snapshotId, "A window requires two different snapshots.")
        val firstStart = instant(before.startedAt)
        val firstEnd = instant(before.finishedAt)
        val lastStart = instant(after.startedAt)
        val lastEnd = instant(after.finishedAt)
        requireInput(firstStart <= firstEnd && lastStart <= lastEnd && firstEnd <= lastStart,
            "Capture intervals must be ordered and must not overlap.")
        val start = firstStart.plus(Duration.between(firstStart, firstEnd).dividedBy(2))
        val end = lastStart.plus(Duration.between(lastStart, lastEnd).dividedBy(2))
        requireInput(start < end, "Observation duration must be positive.")
        val duration = seconds(start, end)
        val period = ObservationPeriod(firstStart, firstEnd, lastStart, lastEnd, start, end,
            finite(duration), finite(seconds(firstEnd, lastStart)), finite(seconds(firstStart, lastEnd)))
        val firstEntries = index(before, context)
        val lastEntries = index(after, context)
        val unidentifiedBefore = before.entries.count { it.queryid == null }
        val unidentifiedAfter = after.entries.count { it.queryid == null }
        val reasons = linkedSetOf<FrequencyReason>()
        if (!before.complete || !after.complete) reasons += FrequencyReason.INCOMPLETE_SNAPSHOT
        if (unidentifiedBefore + unidentifiedAfter > 0) reasons += FrequencyReason.UNIDENTIFIED_ENTRIES
        val epochReasons = linkedSetOf<FrequencyReason>()
        if (before.after == null || after.after == null) epochReasons += FrequencyReason.MISSING_EPOCH
        val epochs = listOfNotNull(before.before, before.after, after.before, after.after)
        epochs.forEach { if (it.dealloc < 0) fail(ErrorCode.NUMERIC_ERROR, "Deallocation counters must be nonnegative.") }
        if (epochs.map { instant(it.statsReset) }.distinct().size != 1 ||
            SnapshotReason.STATS_RESET_DURING_READ in before.reasons + after.reasons) epochReasons += FrequencyReason.STATS_RESET
        if (epochs.map { it.dealloc }.distinct().size != 1 ||
            SnapshotReason.DEALLOC_DURING_READ in before.reasons + after.reasons) epochReasons += FrequencyReason.DEALLOC_CHANGED
        reasons += epochReasons
        val keys = (firstEntries.keys + lastEntries.keys).sortedWith(
            compareBy<StatisticsKey> { it.dbid }.thenBy { it.userid }.thenBy { it.queryid }.thenBy { it.toplevel })
        val observations = keys.map { key ->
            context.checkDeadline()
            observation(key, firstEntries[key], lastEntries[key],
                entryFaults(firstEntries[key], lastEntries[key], epochReasons), duration)
        }
        fun total(value: (FrequencyObservation) -> Double?): Double = finite(observations.fold(BigDecimal.ZERO) { sum, row ->
            context.checkDeadline()
            sum + (value(row)?.let(BigDecimal::valueOf) ?: BigDecimal.ZERO)
        })
        return FrequencyProfile(window.id, window.weight, normalizedWeight, before.snapshotId, after.snapshotId,
            period, observations, reasons, before.reasons.toList(), after.reasons.toList(), unidentifiedBefore, unidentifiedAfter,
            total { it.callsPerHour }, total { it.totalExecTimeMillis })
    }

    /** Continuity faults of one full key: epoch problems plus new/missing entries and counter regressions. */
    private fun entryFaults(
        first: StatisticsEntry?,
        last: StatisticsEntry?,
        epochReasons: Set<FrequencyReason>,
    ): Set<FrequencyReason> {
        val faults = epochReasons.toMutableSet()
        if (first == null) faults += FrequencyReason.NEW_ENTRY
        if (last == null) faults += FrequencyReason.MISSING_ENTRY
        if (first != null && last != null) {
            if (last.calls < first.calls || last.rows < first.rows || last.totalExecTime < first.totalExecTime)
                faults += FrequencyReason.COUNTER_DECREASE
            if (last.calls == first.calls && (last.rows != first.rows || last.totalExecTime != first.totalExecTime))
                faults += FrequencyReason.INCONSISTENT_COUNTERS
            if (first.query != null && last.query != null && first.query != last.query)
                faults += FrequencyReason.QUERY_TEXT_CHANGED
        }
        return faults
    }

    /** Builds one observation from an entry pair: UNKNOWN keeps null increments, otherwise counters are subtracted as Long/BigDecimal before conversion. */
    private fun observation(
        key: StatisticsKey,
        first: StatisticsEntry?,
        last: StatisticsEntry?,
        faults: Set<FrequencyReason>,
        durationSeconds: BigDecimal,
    ): FrequencyObservation {
        val entry = requireNotNull(last ?: first)
        if (faults.isNotEmpty()) return FrequencyObservation(
            key = key, state = ObservationState.UNKNOWN, query = entry.query, textStatus = entry.textStatus,
            deltaCalls = null, callsPerHour = null, deltaRows = null,
            totalExecTimeMillis = null, meanExecTimeMillis = null, reasons = faults,
        )
        val start = requireNotNull(first)
        val end = requireNotNull(last)
        val deltaCalls = end.calls - start.calls
        val deltaRows = end.rows - start.rows
        val executionTime = BigDecimal.valueOf(end.totalExecTime) - BigDecimal.valueOf(start.totalExecTime)
        val callsPerHour = BigDecimal.valueOf(deltaCalls).multiply(BigDecimal(3600)).divide(durationSeconds, MathContext.DECIMAL128)
        return FrequencyObservation(
            key = key,
            state = if (deltaCalls == 0L) ObservationState.ZERO else ObservationState.OBSERVED,
            query = entry.query, textStatus = entry.textStatus,
            deltaCalls = deltaCalls, callsPerHour = finite(callsPerHour), deltaRows = deltaRows,
            totalExecTimeMillis = finite(executionTime),
            meanExecTimeMillis = if (deltaCalls == 0L) null
                else finite(executionTime.divide(BigDecimal.valueOf(deltaCalls), MathContext.DECIMAL128)),
            reasons = emptySet(),
        )
    }

    private fun index(snapshot: StatisticsSnapshot, context: RunContext): Map<StatisticsKey, StatisticsEntry> {
        requireInput(snapshot.entries.size <= snapshot.limits.maxRows, "Snapshot exceeds its row limit.")
        return buildMap {
            for (entry in snapshot.entries) {
                context.checkDeadline()
                requireInput(entry.dbid == snapshot.source.databaseOid && entry.dbid > 0 && entry.userid > 0,
                    "Entry must belong to the declared database and role.")
                if (entry.calls < 0 || entry.rows < 0 || !entry.totalExecTime.isFinite() || entry.totalExecTime < 0)
                    fail(ErrorCode.NUMERIC_ERROR, "Statistics counters must be finite and nonnegative.")
                requireInput((entry.textStatus == QueryTextStatus.AVAILABLE) == (entry.query != null),
                    "Query text must agree with text_status.")
                val queryid = entry.queryid ?: continue
                val key = StatisticsKey(snapshot.source.sourceId, entry.dbid, entry.userid, queryid, entry.toplevel)
                requireInput(put(key, entry) == null, "Snapshot contains duplicate entry keys.")
            }
        }
    }

    private fun seconds(start: Instant, end: Instant): BigDecimal = Duration.between(start, end).let {
        BigDecimal.valueOf(it.seconds) + BigDecimal.valueOf(it.nano.toLong(), 9)
    }

    private fun instant(value: String): Instant = try { Instant.parse(value) }
        catch (_: DateTimeParseException) { fail(ErrorCode.INVALID_INPUT, "Snapshot timestamps must be ISO-8601 instants.") }

    private fun finite(value: BigDecimal): Double = value.toDouble().also {
        if (!it.isFinite() || (value.signum() > 0 && it == 0.0)) fail(ErrorCode.NUMERIC_ERROR, "Observation arithmetic exceeds the supported numeric range.")
    }

    private fun requireInput(condition: Boolean, message: String) { if (!condition) fail(ErrorCode.INVALID_INPUT, message) }
    private fun fail(code: ErrorCode, message: String): Nothing = throw AnalysisException(AnalysisError(code, message))
}
