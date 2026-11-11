package ru.itmo.indexbudget.domain

enum class GenerationReason(val pairPriority: Int? = null) {
    SINGLE_COLUMN,
    COVERING_PROJECTION,
    ORDER_BY_PAIR(0),
    EQUALITY_AND_ORDER(1),
    EQUALITY_AND_RANGE(2),
    TWO_EQUALITIES(3),
}

/** Shared local/global pair ordering; all generation reasons remain available as provenance. */
fun IndexCandidate.bestPairPriority(): Int? = generationReasons.mapNotNull { it.pairPriority }.minOrNull()

/** A structural leaf path in the resolved condition tree, or the unconditional 'order_by' source. */
data class CandidateSourceBranch(val queryId: String, val conditionPath: String)

/** Unknown estimates stay null, including unvisited reserve entries and rejected candidates. */
data class IndexCandidate(
    val id: String,
    val definition: IndexDefinition,
    val sourceQueryIds: Set<String>,
    val generationReasons: Set<GenerationReason>,
    val ddl: String? = null,
    val estimatedBytes: Long? = null,
    val budgetedBytes: Long? = null,
    val writeUnitsPerHour: Double? = null,
    val sourceBranches: Set<CandidateSourceBranch> = emptySet(),
)

enum class CandidateReason {
    NO_BTREE_OPERATOR_CLASS,
    EXACT_EXISTING_DUPLICATE,
    COLUMN_SHORTLIST_TRUNCATED,
    LOCAL_CANDIDATE_LIMIT,
    INCLUDE_PAYLOAD_LIMIT,
    INCLUDE_VARIANT_LIMIT,
    GLOBAL_CANDIDATE_LIMIT,
    STORAGE_BUDGET,
    WRITE_BUDGET,
    MAX_INDEXES,
    HYPOTHETICAL_INDEX_ERROR,
    EVALUATION_FAILED,
    NO_MARGINAL_IMPROVEMENT,
    NOT_SELECTED,
    NOT_EVALUATED_DUE_TO_LIMIT,
}

data class CandidateDecision(
    val reason: CandidateReason,
    val candidateId: String? = null,
    val queryId: String? = null,
    val table: TableRef? = null,
    val phase: AnalysisPhase? = null,
    val details: Map<String, String> = emptyMap(),
)

data class LocalGenerationStatistics(
    val queryId: String,
    val table: TableRef,
    val discoveredColumns: Int,
    val retainedColumns: Int,
    val enumeratedDefinitions: Int,
    val deduplicatedDefinitions: Int,
    val retainedDefinitions: Int,
    val excludedColumns: List<ColumnRef> = emptyList(),
    /** Before the local cap; null only for producers that do not observe this stage. */
    val candidatesAfterExistingDeduplication: Int? = null,
    /** Enumerated variants rejected before canonical IDs, not distinct canonical definitions. */
    val unindexableVariants: Int = 0,
    val enumeratedIncludeDefinitions: Int = 0,
    val retainedIncludeDefinitions: Int = 0,
)

data class CandidateDiagnostics(
    val localScopes: List<LocalGenerationStatistics> = emptyList(),
    val candidatesBeforeGlobalDeduplication: Int = 0,
    val candidatesAfterGlobalDeduplication: Int = 0,
    val shortlistedCandidates: Int = 0,
    val columnLimitPerQueryTable: Int = 16,
    val localCandidateLimit: Int = 32,
    val globalCandidateLimit: Int = 16,
    val ungeneratedDefinitions: Long? = null,
    val decisions: List<CandidateDecision> = emptyList(),
    val warnings: List<AnalysisWarning> = emptyList(),
    val initialShortlistedCandidates: Int = shortlistedCandidates,
    val reserveCandidates: Int = 0,
    val reserveCandidateLimit: Int = CandidatePreparationLimits.RESERVE,
    val maxSizeAttempts: Int = CandidatePreparationLimits.SIZE_ATTEMPTS,
    val preparation: CandidatePreparationStatistics? = null,
    val preparationScope: CandidatePreparationScope = CandidatePreparationScope.RUN,
    val fixedPoolSelection: FixedPoolSelectionStatistics? = null,
)

data class CandidateBatch(
    val candidates: List<IndexCandidate>,
    val diagnostics: CandidateDiagnostics = CandidateDiagnostics(),
    val reserve: List<IndexCandidate> = emptyList(),
)

object CandidatePreparationLimits {
    const val SHORTLIST = 16
    const val RESERVE = 48
    const val SIZE_ATTEMPTS = 32
}

enum class CandidatePreparationStopReason {
    SHORTLIST_FULL, RESERVE_EXHAUSTED, SIZE_LIMIT, DEADLINE_EXCEEDED, CANCELLED, CONNECTION_LOST, FAILED,
}

data class CandidatePreparationStatistics(
    val examinedCandidates: Int = 0,
    val sizeAttempts: Int = 0,
    val budgetRejectedCandidates: Int = 0,
    val candidateErrors: Int = 0,
    val refilledCandidates: Int = 0,
    val finalShortlistedCandidates: Int = 0,
    val stopReason: CandidatePreparationStopReason,
)

enum class CandidatePreparationScope { RUN, COMMON_POOL }

data class FixedPoolSelectionStatistics(
    val poolCandidates: Int,
    val admissibleCandidates: Int,
    val budgetRejectedCandidates: Int,
    val sizeAttempts: Int = 0,
)

fun BudgetViolation.toCandidateReason(): CandidateReason = when (this) {
    BudgetViolation.STORAGE_BUDGET -> CandidateReason.STORAGE_BUDGET
    BudgetViolation.WRITE_BUDGET -> CandidateReason.WRITE_BUDGET
    BudgetViolation.MAX_INDEXES -> CandidateReason.MAX_INDEXES
}
