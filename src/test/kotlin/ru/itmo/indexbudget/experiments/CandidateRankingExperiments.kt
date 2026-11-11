package ru.itmo.indexbudget.experiments

import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.AnalysisSession
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/** Воспроизводимый синтетический контроль рейтинга, без PostgreSQL и физических измерений. */
fun main(args: Array<String>) {
    require(args.size == 2 && args[0] == "--output-dir") { "Expected --output-dir NEW_PATH" }
    CandidateRankingExperiments().run(Path.of(args[1]))
}

class CandidateRankingExperiments {
    private val mapper = JsonMapper.builder().build()
    private val metrics = MetricsCalculatorImpl()
    private val comparator = ObjectiveComparatorImpl()

    fun run(output: Path) {
        require(!Files.exists(output)) { "Experiment destination must be new" }
        val summaries = linkedMapOf<String, Any?>()
        for (scenario in Scenario.entries) {
            val runs = CandidateRanking.entries.associateWith { run(it, scenario) }
            val frequency = runs.getValue(CandidateRanking.FREQUENCY)
            val baselineCost = runs.getValue(CandidateRanking.BASELINE_COST)
            check(frequency.pool.size == 16 && baselineCost.pool.size == 16)
            check(frequency.pool.count { it.definition.keys.size == 1 } == 8)
            check(baselineCost.pool.count { it.definition.keys.size == 1 } == 8)
            check(frequency.baseline.costByQueryId == baselineCost.baseline.costByQueryId)
            check(frequency.workload.input.copy(candidateRanking = CandidateRanking.BASELINE_COST) ==
                baselineCost.workload.input)
            check(frequency.result.diagnostics.limits == baselineCost.result.diagnostics.limits)
            val oldRatio = checkNotNull(checkNotNull(frequency.result.selected).metrics.meanRatio)
            val newRatio = checkNotNull(checkNotNull(baselineCost.result.selected).metrics.meanRatio)
            check(if (scenario == Scenario.RARE_IMPROVES) newRatio < oldRatio else newRatio > oldRatio)
            summaries[scenario.name.lowercase()] = linkedMapOf(
                "complete_cost_vectors_by_configuration_support" to scenario.vectors,
                "runs" to runs.entries.associate { (ranking, run) -> ranking.wireName to summarize(run) },
                "comparison" to linkedMapOf(
                    "same_sql_profiles_budgets_limits_baseline" to true,
                    "same_final_pool_size" to 16,
                    "removed_from_shortlist" to frequency.pool.map { it.id }
                        .toSet().subtract(baselineCost.pool.map { it.id }.toSet()).sorted(),
                    "added_to_shortlist" to baselineCost.pool.map { it.id }
                        .toSet().subtract(frequency.pool.map { it.id }.toSet()).sorted(),
                    "shortlist_intersection" to frequency.pool.map { it.id }.intersect(baselineCost.pool.map { it.id }.toSet()).sorted(),
                    "winner_on_this_fixture" to if (newRatio < oldRatio) "baseline_cost" else "frequency",
                ),
            )
        }
        val manifest = linkedMapOf<String, Any?>(
            "schema_version" to 1,
            "experiment" to "candidate_ranking_synthetic_control",
            "limitations" to listOf(
                "Synthetic resolved workload, catalog, sizes and complete cost vectors; no PostgreSQL connection or EXPLAIN.",
                "Candidate generation, search, budgets and profile metrics use production implementations; fixture sizing follows the pipeline seam.",
                "Cost vectors are specified for the entire set by its table support; single-index gains are never added.",
                "A frozen clock removes machine timing variation. Durations are not measurements of performance.",
                "The cases demonstrate both improvement and regression; they cannot justify a new production default.",
            ),
            "input" to inputSummary(input(CandidateRanking.FREQUENCY)),
            "fixture_size_bytes_per_candidate" to 100,
            "scenarios" to summaries,
            "default_decision" to "Keep frequency; baseline_cost remains experimental pending representative PostgreSQL observations.",
        )
        Files.createDirectories(output)
        Files.writeString(output.resolve("summary.json"), mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(manifest) + "\n")
        Files.writeString(output.resolve("workload-v2.json"), mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(inputSummary(input(CandidateRanking.BASELINE_COST))) + "\n")
    }

    private fun run(ranking: CandidateRanking, scenario: Scenario): RankingRun {
        val input = input(ranking)
        val workload = resolved(input)
        val context = RunContext(clock = NanoClock { 0L }, analysisMode = input.analysisMode)
        context.startSearch()
        context.enterPhase(AnalysisPhase.BASELINE)
        val session = FixtureSession(scenario)
        val baseline = session.evaluate(emptyList(), workload, context)
        context.completePhase()
        context.enterPhase(AnalysisPhase.CANDIDATES)
        val generated = ProfileAwareCandidateGenerator().generate(workload, catalog(), context, baseline)
        check(generated.diagnostics.localScopes.all { it.retainedDefinitions == 32 })
        check(generated.candidates.all { it.definition.includedColumns.isEmpty() })
        // Match the pipeline sizing seam; this experiment does not introduce refill.
        val prepared = generated.copy(candidates = generated.candidates.map { candidate ->
            val sized = candidate.copy(estimatedBytes = session.estimateSize(candidate, context))
            val budget = metrics.assessBudget(listOf(sized), input)
            check(budget.admissible) { "Fixture shortlist must remain individually admissible" }
            sized.copy(budgetedBytes = budget.usage.budgetedBytes,
                writeUnitsPerHour = budget.usage.writeUnitsPerHour)
        })
        context.completePhase()
        val result = LimitedRepairOptimizer().optimize(workload, prepared, baseline,
            session, metrics, comparator, context)
        check(result.status != RunStatus.FAILED)
        check(session.calls.size == context.statistics().evaluationAttempts)
        check(session.calls.distinct().size == session.calls.size)
        return RankingRun(workload, baseline, generated, prepared.candidates, session, result)
    }

    private fun summarize(run: RankingRun): Map<String, Any?> {
        val selected = checkNotNull(run.result.selected)
        val statistics = run.result.diagnostics.statistics
        val limits = run.result.diagnostics.limits
        fun candidate(candidate: IndexCandidate) = linkedMapOf<String, Any?>(
            "id" to candidate.id,
            "table" to candidate.definition.table.name,
            "columns" to candidate.definition.keys.map { it.column.name },
            "source_query_ids" to candidate.sourceQueryIds.sorted(),
        )
        return linkedMapOf(
            "candidate_ranking" to run.workload.input.candidateRanking.wireName,
            "local_survivors" to run.generated.diagnostics.localScopes.map { mapOf(
                "query_id" to it.queryId, "retained_columns" to it.retainedColumns,
                "enumerated_definitions" to it.enumeratedDefinitions,
                "retained_definitions" to it.retainedDefinitions,
            ) },
            "shortlist" to run.pool.map(::candidate),
            "size_attempts" to run.session.sizeAttempts,
            "final_pool_size" to run.pool.size,
            "status" to run.result.status.name.lowercase(),
            "stop_reasons" to run.result.diagnostics.stopReasons.map { it.name },
            "selected_ids" to selected.evaluation.candidateIds,
            "selected_cost_by_query_id" to selected.evaluation.costByQueryId,
            "baseline_cost_by_query_id" to run.baseline.costByQueryId,
            "profile_metrics" to selected.metrics.profiles.map { profile -> linkedMapOf(
                "profile_id" to profile.profileId,
                "normalized_weight" to profile.normalizedWeight,
                "baseline_cost" to profile.baselineCost,
                "cost" to profile.cost,
                "gain" to profile.gain,
                "ratio" to profile.ratio,
                "gain_percent" to profile.gainPercent,
            ) },
            "mean_cost" to selected.metrics.meanCost,
            "baseline_mean_cost" to selected.metrics.baselineMeanCost,
            "mean_ratio" to selected.metrics.meanRatio,
            "worst_ratio" to selected.metrics.worstRatio,
            "selected_estimated_bytes" to selected.metrics.budgetUsage.estimatedBytes,
            "selected_budgeted_bytes" to selected.metrics.budgetUsage.budgetedBytes,
            "selected_write_units_per_hour" to selected.metrics.budgetUsage.writeUnitsPerHour,
            "duration_millis" to 0,
            "duration_source" to "Frozen test clock; not a performance measurement",
            "statistics" to mapOf(
                "actual_explain_attempts" to statistics.explainAttempts,
                "synthetic_evaluation_attempts" to statistics.evaluationAttempts,
                "completed_synthetic_evaluations" to statistics.completedEvaluations,
                "synthetic_query_cost_lookups" to run.session.calls.size * run.workload.queries.size,
                "cache_hits" to statistics.cacheHits,
                "repair_attempts" to statistics.repairAttempts,
            ),
            "limits" to mapOf(
                "command_timeout_millis" to limits.commandTimeout.toMillis(),
                "search_timeout_millis" to limits.searchTimeout.toMillis(),
                "explain_timeout_millis" to limits.explainTimeout.toMillis(),
                "connect_timeout_millis" to limits.connectTimeout.toMillis(),
                "max_explain_attempts" to limits.maxExplainAttempts,
                "max_repair_attempts" to limits.maxRepairAttempts,
            ),
        )
    }

    private fun input(ranking: CandidateRanking): WorkloadInput {
        val queries = listOf("cheap", "rare").map { table ->
            QueryInput(table, "SELECT * FROM public.$table WHERE " +
                (1..16).joinToString(" AND ") { "c$it = 1" })
        }
        return WorkloadInput(queries,
            listOf(Profile("observed", 1.0, mapOf("cheap" to 1000.0, "rare" to 1.0))),
            Constraints(1_000_000, 100.0, maxIndexes = 2),
            tableWriteRates = listOf("cheap", "rare").associate {
                "public.$it" to TableWriteRates(1.0, 2.0, 0.0)
            }, candidateRanking = ranking)
    }

    private fun inputSummary(input: WorkloadInput): Map<String, Any?> = linkedMapOf(
        "schema_version" to 2,
        "objective" to "robust",
        "queries" to input.queries.map { mapOf("id" to it.id, "sql" to it.sql) },
        "profiles" to listOf(mapOf("id" to "observed", "weight" to 1.0,
            "calls_per_hour" to input.profiles.single().callsPerHour)),
        "constraints" to mapOf("storage_budget_bytes" to input.constraints.storageBudgetBytes,
            "write_budget_units_per_hour" to input.constraints.writeBudgetUnitsPerHour,
            "max_indexes" to input.constraints.maxIndexes),
        "table_write_rates" to input.tableWriteRates?.mapValues { (_, rates) ->
            mapOf("inserts_per_hour" to rates.insertsPerHour, "updates_per_hour" to rates.updatesPerHour,
                "deletes_per_hour" to rates.deletesPerHour)
        },
        "analysis_mode" to "normal",
        "candidate_ranking" to input.candidateRanking.wireName,
    )

    private fun resolved(input: WorkloadInput): ResolvedWorkload = ResolvedWorkload(input, input.queries.map { query ->
        val table = TableRef("public", query.id)
        val columns = (1..16).map { ColumnRef(table, "c$it") }
        ResolvedQuery(query.id, query.sql, setOf(table), columns.toSet(), columns.map {
            ResolvedPredicate(it, ComparisonOperator.EQUAL, PredicateSource.WHERE)
        }, emptyList(), selectsAllColumns = true)
    })

    private fun catalog(): CatalogSnapshot = CatalogSnapshot(listOf("cheap", "rare").map { name ->
        CatalogTable(TableRef("public", name), (1..16).map {
            CatalogColumn("c$it", "int4", defaultOperatorClass = "pg_catalog.int4_ops")
        })
    })

    private data class RankingRun(
        val workload: ResolvedWorkload,
        val baseline: EvaluationResult,
        val generated: CandidateBatch,
        val pool: List<IndexCandidate>,
        val session: FixtureSession,
        val result: SearchResult,
    )

    private enum class Scenario(val vectors: Map<String, Map<String, Double>>) {
        RARE_IMPROVES(linkedMapOf(
            "empty" to mapOf("cheap" to 1.0, "rare" to 10000.0),
            "cheap_only" to mapOf("cheap" to 0.1, "rare" to 10000.0),
            "rare_only" to mapOf("cheap" to 1.0, "rare" to 500.0),
            "both" to mapOf("cheap" to 0.05, "rare" to 600.0),
        )),
        RARE_UNCHANGED(linkedMapOf(
            "empty" to mapOf("cheap" to 1.0, "rare" to 10000.0),
            "cheap_only" to mapOf("cheap" to 0.01, "rare" to 10000.0),
            "rare_only" to mapOf("cheap" to 1.0, "rare" to 10000.0),
            "both" to mapOf("cheap" to 0.02, "rare" to 10000.0),
        )),
    }

    private class FixtureSession(private val scenario: Scenario) : AnalysisSession {
        val calls = mutableListOf<List<String>>()
        var sizeAttempts = 0
            private set

        override fun readCatalog(tables: Set<TableName>, context: RunContext): CatalogSnapshot =
            error("Fixture catalog is supplied directly")

        override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long {
            context.checkDeadline()
            sizeAttempts++
            return 100L
        }

        override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload,
            context: RunContext): EvaluationResult {
            context.reserveEvaluationAttempt()
            val ids = candidates.map { it.id }.sorted()
            calls += ids
            val tables = candidates.map { it.definition.table.name }.toSet()
            val support = when (tables) {
                emptySet<String>() -> "empty"
                setOf("cheap") -> "cheap_only"
                setOf("rare") -> "rare_only"
                setOf("cheap", "rare") -> "both"
                else -> error("Unspecified fixture configuration")
            }
            val costs = scenario.vectors.getValue(support)
            val evaluation = EvaluationResult(ids, costs, costs.mapValues { (_, cost) ->
                ExplainPlan("""{"fixture_support":"$support","synthetic_cost":$cost}""")
            }).requireQueryCoverage(workload.queries.map { it.id }.toSet())
            context.recordCompletedEvaluation()
            return evaluation
        }

        override fun cancel() = Unit
        override fun close() = Unit
    }
}
