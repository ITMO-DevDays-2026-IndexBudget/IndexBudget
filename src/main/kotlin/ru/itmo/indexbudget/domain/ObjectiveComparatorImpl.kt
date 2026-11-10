package ru.itmo.indexbudget.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Single deterministic ordering shared by search and reports. Ratios are rounded to 9 decimal
 * places HALF_EVEN before comparison, raw metrics are never mutated, and no epsilon comparator
 * is used. Zero-baseline runs have no ratios and never enter the search: null ratios are
 * rejected here instead of being compared as fabricated zero ratios.
 */
class ObjectiveComparatorImpl : ObjectiveComparator {

    override fun compare(left: EvaluatedConfiguration, right: EvaluatedConfiguration, objective: Objective): Int {
        val primary = primaryOrder(left.metrics, right.metrics, objective)
        if (primary != 0) return primary

        val bytes = left.metrics.budgetUsage.budgetedBytes.compareTo(right.metrics.budgetUsage.budgetedBytes)
        if (bytes != 0) return bytes

        val writes = compareWriteUnits(left.metrics, right.metrics)
        if (writes != 0) return writes

        return compareIds(left.evaluation.candidateIds, right.evaluation.candidateIds)
    }

    override fun improvesPrimaryMetrics(proposed: SetMetrics, current: SetMetrics, objective: Objective): Boolean =
        primaryOrder(proposed, current, objective) < 0

    private fun primaryOrder(left: SetMetrics, right: SetMetrics, objective: Objective): Int {
        val leftKey = primaryKey(left, objective)
        val rightKey = primaryKey(right, objective)
        val first = leftKey.first.compareTo(rightKey.first)
        if (first != 0) return first
        return leftKey.second.compareTo(rightKey.second)
    }

    private fun primaryKey(metrics: SetMetrics, objective: Objective): Pair<BigDecimal, BigDecimal> {
        val worst = rounded(metrics.worstRatio)
        val mean = rounded(metrics.meanRatio)
        return if (objective == Objective.ROBUST) worst to mean else mean to worst
    }

    private fun rounded(ratio: Double?): BigDecimal {
        val value = requireNotNull(ratio) {
            "Ratios must be known: zero-baseline runs do not enter the search and null is not a fake zero"
        }
        require(value.isFinite()) { "Ratios must be finite" }
        return BigDecimal.valueOf(value).setScale(ROUND_DIGITS, RoundingMode.HALF_EVEN)
    }

    private fun compareWriteUnits(left: SetMetrics, right: SetMetrics): Int {
        val leftUnits = left.budgetUsage.writeUnitsPerHour
        val rightUnits = right.budgetUsage.writeUnitsPerHour
        if (leftUnits == null && rightUnits == null) return 0
        require(leftUnits != null && rightUnits != null) {
            "Write units must be known for every configuration of one run or for none"
        }
        return leftUnits.compareTo(rightUnits)
    }

    private fun compareIds(left: List<String>, right: List<String>): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val order = left[index].compareTo(right[index])
            if (order != 0) return order
        }
        return left.size.compareTo(right.size)
    }

    private companion object {
        const val ROUND_DIGITS = 9
    }
}
