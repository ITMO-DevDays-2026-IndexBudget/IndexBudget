package ru.itmo.indexbudget.domain

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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

    private fun advanceSeconds(seconds: Long) {
        now += Duration.ofSeconds(seconds).toNanos()
    }

    private fun failure(action: () -> Unit): AnalysisError =
        assertThrows(AnalysisException::class.java) { action() }.error
}
