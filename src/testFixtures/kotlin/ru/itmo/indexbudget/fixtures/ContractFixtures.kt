package ru.itmo.indexbudget.fixtures

import ru.itmo.indexbudget.domain.*

/**
 * Shared, deterministic data for developing modules before the PostgreSQL adapter is connected.
 * Costs and plans added here are synthetic contract examples, never benchmark results.
 */
object ContractFixtures {
    val recordsTable = TableRef("public", "records")
    val wideTable = TableRef("public", "wide_records")

    fun workload(
        objective: Objective = Objective.ROBUST,
        withWriteProfile: Boolean = true,
    ): WorkloadInput = WorkloadInput(
        queries = listOf(
            QueryInput(
                "records_by_group",
                "SELECT id, value FROM public.records WHERE group_id = 42 ORDER BY created_at DESC LIMIT 20",
            ),
            QueryInput(
                "records_by_value",
                "SELECT id, value FROM public.records WHERE value = 7 ORDER BY created_at DESC LIMIT 20",
            ),
        ),
        profiles = listOf(
            Profile("normal", 0.9, mapOf("records_by_group" to 3000.0, "records_by_value" to 100.0)),
            Profile("campaign", 0.1, mapOf("records_by_group" to 100.0, "records_by_value" to 3000.0)),
        ),
        constraints = Constraints(
            storageBudgetBytes = 67_108_864L,
            writeBudgetUnitsPerHour = if (withWriteProfile) 10_000.0 else null,
            maxIndexes = 5,
        ),
        objective = objective,
        tableWriteRates = if (withWriteProfile) {
            mapOf("public.records" to TableWriteRates(1000.0, 3000.0, 100.0))
        } else {
            null
        },
    )

    fun catalog(): CatalogSnapshot = CatalogSnapshot(
        tables = listOf(
            CatalogTable(
                ref = recordsTable,
                columns = listOf(
                    CatalogColumn("id", "int8", nullable = false, defaultOperatorClass = "pg_catalog.int8_ops"),
                    CatalogColumn("value", "int4", nullable = false, defaultOperatorClass = "pg_catalog.int4_ops"),
                    CatalogColumn("group_id", "int4", nullable = false, defaultOperatorClass = "pg_catalog.int4_ops"),
                    CatalogColumn("created_at", "timestamp", nullable = false, defaultOperatorClass = "pg_catalog.timestamp_ops"),
                ),
                estimatedRows = 10_000.0,
                statisticsAvailable = true,
                existingIndexes = listOf(
                    ExistingIndex(
                        name = "records_pkey",
                        table = recordsTable,
                        method = "btree",
                        keys = listOf(IndexKey(ColumnRef(recordsTable, "id"), operatorClass = "pg_catalog.int8_ops")),
                        unique = true,
                    ),
                ),
            ),
        ),
    )

    /** Catalog width is not the same thing as the candidate generator's 16-column shortlist. */
    fun wideCatalog(): CatalogSnapshot = CatalogSnapshot(
        tables = listOf(
            CatalogTable(
                ref = wideTable,
                columns = (1..100).map { CatalogColumn("c$it", "int4", defaultOperatorClass = "pg_catalog.int4_ops") },
                estimatedRows = 10_000.0,
                statisticsAvailable = true,
            ),
        ),
    )

    fun wideQuery(): QueryInput = QueryInput(
        "wide_two_features",
        "SELECT * FROM public.wide_records WHERE c1 = 1 ORDER BY c2 LIMIT 20",
    )

    fun resolvedWorkload(
        objective: Objective = Objective.ROBUST,
        withWriteProfile: Boolean = true,
    ): ResolvedWorkload {
        val input = workload(objective, withWriteProfile)
        return ResolvedWorkload(
            input = input,
            queries = input.queries.map { query ->
                val predicateColumn = if (query.id == "records_by_group") "group_id" else "value"
                ResolvedQuery(
                    id = query.id,
                    sql = query.sql,
                    tables = setOf(recordsTable),
                    referencedColumns = setOf("id", "value", predicateColumn, "created_at")
                        .map { ColumnRef(recordsTable, it) }.toSet(),
                    predicates = listOf(
                        ResolvedPredicate(
                            ColumnRef(recordsTable, predicateColumn),
                            ComparisonOperator.EQUAL,
                            PredicateSource.WHERE,
                        ),
                    ),
                    orderBy = listOf(
                        OrderByColumn(ColumnRef(recordsTable, "created_at"), SortDirection.DESC, NullsOrder.FIRST),
                    ),
                    projectedColumns = setOf(ColumnRef(recordsTable, "id"), ColumnRef(recordsTable, "value")),
                )
            },
        )
    }

    fun candidates(withWriteProfile: Boolean = true): List<IndexCandidate> = listOf(
        IndexCandidate(
            id = "records_created_at",
            definition = IndexDefinition(
                recordsTable,
                listOf(IndexKey(ColumnRef(recordsTable, "created_at"), SortDirection.DESC, NullsOrder.FIRST,
                    operatorClass = "pg_catalog.timestamp_ops")),
            ),
            sourceQueryIds = setOf("records_by_group", "records_by_value"),
            generationReasons = setOf(GenerationReason.SINGLE_COLUMN),
            estimatedBytes = 262_144L,
            budgetedBytes = 314_573L,
            writeUnitsPerHour = if (withWriteProfile) 4100.0 else null,
        ),
        IndexCandidate(
            id = "records_group_created_at",
            definition = IndexDefinition(
                recordsTable,
                listOf(
                    IndexKey(ColumnRef(recordsTable, "group_id"), operatorClass = "pg_catalog.int4_ops"),
                    IndexKey(ColumnRef(recordsTable, "created_at"), SortDirection.DESC, NullsOrder.FIRST,
                        operatorClass = "pg_catalog.timestamp_ops"),
                ),
            ),
            sourceQueryIds = setOf("records_by_group"),
            generationReasons = setOf(GenerationReason.EQUALITY_AND_ORDER),
            estimatedBytes = 524_288L,
            budgetedBytes = 629_146L,
            writeUnitsPerHour = if (withWriteProfile) 8200.0 else null,
        ),
    )

    fun unestimatedCandidate(): IndexCandidate = candidates().last().copy(
        estimatedBytes = null,
        budgetedBytes = null,
        writeUnitsPerHour = null,
    )

    fun baselineEvaluation(): EvaluationResult = evaluation(emptyList(), 100.0, 100.0)

    fun robustEvaluation(): EvaluationResult = evaluation(listOf("records_created_at"), 50.0, 50.0)

    fun meanEvaluation(): EvaluationResult = evaluation(listOf("records_group_created_at"), 10.0, 100.0)

    fun baselineConfiguration(withWriteProfile: Boolean = true): EvaluatedConfiguration = EvaluatedConfiguration(
        evaluation = baselineEvaluation(),
        metrics = SetMetrics(
            profiles = listOf(
                ProfileMetrics("normal", 0.9, 310_000.0, 310_000.0, 0.0, 1.0, 0.0),
                ProfileMetrics("campaign", 0.1, 310_000.0, 310_000.0, 0.0, 1.0, 0.0),
            ),
            baselineMeanCost = 310_000.0,
            meanCost = 310_000.0,
            meanRatio = 1.0,
            worstRatio = 1.0,
            budgetUsage = BudgetUsage(0L, 0L, if (withWriteProfile) 0.0 else null, 0),
        ),
    )

    fun robustConfiguration(withWriteProfile: Boolean = true): EvaluatedConfiguration = EvaluatedConfiguration(
        evaluation = robustEvaluation(),
        metrics = SetMetrics(
            profiles = listOf(
                ProfileMetrics("normal", 0.9, 310_000.0, 155_000.0, 155_000.0, 0.5, 50.0),
                ProfileMetrics("campaign", 0.1, 310_000.0, 155_000.0, 155_000.0, 0.5, 50.0),
            ),
            baselineMeanCost = 310_000.0,
            meanCost = 155_000.0,
            meanRatio = 0.5,
            worstRatio = 0.5,
            budgetUsage = BudgetUsage(262_144L, 314_573L, if (withWriteProfile) 4100.0 else null, 1),
        ),
    )

    fun meanConfiguration(withWriteProfile: Boolean = true): EvaluatedConfiguration = EvaluatedConfiguration(
        evaluation = meanEvaluation(),
        metrics = SetMetrics(
            profiles = listOf(
                ProfileMetrics("normal", 0.9, 310_000.0, 40_000.0, 270_000.0, 0.12903225806451613, 87.09677419354838),
                ProfileMetrics("campaign", 0.1, 310_000.0, 301_000.0, 9000.0, 0.9709677419354839, 2.903225806451613),
            ),
            baselineMeanCost = 310_000.0,
            meanCost = 66_100.0,
            meanRatio = 0.2132258064516129,
            worstRatio = 0.9709677419354839,
            budgetUsage = BudgetUsage(524_288L, 629_146L, if (withWriteProfile) 8200.0 else null, 1),
        ),
    )

    /** A common synthetic pool contains baseline and either one or both single-index evaluations. */
    fun completedResult(
        objective: Objective = Objective.ROBUST,
        sameIncumbents: Boolean = false,
        withWriteProfile: Boolean = true,
    ): SearchResult.Completed {
        val robust = robustConfiguration(withWriteProfile)
        return SearchResult.Completed(
            selectedObjective = objective,
            baseline = baselineEvaluation(),
            bestRobust = robust,
            bestMean = if (sameIncumbents) robust else meanConfiguration(withWriteProfile),
            diagnostics = SearchDiagnostics(
                completedPhases = listOf(
                    AnalysisPhase.VALIDATION, AnalysisPhase.INTROSPECTION, AnalysisPhase.BASELINE,
                    AnalysisPhase.CANDIDATES, AnalysisPhase.GREEDY, AnalysisPhase.REPAIR,
                ),
                stopReasons = listOf(StopReason.SEARCH_COMPLETE),
                statistics = if (sameIncumbents) {
                    RunStatistics(explainAttempts = 4, evaluationAttempts = 2, completedEvaluations = 2)
                } else {
                    RunStatistics(explainAttempts = 6, evaluationAttempts = 3, completedEvaluations = 3)
                },
            ),
        )
    }

    /** A failed later EXPLAIN preserves the last complete candidates, never a partial cost vector. */
    fun partialResult(objective: Objective = Objective.ROBUST): SearchResult.Partial = SearchResult.Partial(
        selectedObjective = objective,
        baseline = baselineEvaluation(),
        bestRobust = robustConfiguration(),
        bestMean = robustConfiguration(),
        diagnostics = SearchDiagnostics(
            completedPhases = listOf(
                AnalysisPhase.VALIDATION, AnalysisPhase.INTROSPECTION, AnalysisPhase.BASELINE, AnalysisPhase.CANDIDATES,
            ),
            stopReasons = listOf(StopReason.EVALUATION_FAILED),
            statistics = RunStatistics(explainAttempts = 5, evaluationAttempts = 3, completedEvaluations = 2),
            errors = listOf(
                AnalysisError(ErrorCode.EXPLAIN_FAILED, "Synthetic evaluation failure", queryId = "records_by_group"),
            ),
            unexaminedConfigurations = null,
        ),
    )

    fun failedResult(objective: Objective = Objective.ROBUST): SearchResult.Failed = SearchResult.Failed(
        selectedObjective = objective,
        error = AnalysisError(ErrorCode.EXPLAIN_FAILED, "Synthetic baseline failure", queryId = "records_by_group"),
        diagnostics = SearchDiagnostics(
            completedPhases = listOf(AnalysisPhase.VALIDATION, AnalysisPhase.INTROSPECTION),
            stopReasons = listOf(StopReason.EVALUATION_FAILED),
            statistics = RunStatistics(explainAttempts = 1, evaluationAttempts = 1),
        ),
    )

    /** Rendering fixture only: run ID, input hash and environment metadata are deliberately synthetic. */
    fun report(
        status: RunStatus = RunStatus.COMPLETED,
        objective: Objective = Objective.ROBUST,
        sameIncumbents: Boolean = false,
        withWriteProfile: Boolean = true,
    ): AnalysisReport {
        val result = when (status) {
            RunStatus.COMPLETED -> completedResult(objective, sameIncumbents, withWriteProfile)
            RunStatus.PARTIAL -> partialResult(objective).let {
                if (withWriteProfile) it else it.copy(
                    bestRobust = robustConfiguration(false),
                    bestMean = robustConfiguration(false),
                )
            }
            RunStatus.FAILED -> failedResult(objective)
        }
        val input = workload(objective, withWriteProfile)
        val reportCandidates = when {
            status == RunStatus.FAILED -> emptyList()
            status == RunStatus.COMPLETED && sameIncumbents -> candidates(withWriteProfile).take(1)
            else -> candidates(withWriteProfile)
        }
        return AnalysisReport(
            runId = "00000000-0000-0000-0000-000000000001",
            inputHash = "f".repeat(64),
            durationMillis = 100L,
            environment = EnvironmentInfo(applicationVersion = "fixture", javaVersion = "fixture"),
            result = result,
            // Limit the toy report to singles, including when the write budget is switched off.
            input = input.copy(constraints = input.constraints.copy(maxIndexes = 1)),
            candidates = CandidateBatch(
                candidates = reportCandidates,
                diagnostics = CandidateDiagnostics(
                    candidatesBeforeGlobalDeduplication = reportCandidates.size,
                    candidatesAfterGlobalDeduplication = reportCandidates.size,
                    shortlistedCandidates = reportCandidates.size,
                ),
            ),
            priceOfRobustness = PriceOfRobustness.calculate(result),
            baselineMetrics = if (status == RunStatus.FAILED) null else baselineConfiguration(withWriteProfile).metrics,
            normalizedProfileWeights = ProfileWeights.normalize(input.profiles),
            queryComparisons = input.queries.map { query ->
                val before = result.baseline?.costByQueryId?.get(query.id)
                val after = result.selected?.evaluation?.costByQueryId?.get(query.id)
                QueryCostComparison(query.id, before, after,
                    if (before != null && after != null) after > before else null)
            },
            incumbentsCoincide = if (status == RunStatus.FAILED) null else
                result.bestRobust?.evaluation?.candidateIds == result.bestMean?.evaluation?.candidateIds,
        )
    }

    private fun evaluation(candidateIds: List<String>, groupCost: Double, valueCost: Double): EvaluationResult {
        val costs = mapOf("records_by_group" to groupCost, "records_by_value" to valueCost)
        return EvaluationResult(
            candidateIds = candidateIds,
            costByQueryId = costs,
            plansByQueryId = costs.mapValues { (_, cost) ->
                ExplainPlan("""[{"Plan":{"Node Type":"Fixture Scan","Total Cost":$cost}}]""")
            },
        )
    }
}
