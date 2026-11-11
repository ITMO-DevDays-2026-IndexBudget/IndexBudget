package ru.itmo.indexbudget.statistics

import java.time.Instant
import java.util.UUID

data class FrequencyWindow(val id: String, val weight: Double, val before: StatisticsSnapshot, val after: StatisticsSnapshot)

data class StatisticsKey(val sourceId: UUID, val dbid: Long, val userid: Long, val queryid: Long, val toplevel: Boolean)

enum class ObservationState { OBSERVED, ZERO, UNKNOWN }
enum class FrequencyReason {
    INCOMPLETE_SNAPSHOT, MISSING_EPOCH, STATS_RESET, DEALLOC_CHANGED, UNIDENTIFIED_ENTRIES,
    NEW_ENTRY, MISSING_ENTRY, COUNTER_DECREASE, INCONSISTENT_COUNTERS, QUERY_TEXT_CHANGED,
}

data class ObservationPeriod(
    val beforeStartedAt: Instant, val beforeFinishedAt: Instant,
    val afterStartedAt: Instant, val afterFinishedAt: Instant,
    val start: Instant, val end: Instant, val durationSeconds: Double,
    val minimumDurationSeconds: Double, val maximumDurationSeconds: Double,
)

data class FrequencyObservation(
    val key: StatisticsKey,
    val state: ObservationState,
    val query: String?,
    val textStatus: QueryTextStatus,
    val deltaCalls: Long?,
    val callsPerHour: Double?,
    val deltaRows: Long?,
    val totalExecTimeMillis: Double?,
    val meanExecTimeMillis: Double?,
    val reasons: Set<FrequencyReason>,
)

data class FrequencyProfile(
    val id: String, val weight: Double, val normalizedWeight: Double,
    val beforeSnapshotId: UUID, val afterSnapshotId: UUID,
    val period: ObservationPeriod,
    val observations: List<FrequencyObservation>,
    val reasons: Set<FrequencyReason>,
    val beforeReasons: List<SnapshotReason>, val afterReasons: List<SnapshotReason>,
    val unidentifiedBefore: Int, val unidentifiedAfter: Int,
    val observedCallsPerHour: Double,
    val observedTotalExecTimeMillis: Double,
) {
    val complete: Boolean get() = reasons.isEmpty() && observations.all { it.state != ObservationState.UNKNOWN }
}

data class FrequencyProfiles(val source: StatisticsSource, val profiles: List<FrequencyProfile>) {
    val commonUsableKeys: Set<StatisticsKey>
        get() = profiles.map { profile -> profile.observations.filter { it.state != ObservationState.UNKNOWN }
            .mapTo(linkedSetOf()) { it.key } }.reduce { common, keys -> common.apply { retainAll(keys) } }
}
