package ru.itmo.indexbudget.domain

/** A missing relative value must explain whether data, a positive denominator or numeric range is missing. */
enum class RelativeValueStatus { AVAILABLE, ZERO_BASELINE, UNAVAILABLE, OUT_OF_RANGE }

data class CostEffect(
    val baselineCost: Double?,
    val cost: Double?,
    val gain: Double?,
    val ratio: Double?,
    val gainPercent: Double?,
    val relativeStatus: RelativeValueStatus,
) {
    companion object {
        /** Presentation of saved costs only. Positive gain means lower planner cost, never elapsed time. */
        fun calculate(baseline: Double?, cost: Double?): CostEffect {
            if (baseline == null || cost == null) {
                return CostEffect(baseline, cost, null, null, null, RelativeValueStatus.UNAVAILABLE)
            }
            require(baseline.isFinite() && baseline >= 0 && cost.isFinite() && cost >= 0)
            val gain = baseline - cost
            if (baseline == 0.0) return CostEffect(baseline, cost, gain, null, null, RelativeValueStatus.ZERO_BASELINE)
            val ratio = (cost / baseline).takeIf { it.isFinite() && (it != 0.0 || cost == 0.0) }
            val percent = (100.0 * gain / baseline).takeIf { it.isFinite() }
            return CostEffect(baseline, cost, gain, ratio, percent,
                if (ratio == null || percent == null) RelativeValueStatus.OUT_OF_RANGE else RelativeValueStatus.AVAILABLE)
        }

        /** Profile values are already computed by MetricsCalculator: retain their exact saved numbers. */
        fun saved(metrics: ProfileMetrics?) = if (metrics == null) calculate(null, null) else CostEffect(
            metrics.baselineCost, metrics.cost, metrics.gain, metrics.ratio, metrics.gainPercent,
            if (metrics.baselineCost == 0.0) RelativeValueStatus.ZERO_BASELINE else RelativeValueStatus.AVAILABLE,
        )
    }
}
