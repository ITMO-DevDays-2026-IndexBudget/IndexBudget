package ru.itmo.indexbudget.domain

import java.time.Instant
import java.util.UUID

/** Report-facing metadata supplied by an importer, not a second statistics/import algorithm. */
data class WorkloadProvenance(
    val sourceId: UUID,
    val selectionRule: String,
    val windows: List<ObservationWindow>,
    val queryExamples: List<QueryExample>,
    val schemaVersion: Int = 1,
)

data class ObservationWindow(
    val profileId: String,
    val startedAt: Instant,
    val finishedAt: Instant,
    /** Completeness of observation, independent of the fraction selected for analysis. */
    val complete: Boolean,
    val coverage: ObservationCoverage,
)

/** Denominators describe available complete entries, never all server traffic. Unknowns remain null. */
data class ObservationCoverage(
    val selectedCalls: Long?,
    val availableCalls: Long?,
    val selectedExecTimeMillis: Double?,
    val availableExecTimeMillis: Double?,
)

enum class QueryExampleSource { USER_SUPPLIED_LITERALS, ORIGINAL_LITERAL_SQL }

data class QueryExample(val queryId: String, val source: QueryExampleSource)
