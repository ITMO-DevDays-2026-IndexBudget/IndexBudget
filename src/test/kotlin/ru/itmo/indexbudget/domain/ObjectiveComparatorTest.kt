package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ObjectiveComparatorTest {

    private val comparator = ObjectiveComparatorImpl()

    @Test
    fun `robust orders by worst ratio first even when mean is worse`() {
        val worseWorst = config(ids = listOf("idx_a"), worstRatio = 0.9, meanRatio = 0.5)
        val betterWorst = config(ids = listOf("idx_b"), worstRatio = 0.8, meanRatio = 0.7)

        assertTrue(comparator.compare(worseWorst, betterWorst, Objective.ROBUST) > 0)
        assertTrue(comparator.compare(betterWorst, worseWorst, Objective.ROBUST) < 0)
    }

    @Test
    fun `mean orders by mean ratio first even when worst is worse`() {
        val worseMean = config(ids = listOf("idx_a"), worstRatio = 0.5, meanRatio = 0.9)
        val betterMean = config(ids = listOf("idx_b"), worstRatio = 0.7, meanRatio = 0.8)

        assertTrue(comparator.compare(worseMean, betterMean, Objective.MEAN) > 0)
        assertTrue(comparator.compare(betterMean, worseMean, Objective.MEAN) < 0)
    }

    @Test
    fun `robust breaks ratio ties by mean ratio`() {
        val better = config(ids = listOf("idx_a"), worstRatio = 0.8, meanRatio = 0.6)
        val worse = config(ids = listOf("idx_b"), worstRatio = 0.8, meanRatio = 0.9)

        assertTrue(comparator.compare(better, worse, Objective.ROBUST) < 0)
        assertTrue(comparator.compare(better, worse, Objective.MEAN) < 0)
    }

    @Test
    fun `mean breaks ratio ties by worst ratio`() {
        val better = config(ids = listOf("idx_a"), worstRatio = 0.7, meanRatio = 0.8)
        val worse = config(ids = listOf("idx_b"), worstRatio = 0.9, meanRatio = 0.8)

        assertTrue(comparator.compare(better, worse, Objective.MEAN) < 0)
    }

    @Test
    fun `differences beyond nine decimal places are rounded away`() {
        val lower = config(ids = listOf("idx_a"), worstRatio = 1.0000000004, meanRatio = 0.5)
        val higher = config(ids = listOf("idx_a"), worstRatio = 1.0000000005, meanRatio = 0.5)

        assertEquals(0, comparator.compare(lower, higher, Objective.ROBUST))
        assertEquals(0, comparator.compare(higher, lower, Objective.ROBUST))
    }

    @Test
    fun `half even rounds an odd ninth digit up`() {
        val roundsUp = config(ids = listOf("idx_a"), worstRatio = 0.1234567895, meanRatio = 0.5)
        val alreadyUp = config(ids = listOf("idx_a"), worstRatio = 0.123456790, meanRatio = 0.5)
        val justBelow = config(ids = listOf("idx_a"), worstRatio = 0.1234567894, meanRatio = 0.5)

        assertEquals(0, comparator.compare(roundsUp, alreadyUp, Objective.ROBUST))
        assertTrue(comparator.compare(justBelow, alreadyUp, Objective.ROBUST) < 0)
    }

    @Test
    fun `half even keeps an even ninth digit down`() {
        val roundsDown = config(ids = listOf("idx_a"), worstRatio = 0.1234567885, meanRatio = 0.5)
        val alreadyDown = config(ids = listOf("idx_a"), worstRatio = 0.123456788, meanRatio = 0.5)

        assertEquals(0, comparator.compare(roundsDown, alreadyDown, Objective.ROBUST))
    }

    @Test
    fun `equal ratios fall through to budgeted bytes`() {
        val bigger = config(ids = listOf("idx_a"), worstRatio = 0.8, meanRatio = 0.6, budgetedBytes = 1_000)
        val smaller = config(ids = listOf("idx_a"), worstRatio = 0.8, meanRatio = 0.6, budgetedBytes = 900)

        assertTrue(comparator.compare(bigger, smaller, Objective.ROBUST) > 0)
        assertTrue(comparator.compare(smaller, bigger, Objective.MEAN) < 0)
    }

    @Test
    fun `estimated bytes are not part of the key`() {
        val smallEstimate = config(
            ids = listOf("idx_a"),
            worstRatio = 0.8,
            meanRatio = 0.6,
            budgetedBytes = 1_000,
            estimatedBytes = 500,
        )
        val hugeEstimate = config(
            ids = listOf("idx_a"),
            worstRatio = 0.8,
            meanRatio = 0.6,
            budgetedBytes = 1_000,
            estimatedBytes = 9_999,
        )

        assertEquals(0, comparator.compare(smallEstimate, hugeEstimate, Objective.ROBUST))
    }

    @Test
    fun `write units participate in the key when the write model is known`() {
        val cheaper = config(
            ids = listOf("idx_a"),
            worstRatio = 0.8,
            meanRatio = 0.6,
            budgetedBytes = 1_000,
            writeUnitsPerHour = 10.0,
        )
        val costlier = config(
            ids = listOf("idx_a"),
            worstRatio = 0.8,
            meanRatio = 0.6,
            budgetedBytes = 1_000,
            writeUnitsPerHour = 20.0,
        )

        assertTrue(comparator.compare(cheaper, costlier, Objective.ROBUST) < 0)
        assertTrue(comparator.compare(costlier, cheaper, Objective.MEAN) > 0)
    }

    @Test
    fun `write units are skipped when the write model is absent`() {
        val first = config(ids = listOf("idx_a"), worstRatio = 0.8, meanRatio = 0.6, writeUnitsPerHour = null)
        val second = config(ids = listOf("idx_a"), worstRatio = 0.8, meanRatio = 0.6, writeUnitsPerHour = null)

        assertEquals(0, comparator.compare(first, second, Objective.ROBUST))
    }

    @Test
    fun `mixed write unit presence is rejected`() {
        val unknown = config(ids = listOf("idx_a"), worstRatio = 0.8, meanRatio = 0.6, writeUnitsPerHour = null)
        val known = config(ids = listOf("idx_a"), worstRatio = 0.8, meanRatio = 0.6, writeUnitsPerHour = 5.0)

        assertThrows(IllegalArgumentException::class.java) {
            comparator.compare(unknown, known, Objective.ROBUST)
        }
    }

    @Test
    fun `sorted ids are the final tie breaker`() {
        val sharedPrefix = config(ids = listOf("idx_a", "idx_c"), worstRatio = 0.8, meanRatio = 0.6)
        val laterFirstId = config(ids = listOf("idx_b"), worstRatio = 0.8, meanRatio = 0.6)
        val longerWithSamePrefix = config(ids = listOf("idx_a", "idx_c", "idx_z"), worstRatio = 0.8, meanRatio = 0.6)
        val sameIds = config(ids = listOf("idx_a", "idx_c"), worstRatio = 0.8, meanRatio = 0.6)

        assertTrue(comparator.compare(sharedPrefix, laterFirstId, Objective.ROBUST) < 0)
        assertTrue(comparator.compare(sharedPrefix, longerWithSamePrefix, Objective.ROBUST) < 0)
        assertEquals(0, comparator.compare(sharedPrefix, sameIds, Objective.ROBUST))
    }

    @Test
    fun `null ratios are rejected instead of compared as zero`() {
        val zeroBaseline = metrics(worstRatio = null, meanRatio = null)
        val regular = metrics(worstRatio = 0.8, meanRatio = 0.6)

        assertThrows(IllegalArgumentException::class.java) {
            comparator.compare(config(zeroBaseline, listOf("idx_a")), config(regular, listOf("idx_a")), Objective.ROBUST)
        }
        assertThrows(IllegalArgumentException::class.java) {
            comparator.improvesPrimaryMetrics(zeroBaseline, regular, Objective.MEAN)
        }
    }

    @Test
    fun `gate accepts a strict improvement of the first component`() {
        val proposed = metrics(worstRatio = 0.8, meanRatio = 0.9)
        val current = metrics(worstRatio = 0.9, meanRatio = 0.5)

        assertTrue(comparator.improvesPrimaryMetrics(proposed, current, Objective.ROBUST))
        assertFalse(comparator.improvesPrimaryMetrics(proposed, current, Objective.MEAN))
    }

    @Test
    fun `gate accepts a plateau on the first component with a better second component`() {
        val proposed = metrics(worstRatio = 0.9, meanRatio = 0.4)
        val current = metrics(worstRatio = 0.9, meanRatio = 0.5)

        assertTrue(comparator.improvesPrimaryMetrics(proposed, current, Objective.ROBUST))
    }

    @Test
    fun `gate rejects a worse first component despite a better second component`() {
        val proposed = metrics(worstRatio = 0.95, meanRatio = 0.1)
        val current = metrics(worstRatio = 0.9, meanRatio = 0.5)

        assertFalse(comparator.improvesPrimaryMetrics(proposed, current, Objective.ROBUST))
    }

    @Test
    fun `gate rejects equal rounded components`() {
        val proposed = metrics(worstRatio = 0.9, meanRatio = 0.5)
        val current = metrics(worstRatio = 0.9, meanRatio = 0.5)
        val roundedEqual = metrics(worstRatio = 0.9000000004, meanRatio = 0.5)

        assertFalse(comparator.improvesPrimaryMetrics(proposed, current, Objective.ROBUST))
        assertFalse(comparator.improvesPrimaryMetrics(roundedEqual, current, Objective.ROBUST))
    }

    @Test
    fun `gate on mean objective swaps component roles`() {
        val betterMean = metrics(worstRatio = 0.95, meanRatio = 0.8)
        val betterWorst = metrics(worstRatio = 0.7, meanRatio = 0.85)
        val current = metrics(worstRatio = 0.9, meanRatio = 0.9)

        assertTrue(comparator.improvesPrimaryMetrics(betterMean, current, Objective.MEAN))
        assertFalse(comparator.improvesPrimaryMetrics(betterMean, current, Objective.ROBUST))
        assertTrue(comparator.improvesPrimaryMetrics(betterWorst, current, Objective.MEAN))
    }

    @Test
    fun `smaller resources alone do not justify a greedy step`() {
        val proposed = metrics(worstRatio = 0.9, meanRatio = 0.5, budgetedBytes = 100, writeUnitsPerHour = 1.0)
        val current = metrics(worstRatio = 0.9, meanRatio = 0.5, budgetedBytes = 1_000, writeUnitsPerHour = 50.0)

        assertFalse(comparator.improvesPrimaryMetrics(proposed, current, Objective.ROBUST))
        assertFalse(comparator.improvesPrimaryMetrics(proposed, current, Objective.MEAN))
    }

    @Test
    fun `full comparison stays consistent with the greedy gate`() {
        val current = metrics(worstRatio = 0.9, meanRatio = 0.5)
        val improvesBoth = metrics(worstRatio = 0.8, meanRatio = 0.4)
        val plateau = metrics(worstRatio = 0.9, meanRatio = 0.4)
        val rejected = metrics(worstRatio = 0.95, meanRatio = 0.1)
        val improvements = listOf(improvesBoth, plateau, rejected)

        for (candidate in improvements) {
            for (objective in Objective.entries) {
                val configuration = config(candidate, listOf("idx_same"))
                val incumbent = config(current, listOf("idx_same"))
                val gate = comparator.improvesPrimaryMetrics(candidate, current, objective)
                val order = comparator.compare(configuration, incumbent, objective)
                assertEquals(gate, order < 0)
            }
        }
    }

    @Test
    fun `order is transitive when rounding collapses ratio differences`() {
        val first = config(ids = listOf("idx_a"), worstRatio = 1.0000000004, meanRatio = 0.7)
        val second = config(ids = listOf("idx_a"), worstRatio = 1.0000000003, meanRatio = 0.6)
        val third = config(ids = listOf("idx_a"), worstRatio = 1.0000000002, meanRatio = 0.5)

        assertTrue(comparator.compare(third, second, Objective.ROBUST) < 0)
        assertTrue(comparator.compare(second, first, Objective.ROBUST) < 0)
        assertTrue(comparator.compare(third, first, Objective.ROBUST) < 0)
        assertEquals(
            listOf(third, second, first),
            listOf(first, second, third).sortedWith { left, right -> comparator.compare(left, right, Objective.ROBUST) },
        )
    }

    @Test
    fun `raw metrics are not mutated by comparison`() {
        val configuration = config(ids = listOf("idx_a"), worstRatio = 1.0000000004, meanRatio = 0.5)

        comparator.compare(
            configuration,
            config(ids = listOf("idx_b"), worstRatio = 0.8, meanRatio = 0.4),
            Objective.ROBUST,
        )

        assertEquals(1.0000000004, configuration.metrics.worstRatio)
        assertEquals(0.5, configuration.metrics.meanRatio)
    }

    private fun config(
        ids: List<String>,
        worstRatio: Double?,
        meanRatio: Double?,
        budgetedBytes: Long = 1_000,
        estimatedBytes: Long = 1_000,
        writeUnitsPerHour: Double? = null,
    ): EvaluatedConfiguration = config(
        metrics(worstRatio, meanRatio, budgetedBytes, estimatedBytes, writeUnitsPerHour, ids.size),
        ids,
    )

    private fun config(metrics: SetMetrics, ids: List<String>): EvaluatedConfiguration =
        EvaluatedConfiguration(
            evaluation = EvaluationResult(
                candidateIds = ids,
                costByQueryId = mapOf("q1" to 10.0),
                plansByQueryId = mapOf("q1" to ExplainPlan("{}")),
            ),
            metrics = metrics,
        )

    private fun metrics(
        worstRatio: Double?,
        meanRatio: Double?,
        budgetedBytes: Long = 1_000,
        estimatedBytes: Long = 1_000,
        writeUnitsPerHour: Double? = null,
        indexCount: Int = 1,
    ): SetMetrics = SetMetrics(
        profiles = emptyList(),
        baselineMeanCost = 100.0,
        meanCost = 100.0 * (meanRatio ?: 0.0),
        meanRatio = meanRatio,
        worstRatio = worstRatio,
        budgetUsage = BudgetUsage(
            estimatedBytes = estimatedBytes,
            budgetedBytes = budgetedBytes,
            writeUnitsPerHour = writeUnitsPerHour,
            indexCount = indexCount,
        ),
    )
}
