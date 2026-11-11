package ru.itmo.indexbudget.statistics

import ru.itmo.indexbudget.domain.RunContext
import java.time.Duration
import java.util.UUID

/** Independent collection budget: never starts or consumes an index-search window. */
data class SnapshotLimits(
    val maxRows: Int = 10_000,
    val maxBytes: Int = 8 * 1024 * 1024,
    val maxQueryBytes: Int = 64 * 1024,
    val timeout: Duration = Duration.ofSeconds(30),
) {
    init {
        require(maxRows in 1..100_000)
        require(maxBytes in 32_768..64 * 1024 * 1024)
        require(maxQueryBytes in 1..1024 * 1024)
        require(timeout >= Duration.ofSeconds(1) && timeout <= Duration.ofSeconds(300))
    }
}

enum class QueryTextStatus { AVAILABLE, RESTRICTED, UNAVAILABLE, SIZE_LIMIT }

enum class SnapshotReason {
    ROW_LIMIT, BYTE_LIMIT, QUERY_TEXT_LIMIT, DEADLINE_EXCEEDED, CANCELLED,
    READ_FAILED, STATISTICS_PERMISSION_DENIED, STATS_RESET_DURING_READ, DEALLOC_DURING_READ,
}

data class StatisticsEntry(
    val dbid: Long,
    val userid: Long,
    val queryid: Long?,
    val toplevel: Boolean,
    val query: String?,
    val textStatus: QueryTextStatus,
    val calls: Long,
    val totalExecTime: Double,
    val rows: Long,
)

data class StatisticsEpoch(val statsReset: String, val dealloc: Long)

data class StatisticsSource(
    val sourceId: UUID,
    val databaseOid: Long,
    val readerUserid: Long,
    val allQueryTextsVisible: Boolean,
    val postgresVersion: String,
    val extensionVersion: String,
    val track: String?,
)

/** All available rows for the current database, before any workload ranking or top-N selection. */
data class StatisticsSnapshot(
    val snapshotId: UUID,
    val source: StatisticsSource,
    val startedAt: String,
    val finishedAt: String,
    val before: StatisticsEpoch,
    val after: StatisticsEpoch?,
    val entries: List<StatisticsEntry>,
    val reasons: List<SnapshotReason>,
    val limits: SnapshotLimits,
) {
    val complete: Boolean get() = reasons.isEmpty()
}

fun interface StatisticsCollector {
    fun collect(sourceId: UUID, limits: SnapshotLimits, context: RunContext): StatisticsSnapshot
}
