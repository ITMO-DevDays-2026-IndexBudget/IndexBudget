package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

class SeriesRunContextTest {
    @Test
    fun `workload mode selection cannot reset the series deadline during validation`() {
        var nanos = 0L
        val parent = RunContext(clock = NanoClock { nanos })
        parent.selectSeriesTimeout(Duration.ofSeconds(60))
        nanos = Duration.ofSeconds(35).toNanos()
        parent.selectAnalysisMode(AnalysisMode.FAST)
        assertEquals(Duration.ofSeconds(25), parent.remainingTime())
        parent.selectSeriesTimeout(Duration.ofSeconds(36))
        parent.selectAnalysisMode(AnalysisMode.DEEP)
        assertEquals(Duration.ofSeconds(1), parent.remainingTime())
        nanos = Duration.ofSeconds(36).toNanos()
        assertEquals(ErrorCode.DEADLINE_EXCEEDED,
            assertThrows(AnalysisException::class.java) { parent.selectAnalysisMode(AnalysisMode.DEEP) }.error.code)
    }

    @Test
    fun `children keep separate counters and clocks while respecting the total series budget`() {
        var nanos = 0L
        val parent = RunContext(clock = NanoClock { nanos })
        parent.selectSeriesTimeout(Duration.ofSeconds(45))
        nanos = Duration.ofSeconds(10).toNanos()
        val first = parent.child(AnalysisMode.FAST)
        first.reserveExplainAttempt()
        nanos = Duration.ofSeconds(20).toNanos()
        val second = parent.child(AnalysisMode.DEEP)

        assertEquals(1, first.statistics().explainAttempts)
        assertEquals(0, second.statistics().explainAttempts)
        assertEquals(0, parent.statistics().explainAttempts)
        assertEquals(Duration.ofSeconds(20), first.remainingTime())
        assertEquals(Duration.ofSeconds(25), second.remainingTime())
        assertEquals(Duration.ofSeconds(5), second.connectionTimeLimit())
        nanos = Duration.ofSeconds(44).toNanos()
        assertEquals(Duration.ofSeconds(1), second.explainTimeLimit())
        nanos = Duration.ofSeconds(45).toNanos()
        val failure = assertThrows(AnalysisException::class.java) { second.checkDeadline() }
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, failure.error.code)
        second.enterReport()
        assertEquals(Duration.ZERO, second.remainingTime())
    }

    @Test
    fun `parent cancellation propagates to an active point and report does not reset it`() {
        val parent = RunContext()
        val child = parent.child(AnalysisMode.NORMAL)
        parent.cancel()
        assertTrue(child.isCancelled)
        child.enterReport()
        assertEquals(ErrorCode.CANCELLED,
            assertThrows(AnalysisException::class.java) { child.checkDeadline() }.error.code)
        assertEquals(RunStatistics(), child.statistics())
    }

    @Test
    fun `point cancellation does not prevent the next independent point`() {
        val parent = RunContext()
        parent.child(AnalysisMode.NORMAL).cancel()
        val next = parent.child(AnalysisMode.NORMAL)
        assertFalse(parent.isCancelled)
        assertDoesNotThrow { next.checkDeadline() }
    }
}
