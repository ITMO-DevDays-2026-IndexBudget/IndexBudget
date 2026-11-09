package ru.itmo.indexbudget.domain

import java.time.Duration
import java.util.UUID

enum class AnalysisPhase { VALIDATION, INTROSPECTION, BASELINE, CANDIDATES, GREEDY, REPAIR, REPORT }

fun interface NanoClock {
    fun nanoTime(): Long
}

data class RunLimits(
    val commandTimeout: Duration = Duration.ofSeconds(90),
    val searchTimeout: Duration = Duration.ofSeconds(60),
    val explainTimeout: Duration = Duration.ofSeconds(2),
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val maxExplainAttempts: Int = 2000,
    val maxRepairAttempts: Int = 28,
) {
    init {
        require(listOf(commandTimeout, searchTimeout, explainTimeout, connectTimeout).all {
            !it.isNegative && !it.isZero
        }) { "Timeouts must be positive" }
        require(maxExplainAttempts > 0 && maxRepairAttempts > 0) { "Attempt limits must be positive" }
    }
}

data class RunStatistics(
    val explainAttempts: Int = 0,
    val evaluationAttempts: Int = 0,
    val completedEvaluations: Int = 0,
    val cacheHits: Int = 0,
    val repairAttempts: Int = 0,
)

/**
 * Mutable, single-threaded state owned by one application invocation. No connections or secrets.
 * Search starts immediately before baseline. The evaluator owns evaluation/EXPLAIN counters;
 * search owns repair/cache counters. Attempts are reserved before calls, so failures consume a slot.
 * The application decides whether an error permits partial output. Minimal partial export remains
 * an application responsibility even after the overall budget expires; this object never writes files.
 */
class RunContext(
    val runId: String = UUID.randomUUID().toString(),
    val limits: RunLimits = RunLimits(),
    private val clock: NanoClock = NanoClock(System::nanoTime),
) {
    private val commandStartedAt = clock.nanoTime()
    private var searchStartedAt: Long? = null
    private var explainAttempts = 0
    private var evaluationAttempts = 0
    private var completedEvaluations = 0
    private var cacheHits = 0
    private var repairAttempts = 0
    private val completed = linkedSetOf<AnalysisPhase>()

    var phase: AnalysisPhase = AnalysisPhase.VALIDATION
        private set

    fun enterPhase(phase: AnalysisPhase) {
        this.phase = phase
        checkDeadline()
    }

    fun completePhase() {
        completed += phase
    }

    fun completedPhases(): List<AnalysisPhase> = completed.toList()

    fun startSearch() {
        check(searchStartedAt == null) { "Search can only start once in a run" }
        checkDeadline()
        searchStartedAt = clock.nanoTime()
    }

    fun remainingCommandTime(): Duration = Duration.ofNanos(
        (limits.commandTimeout.toNanos() - elapsedNanos(commandStartedAt)).coerceAtLeast(0),
    )

    /** REPORT uses the remaining command budget, even after the search budget expires. */
    fun remainingTime(): Duration {
        val commandRemaining = remainingCommandTime().toNanos()
        val searchStart = searchStartedAt
        if (searchStart == null || phase == AnalysisPhase.REPORT) return Duration.ofNanos(commandRemaining)
        val searchRemaining = (limits.searchTimeout.toNanos() - elapsedNanos(searchStart)).coerceAtLeast(0)
        return Duration.ofNanos(minOf(commandRemaining, searchRemaining))
    }

    fun checkDeadline() {
        if (remainingTime().isZero) fail(ErrorCode.DEADLINE_EXCEEDED, "Analysis time budget exhausted")
    }

    fun explainTimeLimit(): Duration {
        checkDeadline()
        return minOf(limits.explainTimeout, remainingTime())
    }

    fun connectionTimeLimit(): Duration {
        checkDeadline()
        return minOf(limits.connectTimeout, remainingTime())
    }

    fun reserveExplainAttempt() {
        checkDeadline()
        if (explainAttempts >= limits.maxExplainAttempts) fail(ErrorCode.EXPLAIN_LIMIT, "EXPLAIN attempt limit reached")
        explainAttempts++
    }

    fun reserveEvaluationAttempt() {
        checkDeadline()
        evaluationAttempts++
    }

    /** Called for a new repair evaluation, including one that subsequently fails; never for a cache hit. */
    fun reserveRepairAttempt() {
        checkDeadline()
        if (repairAttempts >= limits.maxRepairAttempts) fail(ErrorCode.REPAIR_LIMIT, "Repair attempt limit reached")
        repairAttempts++
    }

    fun recordCompletedEvaluation() {
        check(completedEvaluations < evaluationAttempts) { "A completed evaluation needs a reserved attempt" }
        completedEvaluations++
    }

    fun recordCacheHit() {
        cacheHits++
    }

    fun statistics(): RunStatistics = RunStatistics(
        explainAttempts, evaluationAttempts, completedEvaluations, cacheHits, repairAttempts,
    )

    fun elapsedTime(): Duration = Duration.ofNanos(elapsedNanos(commandStartedAt))

    private fun elapsedNanos(start: Long): Long = (clock.nanoTime() - start).coerceAtLeast(0)

    private fun fail(code: ErrorCode, message: String): Nothing = throw AnalysisException(
        AnalysisError(code, message, details = mapOf("phase" to phase.name.lowercase())),
    )
}
