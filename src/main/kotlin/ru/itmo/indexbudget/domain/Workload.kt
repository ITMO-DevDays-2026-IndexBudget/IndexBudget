package ru.itmo.indexbudget.domain

enum class Objective { ROBUST, MEAN }

enum class WriteRateSource { MANUAL, OBSERVED }

/** Origin of a supplied write profile: one explicitly chosen observation window, never a silent mix.
 * [complete] is false whenever any fault was detected; an incomplete observation never silently
 * disables an enabled write budget. Rates themselves live in [WorkloadInput.tableWriteRates]. */
data class WriteRatesOrigin(
    val from: String,
    val to: String,
    val periodSeconds: Double,
    val complete: Boolean,
    val reasons: List<String>,
    val databaseOid: Long? = null,
    val postgresVersion: String? = null,
)

/** Input v2. Boundary validation belongs to the CLI; cross-table validation follows SQL resolution. */
data class WorkloadInput(
    val queries: List<QueryInput>,
    val profiles: List<Profile>,
    val constraints: Constraints,
    val schemaVersion: Int = 2,
    val objective: Objective = Objective.ROBUST,
    val tableWriteRates: Map<String, TableWriteRates>? = null,
    /** Present exactly when the rates were observed from a snapshot window; null keeps manual input. */
    val tableWriteRatesOrigin: WriteRatesOrigin? = null,
    /** Optional separate evaluation; never contributes candidates or incumbents to ordinary search. */
    val manualIndexes: List<IndexDefinition>? = null,
    val analysisMode: AnalysisMode = AnalysisMode.NORMAL,
    val candidateRanking: CandidateRanking = CandidateRanking.FREQUENCY,
    val provenance: WorkloadProvenance? = null,
    val indexMethods: Set<IndexMethod> = setOf(IndexMethod.BTREE),
)

data class QueryInput(val id: String, val sql: String)

/** [weight] is relative, not yet normalized. Every query ID must occur in [callsPerHour]. */
data class Profile(
    val id: String,
    val weight: Double,
    val callsPerHour: Map<String, Double>,
)

data class Constraints(
    val storageBudgetBytes: Long,
    val writeBudgetUnitsPerHour: Double?,
    val maxIndexes: Int = 5,
    /** Optional per-query planner-cost guards: query ID -> maximum allowed selected/baseline cost ratio (>= 1.0). */
    val queryCostGuards: Map<String, Double> = emptyMap(),
)

data class TableWriteRates(
    val insertsPerHour: Double,
    val updatesPerHour: Double,
    val deletesPerHour: Double,
    val source: WriteRateSource = WriteRateSource.MANUAL,
)
