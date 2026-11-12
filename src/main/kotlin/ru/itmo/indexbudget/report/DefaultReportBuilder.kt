package ru.itmo.indexbudget.report

import ru.itmo.indexbudget.candidates.PostgresDdlRenderer
import ru.itmo.indexbudget.domain.*

/** Builds the shared presentation model from saved results, without any database capability. */
class DefaultReportBuilder(
    private val metrics: MetricsCalculator = MetricsCalculatorImpl(),
    private val environment: EnvironmentInfo = EnvironmentInfo(
        applicationVersion = "0.1.0-SNAPSHOT",
        javaVersion = System.getProperty("java.version"),
        kotlinVersion = KotlinVersion.CURRENT.toString(),
    ),
) : ReportBuilder {
    override fun build(
        input: WorkloadInput,
        candidates: CandidateBatch,
        result: SearchResult,
        context: RunContext,
    ): AnalysisReport {
        require(input.objective == result.selectedObjective) { "Report objective must match the input" }
        val queryIds = input.queries.map { it.id }.toSet()
        result.baseline?.requireQueryCoverage(queryIds)
        result.bestRobust?.evaluation?.requireQueryCoverage(queryIds)
        result.bestMean?.evaluation?.requireQueryCoverage(queryIds)
        require(candidates.candidates.map { it.id }.distinct().size == candidates.candidates.size) {
            "Report candidates must have unique IDs"
        }
        val retainedIds = listOfNotNull(result.bestRobust, result.bestMean)
            .flatMap { it.evaluation.candidateIds }.toSet()
        require(candidates.candidates.map { it.id }.containsAll(retainedIds)) {
            "Every retained index must have a report definition"
        }
        val ddlRenderer = PostgresDdlRenderer()
        val reportCandidates = candidates.copy(candidates = candidates.candidates.map { candidate ->
            // Pre-shortlist exclusions can lack resolved defaults, and must not acquire invented DDL.
            val canRender = candidate.definition.keys.all { it.operatorClass != null }
            require(canRender || candidate.id !in retainedIds) { "Retained index defaults must be resolved" }
            candidate.copy(ddl = if (canRender) ddlRenderer.render(candidate.definition) else null)
        })
        val baseline = result.baseline
        // A failed numeric analysis must remain exportable; do not repeat the calculation that failed.
        val baselineMetrics = if (baseline != null && result.status != RunStatus.FAILED) {
            metrics.calculate(input, baseline, baseline, metrics.assessBudget(emptyList(), input).usage)
        } else null
        val normalizedWeights = try {
            ProfileWeights.normalize(input.profiles)
        } catch (error: AnalysisException) {
            if (result.status != RunStatus.FAILED || error.error.code != ErrorCode.NUMERIC_ERROR) throw error
            emptyMap()
        }
        val selectedCosts = result.selected?.evaluation?.costByQueryId
        val selectedEvaluation = result.selected?.evaluation
        val costGuards = if (baseline != null && selectedEvaluation != null) {
            CostGuards.evaluate(input.constraints.queryCostGuards, baseline, selectedEvaluation)
        } else emptyList()
        return AnalysisReport(
            runId = context.runId,
            inputHash = context.inputHash,
            durationMillis = context.elapsedTime().toMillis(),
            environment = environment.copy(
                postgresVersion = context.databaseVersions.postgresVersion ?: environment.postgresVersion,
                hypopgVersion = context.databaseVersions.hypopgVersion ?: environment.hypopgVersion,
            ),
            result = result,
            input = input,
            analysisMode = context.analysisMode,
            candidateRanking = input.candidateRanking,
            candidates = reportCandidates,
            priceOfRobustness = PriceOfRobustness.calculate(result),
            baselineMetrics = baselineMetrics,
            normalizedProfileWeights = normalizedWeights,
            queryComparisons = input.queries.map { query ->
                val before = baseline?.costByQueryId?.get(query.id)
                val after = selectedCosts?.get(query.id)
                QueryCostComparison(query.id, before, after,
                    if (before != null && after != null) after > before else null)
            },
            incumbentsCoincide = if (result.status == RunStatus.FAILED) null else
                result.bestRobust?.evaluation?.candidateIds == result.bestMean?.evaluation?.candidateIds,
            costGuards = costGuards,
            statementKinds = context.statementKinds.filterKeys { it in queryIds },
        )
    }
}
