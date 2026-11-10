package ru.itmo.indexbudget.acceptance

import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.ConfigurationEvaluator

/** Test-only reference in an explicitly fixed candidate space, never a production search strategy. */
class ExhaustiveOracle(
    private val metrics: MetricsCalculator = MetricsCalculatorImpl(),
    private val comparator: ObjectiveComparator = ObjectiveComparatorImpl(),
) {
    fun run(
        workload: ResolvedWorkload,
        candidates: List<IndexCandidate>,
        evaluator: ConfigurationEvaluator,
        context: RunContext,
    ): OracleResult {
        require(candidates.size <= 8) { "The oracle accepts at most eight candidates" }
        require(candidates.map { it.id }.distinct().size == candidates.size) { "Candidate IDs must be unique" }
        require(workload.input.constraints.maxIndexes in 0..5) { "The oracle accepts at most five indexes" }
        val ordered = candidates.sortedBy { it.id }
        val configurations = (0 until (1 shl ordered.size))
            .filter { Integer.bitCount(it) <= workload.input.constraints.maxIndexes }
            .map { mask -> ordered.filterIndexed { index, _ -> mask and (1 shl index) != 0 } }
            .sortedWith(compareBy<List<IndexCandidate>> { it.size }.thenBy { it.joinToString("\u0000") { c -> c.id } })
        val outcomes = mutableListOf<OracleOutcome>()
        val errors = mutableListOf<AnalysisError>()
        var baseline: EvaluationResult? = null
        var bestRobust: EvaluatedConfiguration? = null
        var bestMean: EvaluatedConfiguration? = null
        var baselineOnly: String? = null
        var fatal = false
        try {
            context.startSearch()
            context.enterPhase(AnalysisPhase.BASELINE)
            for (configuration in configurations) {
                context.checkDeadline()
                val ids = configuration.map { it.id }
                if (configuration.isNotEmpty() && baselineOnly != null) {
                    outcomes += OracleOutcome(ids, baselineOnly)
                    continue
                }
                val budget = metrics.assessBudget(configuration, workload.input)
                if (!budget.admissible) {
                    outcomes += OracleOutcome(ids, "budget_rejected", budgetViolations = budget.violations)
                    continue
                }
                val evaluated = try {
                    evaluator.evaluate(configuration, workload, context).also {
                        if (it.costByQueryId.keys != workload.queries.map { q -> q.id }.toSet()) {
                            throw AnalysisException(AnalysisError(ErrorCode.EXPLAIN_FAILED, "Incomplete oracle evaluation."))
                        }
                        require(it.candidateIds == ids) { "The evaluator must evaluate exactly the requested set" }
                    }
                } catch (error: AnalysisException) {
                    if (configuration.isNotEmpty() && error.error.code in setOf(
                            ErrorCode.EXPLAIN_FAILED, ErrorCode.HYPOTHETICAL_INDEX_ERROR,
                        )) {
                        val operationErrors = oracleOperationErrors(error)
                        outcomes += OracleOutcome(ids, "evaluation_failed", errors = operationErrors)
                        // A local query/hypothesis error is recoverable only when cleanup succeeded.
                        // Suppressed cleanup failures invalidate the session or the whole calculation.
                        if (error.suppressed.isNotEmpty()) throw error
                        errors += operationErrors
                        continue
                    }
                    throw error
                }
                if (configuration.isEmpty()) baseline = evaluated
                val assessment = metrics.assessEvaluation(workload.input, requireNotNull(baseline), evaluated, budget.usage)
                when (assessment) {
                    is EvaluationAssessment.Accepted -> {
                        val accepted = EvaluatedConfiguration(evaluated, assessment.metrics)
                        outcomes += OracleOutcome(ids, "accepted", evaluated, assessment.metrics)
                        if (bestRobust == null || comparator.compare(accepted, bestRobust, Objective.ROBUST) < 0) {
                            bestRobust = accepted
                        }
                        if (bestMean == null || comparator.compare(accepted, bestMean, Objective.MEAN) < 0) {
                            bestMean = accepted
                        }
                        if (configuration.isEmpty()) {
                            baselineOnly = when {
                                !assessment.searchRequired -> "baseline_only_zero_baseline"
                                workload.input.constraints.storageBudgetBytes == 0L -> "baseline_only_zero_storage"
                                else -> null
                            }
                            context.completePhase()
                            context.enterPhase(AnalysisPhase.CANDIDATES)
                        }
                    }
                    is EvaluationAssessment.Rejected -> outcomes += OracleOutcome(
                        ids, "evaluation_rejected", evaluated, assessment.metrics, errors = assessment.violations,
                    )
                }
                // Save a complete result even if the adapter returns just after the deadline.
                context.checkDeadline()
            }
            context.completePhase()
        } catch (error: AnalysisException) {
            val operationErrors = oracleOperationErrors(error)
            errors += operationErrors
            fatal = operationErrors.any { it.code !in ORACLE_RECOVERABLE_CODES }
        } catch (_: Exception) {
            errors += AnalysisError(ErrorCode.INTERNAL_ERROR, "The oracle component failed unexpectedly.")
            fatal = true
        }
        val complete = outcomes.size == configurations.size && errors.isEmpty()
        val status = when {
            fatal || baseline == null || bestRobust == null || bestMean == null -> RunStatus.FAILED
            !complete -> RunStatus.PARTIAL
            else -> RunStatus.COMPLETED
        }
        return OracleResult(status, configurations.size, outcomes.toList(), bestRobust, bestMean,
            context.statistics(), errors.toList(), status == RunStatus.COMPLETED)
    }
}

data class OracleOutcome(
    val candidateIds: List<String>,
    val disposition: String,
    val evaluation: EvaluationResult? = null,
    val metrics: SetMetrics? = null,
    val budgetViolations: Set<BudgetViolation> = emptySet(),
    val errors: List<AnalysisError> = emptyList(),
)

data class OracleResult(
    val status: RunStatus,
    val structuralConfigurations: Int,
    val outcomes: List<OracleOutcome>,
    val bestRobust: EvaluatedConfiguration?,
    val bestMean: EvaluatedConfiguration?,
    val statistics: RunStatistics,
    val errors: List<AnalysisError>,
    /** True only when every set in the declared space was evaluated or rejected by shared rules. */
    val optimumCertifiedInFixedSpace: Boolean,
)

internal val ORACLE_RECOVERABLE_CODES = setOf(
    ErrorCode.DEADLINE_EXCEEDED, ErrorCode.CANCELLED, ErrorCode.EXPLAIN_LIMIT,
    ErrorCode.DB_CONNECTION_ERROR, ErrorCode.EXPLAIN_FAILED, ErrorCode.HYPOTHETICAL_INDEX_ERROR,
)

/** Retain safe typed cleanup diagnostics; an untyped cleanup failure cannot establish a usable state. */
internal fun oracleOperationErrors(failure: AnalysisException): List<AnalysisError> =
    listOf(failure.error) + failure.suppressed.map { cleanup ->
        (cleanup as? AnalysisException)?.error
            ?: AnalysisError(ErrorCode.INTERNAL_ERROR, "Oracle operation cleanup failed unexpectedly.")
    }

/** Final cleanup may make a completed result partial, but can never upgrade an already failed run. */
internal fun OracleResult.withFailure(error: AnalysisError, context: RunContext): OracleResult {
    val combined = errors + error
    return copy(
        status = if (status != RunStatus.FAILED && bestRobust != null && bestMean != null &&
            combined.all { it.code in ORACLE_RECOVERABLE_CODES }) RunStatus.PARTIAL else RunStatus.FAILED,
        statistics = context.statistics(), errors = combined, optimumCertifiedInFixedSpace = false,
    )
}
