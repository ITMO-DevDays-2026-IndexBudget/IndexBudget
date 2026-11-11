package ru.itmo.indexbudget.domain

enum class GenerationReason(val pairPriority: Int? = null) {
    SINGLE_COLUMN,
    ORDER_BY_PAIR(0),
    EQUALITY_AND_ORDER(1),
    EQUALITY_AND_RANGE(2),
    TWO_EQUALITIES(3),
}

/** Shared local/global pair ordering; all generation reasons remain available as provenance. */
fun IndexCandidate.bestPairPriority(): Int? = generationReasons.mapNotNull { it.pairPriority }.minOrNull()

/** Unknown estimates stay null. The PostgreSQL adapter fills sizes only for the final shortlist. */
data class IndexCandidate(
    val id: String,
    val definition: IndexDefinition,
    val sourceQueryIds: Set<String>,
    val generationReasons: Set<GenerationReason>,
    val ddl: String? = null,
    val estimatedBytes: Long? = null,
    val budgetedBytes: Long? = null,
    val writeUnitsPerHour: Double? = null,
)

enum class CandidateReason {
    NO_BTREE_OPERATOR_CLASS,
    EXACT_EXISTING_DUPLICATE,
    COLUMN_SHORTLIST_TRUNCATED,
    LOCAL_CANDIDATE_LIMIT,
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
)

data class CandidateBatch(
    val candidates: List<IndexCandidate>,
    val diagnostics: CandidateDiagnostics = CandidateDiagnostics(),
)
