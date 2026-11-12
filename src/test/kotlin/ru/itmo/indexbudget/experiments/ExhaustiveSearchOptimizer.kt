package ru.itmo.indexbudget.experiments

import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.EvaluationAssessment
import ru.itmo.indexbudget.domain.EvaluationResult
import ru.itmo.indexbudget.domain.EvaluatedConfiguration
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.IndexCandidate
import ru.itmo.indexbudget.domain.MetricsCalculator
import ru.itmo.indexbudget.domain.NanoClock
import ru.itmo.indexbudget.domain.Objective
import ru.itmo.indexbudget.domain.ObjectiveComparator
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.RunLimits
import ru.itmo.indexbudget.domain.RunStatistics
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
 * The reference runs on its own verification context with explicit independent limits
 * (spec §9.3): the EXPLAIN attempt budget covers exactly the declared space, the command/search
 * timeouts equal the verification deadline, and SQL timeouts inside the evaluator inherit it.
 * Cancellation is shared with the caller's context; counters belong to the verification run and
 * are returned in [ExhaustiveSearchResult.statistics]. An all-zero baseline keeps the empty
 * result without enumerating, exactly like production search. An interrupted enumeration — by
 * the verification deadline, cancellation, the EXPLAIN budget or a lost connection — returns the
 * saved incumbents with [ExhaustiveSearchResult.complete] = false and is never presented as a
 * verified optimum.
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
    /** Counters of the dedicated verification context: attempts actually spent by this reference. */
    val statistics: RunStatistics,
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
     * cannot exist and the call fails fast. An accepted baseline with `searchRequired=false`
     * (every profile baseline is zero) returns the verified empty set without enumeration.
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
        val queryCount = input.queries.size
        // Independent verification budget (spec §9.3): exactly one EXPLAIN per subset per query
        // plus the already evaluated baseline; timeouts equal the verification deadline.
        val verificationContext = RunContext(
            limits = RunLimits(
                commandTimeout = verificationTimeout,
                searchTimeout = verificationTimeout,
                maxExplainAttempts = (((space + 1) * queryCount).coerceAtLeast(1L)).toInt(),
                maxRepairAttempts = 1,
            ),
            clock = clock,
            analysisMode = context.analysisMode,
            parent = context,
        )
        fun result(complete: Boolean, stop: String?) = ExhaustiveSearchResult(
            objective = input.objective,
            bestRobust = bestRobust,
            bestMean = bestMean,
            evaluations = evaluations,
            rejectedEvaluations = rejected,
            budgetExcludedSets = budgetExcluded,
            searchSpaceSize = space,
            evaluatedSets = evaluated,
            complete = complete,
            stopReason = stop,
            statistics = verificationContext.statistics(),
        )
        if (!baselineAssessment.searchRequired) {
            // Domain rule: nonempty sets are inadmissible with an all-zero baseline; the verified
            // reference is the empty set itself, as in production search.
            return result(complete = true, stop = null)
        }

        val startedAt = clock.nanoTime()
        try {
            outer@ for (size in 1..depth) {
                for (combination in combinations(ordered, size)) {
                    if (clock.nanoTime() - startedAt >= verificationTimeout.toNanos()) {
                        stopReason = VERIFICATION_DEADLINE
                        break@outer
                    }
                    verificationContext.checkDeadline()
                    val ids = combination.map { it.id }
                    val budget = metrics.assessBudget(combination, input)
                    if (!budget.admissible) {
                        budgetExcluded += ids
                        continue
                    }
                    val evaluation = evaluator.evaluate(combination, workload, verificationContext)
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
        } catch (failure: AnalysisException) {
            // A terminal stop between or during evaluations keeps the saved incumbents and the
            // honest incomplete flag; numeric, input and local hypothetical errors stay fatal.
            if (failure.error.code !in TERMINAL_CODES) throw failure
            stopReason = failure.error.code.name.lowercase()
        }
        // The deadline is re-checked after the last evaluation: an evaluation that returned past
        // the verification deadline must not complete the reference.
        if (stopReason == null && clock.nanoTime() - startedAt >= verificationTimeout.toNanos()) {
            stopReason = VERIFICATION_DEADLINE
        }
        // Rejected vectors are a subset of the evaluated ones; budget-excluded sets were never sent.
        val enumerated = evaluated.toLong() + budgetExcluded.size
        val complete = stopReason == null && enumerated == space
        return result(
            complete = complete,
            stop = if (complete) null else (stopReason ?: "enumeration_mismatch:$enumerated/$space"),
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
        internal val TERMINAL_CODES = setOf(
            ErrorCode.DEADLINE_EXCEEDED,
            ErrorCode.CANCELLED,
            ErrorCode.EXPLAIN_LIMIT,
            ErrorCode.DB_CONNECTION_ERROR,
        )
    }
}
