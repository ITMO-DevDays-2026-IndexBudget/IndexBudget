package ru.itmo.indexbudget.domain

import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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

/** Immutable progress snapshot. Observers must not perform analysis or change the context. */
enum class RunScope {
    PREPARATION, POINT;
    val wireName: String get() = name.lowercase()
}

data class RunProgress(
    val phase: AnalysisPhase,
    val statistics: RunStatistics,
    val completedPhases: List<AnalysisPhase>,
    val scope: RunScope? = null,
    val ordinal: Int? = null,
)

/** Safe metadata captured by the adapter on its existing session; never connection settings. */
data class DatabaseVersions(val postgresVersion: String? = null, val hypopgVersion: String? = null)

/**
 * Mutable state owned by one application invocation. Only cancellation is shared across threads.
 * No connections or secrets. Progress observers execute synchronously on the analysis thread.
 * Search starts immediately before baseline. The evaluator owns evaluation/EXPLAIN counters;
 * search owns repair/cache counters. Attempts are reserved before calls, so failures consume a slot.
 * The application decides whether an error permits partial output. Minimal partial export remains
 * an application responsibility even after the overall budget expires; this object never writes files.
 */
class RunContext(
    val runId: String = UUID.randomUUID().toString(),
    limits: RunLimits? = null,
    private val clock: NanoClock = NanoClock(System::nanoTime),
    private var onProgress: (RunProgress) -> Unit = {},
    analysisMode: AnalysisMode = AnalysisMode.NORMAL,
    private val parent: RunContext? = null,
    private val scope: RunScope? = null,
    private val ordinal: Int? = null,
) {
    // Explicit limits support deterministic tests and offline tools; always report effective values.
    private val limitsOverride = limits
    private var seriesCommandTimeout: Duration? = null
    var limits: RunLimits = limits ?: analysisMode.limits
        private set
    var analysisMode: AnalysisMode = analysisMode
        private set
    private val commandStartedAt = clock.nanoTime()
    private var searchStartedAt: Long? = null
    private var explainAttempts = 0
    private var evaluationAttempts = 0
    private var completedEvaluations = 0
    private var cacheHits = 0
    private var repairAttempts = 0
    private val completed = linkedSetOf<AnalysisPhase>()
    private val cancelled = AtomicBoolean()

    val isCancelled: Boolean get() = cancelled.get() || parent?.isCancelled == true

    /** Series control has its own budget; children retain independent clocks and counters. */
    fun selectSeriesTimeout(timeout: Duration) {
        check(phase == AnalysisPhase.VALIDATION && evaluationAttempts == 0)
        require(!timeout.isZero && !timeout.isNegative && timeout <= Duration.ofDays(1))
        seriesCommandTimeout = timeout
        limits = limits.copy(commandTimeout = timeout)
        checkDeadline()
    }

    fun child(mode: AnalysisMode, scope: RunScope? = null, ordinal: Int? = null): RunContext =
        RunContext(clock = clock, onProgress = onProgress, analysisMode = mode, parent = this, scope = scope, ordinal = ordinal)

    /** Select during validation, retaining time already spent since command startup. */
    fun selectAnalysisMode(mode: AnalysisMode) {
        check(phase == AnalysisPhase.VALIDATION && searchStartedAt == null && evaluationAttempts == 0) {
            "Analysis mode must be selected before introspection and evaluation"
        }
        analysisMode = mode
        limits = limitsOverride ?: mode.limits
        seriesCommandTimeout?.let { limits = limits.copy(commandTimeout = it) }
        checkDeadline()
    }

    /** Fingerprint of validated source JSON, before DTO defaults; never stores the source itself. */
    var inputHash: String? = null
        private set

    var databaseVersions: DatabaseVersions = DatabaseVersions()
        private set

    fun recordDatabaseVersions(versions: DatabaseVersions) {
        databaseVersions = versions
    }

    fun recordInputHash(sha256: String) {
        check(inputHash == null) { "Input hash can only be recorded once per run" }
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Input hash must be a lowercase SHA-256 digest"
        }
        inputHash = sha256
    }

    var phase: AnalysisPhase = AnalysisPhase.VALIDATION
        private set

    /** Attach the command's stderr observer without restarting the clock after application startup. */
    fun observeProgress(observer: (RunProgress) -> Unit) {
        onProgress = observer
    }

    fun enterPhase(phase: AnalysisPhase) {
        this.phase = phase
        publishProgress()
        checkDeadline()
    }

    /**
     * Enter finalization even after cancellation or deadline exhaustion. The application may save
     * an already computed minimal result; remaining time is not replenished and new work is still
     * rejected by [checkDeadline]. This does not authorize further database operations.
     */
    fun enterReport() {
        phase = AnalysisPhase.REPORT
        publishProgress()
    }

    fun completePhase() {
        completed += phase
        publishProgress()
    }

    fun completedPhases(): List<AnalysisPhase> = completed.toList()

    /** Safe to call repeatedly from a shutdown/cancellation thread; does not invoke observers. */
    fun cancel() {
        cancelled.set(true)
    }

    fun startSearch() {
        check(searchStartedAt == null) { "Search can only start once in a run" }
        checkDeadline()
        searchStartedAt = clock.nanoTime()
    }

    fun remainingCommandTime(): Duration {
        val own = Duration.ofNanos((limits.commandTimeout.toNanos() - elapsedNanos(commandStartedAt)).coerceAtLeast(0))
        return parent?.let { minOf(own, it.remainingTime()) } ?: own
    }

    /** REPORT uses the remaining command budget, even after the search budget expires. */
    fun remainingTime(): Duration {
        val commandRemaining = remainingCommandTime().toNanos()
        val searchStart = searchStartedAt
        if (searchStart == null || phase == AnalysisPhase.REPORT) return Duration.ofNanos(commandRemaining)
        val searchRemaining = (limits.searchTimeout.toNanos() - elapsedNanos(searchStart)).coerceAtLeast(0)
        return Duration.ofNanos(minOf(commandRemaining, searchRemaining))
    }

    fun checkDeadline() {
        if (isCancelled) fail(ErrorCode.CANCELLED, "Analysis was cancelled")
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
        claimExplainAttempt()
        publishProgress()
    }

    /**
     * JDBC uses this boundary so synchronous progress observers cannot delay SQL after reservation.
     * Publish even on failure, but only once the actual execute call has begun.
     */
    fun <T> withExplainAttempt(execute: () -> T): T {
        claimExplainAttempt()
        try {
            return execute()
        } finally {
            publishProgress()
        }
    }

    private fun claimExplainAttempt() {
        checkDeadline()
        if (explainAttempts >= limits.maxExplainAttempts) fail(ErrorCode.EXPLAIN_LIMIT, "EXPLAIN attempt limit reached")
        explainAttempts++
    }

    fun reserveEvaluationAttempt() {
        checkDeadline()
        evaluationAttempts++
        publishProgress()
    }

    /** Called for a new repair evaluation, including one that subsequently fails; never for a cache hit. */
    fun reserveRepairAttempt() {
        checkDeadline()
        if (repairAttempts >= limits.maxRepairAttempts) fail(ErrorCode.REPAIR_LIMIT, "Repair attempt limit reached")
        repairAttempts++
        publishProgress()
    }

    fun recordCompletedEvaluation() {
        check(completedEvaluations < evaluationAttempts) { "A completed evaluation needs a reserved attempt" }
        completedEvaluations++
        publishProgress()
    }

    fun recordCacheHit() {
        cacheHits++
        publishProgress()
    }

    fun statistics(): RunStatistics = RunStatistics(
        explainAttempts, evaluationAttempts, completedEvaluations, cacheHits, repairAttempts,
    )

    fun elapsedTime(): Duration = Duration.ofNanos(elapsedNanos(commandStartedAt))

    private fun publishProgress() {
        onProgress(RunProgress(phase, statistics(), completedPhases(), scope, ordinal))
    }

    private fun elapsedNanos(start: Long): Long = (clock.nanoTime() - start).coerceAtLeast(0)

    private fun fail(code: ErrorCode, message: String): Nothing = throw AnalysisException(
        AnalysisError(code, message, details = mapOf("phase" to phase.name.lowercase())),
    )
}
