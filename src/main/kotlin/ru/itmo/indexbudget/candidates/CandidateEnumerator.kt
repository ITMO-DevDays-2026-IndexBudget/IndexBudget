package ru.itmo.indexbudget.candidates

import ru.itmo.indexbudget.domain.*

/** A structural variant, before catalog defaults, canonical IDs, real-index deduplication or estimates. */
data class CandidateVariant(
    val definition: IndexDefinition,
    val sourceQueryIds: Set<String>,
    val generationReasons: Set<GenerationReason>,
    val sourceBranches: Set<CandidateSourceBranch> = emptySet(),
)

/**
 * One (query, table) at the boundary between #10 and #11/#12. At most 976 variants are live here.
 * [retainedColumns] is in shortlist order; excluded columns use physical identifier order.
 * Variants are structurally distinct, but catalog-aware canonicalization can merge them further.
 * Counts after canonicalization/local selection belong to the consumer, not this stage.
 */
data class CandidateScope(
    val queryId: String,
    val table: TableRef,
    val discoveredColumns: Int,
    val retainedColumns: List<ColumnRef>,
    val excludedColumns: List<ColumnRef>,
    val variants: List<CandidateVariant>,
    val decisions: List<CandidateDecision>,
    val warnings: List<AnalysisWarning>,
) {
    val enumeratedDefinitions: Int get() = variants.size
}

fun interface CandidateEnumerator {
    /**
     * Synchronously deliver one bounded scope at a time, in query/schema/table order.
     * The consumer canonicalizes (#11), removes real duplicates and retains at most 32 (#12)
     * before returning. Retain only those local survivors across callbacks, not every scope.
     * Input is validated/resolved SQL; this stage never parses SQL or changes the input.
     * Exceptions and cancellation propagate; no partial CandidateBatch is manufactured.
     */
    fun forEachScope(workload: ResolvedWorkload, context: RunContext, consume: (CandidateScope) -> Unit)
}
