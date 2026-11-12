package ru.itmo.indexbudget.domain

/**
 * Single implementation of profile metrics and budgets; callers must not duplicate these formulas.
 */
class MetricsCalculatorImpl : MetricsCalculator {

    override fun assessBudget(candidates: List<IndexCandidate>, workload: WorkloadInput): BudgetAssessment {
        val knownBudget = assessKnownBudget(candidates, workload)

        var estimatedBytes = 0L
        var budgetedBytes = 0L
        for (candidate in candidates.sortedBy { it.id }) {
            require(candidate.definition.keys.size in 1..2) { "Candidates must have one or two key columns" }
            val bytes = candidate.estimatedBytes ?: throw AnalysisException(
                AnalysisError(ErrorCode.HYPOTHETICAL_INDEX_ERROR, "Candidate size estimate is unavailable",
                    details = mapOf("candidate_id" to candidate.id)),
            )
            if (bytes < 0) throw numericError("Estimated index size must be nonnegative")
            // ceil(6*n/5) = n + ceil(n/5), without floating-point rounding or overflowing 6*n.
            val reserve = bytes / 5 + if (bytes % 5 == 0L) 0 else 1
            val budgeted = addBytes(bytes, reserve)
            estimatedBytes = addBytes(estimatedBytes, bytes)
            budgetedBytes = addBytes(budgetedBytes, budgeted)
        }
        val constraints = workload.constraints
        val violations = buildSet {
            if (budgetedBytes > constraints.storageBudgetBytes) add(BudgetViolation.STORAGE_BUDGET)
            addAll(knownBudget.violations)
        }
        return BudgetAssessment(
            BudgetUsage(estimatedBytes, budgetedBytes, knownBudget.writeUnitsPerHour, candidates.size), violations,
        )
    }

    private fun addBytes(left: Long, right: Long): Long {
        if (left > Long.MAX_VALUE - right) throw numericError("Index size exceeds the supported numeric range")
        return left + right
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

        val profiles = profileMetrics(workload, baseline, evaluation, queryIds)
        val (meanCost, baselineMeanCost) = weightedCosts(profiles)
        return SetMetrics(
            profiles = profiles,
            baselineMeanCost = baselineMeanCost,
            meanCost = meanCost,
            meanRatio = meanRatio(meanCost, baselineMeanCost, profiles.any { it.baselineCost > 0.0 }),
            worstRatio = profiles.mapNotNull { it.ratio }.maxOrNull(),
            budgetUsage = usage,
        )
    }

    private fun profileMetrics(
        workload: WorkloadInput,
        baseline: EvaluationResult,
        evaluation: EvaluationResult,
        queryIds: List<String>,
    ): List<ProfileMetrics> {
        val normalizedWeights = ProfileWeights.normalize(workload.profiles)
        return workload.profiles.sortedBy { it.id }.map { profile ->
            val baselineCost = profileCost(profile, baseline.costByQueryId, queryIds)
            val cost = profileCost(profile, evaluation.costByQueryId, queryIds)
            val effect = CostEffect.calculate(baselineCost, cost)
            if (effect.relativeStatus == RelativeValueStatus.OUT_OF_RANGE) {
                throw numericError("Profile ratio or gain percent is outside the supported numeric range", profile.id)
            }
            ProfileMetrics(
                profileId = profile.id,
                normalizedWeight = normalizedWeights.getValue(profile.id),
                baselineCost = baselineCost,
                cost = cost,
                gain = checkNotNull(effect.gain),
                ratio = effect.ratio,
                gainPercent = effect.gainPercent,
            )
        }
    }

    private data class WeightedCosts(val mean: Double, val baseline: Double)

    private fun weightedCosts(profiles: List<ProfileMetrics>): WeightedCosts {
        var meanCost = 0.0
        var baselineMeanCost = 0.0
        for (metric in profiles) {
            meanCost += metric.normalizedWeight * metric.cost
            baselineMeanCost += metric.normalizedWeight * metric.baselineCost
        }
        if (!meanCost.isFinite() || !baselineMeanCost.isFinite()) throw numericError("Mean cost is not finite")

        return WeightedCosts(meanCost, baselineMeanCost)
    }

    private fun meanRatio(meanCost: Double, baselineMeanCost: Double, hasPositiveBaseline: Boolean): Double? =
        when {
            baselineMeanCost > 0.0 -> {
                val ratio = meanCost / baselineMeanCost
                if (!ratio.isFinite()) throw numericError("Mean ratio is not finite")
                if (meanCost > 0.0 && ratio == 0.0) throw numericError("Positive mean ratio underflows to zero")
                ratio
            }
            hasPositiveBaseline -> throw numericError("B_mean is zero while a positive baseline exists")
            else -> null
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
