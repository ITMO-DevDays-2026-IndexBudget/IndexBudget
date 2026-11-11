package ru.itmo.indexbudget.domain

data class CostComparisonSeries(
    val id: String,
    val baseline: CostEffect,
    val selected: CostEffect,
    val bestRobust: CostEffect,
    val bestMean: CostEffect,
)

data class RecommendationSummary(
    val status: RunStatus,
    /** null = no recommendation; empty = a valid baseline-only recommendation. */
    val selectedIndexIds: List<String>?,
    val meanEffect: CostEffect,
    val worstRatio: Double?,
    val budgetUsage: BudgetUsage?,
    val budgetLimits: Constraints?,
    val regressedQueryCount: Int?,
    val comparisonScope: ComparisonScope,
    val incumbentsCoincide: Boolean?,
    val priceOfRobustness: Double?,
    val stopReasons: List<StopReason>,
    val errors: List<AnalysisError>,
) {
    val writeLimitEnabled: Boolean? get() = budgetLimits?.let { it.writeBudgetUnitsPerHour != null }
}

/** No evaluator/session dependency: all presentation data comes from one retained result. */
object ReportSummary {
    fun recommendation(report: AnalysisReport): RecommendationSummary {
        val result = report.result
        val selected = result.selected
        val metrics = selected?.metrics
        return RecommendationSummary(
            status = result.status,
            selectedIndexIds = selected?.evaluation?.candidateIds,
            meanEffect = CostEffect.calculate(metrics?.baselineMeanCost, metrics?.meanCost)
                .let { effect -> if (effect.ratio == null) effect else effect.copy(ratio = metrics?.meanRatio) },
            worstRatio = metrics?.worstRatio,
            budgetUsage = metrics?.budgetUsage,
            budgetLimits = report.input?.constraints,
            regressedQueryCount = selected?.let { configuration -> result.baseline?.let { baseline ->
                configuration.evaluation.costByQueryId.count { (id, cost) -> cost > baseline.costByQueryId.getValue(id) }
            } },
            comparisonScope = report.comparisonScope,
            incumbentsCoincide = report.incumbentsCoincide,
            priceOfRobustness = report.priceOfRobustness,
            stopReasons = result.diagnostics.stopReasons,
            errors = (listOfNotNull((result as? SearchResult.Failed)?.error) + result.diagnostics.errors).distinct(),
        )
    }

    fun profiles(report: AnalysisReport): List<CostComparisonSeries> = report.input?.profiles.orEmpty().map { profile ->
        fun effect(metrics: SetMetrics?) = CostEffect.saved(metrics?.profiles?.singleOrNull { it.profileId == profile.id })
        CostComparisonSeries(profile.id, effect(report.baselineMetrics), effect(report.result.selected?.metrics),
            effect(report.result.bestRobust?.metrics), effect(report.result.bestMean?.metrics))
    }

    fun queries(report: AnalysisReport): List<CostComparisonSeries> = report.input?.queries.orEmpty().map { query ->
        val before = report.result.baseline?.costByQueryId?.get(query.id)
        fun effect(evaluation: EvaluationResult?) = CostEffect.calculate(before, evaluation?.costByQueryId?.get(query.id))
        CostComparisonSeries(query.id, effect(report.result.baseline), effect(report.result.selected?.evaluation),
            effect(report.result.bestRobust?.evaluation), effect(report.result.bestMean?.evaluation))
    }
}
