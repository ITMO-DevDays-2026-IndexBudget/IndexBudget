package ru.itmo.indexbudget.domain

/**
 * Single implementation of profile metrics and budgets; callers must not duplicate these formulas.
 * [calculate] is implemented here; [assessBudget] lands in the budget subtask and reports
 * NOT_IMPLEMENTED until then.
 */
class MetricsCalculatorImpl : MetricsCalculator {

    override fun assessBudget(candidates: List<IndexCandidate>, workload: WorkloadInput): BudgetAssessment {
        throw AnalysisException(
            AnalysisError(ErrorCode.NOT_IMPLEMENTED, "assessBudget is implemented in the budget subtask"),
        )
    }

    override fun calculate(
        workload: WorkloadInput,
        baseline: EvaluationResult,
        evaluation: EvaluationResult,
        usage: BudgetUsage,
    ): SetMetrics {
        val queryIds = workload.queries.map { it.id }.sorted()
        val expectedQueryIds = queryIds.toSet()
        baseline.requireQueryCoverage(expectedQueryIds)
        evaluation.requireQueryCoverage(expectedQueryIds)

        val normalizedWeights = ProfileWeights.normalize(workload.profiles)
        val profiles = workload.profiles.sortedBy { it.id }.map { profile ->
            val baselineCost = profileCost(profile, baseline.costByQueryId, queryIds)
            val cost = profileCost(profile, evaluation.costByQueryId, queryIds)
            val gain = baselineCost - cost
            val ratio = if (baselineCost > 0.0) cost / baselineCost else null
            val gainPercent = if (baselineCost > 0.0) 100.0 * gain / baselineCost else null
            if (ratio != null && !ratio.isFinite()) {
                throw numericError("Profile ratio is not finite", profile.id)
            }
            if (gainPercent != null && !gainPercent.isFinite()) {
                throw numericError("Profile gain percent is not finite", profile.id)
            }
            ProfileMetrics(
                profileId = profile.id,
                normalizedWeight = normalizedWeights.getValue(profile.id),
                baselineCost = baselineCost,
                cost = cost,
                gain = gain,
                ratio = ratio,
                gainPercent = gainPercent,
            )
        }

        var meanCost = 0.0
        var baselineMeanCost = 0.0
        for (metric in profiles) {
            meanCost += metric.normalizedWeight * metric.cost
            baselineMeanCost += metric.normalizedWeight * metric.baselineCost
        }
        if (!meanCost.isFinite() || !baselineMeanCost.isFinite()) throw numericError("Mean cost is not finite")

        val hasPositiveBaseline = profiles.any { it.baselineCost > 0.0 }
        val meanRatio = when {
            baselineMeanCost > 0.0 -> {
                val ratio = meanCost / baselineMeanCost
                if (!ratio.isFinite()) throw numericError("Mean ratio is not finite")
                ratio
            }
            hasPositiveBaseline -> throw numericError("B_mean is zero while a positive baseline exists")
            else -> null
        }
        val worstRatio = profiles
            .mapNotNull { it.ratio }
            .maxOrNull()

        return SetMetrics(
            profiles = profiles,
            baselineMeanCost = baselineMeanCost,
            meanCost = meanCost,
            meanRatio = meanRatio,
            worstRatio = worstRatio,
            budgetUsage = usage,
        )
    }

    private fun profileCost(profile: Profile, costs: Map<String, Double>, queryIds: List<String>): Double {
        var total = 0.0
        for (queryId in queryIds) {
            val term = profile.callsPerHour.getValue(queryId) * costs.getValue(queryId)
            if (!term.isFinite()) throw numericError("Profile '${profile.id}' cost term is not finite", profile.id)
            total += term
        }
        if (!total.isFinite()) throw numericError("Profile '${profile.id}' cost is not finite", profile.id)
        return total
    }

    private fun numericError(message: String, profileId: String? = null): AnalysisException =
        AnalysisException(AnalysisError(ErrorCode.NUMERIC_ERROR, message, profileId = profileId))
}
