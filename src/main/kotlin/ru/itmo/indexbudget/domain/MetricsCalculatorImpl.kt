package ru.itmo.indexbudget.domain

import java.math.BigDecimal

/**
 * Single implementation of profile metrics and budgets; callers must not duplicate these formulas.
 */
class MetricsCalculatorImpl : MetricsCalculator {

    override fun assessBudget(candidates: List<IndexCandidate>, workload: WorkloadInput): BudgetAssessment {
        require(candidates.map { it.id }.distinct().size == candidates.size) { "Candidate IDs must be unique" }
        val rates = workload.tableWriteRates
        if (rates == null && workload.constraints.writeBudgetUnitsPerHour != null) {
            throw invalidWriteProfile("An enabled write budget requires a complete write profile")
        }
        if (rates != null) {
            if (rates.isEmpty()) throw invalidWriteProfile("A supplied write profile must not be empty")
            for (rate in rates.values) {
                if (listOf(rate.insertsPerHour, rate.updatesPerHour, rate.deletesPerHour)
                        .any { !it.isFinite() || it < 0.0 }) {
                    throw invalidWriteProfile("Write rates must be finite and nonnegative")
                }
            }
        }

        var estimatedBytes = 0L
        var budgetedBytes = 0L
        var exactWriteUnits: BigDecimal? = if (rates == null) null else BigDecimal.ZERO
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
            if (rates != null) {
                val table = candidate.definition.table
                // Same physical, case-sensitive schema.name keys as ResolvedWorkloadValidator (#14).
                val rate = rates["${table.schema}.${table.name}"]
                    ?: throw invalidWriteProfile("Write profile must cover every candidate table")
                // The constructor preserves the exact input Double, unlike a decimal-string conversion.
                // Keep both accumulation stages exact, including multiplication and the budget comparison.
                val rowChanges = BigDecimal(rate.insertsPerHour) + BigDecimal(rate.updatesPerHour) +
                    BigDecimal(rate.deletesPerHour)
                val units = rowChanges * BigDecimal(candidate.definition.keys.size)
                val total = checkNotNull(exactWriteUnits) + units
                // All terms are nonnegative: bounding the total also bounds every intermediate value.
                if (total > BigDecimal(Double.MAX_VALUE)) {
                    throw numericError("Write units exceed the supported numeric range")
                }
                exactWriteUnits = total
            }
        }
        val constraints = workload.constraints
        val violations = buildSet {
            if (budgetedBytes > constraints.storageBudgetBytes) add(BudgetViolation.STORAGE_BUDGET)
            if (constraints.writeBudgetUnitsPerHour != null &&
                checkNotNull(exactWriteUnits) > BigDecimal(constraints.writeBudgetUnitsPerHour)) {
                add(BudgetViolation.WRITE_BUDGET)
            }
            if (candidates.size > constraints.maxIndexes) add(BudgetViolation.MAX_INDEXES)
        }
        return BudgetAssessment(
            BudgetUsage(estimatedBytes, budgetedBytes, exactWriteUnits?.toDouble(), candidates.size), violations,
        )
    }

    private fun addBytes(left: Long, right: Long): Long {
        if (left > Long.MAX_VALUE - right) throw numericError("Index size exceeds the supported numeric range")
        return left + right
    }

    private fun invalidWriteProfile(message: String) = AnalysisException(
        AnalysisError(ErrorCode.INVALID_WRITE_PROFILE, message, details = mapOf("field" to "table_write_rates")),
    )

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
