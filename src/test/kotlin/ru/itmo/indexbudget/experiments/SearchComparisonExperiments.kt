package ru.itmo.indexbudget.experiments

import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.AnalysisPhase
import ru.itmo.indexbudget.domain.CandidateRanking
import ru.itmo.indexbudget.domain.CandidateBatch
import ru.itmo.indexbudget.domain.CatalogColumn
import ru.itmo.indexbudget.domain.CatalogSnapshot
import ru.itmo.indexbudget.domain.CatalogTable
import ru.itmo.indexbudget.domain.ColumnRef
import ru.itmo.indexbudget.domain.ComparisonOperator
import ru.itmo.indexbudget.domain.Constraints
import ru.itmo.indexbudget.domain.EvaluationResult
import ru.itmo.indexbudget.domain.ExplainPlan
import ru.itmo.indexbudget.domain.GenerationReason
import ru.itmo.indexbudget.domain.IndexCandidate
import ru.itmo.indexbudget.domain.IndexDefinition
import ru.itmo.indexbudget.domain.IndexKey
import ru.itmo.indexbudget.domain.MetricsCalculatorImpl
import ru.itmo.indexbudget.domain.NanoClock
import ru.itmo.indexbudget.domain.Objective
import ru.itmo.indexbudget.domain.ObjectiveComparatorImpl
import ru.itmo.indexbudget.domain.PredicateSource
import ru.itmo.indexbudget.domain.Profile
import ru.itmo.indexbudget.domain.QueryInput
import ru.itmo.indexbudget.domain.ResolvedPredicate
import ru.itmo.indexbudget.domain.ResolvedQuery
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.RunStatistics
import ru.itmo.indexbudget.domain.SearchResult
import ru.itmo.indexbudget.domain.SetMetrics
import ru.itmo.indexbudget.domain.TableName
import ru.itmo.indexbudget.domain.TableRef
import ru.itmo.indexbudget.domain.TableWriteRates
import ru.itmo.indexbudget.domain.WorkloadInput
import ru.itmo.indexbudget.optimizer.GreedyOptimizer
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.AnalysisSession
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Reproducible comparison of the production greedy, greedy+repair and the verification-only
 * exhaustive reference (#104). Same SQL, candidate pool, objective, budgets and limits per
 * scenario; every configuration is judged by its own complete cost vector and single-index
 * wins are never summed. The candidate-ranking axis is recorded separately and is never mixed
 * into the strategy comparison.
 */
fun main(args: Array<String>) {
    require(args.size == 2 && args[0] == "--output-dir") { "Expected --output-dir NEW_PATH" }
    SearchComparisonExperiments().run(Path.of(args[1]))
}

class SearchComparisonExperiments {
    private val mapper = JsonMapper.builder().build()
    private val metrics = MetricsCalculatorImpl()
    private val comparator = ObjectiveComparatorImpl()

    fun run(output: Path) {
        require(!Files.exists(output)) { "Experiment destination must be new" }
        val scenarios = linkedMapOf<String, Any?>()
        for (scenario in Scenario.entries) {
            val fixture = fixture(scenario)
            val runs = Objective.entries.associateWith { objective ->
                Strategy.entries.associateWith { strategy -> runStrategy(scenario, fixture, objective, strategy) }
            }
            val exhaustive = runs.getValue(Objective.ROBUST).getValue(Strategy.EXHAUSTIVE).reference
            check(requireNotNull(exhaustive).complete) { "${scenario.name}: the reference must complete its space" }
            val summary = linkedMapOf<String, Any?>(
                "description" to scenario.description,
                "input" to inputSummary(fixture.input),
                "fixed_pool" to fixture.pool.map(::candidateSummary),
                "cost_vectors_by_sorted_ids" to fixture.vectorTable(),
                "search_space_subsets" to exhaustive.searchSpaceSize,
                "runs" to runs.entries.associate { (objective, byStrategy) ->
                    objective.name.lowercase() to byStrategy.entries.associate { (strategy, run) ->
                        strategy.wireName to runSummary(run)
                    }
                },
                "comparison" to comparison(runs, fixture),
            )
            if (scenario == Scenario.WIDE_RARE) {
                summary["ranking_composition"] = rankingComposition(fixture)
            }
            scenarios[scenario.name.lowercase()] = summary
        }
        val manifest = linkedMapOf<String, Any?>(
            "schema_version" to 1,
            "experiment" to "search_strategy_comparison",
            "tool" to linkedMapOf(
                "kind" to "verification-only exhaustive reference in test sources; not in production analyze",
                "max_pool_candidates" to ExhaustiveSearchOptimizer.MAX_POOL,
                "max_selected_indexes" to ExhaustiveSearchOptimizer.MAX_INDEXES,
                "own_deadline" to VERIFICATION_TIMEOUT.toString(),
                "deadline_source" to "Frozen test clock removes timing; the deadline path is covered by unit tests",
                "enumeration_order" to "subsets by size, then lexicographic sorted IDs; budget check before any evaluation",
            ),
            "limitations" to listOf(
                "Synthetic resolved workloads and complete cost vectors; no PostgreSQL connection or EXPLAIN here.",
                "Greedy, greedy+repair, candidate generation, budgets, metrics and the comparator are production code.",
                "A combination has its own vector; single-index gains are never added.",
                "A frozen clock removes machine timing variation; durations are not performance measurements.",
                "Optimality claims are bounded to the fixed verified pool of each scenario.",
                "The ranking axis changes shortlist composition only and is recorded in its own section.",
            ),
            "scenarios" to scenarios,
            "conclusion" to "Greedy matches the verified optimum when singleton steps improve; " +
                "repair additionally escapes the recorded greedy traps; exhaustive verification stays " +
                "within its declared space and caps on every scenario.",
        )
        Files.createDirectories(output)
        Files.writeString(output.resolve("summary.json"), mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(manifest) + "\n")
        Files.writeString(output.resolve("workload-v2-wide-rare.json"), mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(inputSummary(wideRareInput(CandidateRanking.FREQUENCY))) + "\n")
    }

    private enum class Strategy(val wireName: String) {
        GREEDY("greedy"), GREEDY_REPAIR("greedy_repair"), EXHAUSTIVE("exhaustive"),
    }

    private data class RunObservation(
        val strategy: Strategy,
        val objective: Objective,
        val status: String,
        val stopReasons: List<String>,
        val selectedIds: List<String>,
        val bestRobustIds: List<String>,
        val bestMeanIds: List<String>,
        val metricsOfSelected: SetMetrics?,
        val statistics: RunStatistics,
        val evaluatorCalls: Int,
        val reference: ExhaustiveSearchResult?,
    )

    private fun runStrategy(
        scenario: Scenario,
        fixture: Fixture,
        objective: Objective,
        strategy: Strategy,
    ): RunObservation {
        val input = fixture.input.copy(objective = objective)
        val workload = ResolvedWorkload(input, fixture.resolvedQueries)
        val context = RunContext(clock = NanoClock { 0L })
        context.startSearch()
        context.enterPhase(AnalysisPhase.BASELINE)
        val session = fixture.newSession()
        val baseline = session.evaluate(emptyList(), workload, context)
        context.completePhase()
        context.enterPhase(AnalysisPhase.CANDIDATES)
        val pool = fixture.pool
        val sized = pool.map { it.copy(estimatedBytes = session.estimateSize(it, context)) }
        // Search strategies only ever see admissible singletons; whole-pool admissibility is
        // not required (the budget scenario exists precisely because some pairs cannot fit).
        val prepared = sized.map { candidate ->
            val budget = metrics.assessBudget(listOf(candidate), input)
            check(budget.admissible) { "Fixture singletons must remain individually admissible" }
            candidate.copy(budgetedBytes = budget.usage.budgetedBytes,
                writeUnitsPerHour = budget.usage.writeUnitsPerHour)
        }
        context.completePhase()

        val observation = when (strategy) {
            Strategy.GREEDY -> {
                val result = GreedyOptimizer().search(workload, CandidateBatch(prepared), baseline,
                    session, metrics, comparator, context).result
                summarizeSearch(strategy, objective, result, context, session, null)
            }
            Strategy.GREEDY_REPAIR -> {
                val result = LimitedRepairOptimizer().optimize(workload, CandidateBatch(prepared),
                    baseline, session, metrics, comparator, context)
                summarizeSearch(strategy, objective, result, context, session, null)
            }
            Strategy.EXHAUSTIVE -> {
                val reference = ExhaustiveSearchOptimizer(verificationTimeout = VERIFICATION_TIMEOUT,
                    clock = NanoClock { 0L }).search(workload, prepared, baseline, session,
                    metrics, comparator, context)
                summarizeSearch(strategy, objective, null, context, session, reference)
            }
        }
        check(session.calls.size == context.statistics().evaluationAttempts) {
            "Every evaluation attempt must reach the fixture session exactly once"
        }
        check(session.calls.distinct().size == session.calls.size) {
            "No configuration may be evaluated twice within one run"
        }
        return observation
    }

    private fun summarizeSearch(
        strategy: Strategy,
        objective: Objective,
        result: SearchResult?,
        context: RunContext,
        session: VectorSession,
        reference: ExhaustiveSearchResult?,
    ): RunObservation {
        val robust = result?.bestRobust ?: reference?.bestRobust
        val mean = result?.bestMean ?: reference?.bestMean
        val selected = when {
            result != null -> requireNotNull(result.selected)
            reference != null -> reference.selected(objective)
            else -> error("unreachable")
        }
        return RunObservation(
            strategy = strategy,
            objective = objective,
            status = result?.status?.name?.lowercase() ?: "reference",
            stopReasons = result?.diagnostics?.stopReasons?.map { it.name } ?: emptyList(),
            selectedIds = selected.evaluation.candidateIds,
            bestRobustIds = requireNotNull(robust).evaluation.candidateIds,
            bestMeanIds = requireNotNull(mean).evaluation.candidateIds,
            metricsOfSelected = selected.metrics,
            statistics = context.statistics(),
            evaluatorCalls = session.calls.size,
            reference = reference,
        )
    }

    private fun runSummary(run: RunObservation): Map<String, Any?> {
        val metricsOfSelected = requireNotNull(run.metricsOfSelected)
        val usage = metricsOfSelected.budgetUsage
        return linkedMapOf(
            "selected_ids" to run.selectedIds,
            "best_robust_ids" to run.bestRobustIds,
            "best_mean_ids" to run.bestMeanIds,
            "mean_cost" to metricsOfSelected.meanCost,
            "baseline_mean_cost" to metricsOfSelected.baselineMeanCost,
            "mean_ratio" to metricsOfSelected.meanRatio,
            "worst_ratio" to metricsOfSelected.worstRatio,
            "selected_budgeted_bytes" to usage.budgetedBytes,
            "selected_write_units_per_hour" to usage.writeUnitsPerHour,
            "status" to run.status,
            "stop_reasons" to run.stopReasons,
            "statistics" to mapOf(
                "evaluation_attempts" to run.statistics.evaluationAttempts,
                "completed_evaluations" to run.statistics.completedEvaluations,
                "cache_hits" to run.statistics.cacheHits,
                "repair_attempts" to run.statistics.repairAttempts,
            ),
            "evaluator_calls" to run.evaluatorCalls,
            "exhaustive" to run.reference?.let { reference ->
                linkedMapOf(
                    "complete" to reference.complete,
                    "verified_optimum" to reference.verifiedOptimum,
                    "stop_reason" to reference.stopReason,
                    "search_space_subsets" to reference.searchSpaceSize,
                    "evaluated_sets" to reference.evaluatedSets,
                    "budget_excluded_sets" to reference.budgetExcludedSets,
                    "rejected_sets" to reference.rejectedEvaluations.keys.toList(),
                )
            },
        )
    }

    private fun comparison(
        runs: Map<Objective, Map<Strategy, RunObservation>>,
        fixture: Fixture,
    ): Map<String, Any?> = Objective.entries.associateWith { objective ->
        val byStrategy = runs.getValue(objective)
        val exhaustive = byStrategy.getValue(Strategy.EXHAUSTIVE)
        val optimum = exhaustive.reference!!.selected(objective)
        fun matches(strategy: Strategy) =
            byStrategy.getValue(strategy).selectedIds == optimum.evaluation.candidateIds
        linkedMapOf<String, Any?>(
            "objective" to objective.name.lowercase(),
            "verified_optimum_ids" to optimum.evaluation.candidateIds,
            "verified_optimum_mean_ratio" to optimum.metrics.meanRatio,
            "verified_optimum_worst_ratio" to optimum.metrics.worstRatio,
            "greedy_matches_optimum" to matches(Strategy.GREEDY),
            "greedy_repair_matches_optimum" to matches(Strategy.GREEDY_REPAIR),
            "greedy_mean_ratio_gap_to_optimum" to
                (byStrategy.getValue(Strategy.GREEDY).metricsOfSelected!!.meanRatio!! -
                    optimum.metrics.meanRatio!!),
            "greedy_repair_mean_ratio_gap_to_optimum" to
                (byStrategy.getValue(Strategy.GREEDY_REPAIR).metricsOfSelected!!.meanRatio!! -
                    optimum.metrics.meanRatio!!),
        )
    }.let { byObjective ->
        linkedMapOf(
            "same_sql_pool_objective_budgets_limits_between_strategies" to true,
            "pool_ids" to fixture.pool.map { it.id },
            "by_objective" to byObjective.mapKeys { it.key.name.lowercase() },
        )
    }

    /** The ranking axis is isolated from the strategy comparison: same input, objective and
     * limits, only the shortlist composition changes. */
    private fun rankingComposition(fixture: Fixture): Map<String, Any?> {
        val frequency = generateShortlist(fixture, CandidateRanking.FREQUENCY)
        val baselineCost = generateShortlist(fixture, CandidateRanking.BASELINE_COST)
        fun ids(batch: CandidateBatch) = batch.candidates.map { it.id }
        return linkedMapOf(
            "same_input_objective_limits_except_ranking" to true,
            "frequency_pool_size" to frequency.candidates.size,
            "baseline_cost_pool_size" to baselineCost.candidates.size,
            "removed_from_shortlist" to ids(frequency).toSet().subtract(ids(baselineCost).toSet()).sorted(),
            "added_to_shortlist" to ids(baselineCost).toSet().subtract(ids(frequency).toSet()).sorted(),
            "shortlist_intersection" to ids(frequency).toSet().intersect(ids(baselineCost).toSet()).sorted(),
            "reference" to "docs/experiments/candidate-ranking-2026-11-11",
        )
    }

    private fun generateShortlist(fixture: Fixture, ranking: CandidateRanking): CandidateBatch {
        val input = fixture.input.copy(candidateRanking = ranking)
        val workload = ResolvedWorkload(input, fixture.resolvedQueries)
        val context = RunContext(clock = NanoClock { 0L })
        context.startSearch()
        context.enterPhase(AnalysisPhase.CANDIDATES)
        val baseline = fixture.newSession().evaluate(emptyList(), workload, context)
        val generated = ProfileAwareCandidateGenerator().generate(workload, wideCatalog(), context, baseline)
        check(generated.candidates.size == 16) { "Wide fixture must produce the capped 16-candidate shortlist" }
        return generated
    }

    private fun candidateSummary(candidate: IndexCandidate): Map<String, Any?> = linkedMapOf(
        "id" to candidate.id,
        "table" to candidate.definition.table.name,
        "columns" to candidate.definition.keys.map { it.column.name },
        "estimated_bytes" to candidate.estimatedBytes,
    )

    private fun inputSummary(input: WorkloadInput): Map<String, Any?> = linkedMapOf(
        "schema_version" to 2,
        "objective" to input.objective.name.lowercase(),
        "queries" to input.queries.map { mapOf("id" to it.id, "sql" to it.sql) },
        "profiles" to input.profiles.map { mapOf("id" to it.id, "weight" to it.weight,
            "calls_per_hour" to it.callsPerHour) },
        "constraints" to mapOf("storage_budget_bytes" to input.constraints.storageBudgetBytes,
            "write_budget_units_per_hour" to input.constraints.writeBudgetUnitsPerHour,
            "max_indexes" to input.constraints.maxIndexes),
        "table_write_rates" to input.tableWriteRates?.mapValues { (_, rates) ->
            mapOf("inserts_per_hour" to rates.insertsPerHour, "updates_per_hour" to rates.updatesPerHour,
                "deletes_per_hour" to rates.deletesPerHour)
        },
        "candidate_ranking" to input.candidateRanking.wireName,
    )

    private companion object {
        val VERIFICATION_TIMEOUT: Duration = Duration.ofMinutes(10)
    }

    /** Deterministic scenario catalogue; every combination has its own complete vector. */
    private enum class Scenario(val description: String) {
        CHAIN("Singleton steps improve one by one: greedy reaches the three-index optimum itself"),
        TRAP("No singleton improves while the pair does: greedy stays empty and only repair escapes"),
        BUDGET_REPLACEMENT("The best single does not fit any pair: repair replaces it with {j,k} under budget"),
        NO_IMPROVEMENT("Every set is worse than the baseline: all strategies keep the empty set"),
        WIDE_RARE("Wide tables with a rare profile, an 8-candidate pool and max_indexes 5: " +
            "existing caps hold and the reference still verifies its whole space"),
    }

    private data class Fixture(
        val input: WorkloadInput,
        val pool: List<IndexCandidate>,
        val resolvedQueries: List<ResolvedQuery>,
        val vectors: Map<List<String>, Map<String, Double>>,
        private val sizes: Map<String, Long> = emptyMap(),
        private val supportBased: Boolean = false,
    ) {
        fun newSession(): VectorSession =
            if (supportBased) VectorSession(::supportVectors, ::sizeOf) else VectorSession(::idsVectors, ::sizeOf)

        fun idsVectors(candidates: List<IndexCandidate>): Map<String, Double> =
            vectors.getValue(candidates.map { it.id }.sorted())

        /** Vectors are keyed by table support: empty, cheap_only, rare_only, both. */
        fun supportVectors(candidates: List<IndexCandidate>): Map<String, Double> {
            val tables = candidates.map { it.definition.table.name }.toSet()
            val support = when (tables) {
                emptySet<String>() -> "empty"
                setOf("cheap") -> "cheap_only"
                setOf("rare") -> "rare_only"
                setOf("cheap", "rare") -> "both"
                else -> error("Unspecified fixture support")
            }
            return vectors.getValue(listOf(support))
        }

        fun sizeOf(candidate: IndexCandidate): Long = sizes[candidate.id] ?: 100L

        fun vectorTable(): Map<String, Map<String, Double>> = vectors.entries.associate { (key, value) ->
            (if (supportBased) key.single() else key.joinToString(",")) to value
        }
    }

    /** Fixture session: complete synthetic vectors, deterministic sizes, honest counters. */
    private class VectorSession(
        private val resolve: (List<IndexCandidate>) -> Map<String, Double>,
        private val sizeOf: (IndexCandidate) -> Long,
    ) : AnalysisSession {
        val calls = mutableListOf<List<String>>()
        var sizeAttempts = 0
            private set

        override fun readCatalog(tables: Set<TableName>, context: RunContext): CatalogSnapshot =
            error("Fixture catalog is supplied directly")

        override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long {
            context.checkDeadline()
            sizeAttempts++
            return sizeOf(candidate)
        }

        override fun evaluate(
            candidates: List<IndexCandidate>,
            workload: ResolvedWorkload,
            context: RunContext,
        ): EvaluationResult {
            context.reserveEvaluationAttempt()
            val ids = candidates.map { it.id }.sorted()
            calls += ids
            val costs = resolve(candidates)
            val evaluation = EvaluationResult(ids, costs, costs.mapValues { (query, cost) ->
                ExplainPlan("""{"fixture_ids":"${ids.joinToString(",")}","query":"$query","cost":$cost}""")
            }).requireQueryCoverage(workload.queries.map { it.id }.toSet())
            context.recordCompletedEvaluation()
            return evaluation
        }

        override fun cancel() = Unit
        override fun close() = Unit
    }

    private fun fixture(scenario: Scenario): Fixture = when (scenario) {
        Scenario.CHAIN -> Fixture(
            input = scriptedInput(Constraints(1_000_000, null, 3)),
            pool = listOf(scriptedCandidate("idx_a", "col_a"), scriptedCandidate("idx_b", "col_b"),
                scriptedCandidate("idx_c", "col_c")),
            resolvedQueries = scriptedQueries(),
            vectors = linkedMapOf(
                emptyList<String>() to q(100.0, 100.0),
                listOf("idx_a") to q(90.0, 80.0),
                listOf("idx_b") to q(110.0, 100.0),
                listOf("idx_c") to q(100.0, 115.0),
                listOf("idx_a", "idx_b") to q(70.0, 60.0),
                listOf("idx_a", "idx_c") to q(85.0, 90.0),
                listOf("idx_b", "idx_c") to q(105.0, 95.0),
                listOf("idx_a", "idx_b", "idx_c") to q(50.0, 50.0),
            ),
        )
        Scenario.TRAP -> Fixture(
            input = scriptedInput(Constraints(1_000_000, null, 2)),
            pool = listOf(scriptedCandidate("idx_a", "col_a"), scriptedCandidate("idx_b", "col_b")),
            resolvedQueries = scriptedQueries(),
            vectors = linkedMapOf(
                emptyList<String>() to q(100.0, 100.0),
                listOf("idx_a") to q(100.0, 100.0),
                listOf("idx_b") to q(100.0, 100.0),
                listOf("idx_a", "idx_b") to q(60.0, 40.0),
            ),
        )
        Scenario.BUDGET_REPLACEMENT -> Fixture(
            input = scriptedInput(Constraints(60, null, 3)),
            pool = listOf(scriptedCandidate("idx_i", "col_i"), scriptedCandidate("idx_j", "col_j"),
                scriptedCandidate("idx_k", "col_k")),
            resolvedQueries = scriptedQueries(),
            // Budgeted sizes: ceil(1.2*40)=48, ceil(1.2*25)=30; only {i}, {j}, {k}, {j,k} fit 60.
            vectors = linkedMapOf(
                emptyList<String>() to q(100.0, 100.0),
                listOf("idx_i") to q(80.0, 80.0),
                listOf("idx_j") to q(90.0, 90.0),
                listOf("idx_k") to q(95.0, 95.0),
                listOf("idx_i", "idx_j") to q(70.0, 70.0),
                listOf("idx_i", "idx_k") to q(75.0, 65.0),
                listOf("idx_j", "idx_k") to q(50.0, 50.0),
                listOf("idx_i", "idx_j", "idx_k") to q(60.0, 60.0),
            ),
            sizes = mapOf("idx_i" to 40L, "idx_j" to 25L, "idx_k" to 25L),
        )
        Scenario.NO_IMPROVEMENT -> Fixture(
            input = scriptedInput(Constraints(1_000_000, null, 2)),
            pool = listOf(scriptedCandidate("idx_a", "col_a"), scriptedCandidate("idx_b", "col_b")),
            resolvedQueries = scriptedQueries(),
            vectors = linkedMapOf(
                emptyList<String>() to q(100.0, 100.0),
                listOf("idx_a") to q(150.0, 120.0),
                listOf("idx_b") to q(130.0, 160.0),
                listOf("idx_a", "idx_b") to q(170.0, 180.0),
            ),
        )
        Scenario.WIDE_RARE -> wideRareFixture()
    }

    private fun q(first: Double, second: Double): Map<String, Double> =
        mapOf("q_one" to first, "q_two" to second)

    private fun scriptedInput(constraints: Constraints): WorkloadInput = WorkloadInput(
        listOf(QueryInput("q_one", "SELECT 1"), QueryInput("q_two", "SELECT 2")),
        listOf(Profile("only", 1.0, mapOf("q_one" to 1.0, "q_two" to 1.0))),
        constraints,
    )

    private fun scriptedQueries(): List<ResolvedQuery> = listOf("q_one", "q_two").map {
        ResolvedQuery(it, "SELECT 1", emptySet(), emptySet(), emptyList(), emptyList())
    }

    private fun scriptedCandidate(id: String, column: String): IndexCandidate {
        val table = TableRef("public", "records")
        return IndexCandidate(id,
            IndexDefinition(table, listOf(IndexKey(ColumnRef(table, column)))),
            emptySet(), setOf(GenerationReason.SINGLE_COLUMN), estimatedBytes = 100L)
    }

    private fun wideRareInput(ranking: CandidateRanking): WorkloadInput {
        val queries = listOf("cheap", "rare").map { table ->
            QueryInput(table, "SELECT * FROM public.$table WHERE " +
                (1..16).joinToString(" AND ") { "c$it = 1" })
        }
        return WorkloadInput(queries,
            listOf(
                Profile("day", 0.9, mapOf("cheap" to 1000.0, "rare" to 1.0)),
                Profile("night", 0.1, mapOf("cheap" to 10.0, "rare" to 500.0)),
            ),
            Constraints(1_000_000, 100.0, maxIndexes = 5),
            tableWriteRates = listOf("cheap", "rare").associate {
                "public.$it" to TableWriteRates(1.0, 2.0, 0.0)
            }, candidateRanking = ranking)
    }

    private fun wideRareFixture(): Fixture {
        val input = wideRareInput(CandidateRanking.FREQUENCY)
        val resolved = ResolvedWorkload(input, input.queries.map { query ->
            val table = TableRef("public", query.id)
            val columns = (1..16).map { ColumnRef(table, "c$it") }
            ResolvedQuery(query.id, query.sql, setOf(table), columns.toSet(), columns.map {
                ResolvedPredicate(it, ComparisonOperator.EQUAL, PredicateSource.WHERE)
            }, emptyList(), selectsAllColumns = true)
        })
        val context = RunContext(clock = NanoClock { 0L })
        val generated = ProfileAwareCandidateGenerator().generate(resolved, wideCatalog(), context,
            EvaluationResult(emptyList(), mapOf("cheap" to 1.0, "rare" to 10000.0), mapOf(
                "cheap" to ExplainPlan("{}"), "rare" to ExplainPlan("{}"))))
        check(generated.candidates.size == 16) { "Wide fixture must produce the capped 16-candidate shortlist" }
        // Fixed verification pool: the first four generated candidates of each table, capped to 8.
        val cheapPool = generated.candidates.filter { it.definition.table.name == "cheap" }.take(4)
        val rarePool = generated.candidates.filter { it.definition.table.name == "rare" }.take(4)
        val pool = (cheapPool + rarePool).sortedBy { it.id }
        check(pool.size == 8) { "Wide fixture pool must hold four candidates per table" }
        return Fixture(
            input = input,
            pool = pool,
            resolvedQueries = resolved.queries,
            vectors = linkedMapOf(
                listOf("empty") to mapOf("cheap" to 1.0, "rare" to 10000.0),
                listOf("cheap_only") to mapOf("cheap" to 0.05, "rare" to 10000.0),
                listOf("rare_only") to mapOf("cheap" to 1.0, "rare" to 500.0),
                listOf("both") to mapOf("cheap" to 0.08, "rare" to 600.0),
            ),
            supportBased = true,
        )
    }

    private fun wideCatalog(): CatalogSnapshot = CatalogSnapshot(listOf("cheap", "rare").map { name ->
        CatalogTable(TableRef("public", name), (1..16).map {
            CatalogColumn("c$it", "int4", defaultOperatorClass = "pg_catalog.int4_ops")
        })
    })
}
