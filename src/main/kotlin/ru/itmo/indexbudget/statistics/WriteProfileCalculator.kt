package ru.itmo.indexbudget.statistics

import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.TableWriteRates
import ru.itmo.indexbudget.domain.WriteRateSource
import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Why an observed write profile is incomplete. Rates for faulty tables are never fabricated. */
enum class WriteProfileReason {
    /** One or both snapshots stopped early by its own collection limits. */
    INCOMPLETE_SNAPSHOT,
    /** The shared reset epoch moved between the snapshots: the whole window is unusable. */
    STATS_RESET,
    /** A table is absent on one side of the window: its rate is unknown, not zero. */
    TABLE_MISSING,
    /** A counter decreased without an epoch change: a targeted reset cannot be excluded (PG16). */
    COUNTER_DECREASE,
}

/**
 * The single write-profile implementation (#107): changed rows per hour from one explicitly
 * chosen compatible pair of table snapshots, `delta * 3600 / delta_seconds` with exact decimal
 * arithmetic. Rates are rows, never SQL calls, and are never mixed with pg_stat_statements
 * counts. One shared write profile serves the whole workload; read profiles never create
 * additional write profiles. HOT updates stay a historical diagnostic and are never subtracted
 * from a future index price. An incomplete observation keeps its per-table faults and never
 * silently disables an enabled write budget.
 */
class WriteProfileCalculator(private val mathContext: MathContext = MathContext.DECIMAL64) {

    data class WriteProfile(
        val rates: Map<String, TableWriteRates>,
        /** Historical diagnostic only; per-table HOT updates per hour, never an index price. */
        val hotUpdatesPerHour: Map<String, Double>,
        val from: String,
        val to: String,
        val periodSeconds: Double,
        val complete: Boolean,
        val reasons: List<WriteProfileReason>,
        val tableFaults: Map<String, WriteProfileReason>,
        /** The collector source UUID shared by both snapshots; checked against the read windows. */
        val sourceId: UUID,
        val databaseOid: Long,
        val postgresVersion: String,
    )

    fun calculate(before: TableWriteSnapshot, after: TableWriteSnapshot, context: RunContext = RunContext()): WriteProfile {
        context.checkDeadline()
        requireInput(before.snapshotId != after.snapshotId, "A window requires two different snapshots.")
        requireInput(before.source.sourceId == after.source.sourceId &&
            before.source.databaseOid == after.source.databaseOid &&
            before.source.readerUserid == after.source.readerUserid,
            "Observation snapshots must come from the same source, database and reader.")
        requireInput(before.source.trackCounts == true && after.source.trackCounts == true,
            "track_counts must be enabled and confirmed on both snapshots.")
        val firstStart = instant(before.startedAt)
        val firstEnd = instant(before.finishedAt)
        val lastStart = instant(after.startedAt)
        val lastEnd = instant(after.finishedAt)
        requireInput(firstStart <= firstEnd && lastStart <= lastEnd && firstEnd <= lastStart,
            "Capture intervals must be ordered and must not overlap.")
        // Window duration follows the statements windows: midpoints of the two capture intervals.
        val start = firstStart.plus(Duration.between(firstStart, firstEnd).dividedBy(2))
        val end = lastStart.plus(Duration.between(lastStart, lastEnd).dividedBy(2))
        requireInput(start < end, "Observation duration must be positive.")
        val duration = BigDecimal.valueOf(Duration.between(start, end).toNanos(), 9)

        val reasons = linkedSetOf<WriteProfileReason>()
        if (!before.complete || !after.complete) reasons += WriteProfileReason.INCOMPLETE_SNAPSHOT
        val resetChanged = before.source.statsReset != after.source.statsReset
        if (resetChanged) reasons += WriteProfileReason.STATS_RESET

        val first = before.entries.associateBy { it.tableOid }
        val last = after.entries.associateBy { it.tableOid }
        val rates = linkedMapOf<String, TableWriteRates>()
        val hot = linkedMapOf<String, Double>()
        val faults = linkedMapOf<String, WriteProfileReason>()
        for (tableOid in (first.keys + last.keys).sorted()) {
            context.checkDeadline()
            val left = first[tableOid]
            val right = last[tableOid]
            if (left == null || right == null) {
                faults[keyOf(left ?: right)] = WriteProfileReason.TABLE_MISSING
                continue
            }
            requireInput(left.schemaName == right.schemaName && left.tableName == right.tableName,
                "Table OID ${tableOid} changed its name inside the window.")
            val key = keyOf(left)
            if (resetChanged) {
                faults[key] = WriteProfileReason.STATS_RESET
                continue
            }
            // Any decreasing counter — including HOT updates, even after the row counters have
            // recovered above their old values — proves the window spans a reset for this table.
            // PG16 cannot distinguish a targeted pg_stat_reset_single_table_counters from other
            // decreases without an epoch change: keep the fault instead of inventing a rate.
            if (left.inserts > right.inserts || left.updates > right.updates || left.deletes > right.deletes ||
                (left.hotUpdates != null && right.hotUpdates != null && right.hotUpdates < left.hotUpdates)
            ) {
                faults[key] = WriteProfileReason.COUNTER_DECREASE
                continue
            }
            rates[key] = TableWriteRates(
                insertsPerHour = perHour(right.inserts - left.inserts, duration, key, "inserts"),
                updatesPerHour = perHour(right.updates - left.updates, duration, key, "updates"),
                deletesPerHour = perHour(right.deletes - left.deletes, duration, key, "deletes"),
                source = WriteRateSource.OBSERVED,
            )
            if (left.hotUpdates != null && right.hotUpdates != null) {
                hot[key] = perHour(right.hotUpdates - left.hotUpdates, duration, key, "hot_updates")
            }
        }
        reasons += faults.values.toSet()
        return WriteProfile(
            rates = rates,
            hotUpdatesPerHour = hot,
            from = start.toString(),
            to = end.toString(),
            periodSeconds = finite(duration, "period"),
            complete = reasons.isEmpty(),
            reasons = reasons.toList(),
            tableFaults = faults,
            sourceId = before.source.sourceId,
            databaseOid = before.source.databaseOid,
            postgresVersion = before.source.postgresVersion,
        )
    }

    /** delta rows * 3600 / seconds, exact decimals first; zero deltas are a valid zero rate. */
    private fun perHour(delta: Long, seconds: BigDecimal, table: String, counter: String): Double {
        if (delta == 0L) return 0.0
        val value = BigDecimal.valueOf(delta).multiply(BigDecimal(3600)).divide(seconds, mathContext)
        return finite(value, "$table $counter", counter)
    }

    private fun finite(value: BigDecimal, what: String, counter: String? = null): Double {
        val result = value.toDouble()
        if (!result.isFinite() || result < 0) {
            throw AnalysisException(AnalysisError(ErrorCode.NUMERIC_ERROR,
                "Observed write rate is not finite or negative.", details = buildMap {
                    put("table", what)
                    counter?.let { put("counter", it) }
                }))
        }
        return result
    }

    private fun keyOf(entry: TableWriteEntry?): String {
        checkNotNull(entry)
        return "${entry.schemaName}.${entry.tableName}"
    }

    private fun instant(value: String): Instant = try {
        Instant.parse(value)
    } catch (_: Exception) {
        fail(ErrorCode.INVALID_INPUT, "Snapshot timestamps must be ISO-8601 instants.")
    }

    private fun requireInput(condition: Boolean, message: String) {
        if (!condition) fail(ErrorCode.INVALID_INPUT, message)
    }

    private fun fail(code: ErrorCode, message: String): Nothing =
        throw AnalysisException(AnalysisError(code, message))
}
