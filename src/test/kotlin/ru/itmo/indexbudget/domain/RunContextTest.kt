package ru.itmo.indexbudget.domain

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunContextTest {
    private var now = 0L
    private val clock = NanoClock { now }

    @Test
    fun `search uses its own budget but report can use remaining command time`() {
        val context = RunContext(clock = clock)
        advanceSeconds(10)
        context.startSearch()
        context.enterPhase(AnalysisPhase.BASELINE)
        advanceSeconds(59)

        assertEquals(Duration.ofSeconds(1), context.remainingTime())
        assertEquals(Duration.ofSeconds(1), context.explainTimeLimit())
        advanceSeconds(1)
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, failure { context.checkDeadline() }.code)

        context.enterPhase(AnalysisPhase.REPORT)
        assertEquals(Duration.ofSeconds(20), context.remainingTime())
    }

    @Test
    fun `late search cannot extend the overall command deadline`() {
        val context = RunContext(clock = clock)
        advanceSeconds(80)
        context.startSearch()

        assertEquals(Duration.ofSeconds(10), context.remainingTime())
        advanceSeconds(10)
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, failure { context.enterPhase(AnalysisPhase.REPORT) }.code)
    }

    @Test
    fun `reserved failed attempts count and cache hits consume no additional attempts`() {
        val context = RunContext(
            limits = RunLimits(maxExplainAttempts = 2, maxRepairAttempts = 1),
            clock = clock,
        )
        context.startSearch()
        context.reserveEvaluationAttempt()
        context.reserveExplainAttempt()
        // Simulate an external call failing: no completed evaluation is recorded.
        context.reserveEvaluationAttempt()
        context.reserveRepairAttempt()
        context.reserveExplainAttempt()
        context.recordCompletedEvaluation()
        context.recordCacheHit()

        assertEquals(ErrorCode.EXPLAIN_LIMIT, failure { context.reserveExplainAttempt() }.code)
        assertEquals(ErrorCode.REPAIR_LIMIT, failure { context.reserveRepairAttempt() }.code)
        assertEquals(
            RunStatistics(
                explainAttempts = 2,
                evaluationAttempts = 2,
                completedEvaluations = 1,
                cacheHits = 1,
                repairAttempts = 1,
            ),
            context.statistics(),
        )
    }

    @Test
    fun `an evaluation cannot complete without consuming an attempt`() {
        val context = RunContext(clock = clock)

        assertThrows(IllegalStateException::class.java) { context.recordCompletedEvaluation() }
    }

    @Test
    fun `minimal report can be entered after both deadlines without granting more time`() {
        val context = RunContext(clock = clock)
        context.startSearch()
        context.enterPhase(AnalysisPhase.BASELINE)
        context.completePhase()
        advanceSeconds(90)

        context.enterReport()

        assertEquals(AnalysisPhase.REPORT, context.phase)
        assertEquals(Duration.ZERO, context.remainingTime())
        assertEquals(listOf(AnalysisPhase.BASELINE), context.completedPhases())
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, failure { context.reserveExplainAttempt() }.code)
        assertEquals(RunStatistics(), context.statistics())
    }

    @Test
    fun `cancellation from another thread rejects operations but allows finalization`() {
        val context = RunContext(clock = clock)
        context.startSearch()
        context.enterPhase(AnalysisPhase.BASELINE)
        Thread {
            context.cancel()
            context.cancel()
        }.apply {
            start()
            join()
        }

        assertTrue(context.isCancelled)
        assertEquals(ErrorCode.CANCELLED, failure { context.checkDeadline() }.code)
        assertEquals(ErrorCode.CANCELLED, failure { context.connectionTimeLimit() }.code)
        assertEquals(ErrorCode.CANCELLED, failure { context.explainTimeLimit() }.code)
        assertEquals(ErrorCode.CANCELLED, failure { context.reserveEvaluationAttempt() }.code)
        assertEquals(ErrorCode.CANCELLED, failure { context.reserveRepairAttempt() }.code)
        assertEquals(ErrorCode.CANCELLED, failure { context.reserveExplainAttempt() }.code)
        assertEquals(RunStatistics(), context.statistics())

        context.enterReport()
        assertEquals(AnalysisPhase.REPORT, context.phase)
        assertEquals(Duration.ofSeconds(90), context.remainingTime())
        assertEquals(ErrorCode.CANCELLED, failure { context.checkDeadline() }.code)
    }

    @Test
    fun `progress exposes real counters and completed phases as independent snapshots`() {
        val progress = mutableListOf<RunProgress>()
        val context = RunContext(clock = clock, onProgress = { progress += it })
        context.enterPhase(AnalysisPhase.VALIDATION)
        context.completePhase()
        context.startSearch()
        context.enterPhase(AnalysisPhase.BASELINE)
        context.reserveEvaluationAttempt()
        context.reserveExplainAttempt()
        context.recordCompletedEvaluation()
        context.completePhase()
        context.enterPhase(AnalysisPhase.REPAIR)
        context.recordCacheHit()
        context.reserveRepairAttempt()
        context.reserveEvaluationAttempt()
        context.reserveExplainAttempt()
        // The second evaluation failed: no completed evaluation is added.
        context.enterReport()

        assertEquals(AnalysisPhase.VALIDATION, progress.first().phase)
        assertEquals(RunStatistics(), progress.first().statistics)
        assertEquals(emptyList<AnalysisPhase>(), progress.first().completedPhases)
        assertEquals(listOf(AnalysisPhase.VALIDATION), progress[1].completedPhases)
        assertEquals(
            RunProgress(
                AnalysisPhase.REPORT,
                RunStatistics(2, 2, 1, 1, 1),
                listOf(AnalysisPhase.VALIDATION, AnalysisPhase.BASELINE),
            ),
            progress.last(),
        )
        assertEquals(listOf(0, 1, 2), progress.map { it.statistics.explainAttempts }.distinct())
        assertEquals(listOf(0, 1), progress.map { it.statistics.completedEvaluations }.distinct())
    }

    @Test
    fun `connection and explain timeouts respect the remaining command budget before search`() {
        val context = RunContext(clock = clock)
        assertEquals(Duration.ofSeconds(5), context.connectionTimeLimit())
        assertEquals(Duration.ofSeconds(2), context.explainTimeLimit())
        advanceSeconds(89)

        assertEquals(Duration.ofSeconds(1), context.connectionTimeLimit())
        assertEquals(Duration.ofSeconds(1), context.explainTimeLimit())
        assertEquals(Duration.ofSeconds(89), context.elapsedTime())
    }

    @Test
    fun `search cannot be restarted to extend its budget`() {
        val context = RunContext(clock = clock)
        context.startSearch()
        advanceSeconds(59)

        assertThrows(IllegalStateException::class.java) { context.startSearch() }
        assertEquals(Duration.ofSeconds(1), context.remainingTime())
    }

    private fun advanceSeconds(seconds: Long) {
        now += Duration.ofSeconds(seconds).toNanos()
    }

    private fun failure(action: () -> Unit): AnalysisError =
        assertThrows(AnalysisException::class.java) { action() }.error
}
