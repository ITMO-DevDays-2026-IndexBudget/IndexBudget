package ru.itmo.indexbudget.application

import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.postgres.AnalysisSession

data class CandidatePreparationResult(val reportBatch: CandidateBatch, val searchBatch: CandidateBatch)

/** Frozen common shortlist prepared once at the largest budgets of a future budget series. */
class PreparedCandidatePool internal constructor(
    val input: WorkloadInput,
    val reportBatch: CandidateBatch,
    val candidates: List<IndexCandidate>,
)

/** Bounded preparation; never evaluates SQL costs or substitutes zero for an unknown size. */
class CandidatePreparation(private val metrics: MetricsCalculator) {
    fun prepare(
        batch: CandidateBatch,
        session: AnalysisSession,
        input: WorkloadInput,
        context: RunContext,
        onPrepared: (CandidateBatch) -> Unit = {},
    ): CandidatePreparationResult {
        require(batch.candidates.size <= CandidatePreparationLimits.SHORTLIST)
        require(batch.reserve.size <= CandidatePreparationLimits.RESERVE)
        val queue = batch.candidates + batch.reserve
        require(queue.map { it.id }.distinct().size == queue.size) { "Preparation queue must be deduplicated" }
        val reported = queue.map { it.copy(estimatedBytes = null, budgetedBytes = null, writeUnitsPerHour = null) }
            .toMutableList()
        val accepted = mutableListOf<IndexCandidate>()
        val processedIds = mutableSetOf<String>()
        val decisions = mutableListOf<CandidateDecision>()
        val warnings = mutableListOf<AnalysisWarning>()
        var examined = 0
        var sizeAttempts = 0
        var budgetRejected = 0
        var candidateErrors = 0
        var refilled = 0

        fun snapshot(reason: CandidatePreparationStopReason, finished: Boolean): CandidateBatch {
            val remaining = if (finished) reported.filter { it.id !in processedIds }.map {
                CandidateDecision(CandidateReason.NOT_EVALUATED_DUE_TO_LIMIT, it.id,
                    table = it.definition.table, phase = AnalysisPhase.CANDIDATES,
                    details = mapOf("preparation_stop_reason" to reason.name))
            } else emptyList()
            return CandidateBatch(reported.toList(), batch.diagnostics.copy(
                initialShortlistedCandidates = batch.candidates.size,
                reserveCandidates = batch.reserve.size,
                // Retain the pre-preparation meaning of the existing report field.
                shortlistedCandidates = batch.candidates.size,
                preparation = CandidatePreparationStatistics(examined, sizeAttempts, budgetRejected,
                    candidateErrors, refilled, accepted.size, reason),
                decisions = batch.diagnostics.decisions + decisions + remaining,
                warnings = batch.diagnostics.warnings + warnings,
            ))
        }

        fun reject(candidate: IndexCandidate, violations: Set<BudgetViolation>) {
            budgetRejected++
            decisions += violations.map { violation ->
                CandidateDecision(violation.toCandidateReason(), candidate.id,
                    table = candidate.definition.table, phase = AnalysisPhase.CANDIDATES)
            }
        }

        var stop = CandidatePreparationStopReason.RESERVE_EXHAUSTED
        try {
            context.checkDeadline()
            for ((index, candidate) in reported.withIndex()) {
                context.checkDeadline()
                if (accepted.size == CandidatePreparationLimits.SHORTLIST) {
                    stop = CandidatePreparationStopReason.SHORTLIST_FULL
                    break
                }
                examined++
                val known = metrics.assessKnownBudget(listOf(candidate), input)
                reported[index] = candidate.copy(writeUnitsPerHour = known.writeUnitsPerHour)
                if (!known.admissible || input.constraints.storageBudgetBytes == 0L) {
                    val violations = known.violations + if (input.constraints.storageBudgetBytes == 0L)
                        setOf(BudgetViolation.STORAGE_BUDGET) else emptySet()
                    reject(candidate, violations)
                    processedIds += candidate.id
                    onPrepared(snapshot(stop, false))
                    context.checkDeadline()
                    continue
                }
                if (sizeAttempts == CandidatePreparationLimits.SIZE_ATTEMPTS) {
                    stop = CandidatePreparationStopReason.SIZE_LIMIT
                    warnings += AnalysisWarning(WarningCode.CANDIDATE_PREPARATION_LIMIT,
                        "Candidate preparation size-attempt limit reached; remaining sizes are unknown.",
                        details = mapOf("max_size_attempts" to CandidatePreparationLimits.SIZE_ATTEMPTS.toString()))
                    break
                }
                try {
                    sizeAttempts++ // Count started calls, including local errors and lost connections.
                    val bytes = session.estimateSize(candidate, context)
                    if (bytes < 0) throw AnalysisException(AnalysisError(ErrorCode.NUMERIC_ERROR,
                        "Estimated index size must be nonnegative."))
                    val sized = reported[index].copy(estimatedBytes = bytes)
                    val budget = metrics.assessBudget(listOf(sized), input)
                    val prepared = sized.copy(budgetedBytes = budget.usage.budgetedBytes,
                        writeUnitsPerHour = budget.usage.writeUnitsPerHour)
                    reported[index] = prepared
                    if (budget.admissible) {
                        accepted += prepared
                        if (index >= batch.candidates.size) refilled++
                    } else reject(prepared, budget.violations)
                } catch (error: AnalysisException) {
                    if (error.error.code != ErrorCode.HYPOTHETICAL_INDEX_ERROR) throw error
                    candidateErrors++
                    decisions += CandidateDecision(CandidateReason.HYPOTHETICAL_INDEX_ERROR, candidate.id,
                        table = candidate.definition.table, phase = AnalysisPhase.CANDIDATES)
                    warnings += AnalysisWarning(WarningCode.CANDIDATE_REJECTED,
                        "A hypothetical index could not be created or sized.",
                        details = mapOf("candidate_id" to candidate.id))
                    processedIds += candidate.id
                    // A candidate rejection is recoverable only after successful session cleanup.
                    if (error.suppressed.isNotEmpty()) throw error
                }
                processedIds += candidate.id
                onPrepared(snapshot(stop, false))
                context.checkDeadline()
            }
            if (accepted.size == CandidatePreparationLimits.SHORTLIST) stop = CandidatePreparationStopReason.SHORTLIST_FULL
            context.checkDeadline()
        } catch (error: Exception) {
            val code = (error as? AnalysisException)?.let { failure ->
                if (failure.error.code == ErrorCode.HYPOTHETICAL_INDEX_ERROR)
                    failure.suppressed.filterIsInstance<AnalysisException>().firstOrNull()?.error?.code
                else failure.error.code
            }
            stop = when (code) {
                ErrorCode.DEADLINE_EXCEEDED -> CandidatePreparationStopReason.DEADLINE_EXCEEDED
                ErrorCode.CANCELLED -> CandidatePreparationStopReason.CANCELLED
                ErrorCode.DB_CONNECTION_ERROR -> CandidatePreparationStopReason.CONNECTION_LOST
                else -> CandidatePreparationStopReason.FAILED
            }
            onPrepared(snapshot(stop, true))
            throw error
        }
        val reportBatch = snapshot(stop, true)
        onPrepared(reportBatch)
        return CandidatePreparationResult(reportBatch, CandidateBatch(accepted.toList(), reportBatch.diagnostics))
    }

    fun prepareFixedPool(
        batch: CandidateBatch,
        session: AnalysisSession,
        input: WorkloadInput,
        context: RunContext,
        onPrepared: (CandidateBatch) -> Unit = {},
    ): PreparedCandidatePool {
        val prepared = prepare(batch, session, input, context, onPrepared)
        return PreparedCandidatePool(input, prepared.reportBatch, prepared.searchBatch.candidates)
    }

    /** Only admissibility changes at a series point. No session, generation, size call or refill. */
    fun selectFixedPool(pool: PreparedCandidatePool, input: WorkloadInput, context: RunContext): CandidatePreparationResult {
        val maximumWrite = pool.input.constraints.writeBudgetUnitsPerHour
        val pointWrite = input.constraints.writeBudgetUnitsPerHour
        if (input.copy(constraints = pool.input.constraints) != pool.input ||
            input.constraints.maxIndexes != pool.input.constraints.maxIndexes ||
            input.constraints.storageBudgetBytes > pool.input.constraints.storageBudgetBytes ||
            (maximumWrite != null && (pointWrite == null || pointWrite > maximumWrite))) {
            throw AnalysisException(AnalysisError(ErrorCode.INVALID_INPUT,
                "Fixed-pool runs require unchanged input and storage/write budgets no greater than the prepared maximum."))
        }
        val decisions = mutableListOf<CandidateDecision>()
        val accepted = pool.candidates.filter { candidate ->
            context.checkDeadline()
            val budget = metrics.assessBudget(listOf(candidate), input)
            decisions += budget.violations.map { violation ->
                CandidateDecision(violation.toCandidateReason(), candidate.id,
                    table = candidate.definition.table, phase = AnalysisPhase.CANDIDATES,
                    details = mapOf("pool_scope" to "fixed"))
            }
            budget.admissible
        }
        context.checkDeadline()
        val diagnostics = pool.reportBatch.diagnostics.copy(
            decisions = pool.reportBatch.diagnostics.decisions + decisions,
            preparationScope = CandidatePreparationScope.COMMON_POOL,
            fixedPoolSelection = FixedPoolSelectionStatistics(pool.candidates.size, accepted.size,
                pool.candidates.size - accepted.size),
        )
        return CandidatePreparationResult(pool.reportBatch.copy(diagnostics = diagnostics), CandidateBatch(accepted, diagnostics))
    }
}
