package ru.itmo.indexbudget.domain

/** Post-EXPLAIN admissibility. Rejection preserves raw metrics and does not fail the analysis. */
sealed interface EvaluationAssessment {
    val metrics: SetMetrics

    /** A false [searchRequired] means every profile baseline is zero; retain the empty baseline. */
    data class Accepted(
        override val metrics: SetMetrics,
        val searchRequired: Boolean,
    ) : EvaluationAssessment

    /** The search records these profile diagnostics and continues without updating either incumbent. */
    data class Rejected(
        override val metrics: SetMetrics,
        val violations: List<AnalysisError>,
    ) : EvaluationAssessment

    companion object {
        internal fun assess(metrics: SetMetrics): EvaluationAssessment {
            require(metrics.profiles.isNotEmpty()) { "An assessment must contain workload profiles" }
            require(metrics.profiles.all {
                it.baselineCost.isFinite() && it.baselineCost >= 0.0 && it.cost.isFinite() && it.cost >= 0.0
            }) { "Profile costs must be finite and nonnegative" }

            val violations = metrics.profiles
                .filter { it.baselineCost == 0.0 && it.cost > 0.0 }
                .sortedBy { it.profileId }
                .map { profile ->
                    AnalysisError(
                        ErrorCode.ZERO_BASELINE_REGRESSION,
                        "A zero-baseline profile must retain zero cost.",
                        profileId = profile.profileId,
                    )
                }
            if (violations.isNotEmpty()) return Rejected(metrics, violations)

            val searchRequired = metrics.profiles.any { it.baselineCost > 0.0 }
            require(searchRequired || (metrics.meanRatio == null && metrics.worstRatio == null)) {
                "All-zero baselines must retain null mean and worst ratios"
            }
            return Accepted(metrics, searchRequired)
        }
    }
}

/** Guards callers bypassing assessEvaluation; a violation here is an implementation error. */
internal fun SetMetrics.requireAdmissible(): EvaluationAssessment.Accepted {
    val assessment = EvaluationAssessment.assess(this)
    require(assessment is EvaluationAssessment.Accepted) {
        "Zero-baseline regressions must be rejected before comparing or retaining a configuration"
    }
    return assessment
}
