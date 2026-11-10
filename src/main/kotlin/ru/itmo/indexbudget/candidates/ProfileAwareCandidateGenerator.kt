package ru.itmo.indexbudget.candidates

import ru.itmo.indexbudget.domain.*

/** Spec v0.2 §5: consume one bounded scope, retain local survivors, then select across profiles. */
class ProfileAwareCandidateGenerator(
    private val enumerator: CandidateEnumerator = BtreeCandidateEnumerator(),
) : CandidateGenerator {
    override fun generate(workload: ResolvedWorkload, catalog: CatalogSnapshot, context: RunContext): CandidateBatch {
        context.checkDeadline()
        val canonicalizer = BtreeCandidateCanonicalizer(catalog, context)
        val survivors = mutableListOf<IndexCandidate>()
        val scopes = mutableListOf<LocalGenerationStatistics>()
        val decisions = mutableListOf<CandidateDecision>()
        val warnings = mutableListOf<AnalysisWarning>()
        enumerator.forEachScope(workload, context) { scope ->
            context.checkDeadline()
            check(scopes.size < MAX_SCOPES && scope.enumeratedDefinitions <= MAX_VARIANTS_PER_SCOPE) {
                "Enumerator exceeded the bounded scope contract"
            }
            val canonical = canonicalizer.canonicalize(scope)
            val local = canonical.candidates.sortedWith(localOrder).take(LOCAL_LIMIT)
            context.checkDeadline()
            survivors += local
            scopes += LocalGenerationStatistics(
                queryId = scope.queryId,
                table = scope.table,
                discoveredColumns = scope.discoveredColumns,
                retainedColumns = scope.retainedColumns.size,
                enumeratedDefinitions = scope.enumeratedDefinitions,
                deduplicatedDefinitions = canonical.deduplicatedDefinitions,
                retainedDefinitions = local.size,
                excludedColumns = scope.excludedColumns,
                candidatesAfterExistingDeduplication = canonical.candidates.size,
            )
            decisions += canonical.decisions
            warnings += scope.warnings
            if (canonical.candidates.size > local.size) {
                val details = mapOf(
                    "schema" to scope.table.schema, "table" to scope.table.name,
                    "candidates_before" to canonical.candidates.size.toString(),
                    "candidates_after" to local.size.toString(),
                )
                // Aggregate scope diagnostics, not a retained copy of all generation events.
                decisions += CandidateDecision(CandidateReason.LOCAL_CANDIDATE_LIMIT,
                    queryId = scope.queryId, table = scope.table, phase = AnalysisPhase.CANDIDATES, details = details)
                warnings += AnalysisWarning(WarningCode.LOCAL_CANDIDATE_LIMIT,
                    "Локальный список ограничен после канонизации и удаления точных реальных дублей.",
                    queryId = scope.queryId, details = details)
            }
        }
        context.checkDeadline()
        val pool = canonicalizer.merge(survivors)
        val selected = select(pool, workload, context)
        if (pool.size > GLOBAL_LIMIT) {
            val details = mapOf("candidates_before" to pool.size.toString(),
                "candidates_after" to selected.size.toString(), "refill_after_budget_filter" to "false")
            val selectedIds = selected.map { it.id }.toSet()
            for (candidate in pool) {
                context.checkDeadline()
                if (candidate.id !in selectedIds) decisions += CandidateDecision(
                    CandidateReason.GLOBAL_CANDIDATE_LIMIT, candidateId = candidate.id,
                    table = candidate.definition.table, phase = AnalysisPhase.CANDIDATES, details = details)
            }
            warnings += AnalysisWarning(WarningCode.GLOBAL_CANDIDATE_LIMIT,
                "Общий shortlist ограничен до оценки размеров. После бюджетного отсева вытесненные кандидаты не возвращаются.",
                details = details)
        }
        context.checkDeadline()
        return CandidateBatch(selected, CandidateDiagnostics(
            localScopes = scopes,
            candidatesBeforeGlobalDeduplication = survivors.size,
            candidatesAfterGlobalDeduplication = pool.size,
            shortlistedCandidates = selected.size,
            ungeneratedDefinitions = null,
            decisions = decisions,
            warnings = warnings,
        ))
    }

    private fun select(pool: List<IndexCandidate>, workload: ResolvedWorkload, context: RunContext): List<IndexCandidate> {
        val queryIds = workload.queries.map { it.id }.toSet()
        val profiles = workload.input.profiles.sortedBy { it.id }
        require(profiles.size in 1..3 && profiles.map { it.id }.distinct().size == profiles.size) {
            "Expected 1–3 validated profiles with unique IDs"
        }
        val queues = profiles.map { profile ->
            context.checkDeadline()
            val frequencies = normalizedFrequencies(profile, queryIds, context)
            val scores = pool.associate { candidate ->
                context.checkDeadline()
                var score = 0.0
                for (id in candidate.sourceQueryIds.sorted()) {
                    context.checkDeadline()
                    score += frequencies.getValue(id)
                }
                if (!score.isFinite()) numericError(profile.id, "Candidate support is not finite")
                candidate.id to score
            }
            fun queue(keyCount: Int): ArrayDeque<IndexCandidate> {
                val order = compareByDescending<IndexCandidate> { scores.getValue(it.id) }
                    .thenBy { if (keyCount == 2) it.bestPairPriority() else null }.thenBy { it.id }
                val candidates = pool.filter {
                    context.checkDeadline()
                    it.definition.keys.size == keyCount && scores.getValue(it.id) > 0.0
                }.sortedWith(order)
                context.checkDeadline()
                return ArrayDeque(candidates)
            }
            ProfileQueues(queue(1), queue(2))
        }
        val selected = linkedMapOf<String, IndexCandidate>()
        while (selected.size < GLOBAL_LIMIT) {
            context.checkDeadline()
            var added = false
            for (queue in queues) {
                context.checkDeadline()
                val candidate = queue.next(selected.keys, context) ?: continue
                selected[candidate.id] = candidate
                added = true
                if (selected.size == GLOBAL_LIMIT) break
            }
            if (!added) break
        }
        return selected.values.toList()
    }

    private class ProfileQueues(val singles: ArrayDeque<IndexCandidate>, val pairs: ArrayDeque<IndexCandidate>) {
        private var preferSingles = true

        fun next(selected: Set<String>, context: RunContext): IndexCandidate? {
            fun take(queue: ArrayDeque<IndexCandidate>): IndexCandidate? {
                while (queue.isNotEmpty()) {
                    context.checkDeadline()
                    val candidate = queue.removeFirst()
                    if (candidate.id !in selected) return candidate
                }
                return null
            }
            val candidate = if (preferSingles) take(singles) ?: take(pairs) else take(pairs) ?: take(singles)
            if (candidate != null) preferSingles = !preferSingles
            return candidate
        }
    }

    private fun numericError(profileId: String, message: String): Nothing = throw AnalysisException(
        AnalysisError(ErrorCode.NUMERIC_ERROR, message, profileId = profileId),
    )

    private companion object {
        const val LOCAL_LIMIT = 32
        const val GLOBAL_LIMIT = 16
        const val MAX_SCOPES = 60
        const val MAX_VARIANTS_PER_SCOPE = 976
        val localOrder = compareBy<IndexCandidate> { it.definition.keys.size }
            .thenBy { it.bestPairPriority() }.thenBy { it.id }
    }
}
