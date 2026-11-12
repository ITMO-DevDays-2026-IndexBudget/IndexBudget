package ru.itmo.indexbudget.experiments

import ru.itmo.indexbudget.domain.EvaluationAssessment
import ru.itmo.indexbudget.domain.EvaluationResult
import ru.itmo.indexbudget.domain.EvaluatedConfiguration
import ru.itmo.indexbudget.domain.IndexCandidate
import ru.itmo.indexbudget.domain.MetricsCalculator
import ru.itmo.indexbudget.domain.NanoClock
import ru.itmo.indexbudget.domain.Objective
import ru.itmo.indexbudget.domain.ObjectiveComparator
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.optimizer.ConfigurationEvaluator
import java.time.Duration

/**
 * Verification-only exhaustive reference over a fixed candidate pool (#104). It lives in test
 * sources, is never a Spring bean and is not reachable from the production `analyze` command.
 *
 * Enumerates every subset of the pool up to `max_indexes` in a deterministic order (by size, then
 * lexicographic sorted IDs), skips configurations that violate any pre-EXPLAIN budget without
 * evaluating them, and applies the same post-evaluation admission as production search
 * ([MetricsCalculator.assessEvaluation]): rejected full vectors keep their diagnostics and never
 * update either incumbent. Formulas and comparison come from the shared production calculator
 * and comparator, so greedy and greedy+repair results are directly comparable set-by-set.
 *
 * The tool carries its own explicit verification deadline in addition to the run budget: an
 * interrupted enumeration reports [ExhaustiveSearchResult.complete] = false and its incumbents
 * are never presented as a verified optimum. An incomplete reference is an observation, not a
 * proof of optimality.
 */
data class ExhaustiveSearchResult(
    val objective: Objective,
    val bestRobust: EvaluatedConfiguration,
    val bestMean: EvaluatedConfiguration,
    /** Complete cost vectors by sorted candidate IDs, including the supplied baseline as the empty set. */
    val evaluations: Map<List<String>, EvaluationResult>,
    /** Rejected full vectors with raw metrics and per-SQL/per-profile violations; they keep their plans. */
    val rejectedEvaluations: Map<List<String>, EvaluationAssessment.Rejected>,
    /** Subsets excluded by storage/write budgets before any EXPLAIN; they consume no attempt. */
    val budgetExcludedSets: List<List<String>>,
    /** Number of subsets in the enumeration space: sum of C(pool, k) for k in 1..min(maxIndexes, pool). */
    val searchSpaceSize: Long,
    val evaluatedSets: Int,
    /** True only when every subset of the space was budget-excluded, evaluated or rejected. */
    val complete: Boolean,
    /** Null when complete; otherwise "verification_deadline" or the terminal error code name. */
    val stopReason: String?,
) {
    val verifiedOptimum: Boolean get() = complete

    /** The incumbent of [objective], mirroring how the production search exposes its selection. */
    fun selected(objective: Objective): EvaluatedConfiguration =
        if (objective == Objective.ROBUST) bestRobust else bestMean
}

class ExhaustiveSearchOptimizer(
    private val verificationTimeout: Duration = Duration.ofSeconds(60),
    private val clock: NanoClock = NanoClock(System::nanoTime),
) {
    init {
        require(!verificationTimeout.isNegative && !verificationTimeout.isZero) {
            "Verification timeout must be positive"
        }
    }

    /**
     * Baseline admission follows the production search: the empty set must be budget-admissible
     * and the baseline must pass [MetricsCalculator.assessEvaluation]; otherwise the reference
     * cannot exist and the call fails fast.
     */
    fun search(
        workload: ResolvedWorkload,
        pool: List<IndexCandidate>,
        baseline: EvaluationResult,
        evaluator: ConfigurationEvaluator,
        metrics: MetricsCalculator,
        comparator: ObjectiveComparator,
        context: RunContext,
    ): ExhaustiveSearchResult {
        val input = workload.input
        require(pool.size <= MAX_POOL) { "Verification pool must hold at most $MAX_POOL candidates" }
        require(pool.map { it.id }.distinct().size == pool.size) { "Candidate IDs must be unique" }
        val maxIndexes = input.constraints.maxIndexes
        require(maxIndexes in 0..MAX_INDEXES) { "max_indexes must stay within 0..$MAX_INDEXES" }

        val emptyBudget = metrics.assessBudget(emptyList(), input)
        require(emptyBudget.admissible) { "The empty set must be admissible" }
        val baselineAssessment = metrics.assessEvaluation(input, baseline, baseline, emptyBudget.usage)
        require(baselineAssessment is EvaluationAssessment.Accepted) { "Baseline must be admissible" }
        var bestRobust = EvaluatedConfiguration(baseline, baselineAssessment.metrics)
        var bestMean = bestRobust
        val evaluations = linkedMapOf<List<String>, EvaluationResult>(emptyList<String>() to baseline)
        val rejected = linkedMapOf<List<String>, EvaluationAssessment.Rejected>()
        val budgetExcluded = mutableListOf<List<String>>()
        var evaluated = 0
        var stopReason: String? = null

        val ordered = pool.sortedBy { it.id }
        val depth = minOf(maxIndexes, ordered.size)
        val space = (1..depth).sumOf { size -> binomial(ordered.size, size) }
        val startedAt = clock.nanoTime()

        outer@ for (size in 1..depth) {
            for (combination in combinations(ordered, size)) {
                context.checkDeadline()
                if (clock.nanoTime() - startedAt > verificationTimeout.toNanos()) {
                    stopReason = VERIFICATION_DEADLINE
                    break@outer
                }
                val ids = combination.map { it.id }
                val budget = metrics.assessBudget(combination, input)
                if (!budget.admissible) {
                    budgetExcluded += ids
                    continue
                }
                val evaluation = try {
                    evaluator.evaluate(combination, workload, context)
                } catch (failure: ru.itmo.indexbudget.domain.AnalysisException) {
                    if (failure.error.code in TERMINAL_CODES) {
                        stopReason = failure.error.code.name.lowercase()
                        break@outer
                    }
                    throw failure
                }
                evaluated++
                evaluations[ids] = evaluation
                val assessment = metrics.assessEvaluation(input, baseline, evaluation, budget.usage)
                if (assessment is EvaluationAssessment.Rejected) {
                    rejected[ids] = assessment
                    continue
                }
                val configuration = EvaluatedConfiguration(evaluation, assessment.metrics)
                if (comparator.compare(configuration, bestRobust, Objective.ROBUST) < 0) bestRobust = configuration
                if (comparator.compare(configuration, bestMean, Objective.MEAN) < 0) bestMean = configuration
            }
        }
        // Rejected vectors are a subset of the evaluated ones; budget-excluded sets were never sent.
        val enumerated = evaluated.toLong() + budgetExcluded.size
        val complete = stopReason == null && enumerated == space
        return ExhaustiveSearchResult(
            objective = input.objective,
            bestRobust = bestRobust,
            bestMean = bestMean,
            evaluations = evaluations,
            rejectedEvaluations = rejected,
            budgetExcludedSets = budgetExcluded,
            searchSpaceSize = space,
            evaluatedSets = evaluated,
            complete = complete,
            stopReason = if (complete) null else (stopReason ?: "enumeration_mismatch:$enumerated/$space"),
        )
    }

    private fun binomial(n: Int, k: Int): Long {
        var result = 1L
        for (shift in 0 until k) result = result * (n - shift) / (shift + 1)
        return result
    }

    /** Deterministic order: elements are already sorted by ID, indices grow lexicographically. */
    private fun <T> combinations(items: List<T>, size: Int): Sequence<List<T>> = sequence {
        val indices = IntArray(size) { it }
        while (true) {
            yield(indices.map { items[it] })
            var slot = size - 1
            while (slot >= 0 && indices[slot] == items.size - size + slot) slot--
            if (slot < 0) return@sequence
            indices[slot]++
            for (after in slot + 1 until size) indices[after] = indices[after - 1] + 1
        }
    }

    companion object {
        const val MAX_POOL = 8
        const val MAX_INDEXES = 5
        const val VERIFICATION_DEADLINE = "verification_deadline"
        private val TERMINAL_CODES = setOf(
            ru.itmo.indexbudget.domain.ErrorCode.DEADLINE_EXCEEDED,
            ru.itmo.indexbudget.domain.ErrorCode.CANCELLED,
            ru.itmo.indexbudget.domain.ErrorCode.EXPLAIN_LIMIT,
            ru.itmo.indexbudget.domain.ErrorCode.DB_CONNECTION_ERROR,
        )
    }
}
