package ru.itmo.indexbudget.optimizer

import ru.itmo.indexbudget.domain.*

/**
 * Saved greedy pool for the fixed-S0 repair in #18. Evaluations include the supplied baseline and
 * rejected full vectors; only admissible configurations can become incumbents. Singleton vectors
 * remain available even when they did not improve the chain. All plans belong to this run.
 */
data class GreedySearchResult(
    val result: SearchResult.Completed,
    val current: EvaluatedConfiguration,
    val candidates: List<IndexCandidate>,
    val evaluations: Map<List<String>, EvaluationResult>,
    val rejectedEvaluations: Map<List<String>, EvaluationAssessment.Rejected>,
) {
    val repairStart: EvaluatedConfiguration get() = result.selected
}

/**
 * Greedy-only strategy for #17/PoC. Completion means the greedy phase finished, not that repair ran.
 * The application supplies the baseline and owns failure/partial finalization via checkpoints.
 * No per-run state is retained on this instance; formulas and tie-breaking belong to the domain.
 */
class GreedyOptimizer : Optimizer {
    override fun optimize(
        workload: ResolvedWorkload,
        candidates: CandidateBatch,
        baseline: EvaluationResult,
        evaluator: ConfigurationEvaluator,
        metrics: MetricsCalculator,
        comparator: ObjectiveComparator,
        context: RunContext,
        onCheckpoint: (SearchCheckpoint) -> Unit,
    ): SearchResult = search(workload, candidates, baseline, evaluator, metrics, comparator, context, onCheckpoint).result

    fun search(
        workload: ResolvedWorkload,
        candidates: CandidateBatch,
        baseline: EvaluationResult,
        evaluator: ConfigurationEvaluator,
        metrics: MetricsCalculator,
        comparator: ObjectiveComparator,
        context: RunContext,
        onCheckpoint: (SearchCheckpoint) -> Unit = {},
    ): GreedySearchResult {
        validateInput(workload, candidates)
        val input = workload.input
        val queryIds = input.queries.map { it.id }.toSet()
        validateEvaluation(baseline, emptyList(), queryIds)
        val emptyBudget = metrics.assessBudget(emptyList(), input)
        require(emptyBudget.admissible) { "The empty set must be admissible" }
        val baselineAssessment = metrics.assessEvaluation(input, baseline, baseline, emptyBudget.usage)
        require(baselineAssessment is EvaluationAssessment.Accepted) { "Baseline must be admissible" }
        var current = EvaluatedConfiguration(baseline, baselineAssessment.metrics)
        var bestRobust = current
        var bestMean = current
        val evaluations = linkedMapOf(emptyList<String>() to baseline)
        val rejected = linkedMapOf<List<String>, EvaluationAssessment.Rejected>()
        val decisions = candidates.diagnostics.decisions.toMutableList()
        val warnings = candidates.diagnostics.warnings.toMutableList()
        val available = mutableListOf<IndexCandidate>()

        fun diagnostics(reason: StopReason? = null) = SearchDiagnostics(
            completedPhases = context.completedPhases(),
            stopReasons = listOfNotNull(reason),
            statistics = context.statistics(),
            limits = context.limits,
            warnings = warnings.toList(),
            candidateDecisions = decisions.toList(),
        )

        fun publish() = onCheckpoint(SearchCheckpoint(bestRobust, bestMean, diagnostics()))

        fun finish(reason: StopReason): GreedySearchResult {
            context.checkDeadline()
            context.completePhase()
            val result = SearchResult.Completed(input.objective, baseline, bestRobust, bestMean, diagnostics(reason))
            onCheckpoint(SearchCheckpoint(bestRobust, bestMean, result.diagnostics))
            return GreedySearchResult(result, current, available.toList(), evaluations.toMap(), rejected.toMap())
        }

        // Publish before a deadline check so a late complete baseline is still recoverable.
        publish()
        context.enterPhase(AnalysisPhase.GREEDY)
        when {
            input.constraints.storageBudgetBytes == 0L -> return finish(StopReason.ZERO_STORAGE_BUDGET)
            input.constraints.maxIndexes == 0 -> return finish(StopReason.ZERO_MAX_INDEXES)
            !baselineAssessment.searchRequired -> return finish(StopReason.ZERO_BASELINE)
        }

        fun recordBudgetRejection(candidate: IndexCandidate, assessment: BudgetAssessment, ids: List<String>) {
            for (violation in assessment.violations) {
                val reason = when (violation) {
                    BudgetViolation.STORAGE_BUDGET -> CandidateReason.STORAGE_BUDGET
                    BudgetViolation.WRITE_BUDGET -> CandidateReason.WRITE_BUDGET
                    // The loop never proposes a set above K, and K=0 has already returned.
                    BudgetViolation.MAX_INDEXES -> error("Greedy proposed too many indexes")
                }
                decisions += CandidateDecision(reason, candidateId = candidate.id, phase = AnalysisPhase.GREEDY,
                    details = mapOf("candidate_ids" to ids.joinToString(",")))
            }
        }

        for (candidate in candidates.candidates.sortedBy { it.id }) {
            context.checkDeadline()
            val assessment = metrics.assessBudget(listOf(candidate), input)
            if (assessment.admissible) available += candidate
            else {
                recordBudgetRejection(candidate, assessment, listOf(candidate.id))
                publish()
            }
        }
        publish()
        if (available.isEmpty()) return finish(StopReason.NO_CANDIDATES)

        while (current.evaluation.candidateIds.size < input.constraints.maxIndexes) {
            context.checkDeadline()
            val currentIds = current.evaluation.candidateIds.toSet()
            val currentCandidates = available.filter { it.id in currentIds }
            var next: EvaluatedConfiguration? = null
            for (candidate in available) {
                context.checkDeadline()
                if (candidate.id in currentIds) continue
                val proposed = (currentCandidates + candidate).sortedBy { it.id }
                val ids = proposed.map { it.id }
                val budget = metrics.assessBudget(proposed, input)
                if (!budget.admissible) {
                    recordBudgetRejection(candidate, budget, ids)
                    publish()
                    continue
                }

                context.checkDeadline()
                val evaluation = evaluator.evaluate(proposed, workload, context)
                validateEvaluation(evaluation, ids, queryIds)
                val assessment = metrics.assessEvaluation(input, baseline, evaluation, budget.usage)
                evaluations[ids] = evaluation
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
                    continue
                }
                val evaluated = EvaluatedConfiguration(evaluation, assessment.metrics)
                if (comparator.compare(evaluated, bestRobust, Objective.ROBUST) < 0) bestRobust = evaluated
                if (comparator.compare(evaluated, bestMean, Objective.MEAN) < 0) bestMean = evaluated
                if (comparator.improvesPrimaryMetrics(evaluated.metrics, current.metrics, input.objective)) {
                    val previous = next
                    if (previous == null || comparator.compare(evaluated, previous, input.objective) < 0) next = evaluated
                } else {
                    decisions += CandidateDecision(CandidateReason.NO_MARGINAL_IMPROVEMENT,
                        candidateId = candidate.id, phase = AnalysisPhase.GREEDY,
                        details = mapOf("candidate_ids" to ids.joinToString(","),
                            "current_ids" to current.evaluation.candidateIds.joinToString(",")))
                }
                // Save both incumbents even if this evaluation cannot advance the current chain.
                publish()
                context.checkDeadline()
            }
            current = next ?: return finish(StopReason.NO_IMPROVEMENT)
        }
        return finish(StopReason.MAX_INDEXES)
    }

    private fun validateInput(workload: ResolvedWorkload, candidates: CandidateBatch) {
        val input = workload.input
        if (input.queries.size !in 1..20 || candidates.candidates.size > 16 || input.constraints.maxIndexes !in 0..5) {
            throw AnalysisException(AnalysisError(ErrorCode.INPUT_LIMIT_EXCEEDED,
                "Greedy requires 1–20 queries, at most 16 candidates and max_indexes between 0 and 5."))
        }
        require(input.queries.map { it.id }.distinct().size == input.queries.size) { "Query IDs must be unique" }
        require(workload.queries.map { it.id }.toSet() == input.queries.map { it.id }.toSet() &&
            workload.queries.size == input.queries.size) { "Resolution must preserve every shared query" }
        require(candidates.candidates.map { it.id }.distinct().size == candidates.candidates.size) {
            "Candidate IDs must be unique"
        }
    }

    private fun validateEvaluation(evaluation: EvaluationResult, ids: List<String>, queryIds: Set<String>) {
        if (evaluation.costByQueryId.keys != queryIds) throw AnalysisException(AnalysisError(
            ErrorCode.EXPLAIN_FAILED, "Evaluation did not cover every workload query.",
        ))
        if (evaluation.candidateIds != ids) throw AnalysisException(AnalysisError(
            ErrorCode.INTERNAL_ERROR, "Evaluator returned a different candidate set.",
        ))
    }
}
