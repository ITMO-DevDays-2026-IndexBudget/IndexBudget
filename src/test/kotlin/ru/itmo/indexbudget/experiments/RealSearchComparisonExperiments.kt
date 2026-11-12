package ru.itmo.indexbudget.experiments

import ru.itmo.indexbudget.domain.CandidateBatch
import ru.itmo.indexbudget.domain.ColumnRef
import ru.itmo.indexbudget.domain.Constraints
import ru.itmo.indexbudget.domain.GenerationReason
import ru.itmo.indexbudget.domain.IndexCandidate
import ru.itmo.indexbudget.domain.IndexDefinition
import ru.itmo.indexbudget.domain.IndexKey
import ru.itmo.indexbudget.domain.MetricsCalculatorImpl
import ru.itmo.indexbudget.domain.Objective
import ru.itmo.indexbudget.domain.ObjectiveComparatorImpl
import ru.itmo.indexbudget.domain.Profile
import ru.itmo.indexbudget.domain.QueryInput
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.RunStatus
import ru.itmo.indexbudget.domain.SearchResult
import ru.itmo.indexbudget.domain.TableRef
import ru.itmo.indexbudget.domain.WorkloadInput
import ru.itmo.indexbudget.optimizer.GreedyOptimizer
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.JdbcAnalysisSession
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID

/**
 * Real PostgreSQL + HypoPG run of #104: greedy, greedy+repair and the exhaustive reference on
 * the same real EXPLAIN costs of one disposable database. Produces the machine-readable
 * real-run artifact (inputs, sets, M/R, counters, actual wall-clock duration and stop reasons)
 * that the acceptance criteria require; the synthetic summary cannot replace it.
 *
 * The connection targets an explicitly selected disposable database (INDEXBUDGET_HYPOPG_TEST_URL);
 * the shared stand is never touched, no physical indexes are created and ordinary `analyze`
 * behaviour is unchanged. Physical measurements happen only here.
 */
fun main(args: Array<String>) {
    require(args.size == 2 && args[0] == "--output-dir") { "Expected --output-dir NEW_PATH" }
    val url = System.getenv("INDEXBUDGET_HYPOPG_TEST_URL")
        ?: error("INDEXBUDGET_HYPOPG_TEST_URL must select a disposable PostgreSQL 16 + HypoPG database")
    RealSearchComparison().run(
        output = Path.of(args[1]),
        url = url,
        user = System.getenv("INDEXBUDGET_HYPOPG_TEST_USER") ?: "postgres",
        password = System.getenv("INDEXBUDGET_HYPOPG_TEST_PASSWORD") ?: "",
    )
}

class RealSearchComparison(
    private val verificationTimeout: Duration = Duration.ofMinutes(10),
) {
    private data class Outcome(
        val durationMillis: Long,
        val result: SearchResult?,
        val reference: ExhaustiveSearchResult?,
        val statistics: ru.itmo.indexbudget.domain.RunStatistics,
        val explainAttempts: Int,
    )

    private val metrics = MetricsCalculatorImpl()
    private val comparator = ObjectiveComparatorImpl()
    private val mapper = JsonMapper.builder().build()

    fun run(output: Path, url: String, user: String, password: String): Map<String, Any?> {
        require(!Files.exists(output)) { "Real-run destination must be new" }
        val schema = "searchcmp_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, user, password).use { admin ->
            execute(admin, "CREATE EXTENSION IF NOT EXISTS hypopg WITH SCHEMA public")
            execute(admin, "CREATE SCHEMA $schema")
            try {
                val columns = (1..10).map { "c" + it.toString().padStart(2, '0') }
                execute(admin, "CREATE TABLE $schema.wide (id integer PRIMARY KEY, " +
                    columns.joinToString(", ") { "$it integer" } + ", payload text)")
                execute(admin, "INSERT INTO $schema.wide SELECT i, " +
                    columns.mapIndexed { index, _ -> "(i * ${index + 1}) % 97" }.joinToString(", ") +
                    ", repeat('w', 80) FROM generate_series(1, 50000) i")
                // Fixture statistics are prepared separately from ordinary analysis.
                execute(admin, "ANALYZE $schema.wide")
                val indexesBefore = scalar(admin,
                    "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = '$schema'")

                val summary = DriverManager.getConnection(url, user, password).use { connection ->
                    JdbcAnalysisSession(connection).use { session -> measure(session, schema, columns) }
                }

                check(indexesBefore == scalar(admin,
                    "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = '$schema'")) {
                    "Hypothetical verification must not create physical indexes"
                }
                Files.createDirectories(output)
                Files.writeString(output.resolve("real-summary.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n")
                return summary
            } finally {
                execute(admin, "DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }

    private fun measure(session: JdbcAnalysisSession, schema: String, columns: List<String>): Map<String, Any?> {
        val input = workload(schema)
        val runs = linkedMapOf<String, Any?>()
        val baselines = mutableMapOf<String, Map<String, Double>>()
        for (objective in Objective.entries) {
            val objectiveInput = input.copy(objective = objective)
            fun freshContext() = RunContext().also { it.startSearch() }
            var context = freshContext()
            val resolved = resolve(objectiveInput, session, context)
            val baseline = session.evaluate(emptyList(), resolved, context)
            baselines[objective.name] = baseline.costByQueryId
            val pool = pool(schema, columns).map { candidate ->
                val sized = candidate.copy(estimatedBytes = session.estimateSize(candidate, context))
                val budget = metrics.assessBudget(listOf(sized), objectiveInput)
                check(budget.admissible) { "Real singletons must stay admissible" }
                sized.copy(budgetedBytes = budget.usage.budgetedBytes,
                    writeUnitsPerHour = budget.usage.writeUnitsPerHour)
            }

            fun timed(block: () -> Pair<SearchResult?, ExhaustiveSearchResult?>): Outcome {
                context = freshContext()
                val startedAt = System.nanoTime()
                val (result, reference) = block()
                val runStatistics = context.statistics()
                val statistics = runStatistics.let { stats ->
                    val referenceStats = reference?.statistics
                    if (referenceStats == null) stats else ru.itmo.indexbudget.domain.RunStatistics(
                        explainAttempts = stats.explainAttempts + referenceStats.explainAttempts,
                        evaluationAttempts = stats.evaluationAttempts + referenceStats.evaluationAttempts,
                        completedEvaluations = stats.completedEvaluations + referenceStats.completedEvaluations,
                        cacheHits = stats.cacheHits + referenceStats.cacheHits,
                        repairAttempts = stats.repairAttempts + referenceStats.repairAttempts,
                    )
                }
                return Outcome(
                    durationMillis = (System.nanoTime() - startedAt) / 1_000_000,
                    result = result,
                    reference = reference,
                    statistics = statistics,
                    // The reference spends its EXPLAIN attempts on its own verification context.
                    explainAttempts = reference?.statistics?.explainAttempts
                        ?: runStatistics.explainAttempts,
                )
            }

            val greedy = timed {
                GreedyOptimizer().search(resolved, CandidateBatch(pool), baseline, session,
                    metrics, comparator, context).result to null
            }
            val repair = timed {
                LimitedRepairOptimizer().optimize(resolved, CandidateBatch(pool), baseline, session,
                    metrics, comparator, context) to null
            }
            val exhaustive = timed {
                null to ExhaustiveSearchOptimizer(verificationTimeout = verificationTimeout)
                    .search(resolved, pool, baseline, session, metrics, comparator, context)
            }

            verify(objectiveInput, greedy.result, repair.result, exhaustive.reference)
            runs[objective.name.lowercase()] = linkedMapOf(
                "greedy" to runSummary(greedy.result, null, greedy, "greedy"),
                "greedy_repair" to runSummary(repair.result, null, repair, "greedy_repair"),
                "exhaustive" to runSummary(null, exhaustive.reference, exhaustive, "exhaustive"),
            )
        }
        return linkedMapOf(
            "schema_version" to 1,
            "experiment" to "search_strategy_comparison_real",
            "kind" to "real_postgresql_hypopg_run",
            "input" to linkedMapOf(
                "queries" to input.queries.map { mapOf("id" to it.id, "sql" to it.sql) },
                "profiles" to input.profiles.map { mapOf("id" to it.id, "weight" to it.weight,
                    "calls_per_hour" to it.callsPerHour) },
                "constraints" to mapOf("storage_budget_bytes" to input.constraints.storageBudgetBytes,
                    "write_budget_units_per_hour" to input.constraints.writeBudgetUnitsPerHour,
                    "max_indexes" to input.constraints.maxIndexes),
            ),
            "fixture" to linkedMapOf("rows" to 50_000, "columns" to columns, "analyzed" to true,
                "physical_indexes_created" to 0),
            "baseline_cost_by_query_id" to baselines.getValue("ROBUST"),
            "verification_timeout" to verificationTimeout.toString(),
            "runs" to runs,
            "limitations" to listOf(
                "Planner costs are deterministic for this fixture and PostgreSQL build; wall-clock durations are machine-dependent observations.",
                "Optimality claims are bounded to the fixed verified pool of this run.",
                "A disposable database is required; the shared stand is never touched.",
            ),
        )
    }

    /** Fail-fast honesty checks shared by the artifact and the integration test. */
    private fun verify(
        input: WorkloadInput,
        greedy: SearchResult?,
        repair: SearchResult?,
        reference: ExhaustiveSearchResult?,
    ) {
        val exhaustive = requireNotNull(reference)
        check(exhaustive.complete) { "The reference must complete its declared space" }
        check(exhaustive.searchSpaceSize == 36L) { "Expected C(8,1)+C(8,2)=36 subsets" }
        check(exhaustive.evaluatedSets == 36) { "The reference must evaluate every admissible subset" }
        check(exhaustive.budgetExcludedSets.isEmpty())
        for (result in listOf(requireNotNull(greedy), requireNotNull(repair))) {
            check(result.status == RunStatus.COMPLETED) { "Strategies must complete on the real fixture" }
            val selected = requireNotNull(result.selected)
            check(selected.evaluation.candidateIds.size <= input.constraints.maxIndexes)
            // Whatever a limited strategy picked was also evaluated by the reference with the
            // same real planner costs, and cannot beat a complete admissible enumeration of the
            // same pool under its own objective ordering.
            check(selected.evaluation.costByQueryId ==
                exhaustive.evaluations.getValue(selected.evaluation.candidateIds).costByQueryId) {
                "Real planner costs must agree between the strategy and the reference"
            }
            val optimum = exhaustive.selected(input.objective)
            check(comparator.compare(optimum, selected, input.objective) <= 0) {
                "A complete enumeration cannot lose to a limited strategy under the objective ordering"
            }
        }
    }

    private fun runSummary(
        result: SearchResult?,
        reference: ExhaustiveSearchResult?,
        outcome: Outcome,
        strategy: String,
    ): Map<String, Any?> {
        val selected = result?.selected ?: reference!!.selected(reference.objective)
        val metricsOfSelected = selected.metrics
        return linkedMapOf(
            "strategy" to strategy,
            "selected_ids" to selected.evaluation.candidateIds,
            "selected_cost_by_query_id" to selected.evaluation.costByQueryId,
            "mean_cost" to metricsOfSelected.meanCost,
            "baseline_mean_cost" to metricsOfSelected.baselineMeanCost,
            "mean_ratio" to metricsOfSelected.meanRatio,
            "worst_ratio" to metricsOfSelected.worstRatio,
            "selected_budgeted_bytes" to metricsOfSelected.budgetUsage.budgetedBytes,
            "status" to (result?.status?.name?.lowercase() ?: "reference"),
            "stop_reasons" to (result?.diagnostics?.stopReasons?.map { it.name } ?: emptyList()),
            "statistics" to mapOf(
                "actual_explain_attempts" to outcome.explainAttempts,
                "evaluation_attempts" to outcome.statistics.evaluationAttempts,
                "completed_evaluations" to outcome.statistics.completedEvaluations,
                "cache_hits" to outcome.statistics.cacheHits,
                "repair_attempts" to outcome.statistics.repairAttempts,
            ),
            "duration_millis" to outcome.durationMillis,
            "duration_source" to "Measured wall clock of this real run",
            "exhaustive" to reference?.let {
                linkedMapOf(
                    "complete" to it.complete,
                    "verified_optimum" to it.verifiedOptimum,
                    "stop_reason" to it.stopReason,
                    "search_space_subsets" to it.searchSpaceSize,
                    "evaluated_sets" to it.evaluatedSets,
                    "budget_excluded_sets" to it.budgetExcludedSets,
                    "rejected_sets" to it.rejectedEvaluations.keys.toList(),
                )
            },
        )
    }

    private fun workload(schema: String): WorkloadInput = WorkloadInput(
        listOf(
            QueryInput("hot_c01", "SELECT payload FROM $schema.wide WHERE c01 = 42"),
            QueryInput("hot_c02", "SELECT payload FROM $schema.wide WHERE c02 = 42"),
            QueryInput("rare_c03", "SELECT payload FROM $schema.wide WHERE c03 = 42"),
        ),
        listOf(
            Profile("day", 0.9, mapOf("hot_c01" to 1000.0, "hot_c02" to 100.0, "rare_c03" to 1.0)),
            Profile("night", 0.1, mapOf("hot_c01" to 10.0, "hot_c02" to 100.0, "rare_c03" to 200.0)),
        ),
        Constraints(Long.MAX_VALUE, null, maxIndexes = 2),
    )

    /** Fixed verification pool: eight real single-column candidates, five never used by the queries. */
    private fun pool(schema: String, columns: List<String>): List<IndexCandidate> =
        columns.take(8).map { column ->
            val table = TableRef(schema, "wide")
            IndexCandidate("wide_$column",
                IndexDefinition(table, listOf(IndexKey(ColumnRef(table, column),
                    operatorClass = "pg_catalog.int4_ops"))),
                emptySet(), setOf(GenerationReason.SINGLE_COLUMN))
        }

    private fun resolve(input: WorkloadInput, session: JdbcAnalysisSession, context: RunContext): ResolvedWorkload {
        val analyzer = JSqlParserSqlAnalyzer()
        val parsed = analyzer.parse(input, context)
        return analyzer.resolve(parsed, session.readCatalog(parsed.referencedTables, context), context)
    }

    private fun execute(connection: Connection, statement: String) =
        connection.createStatement().use { it.execute(statement) }

    private fun scalar(connection: Connection, statement: String): Int =
        connection.createStatement().use { handle ->
            handle.executeQuery(statement).use { rows -> check(rows.next()); rows.getInt(1) }
        }
}
