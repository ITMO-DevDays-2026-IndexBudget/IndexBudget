package ru.itmo.indexbudget.statistics

import java.util.UUID

/** Why a table write snapshot is partial; mirrors the statements snapshot reason set where applicable. */
enum class TableWriteSnapshotReason {
    ROW_LIMIT, BYTE_LIMIT, DEADLINE_EXCEEDED, CANCELLED, READ_FAILED,
    /** Collector could not read counters for at least one table (e.g. statistics row absent). */
    COUNTERS_UNAVAILABLE,
    /** A reset happened while the collector was reading (#102 snapshot, best-effort PG16 detection). */
    RESET_DETECTED,
}

/** One row of pg_stat_user_tables for the observed database; counters are cumulative rows, not calls. */
data class TableWriteEntry(
    val tableOid: Long,
    val schemaName: String,
    val tableName: String,
    val inserts: Long,
    val updates: Long,
    val deletes: Long,
    /** Historical diagnostic only: HOT updates are never subtracted from a future index price. */
    val hotUpdates: Long?,
)

data class TableWriteSource(
    val sourceId: UUID,
    val databaseOid: Long,
    val readerUserid: Long,
    val trackCounts: Boolean?,
    val postgresVersion: String,
    /** Shared reset epoch from pg_stat_database; null when statistics were never reset. */
    val statsReset: String?,
)

/**
 * A saved per-table write counters snapshot (#102 collector output). Collection is independent of
 * any analysis window; a compatible before/after pair yields the observed write profile (#107).
 */
data class TableWriteSnapshot(
    val snapshotId: UUID,
    val source: TableWriteSource,
    val startedAt: String,
    val finishedAt: String,
    val entries: List<TableWriteEntry>,
    val complete: Boolean = true,
    val reasons: List<TableWriteSnapshotReason> = emptyList(),
)
