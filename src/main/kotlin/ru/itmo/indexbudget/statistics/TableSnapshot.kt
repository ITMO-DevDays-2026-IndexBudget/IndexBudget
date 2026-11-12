package ru.itmo.indexbudget.statistics

import ru.itmo.indexbudget.domain.RunContext
import java.util.UUID

enum class TableSnapshotReason {
    ROW_LIMIT, BYTE_LIMIT, TRACK_COUNTS_OFF, COUNTERS_UNAVAILABLE, READ_FAILED,
    DEADLINE_EXCEEDED, CANCELLED, RESET_DETECTED,
}

data class TableStatisticsSource(val sourceId: UUID, val databaseOid: Long, val readerUserid: Long,
    val postgresVersion: String, val trackCounts: Boolean)

data class TableCounters(val relationOid: Long, val schema: String, val name: String,
    val selectAccess: Boolean, val nTupIns: Long?, val nTupUpd: Long?, val nTupDel: Long?, val nTupHotUpd: Long?)

/** Separate format from query statistics; HOT updates are diagnostic and remain in nTupUpd. */
data class TableSnapshot(val snapshotId: UUID, val source: TableStatisticsSource,
    val startedAt: String, val finishedAt: String, val resetBefore: String?, val resetAfter: String?,
    val postmasterStartedAt: String, val entries: List<TableCounters>,
    val reasons: List<TableSnapshotReason>, val limits: SnapshotLimits) {
    val complete: Boolean get() = reasons.isEmpty()
    val scope: String get() = "current_database_ordinary_user_tables"
}

fun interface TableStatisticsCollector {
    fun collect(sourceId: UUID, limits: SnapshotLimits, context: RunContext): TableSnapshot
}
