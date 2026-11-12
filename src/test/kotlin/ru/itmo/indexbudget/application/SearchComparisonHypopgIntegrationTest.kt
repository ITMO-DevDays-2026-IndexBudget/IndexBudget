package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import ru.itmo.indexbudget.domain.CandidateBatch
import ru.itmo.indexbudget.domain.ColumnRef
import ru.itmo.indexbudget.domain.Constraints
import ru.itmo.indexbudget.domain.GenerationReason
import ru.itmo.indexbudget.domain.IndexCandidate
import ru.itmo.indexbudget.domain.IndexDefinition
import ru.itmo.indexbudget.domain.IndexKey
import ru.itmo.indexbudget.domain.MetricsCalculatorImpl
import ru.itmo.indexbudget.domain.NanoClock
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
import ru.itmo.indexbudget.experiments.ExhaustiveSearchOptimizer
import ru.itmo.indexbudget.experiments.ExhaustiveSearchResult
import ru.itmo.indexbudget.optimizer.GreedyOptimizer
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.JdbcAnalysisSession
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID

/**
 * Real PostgreSQL + HypoPG example for #104: greedy, greedy+repair and the verification-only
 * exhaustive reference run on the same real EXPLAIN costs of one disposable database with a
 * wide table and a rare profile. Every physical measurement happens only here on an explicitly
 * selected disposable DB; the shared stand is never touched and ordinary `analyze` is unchanged.
 */
@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_HYPOPG_TEST_URL", matches = ".+")
class SearchComparisonHypopgIntegrationTest {
    private val metrics = MetricsCalculatorImpl()
    private val comparator = ObjectiveComparatorImpl()

    @Test
    fun `strategies share real costs and the exhaustive reference verifies its whole space`() {
        connect().use { admin ->
            val schema = "searchcmp_" + UUID.randomUUID().toString().replace("-", "")
            execute(admin, "CREATE EXTENSION IF NOT EXISTS hypopg WITH SCHEMA public")
            execute(admin, "CREATE SCHEMA $schema")
            try {
                val columns = (1..10).map { "c" + it.toString().padStart(2, '0') }
                execute(admin, "CREATE TABLE $schema.wide (id integer PRIMARY KEY, " +
                    columns.joinToString(", ") { "$it integer" } + ", payload text)")
                execute(admin, "INSERT INTO $schema.wide SELECT i, " +
                    columns.mapIndexed { index, _ -> "(i * ${index + 1}) % 97" }
                        .joinToString(", ") + ", repeat('w', 80) " +
                    "FROM generate_series(1, 50000) i")
                // Fixture statistics are prepared separately from ordinary analysis.
                execute(admin, "ANALYZE $schema.wide")
                val indexesBefore = scalar(admin,
                    "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = '$schema'")

                DriverManager.getConnection(
                    System.getenv("INDEXBUDGET_HYPOPG_TEST_URL"),
                    System.getenv("INDEXBUDGET_HYPOPG_TEST_USER"),
                    System.getenv("INDEXBUDGET_HYPOPG_TEST_PASSWORD"),
                ).use { connection ->
                    JdbcAnalysisSession(connection).use { session ->
                        val input = workload(schema, columns)
                        val baselines = mutableMapOf<String, Map<String, Double>>()
                        val greedy = mutableMapOf<Objective, SearchResult>()
                        val repair = mutableMapOf<Objective, SearchResult>()
                        val reference = mutableMapOf<Objective, ExhaustiveSearchResult>()
                        val explainAttempts = mutableMapOf<String, Int>()

                        for (objective in Objective.entries) {
                            val objectiveInput = input.copy(objective = objective)
                            fun freshContext() = RunContext(clock = NanoClock { 0L })
                                .also { it.startSearch() }
                            var context = freshContext()
                            val resolved = resolve(objectiveInput, session, context)
                            val baseline = session.evaluate(emptyList(), resolved, context)
                            baselines[objective.name] = baseline.costByQueryId
                            fun sizedPool(context: RunContext): List<IndexCandidate> =
                                pool(schema, columns).map { candidate ->
                                    val sized = candidate.copy(
                                        estimatedBytes = session.estimateSize(candidate, context))
                                    val budget = metrics.assessBudget(listOf(sized), objectiveInput)
                                    check(budget.admissible) { "Real singletons must stay admissible" }
                                    sized.copy(budgetedBytes = budget.usage.budgetedBytes,
                                        writeUnitsPerHour = budget.usage.writeUnitsPerHour)
                                }

                            context = freshContext()
                            greedy[objective] = GreedyOptimizer().search(resolved,
                                CandidateBatch(sizedPool(context)), baseline, session, metrics,
                                comparator, context).result
                            explainAttempts["greedy/${objective.name}"] = context.statistics().explainAttempts

                            context = freshContext()
                            repair[objective] = LimitedRepairOptimizer().optimize(resolved,
                                CandidateBatch(sizedPool(context)), baseline, session, metrics,
                                comparator, context)
                            explainAttempts["greedy_repair/${objective.name}"] = context.statistics().explainAttempts

                            context = freshContext()
                            reference[objective] = ExhaustiveSearchOptimizer(
                                verificationTimeout = Duration.ofMinutes(10)).search(resolved,
                                sizedPool(context), baseline, session, metrics, comparator, context)
                            explainAttempts["exhaustive/${objective.name}"] = context.statistics().explainAttempts
                        }

                        verify(input, baselines, greedy, repair, reference, explainAttempts)
                        assertEquals(indexesBefore, scalar(admin,
                            "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = '$schema'"),
                            "Hypothetical verification must not create physical indexes")
                    }
                }
            } finally {
                execute(admin, "DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }

    private fun verify(
        input: WorkloadInput,
        baselines: Map<String, Map<String, Double>>,
        greedy: Map<Objective, SearchResult>,
        repair: Map<Objective, SearchResult>,
        reference: Map<Objective, ExhaustiveSearchResult>,
        explainAttempts: Map<String, Int>,
    ) {
        // One real baseline per objective; every run sees exactly the same costs.
        assertEquals(baselines.getValue("ROBUST"), baselines.getValue("MEAN"))
        for (objective in Objective.entries) {
            val exhaustive = reference.getValue(objective)
            val greedyResult = greedy.getValue(objective)
            val repairResult = repair.getValue(objective)

            // The reference completes its declared space: C(8,1) + C(8,2) = 36 subsets. Its run
            // context sees exactly those evaluations; the shared baseline is evaluated separately.
            assertTrue(exhaustive.complete, "${objective.name}: reference must complete")
            assertEquals(36L, exhaustive.searchSpaceSize)
            assertEquals(36, exhaustive.evaluatedSets)
            assertTrue(exhaustive.budgetExcludedSets.isEmpty())
            assertEquals(36 * 3, explainAttempts.getValue("exhaustive/${objective.name}"))

            assertEquals(RunStatus.COMPLETED, greedyResult.status)
            assertEquals(RunStatus.COMPLETED, repairResult.status)
            for (result in listOf(greedyResult, repairResult)) {
                val selected = requireNotNull(result.selected)
                assertTrue(selected.evaluation.candidateIds.size <= input.constraints.maxIndexes)
                // Whatever a limited strategy picked was also evaluated by the reference,
                // with exactly the same real planner costs.
                assertEquals(selected.evaluation.costByQueryId,
                    exhaustive.evaluations.getValue(selected.evaluation.candidateIds).costByQueryId)
                // No strategy can beat a complete admissible enumeration of the same pool on
                // its own objective: compare the primary metric of that objective.
                val optimum = exhaustive.selected(objective)
                if (objective == Objective.ROBUST) {
                    assertTrue(optimum.metrics.worstRatio!! <= selected.metrics.worstRatio!! + 1e-12)
                } else {
                    assertTrue(optimum.metrics.meanRatio!! <= selected.metrics.meanRatio!! + 1e-12)
                }
                // The rare profile participates in every saved metric set.
                assertEquals(2, selected.metrics.profiles.size)
            }
            assertTrue(explainAttempts.getValue("greedy/${objective.name}") <= 36 * 3)
            assertTrue(explainAttempts.getValue("greedy_repair/${objective.name}") <= 36 * 3)
        }
    }

    private fun workload(schema: String, columns: List<String>): WorkloadInput {
        val queries = listOf(
            QueryInput("hot_c01", "SELECT payload FROM $schema.wide WHERE c01 = 42"),
            QueryInput("hot_c02", "SELECT payload FROM $schema.wide WHERE c02 = 42"),
            QueryInput("rare_c03", "SELECT payload FROM $schema.wide WHERE c03 = 42"),
        )
        return WorkloadInput(queries,
            listOf(
                Profile("day", 0.9, mapOf("hot_c01" to 1000.0, "hot_c02" to 100.0, "rare_c03" to 1.0)),
                Profile("night", 0.1, mapOf("hot_c01" to 10.0, "hot_c02" to 100.0, "rare_c03" to 200.0)),
            ),
            Constraints(Long.MAX_VALUE, null, maxIndexes = 2))
    }

    /** Fixed verification pool: eight real single-column candidates, including five the queries never filter on. */
    private fun pool(schema: String, columns: List<String>): List<IndexCandidate> =
        columns.take(8).map { column ->
            val table = TableRef(schema, "wide")
            IndexCandidate("wide_$column",
                IndexDefinition(table, listOf(IndexKey(ColumnRef(table, column),
                    operatorClass = "pg_catalog.int4_ops"))),
                emptySet(), setOf(GenerationReason.SINGLE_COLUMN))
        }

    private fun resolve(
        input: WorkloadInput,
        session: JdbcAnalysisSession,
        context: RunContext,
    ): ResolvedWorkload {
        val analyzer = JSqlParserSqlAnalyzer()
        val parsed = analyzer.parse(input, context)
        return analyzer.resolve(parsed, session.readCatalog(parsed.referencedTables, context), context)
    }

    private fun connect(): Connection = DriverManager.getConnection(
        System.getenv("INDEXBUDGET_HYPOPG_TEST_URL"), System.getenv("INDEXBUDGET_HYPOPG_TEST_USER"),
        System.getenv("INDEXBUDGET_HYPOPG_TEST_PASSWORD"),
    )

    private fun execute(connection: Connection, statement: String) =
        connection.createStatement().use { it.execute(statement) }

    private fun scalar(connection: Connection, statement: String): Int =
        connection.createStatement().use { statementHandle ->
            statementHandle.executeQuery(statement).use { result ->
                check(result.next())
                result.getInt(1)
            }
        }
}
