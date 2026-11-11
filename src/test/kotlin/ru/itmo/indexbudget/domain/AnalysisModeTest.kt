package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.api.Test
import java.time.Duration

class AnalysisModeTest {
    @Test
    fun `normal mode preserves every old resource limit`() {
        assertEquals(RunLimits(), AnalysisMode.NORMAL.limits)
        assertEquals(AnalysisMode.NORMAL, RunContext().analysisMode)
    }

    @ParameterizedTest
    @EnumSource(AnalysisMode::class)
    fun `preset enforces exact EXPLAIN and repair boundaries including failures but excludes cache hits`(mode: AnalysisMode) {
        val context = RunContext(analysisMode = mode, clock = NanoClock { 0L })
        context.startSearch()
        repeat(mode.limits.maxExplainAttempts) {
            assertThrows(IllegalStateException::class.java) {
                context.withExplainAttempt<Unit> { throw IllegalStateException("Simulated EXPLAIN failure") }
            }
        }
        repeat(mode.limits.maxRepairAttempts) { context.reserveRepairAttempt() }
        repeat(3) { context.recordCacheHit() }

        assertEquals(ErrorCode.EXPLAIN_LIMIT, failure { context.reserveExplainAttempt() }.code)
        assertEquals(ErrorCode.REPAIR_LIMIT, failure { context.reserveRepairAttempt() }.code)
        assertEquals(mode.limits.maxExplainAttempts, context.statistics().explainAttempts)
        assertEquals(mode.limits.maxRepairAttempts, context.statistics().repairAttempts)
        assertEquals(3, context.statistics().cacheHits)
        assertEquals(0, context.statistics().completedEvaluations)
    }

    @ParameterizedTest
    @EnumSource(AnalysisMode::class)
    fun `selection retains elapsed validation time and both deadlines bound SQL`(mode: AnalysisMode) {
        var now = 0L
        val context = RunContext(clock = NanoClock { now })
        now = Duration.ofSeconds(10).toNanos()
        context.selectAnalysisMode(mode)
        assertEquals(mode.limits.commandTimeout.minusSeconds(10), context.remainingCommandTime())
        assertEquals(Duration.ofSeconds(2), context.explainTimeLimit())
        assertEquals(Duration.ofSeconds(5), context.connectionTimeLimit())
        context.startSearch()
        now += mode.limits.searchTimeout.minusMillis(1).toNanos()
        assertEquals(Duration.ofMillis(1), context.explainTimeLimit())
        assertEquals(Duration.ofMillis(1), context.connectionTimeLimit())
        now += Duration.ofMillis(1).toNanos()
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, failure { context.checkDeadline() }.code)
        context.enterReport()
        assertThrows(IllegalStateException::class.java) { context.selectAnalysisMode(mode) }
    }

    @Test
    fun `selecting fast after its command deadline cannot restart the clock`() {
        var now = 0L
        val context = RunContext(clock = NanoClock { now })
        now = Duration.ofSeconds(30).toNanos()
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, failure { context.selectAnalysisMode(AnalysisMode.FAST) }.code)
        assertEquals(Duration.ZERO, context.remainingCommandTime())
    }

    @Test
    fun `mode cannot change after introspection and explicit test limits stay effective`() {
        val override = RunLimits(maxExplainAttempts = 1)
        val context = RunContext(limits = override, clock = NanoClock { 0L })
        context.selectAnalysisMode(AnalysisMode.DEEP)
        assertEquals(AnalysisMode.DEEP, context.analysisMode)
        assertEquals(override, context.limits)
        context.enterPhase(AnalysisPhase.INTROSPECTION)
        assertThrows(IllegalStateException::class.java) { context.selectAnalysisMode(AnalysisMode.FAST) }
        assertEquals(override, context.limits)
    }

    private fun failure(action: () -> Unit): AnalysisError =
        assertThrows(AnalysisException::class.java) { action() }.error
}
