package ru.itmo.indexbudget.optimizer

import ru.itmo.indexbudget.domain.*

/** All mutable search state belongs to this invocation, including failures and saved plans. */
internal class SearchRun(
    private val workload: ResolvedWorkload,
    private val batch: CandidateBatch,
    private val baseline: EvaluationResult,
    private val evaluator: ConfigurationEvaluator,
    private val metrics: MetricsCalculator,
    private val comparator: ObjectiveComparator,
    private val context: RunContext,
    private val onCheckpoint: (SearchCheckpoint) -> Unit,
    private val recoverLocalFailures: Boolean,
) {
    private val input = workload.input
    private val queryIds = input.queries.map { it.id }.toSet()
    private val evaluations = linkedMapOf<List<String>, EvaluationResult>()
    private val accepted = linkedMapOf<List<String>, EvaluatedConfiguration>()
    private val rejected = linkedMapOf<List<String>, EvaluationAssessment.Rejected>()
    private val failed = linkedMapOf<List<String>, AnalysisError>()
    private val blockedCandidates = mutableSetOf<String>()
    private val available = mutableListOf<IndexCandidate>()
    private val decisions = batch.diagnostics.decisions.toMutableList()
    private val warnings = batch.diagnostics.warnings.toMutableList()
    private val errors = mutableListOf<AnalysisError>()
    private val stopReasons = mutableListOf<StopReason>()
    private var unexamined: Long? = null
    private val baselineAssessment: EvaluationAssessment.Accepted
    private var current: EvaluatedConfiguration
    private var bestRobust: EvaluatedConfiguration
    private var bestMean: EvaluatedConfiguration
    private var pendingIds: List<String>? = null

    init {
        validateInput()
        validateEvaluation(baseline, emptyList())
        val budget = metrics.assessBudget(emptyList(), input)
        require(budget.admissible) { "The empty set must be admissible" }
        val assessment = metrics.assessEvaluation(input, baseline, baseline, budget.usage)
        require(assessment is EvaluationAssessment.Accepted) { "Baseline must be admissible" }
        baselineAssessment = assessment
        current = EvaluatedConfiguration(baseline, assessment.metrics)
        bestRobust = current
        bestMean = current
        evaluations[emptyList()] = baseline
        accepted[emptyList()] = current
    }

    fun greedyOnly(): GreedySearchResult {
        publish()
        greedy()
        val result = SearchResult.Completed(input.objective, baseline, bestRobust, bestMean, diagnostics())
        publish()
        return GreedySearchResult(result, current, available.toList(), evaluations.toMap(), rejected.toMap())
    }

    fun withRepair(): SearchResult {
        // Publish before any deadline check so a complete baseline remains recoverable.
        publish()
        try {
            greedy()
            if (stopReasons.none { it in EARLY_STOPS }) repair()
            context.checkDeadline()
        } catch (failure: AnalysisException) {
            val relatedErrors = listOf(failure.error) + failure.suppressed
                .filterIsInstance<AnalysisException>().map { it.error }
            val reasons = relatedErrors.mapNotNull { partialReason(it.code) }
            // Invalid input, catalog and numeric errors must never become a usable partial.
            if (reasons.isEmpty() || relatedErrors.any { it.code !in RECOVERABLE_CODES }) throw failure
            errors += relatedErrors
            stopReasons += reasons
            pendingIds?.let { recordUnexamined(it) }
            publish()
        }
        recordNotSelected()
        val diagnostics = diagnostics()
        return if (stopReasons.any { it in PARTIAL_STOPS }) {
            SearchResult.Partial(input.objective, baseline, bestRobust, bestMean, diagnostics)
        } else SearchResult.Completed(input.objective, baseline, bestRobust, bestMean, diagnostics)
    }

    private fun greedy() {
        context.enterPhase(AnalysisPhase.GREEDY)
        val earlyStop = when {
            input.constraints.storageBudgetBytes == 0L -> StopReason.ZERO_STORAGE_BUDGET
            input.constraints.maxIndexes == 0 -> StopReason.ZERO_MAX_INDEXES
            !baselineAssessment.searchRequired -> StopReason.ZERO_BASELINE
            else -> null
        }
        if (earlyStop != null) return finishGreedy(earlyStop)
        for (candidate in batch.candidates.sortedBy { it.id }) {
            context.checkDeadline()
            val budget = metrics.assessBudget(listOf(candidate), input)
            if (budget.admissible) available += candidate
            else recordBudgetRejection(budget, listOf(candidate.id), candidate.id)
            publish()
        }
        if (available.isEmpty()) return finishGreedy(StopReason.NO_CANDIDATES)

        while (current.evaluation.candidateIds.size < input.constraints.maxIndexes) {
            context.checkDeadline()
            val currentIds = current.evaluation.candidateIds.toSet()
            val currentCandidates = available.filter { it.id in currentIds }
            var next: EvaluatedConfiguration? = null
            for (candidate in available) {
                context.checkDeadline()
                if (candidate.id in currentIds || candidate.id in blockedCandidates) continue
                val proposed = (currentCandidates + candidate).sortedBy { it.id }
                val evaluated = evaluate(proposed, candidate.id) ?: continue
                if (comparator.improvesPrimaryMetrics(evaluated.metrics, current.metrics, input.objective)) {
                    val previous = next
                    if (previous == null || comparator.compare(evaluated, previous, input.objective) < 0) next = evaluated
                } else {
                    decisions += CandidateDecision(CandidateReason.NO_MARGINAL_IMPROVEMENT,
                        candidateId = candidate.id, phase = AnalysisPhase.GREEDY,
                        details = mapOf("candidate_ids" to proposed.map { it.id }.joinToString(","),
                            "current_ids" to current.evaluation.candidateIds.joinToString(",")))
                }
                publish()
                context.checkDeadline()
            }
            current = next ?: return finishGreedy(StopReason.NO_IMPROVEMENT)
        }
        finishGreedy(StopReason.MAX_INDEXES)
    }

    private fun finishGreedy(reason: StopReason) {
        context.checkDeadline()
        context.completePhase()
        stopReasons += reason
        publish()
    }

    private fun repair() {
        context.enterPhase(AnalysisPhase.REPAIR)
        val start = if (input.objective == Objective.ROBUST) bestRobust else bestMean
        // Keep the complete shortlist here: a previously successful incumbent may contain an index
        // whose later hypothesis failed. Such neighbors are filtered before any new evaluation.
        val structures = RepairNeighborhood.queues(input, start, available, evaluations, metrics, baseline, context)
        val seen = mutableSetOf<List<String>>()
        val queues = structures.map { queue ->
            queue.mapNotNull { proposed ->
                context.checkDeadline()
                val ids = proposed.map { it.id }
                if (!seen.add(ids) || ids.any { it in blockedCandidates } || ids in failed) null
                else {
                    val budget = metrics.assessBudget(proposed, input)
                    if (budget.admissible) proposed to budget
                    else {
                        recordBudgetRejection(budget, ids)
                        null
                    }
                }
            }
        }
        publish()
        val positions = IntArray(queues.size)
        val deferred = mutableListOf<List<String>>()
        while (queues.indices.any { positions[it] < queues[it].size }) {
            for (queue in queues.indices) {
                context.checkDeadline()
                while (positions[queue] < queues[queue].size &&
                    queues[queue][positions[queue]].first.map { it.id }.let { ids ->
                        ids.any { it in blockedCandidates } || ids in failed
                    }) {
                    positions[queue]++
                    context.checkDeadline()
                }
                if (positions[queue] >= queues[queue].size) continue
                val (proposed, budget) = queues[queue][positions[queue]++]
                val ids = proposed.map { it.id }
                pendingIds = ids
                // After the cap, still consume free cache hits and filter the remaining structures.
                // Only a genuinely untested admissible neighbor makes this a REPAIR_LIMIT partial.
                if (ids !in evaluations && context.statistics().repairAttempts >= context.limits.maxRepairAttempts) {
                    deferred += ids
                    recordUnexamined(ids)
                    pendingIds = null
                    continue
                }
                evaluate(proposed, budget = budget)
                pendingIds = null
                context.checkDeadline()
            }
        }
        unexamined = deferred.count { ids -> ids.none { it in blockedCandidates } }.toLong()
        if (unexamined != 0L) {
            stopReasons += StopReason.REPAIR_LIMIT
            errors += AnalysisError(ErrorCode.REPAIR_LIMIT, "Repair attempt limit reached.")
        } else {
            context.completePhase()
            stopReasons += StopReason.SEARCH_COMPLETE
        }
        publish()
    }

    private fun evaluate(
        proposed: List<IndexCandidate>,
        candidateId: String? = null,
        budget: BudgetAssessment = metrics.assessBudget(proposed, input),
    ): EvaluatedConfiguration? {
        val ids = proposed.map { it.id }
        if (ids.any { it in blockedCandidates } || ids in failed) return null
        if (!budget.admissible) {
            recordBudgetRejection(budget, ids, candidateId)
            publish()
            return null
        }
        pendingIds = ids
        if (ids in evaluations) {
            context.recordCacheHit()
            pendingIds = null
            publish()
            return accepted[ids]
        }
        context.checkDeadline()
        if (context.phase == AnalysisPhase.REPAIR) context.reserveRepairAttempt()
        val evaluation = try {
            evaluator.evaluate(proposed, workload, context).also { validateEvaluation(it, ids) }
        } catch (failure: AnalysisException) {
            if (!recoverLocalFailures) throw failure
            if (failure.error.code !in LOCAL_FAILURES) {
                if (failure.error.code in RECOVERABLE_CODES) {
                    failed[ids] = failure.error
                    decisions += CandidateDecision(CandidateReason.EVALUATION_FAILED, phase = context.phase,
                        details = mapOf("candidate_ids" to ids.joinToString(","), "code" to failure.error.code.name))
                    pendingIds = null
                    publish()
                }
                throw failure
            }
            failed[ids] = failure.error
            val badCandidate = failure.error.details["candidate_id"]?.takeIf { it in ids }
                ?: ids.singleOrNull().takeIf { failure.error.code == ErrorCode.HYPOTHETICAL_INDEX_ERROR }
            if (failure.error.code == ErrorCode.HYPOTHETICAL_INDEX_ERROR && badCandidate != null) {
                blockedCandidates += badCandidate
                decisions += CandidateDecision(CandidateReason.HYPOTHETICAL_INDEX_ERROR,
                    candidateId = badCandidate, phase = context.phase,
                    details = mapOf("candidate_ids" to ids.joinToString(",")))
                warnings += AnalysisWarning(WarningCode.CANDIDATE_REJECTED,
                    "A hypothetical index could not be created.", details = mapOf("candidate_id" to badCandidate))
            } else {
                errors += failure.error
                stopReasons += StopReason.EVALUATION_FAILED
                decisions += CandidateDecision(CandidateReason.EVALUATION_FAILED, phase = context.phase,
                    details = mapOf("candidate_ids" to ids.joinToString(","), "code" to failure.error.code.name))
                warnings += AnalysisWarning(WarningCode.EVALUATION_FAILED, "A configuration could not be evaluated completely.",
                    queryId = failure.error.queryId, details = mapOf("candidate_ids" to ids.joinToString(",")))
            }
            pendingIds = null
            publish()
            // A local error is recoverable only after successful cleanup on the same session.
            if (failure.suppressed.isNotEmpty()) throw failure
            context.checkDeadline()
            return null
        }
        val assessment = metrics.assessEvaluation(input, baseline, evaluation, budget.usage)
        evaluations[ids] = evaluation
        pendingIds = null
        if (assessment is EvaluationAssessment.Rejected) {
            rejected[ids] = assessment
            for (violation in assessment.violations) {
                warnings += AnalysisWarning(WarningCode.CANDIDATE_REJECTED, violation.message,
                    queryId = violation.queryId, profileId = violation.profileId,
                    details = violation.details + mapOf("code" to violation.code.name,
                        "candidate_ids" to ids.joinToString(",")))
            }
            publish()
            context.checkDeadline()
            return null
        }
        val evaluated = EvaluatedConfiguration(evaluation, assessment.metrics)
        accepted[ids] = evaluated
        if (comparator.compare(evaluated, bestRobust, Objective.ROBUST) < 0) bestRobust = evaluated
        if (comparator.compare(evaluated, bestMean, Objective.MEAN) < 0) bestMean = evaluated
        publish()
        context.checkDeadline()
        return evaluated
    }

    private fun recordBudgetRejection(budget: BudgetAssessment, ids: List<String>, candidateId: String? = null) {
        for (violation in budget.violations) {
            val reason = when (violation) {
                BudgetViolation.STORAGE_BUDGET -> CandidateReason.STORAGE_BUDGET
                BudgetViolation.WRITE_BUDGET -> CandidateReason.WRITE_BUDGET
                BudgetViolation.MAX_INDEXES -> CandidateReason.MAX_INDEXES
            }
            decisions += CandidateDecision(reason, candidateId = candidateId, phase = context.phase,
                details = mapOf("candidate_ids" to ids.joinToString(",")))
        }
    }

    private fun recordUnexamined(ids: List<String>) {
        decisions += CandidateDecision(CandidateReason.NOT_EVALUATED_DUE_TO_LIMIT, phase = context.phase,
            details = mapOf("candidate_ids" to ids.joinToString(",")))
    }

    private fun recordNotSelected() {
        val selectedIds = (if (input.objective == Objective.ROBUST) bestRobust else bestMean).evaluation.candidateIds
        for (candidate in available) {
            if (candidate.id !in selectedIds && candidate.id !in blockedCandidates &&
                evaluations.keys.any { candidate.id in it }) {
                decisions += CandidateDecision(CandidateReason.NOT_SELECTED, candidateId = candidate.id)
            }
        }
        publish()
    }

    private fun diagnostics() = SearchDiagnostics(
        completedPhases = context.completedPhases(), stopReasons = stopReasons.distinct(),
        statistics = context.statistics(), limits = context.limits, warnings = warnings.distinct(),
        errors = errors.distinct(), candidateDecisions = decisions.distinct(), unexaminedConfigurations = unexamined,
    )

    private fun publish() = onCheckpoint(SearchCheckpoint(bestRobust, bestMean, diagnostics()))

    private fun validateInput() {
        if (input.queries.size !in 1..20 || batch.candidates.size > 16 || input.constraints.maxIndexes !in 0..5) {
            throw AnalysisException(AnalysisError(ErrorCode.INPUT_LIMIT_EXCEEDED,
                "Search requires 1–20 queries, at most 16 candidates and max_indexes between 0 and 5."))
        }
        require(queryIds.size == input.queries.size) { "Query IDs must be unique" }
        require(workload.queries.map { it.id }.toSet() == queryIds && workload.queries.size == input.queries.size) {
            "Resolution must preserve every shared query"
        }
        require(batch.candidates.map { it.id }.distinct().size == batch.candidates.size) { "Candidate IDs must be unique" }
    }

    private fun validateEvaluation(evaluation: EvaluationResult, ids: List<String>) {
        if (evaluation.costByQueryId.keys != queryIds) throw AnalysisException(AnalysisError(
            ErrorCode.EXPLAIN_FAILED, "Evaluation did not cover every workload query.",
        ))
        if (evaluation.candidateIds != ids) throw AnalysisException(AnalysisError(
            ErrorCode.INTERNAL_ERROR, "Evaluator returned a different candidate set.",
        ))
    }

    private fun partialReason(code: ErrorCode): StopReason? = when (code) {
        ErrorCode.DEADLINE_EXCEEDED -> StopReason.DEADLINE_EXCEEDED
        ErrorCode.CANCELLED -> StopReason.CANCELLED
        ErrorCode.EXPLAIN_LIMIT -> StopReason.EXPLAIN_LIMIT
        ErrorCode.REPAIR_LIMIT -> StopReason.REPAIR_LIMIT
        ErrorCode.DB_CONNECTION_ERROR -> StopReason.CONNECTION_LOST
        ErrorCode.EXPLAIN_FAILED -> StopReason.EVALUATION_FAILED
        else -> null
    }

    private companion object {
        val EARLY_STOPS = setOf(StopReason.ZERO_STORAGE_BUDGET, StopReason.ZERO_MAX_INDEXES,
            StopReason.ZERO_BASELINE, StopReason.NO_CANDIDATES)
        val PARTIAL_STOPS = setOf(StopReason.DEADLINE_EXCEEDED, StopReason.CANCELLED, StopReason.EXPLAIN_LIMIT,
            StopReason.REPAIR_LIMIT, StopReason.CONNECTION_LOST, StopReason.EVALUATION_FAILED)
        val LOCAL_FAILURES = setOf(ErrorCode.EXPLAIN_FAILED, ErrorCode.HYPOTHETICAL_INDEX_ERROR)
        val RECOVERABLE_CODES = LOCAL_FAILURES + setOf(ErrorCode.DEADLINE_EXCEEDED, ErrorCode.CANCELLED,
            ErrorCode.EXPLAIN_LIMIT, ErrorCode.REPAIR_LIMIT, ErrorCode.DB_CONNECTION_ERROR)
    }
}
