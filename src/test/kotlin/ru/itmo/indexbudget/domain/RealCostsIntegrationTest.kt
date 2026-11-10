package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import ru.itmo.indexbudget.application.ResolvedWorkloadValidator
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.candidates.BtreeCandidateCanonicalizer
import ru.itmo.indexbudget.candidates.PostgresDdlRenderer
import ru.itmo.indexbudget.postgres.JdbcCatalogReader
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.security.MessageDigest
import java.util.HexFormat

@EnabledIfEnvironmentVariable(named = "INDEXBUDGET_METRICS_TEST_URL", matches = ".+")
class RealCostsIntegrationTest {
    private val mapper = JsonMapper.builder().build()
    private val calculator = MetricsCalculatorImpl()
    private val comparator = ObjectiveComparatorImpl()
    private val precision = MathContext.DECIMAL128

    @Test
    @Timeout(180)
    fun `real shared cost vectors agree with independent arithmetic and budget decisions`() {
        val context = RunContext()
        val input = WorkloadReader().read(Path.of("demo/metrics/workload.json"), context)
        val analyzer = JSqlParserSqlAnalyzer()
        val parsed = analyzer.parse(input, context)
        DriverManager.getConnection(
            System.getenv("INDEXBUDGET_METRICS_TEST_URL"),
            "indexbudget_reader", System.getenv("INDEXBUDGET_METRICS_TEST_PASSWORD"),
        ).use { connection ->
            execute(connection, "SET statement_timeout = '2s'")
            assertEquals("16", scalar(connection, "SHOW server_version").substringBefore('.'))
            assertEquals("1.4.2", scalar(connection, "SELECT extversion FROM pg_extension WHERE extname = 'hypopg'"))
            reset(connection)
            assertEquals("on", scalar(connection, "SHOW hypopg.enabled"))
            val planner = listOf("enable_seqscan", "random_page_cost", "seq_page_cost", "effective_cache_size", "search_path")
                .associateWith { scalar(connection, "SHOW $it") }
            val realIndexes = scalar(connection, "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'")
            val catalog = JdbcCatalogReader(connection).readCatalog(parsed.referencedTables, context)
            val resolved = analyzer.resolve(parsed, catalog, context)
            ResolvedWorkloadValidator().validate(resolved, context)
            val canonicalizer = BtreeCandidateCanonicalizer(catalog, context)
            val candidates = listOf(
                candidate("group", "records", listOf("group_id", "created_at"), "by_group"),
                candidate("value", "records", listOf("value", "created_at"), "by_value"),
                candidate("category", "groups", listOf("category"), "groups"),
            ).map { candidate ->
                val definition = canonicalizer.canonicalize(candidate.definition)
                candidate.copy(definition = definition, ddl = PostgresDdlRenderer().render(definition))
            }.map { candidate ->
                reset(connection)
                try {
                    val oid = create(connection, candidate)
                    candidate.copy(estimatedBytes = scalar(connection, "SELECT hypopg_relation_size($oid)").toLong())
                } finally {
                    reset(connection)
                }
            }
            val sets = listOf(emptyList(), listOf(candidates[0]), listOf(candidates[1]), listOf(candidates[2]),
                listOf(candidates[0], candidates[1]), listOf(candidates[0], candidates[2]), listOf(candidates[1], candidates[2]))
            var explains = 0
            val evaluations = sets.map { selected ->
                reset(connection)
                try {
                    selected.forEach { create(connection, it) }
                    val plans = input.queries.sortedBy { it.id }.associate { query ->
                        explains++
                        query.id to scalar(connection, "EXPLAIN (FORMAT JSON) ${query.sql}")
                    }
                    EvaluationResult(selected.map { it.id }.sorted(), plans.mapValues { (_, plan) ->
                        mapper.readTree(plan).get(0).get("Plan").get("Total Cost").doubleValue()
                    }, plans.mapValues { ExplainPlan(it.value) }).requireQueryCoverage(input.queries.map { it.id }.toSet())
                } finally {
                    reset(connection)
                }
            }
            val baseline = evaluations.first()
            val configurations = evaluations.zip(sets).map { (evaluation, selected) ->
                val budget = calculator.assessBudget(selected, input)
                val expectedBytes = selected.sumOf {
                    BigDecimal(requireNotNull(it.estimatedBytes)).multiply(BigDecimal("1.2")).setScale(0, RoundingMode.CEILING).longValueExact()
                }
                val expectedWrites = selected.sumOf { candidate ->
                    val rates = requireNotNull(input.tableWriteRates).getValue("public.${candidate.definition.table.name}")
                    (rates.insertsPerHour + rates.updatesPerHour + rates.deletesPerHour) * candidate.definition.keys.size
                }
                assertEquals(expectedBytes, budget.usage.budgetedBytes)
                assertEquals(expectedWrites, budget.usage.writeUnitsPerHour)
                assertTrue(budget.admissible)
                if (selected.isNotEmpty()) {
                    assertTrue(calculator.assessBudget(selected, input.copy(constraints =
                        input.constraints.copy(storageBudgetBytes = expectedBytes, writeBudgetUnitsPerHour = expectedWrites))).admissible)
                    assertEquals(setOf(BudgetViolation.STORAGE_BUDGET), calculator.assessBudget(selected,
                        input.copy(constraints = input.constraints.copy(storageBudgetBytes = expectedBytes - 1))).violations)
                    assertEquals(setOf(BudgetViolation.WRITE_BUDGET), calculator.assessBudget(selected,
                        input.copy(constraints = input.constraints.copy(writeBudgetUnitsPerHour = expectedWrites - 1))).violations)
                }
                val metrics = calculator.calculate(input, baseline, evaluation, budget.usage)
                verifyMetrics(input, baseline, evaluation, metrics)
                EvaluatedConfiguration(evaluation, metrics)
            }
            for (objective in Objective.entries) {
                for (left in configurations) for (right in configurations) {
                    assertEquals(independentCompare(left, right, objective).sign(), comparator.compare(left, right, objective).sign())
                }
            }
            val robust = configurations.minWith { left, right -> comparator.compare(left, right, Objective.ROBUST) }
            val mean = configurations.minWith { left, right -> comparator.compare(left, right, Objective.MEAN) }
            assertTrue(robust.evaluation.candidateIds.isNotEmpty(), "Prepared workload must have an improving set")
            val budgetChecks = listOf(
                input.constraints.copy(storageBudgetBytes = 1),
                input.constraints.copy(writeBudgetUnitsPerHour = 1.0),
            ).map { constraints ->
                val changed = input.copy(constraints = constraints)
                val allowed = sets.zip(configurations).filter { (selected, configuration) ->
                    val expected = configuration.metrics.budgetUsage.budgetedBytes <= constraints.storageBudgetBytes &&
                        requireNotNull(configuration.metrics.budgetUsage.writeUnitsPerHour) <= requireNotNull(constraints.writeBudgetUnitsPerHour)
                    assertEquals(expected, calculator.assessBudget(selected, changed).admissible)
                    expected
                }.map { it.second }
                assertEquals(listOf(emptyList<String>()), allowed.map { it.evaluation.candidateIds })
                mapOf("constraints" to constraints, "allowed_sets" to allowed.map { it.evaluation.candidateIds })
            }
            val sensitivity = input.copy(profiles = listOf(Profile("held_out_balanced", 1.0,
                mapOf("by_group" to 1000.0, "by_value" to 1000.0, "groups" to 500.0))))
            val heldOut = configurations.map {
                val metrics = calculator.calculate(sensitivity, baseline, it.evaluation, it.metrics.budgetUsage)
                verifyMetrics(sensitivity, baseline, it.evaluation, metrics)
                mapOf("candidate_ids" to it.evaluation.candidateIds, "metrics" to metrics)
            }
            assertEquals(sets.size * input.queries.size, explains)
            assertEquals("0", scalar(connection, "SELECT count(*) FROM hypopg_list_indexes"))
            assertEquals(realIndexes, scalar(connection, "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'"))
            planner.forEach { (setting, value) -> assertEquals(value, scalar(connection, "SHOW $setting")) }
            val evidence = mapOf(
                "scope" to "fixed_test_pool_not_optimizer", "seed" to "demo/postgres/init/001-seed.sql",
                "seed_sha256" to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(Path.of("demo/postgres/init/001-seed.sql")))),
                "java" to System.getProperty("java.version"),
                "input_hash" to context.inputHash, "workload" to input,
                "postgres" to scalar(connection, "SHOW server_version"), "hypopg" to "1.4.2",
                "planner_settings" to planner, "candidates" to candidates, "configurations" to configurations,
                "explain_attempts" to explains, "best_robust" to robust.evaluation.candidateIds,
                "best_mean" to mean.evaluation.candidateIds,
                "expected_price_of_robustness" to (robust.metrics.meanCost - mean.metrics.meanCost),
                "budget_checks" to budgetChecks, "held_out" to heldOut,
                "independent_metrics" to configurations.map {
                    mapOf("candidate_ids" to it.evaluation.candidateIds,
                        "expected" to verifyMetrics(input, baseline, it.evaluation, it.metrics))
                },
                "unverified" to listOf("production evaluator", "optimizer", "price helper #44", "JSON/HTML/SQL export"),
            )
            val output = Path.of("build/metrics-verification/real-costs.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence))
        }
    }

    private fun verifyMetrics(input: WorkloadInput, baseline: EvaluationResult, evaluation: EvaluationResult, actual: SetMetrics): Map<String, Any> {
        fun weightedCost(profile: Profile, costs: Map<String, Double>): BigDecimal = input.queries.fold(BigDecimal.ZERO) { sum, query ->
            sum + BigDecimal.valueOf(profile.callsPerHour.getValue(query.id)) * BigDecimal.valueOf(costs.getValue(query.id))
        }
        val totalWeight = input.profiles.fold(BigDecimal.ZERO) { sum, profile -> sum + BigDecimal.valueOf(profile.weight) }
        var baselineMean = BigDecimal.ZERO
        var mean = BigDecimal.ZERO
        val ratios = input.profiles.map { profile ->
            val before = weightedCost(profile, baseline.costByQueryId)
            val after = weightedCost(profile, evaluation.costByQueryId)
            val probability = BigDecimal.valueOf(profile.weight).divide(totalWeight, precision)
            baselineMean += probability * before
            mean += probability * after
            val row = actual.profiles.single { it.profileId == profile.id }
            close(before.toDouble(), row.baselineCost)
            close(after.toDouble(), row.cost)
            close(probability.toDouble(), row.normalizedWeight)
            close((before - after).toDouble(), row.gain)
            val ratio = after.divide(before, precision).toDouble()
            close(ratio, requireNotNull(row.ratio))
            close((before - after).multiply(BigDecimal(100)).divide(before, precision).toDouble(), requireNotNull(row.gainPercent))
            ratio
        }
        close(baselineMean.toDouble(), actual.baselineMeanCost)
        close(mean.toDouble(), actual.meanCost)
        close(mean.divide(baselineMean, precision).toDouble(), requireNotNull(actual.meanRatio))
        close(ratios.max(), requireNotNull(actual.worstRatio))
        return mapOf("baseline_mean" to baselineMean, "mean" to mean,
            "mean_ratio" to mean.divide(baselineMean, precision), "worst_ratio" to ratios.max())
    }

    private fun independentCompare(left: EvaluatedConfiguration, right: EvaluatedConfiguration, objective: Objective): Int {
        fun key(configuration: EvaluatedConfiguration): List<BigDecimal> {
            val metrics = configuration.metrics
            val ratios = if (objective == Objective.ROBUST) listOf(metrics.worstRatio, metrics.meanRatio)
                else listOf(metrics.meanRatio, metrics.worstRatio)
            return ratios.map { BigDecimal.valueOf(requireNotNull(it)).setScale(9, RoundingMode.HALF_EVEN) } +
                listOf(BigDecimal(metrics.budgetUsage.budgetedBytes), BigDecimal.valueOf(requireNotNull(metrics.budgetUsage.writeUnitsPerHour)))
        }
        key(left).zip(key(right)).forEach { (first, second) ->
            val order = first.compareTo(second)
            if (order != 0) return order
        }
        val leftIds = left.evaluation.candidateIds
        val rightIds = right.evaluation.candidateIds
        leftIds.zip(rightIds).forEach { (first, second) ->
            val order = first.compareTo(second)
            if (order != 0) return order
        }
        return leftIds.size.compareTo(rightIds.size)
    }

    private fun Int.sign() = compareTo(0)
    private fun close(expected: Double, actual: Double) = assertEquals(expected, actual, maxOf(1e-9, kotlin.math.abs(expected) * 1e-12))

    private fun candidate(id: String, table: String, columns: List<String>, query: String): IndexCandidate {
        val ref = TableRef("public", table)
        return IndexCandidate(id, IndexDefinition(ref, columns.map { name ->
            IndexKey(ColumnRef(ref, name), if (name == "created_at") SortDirection.DESC else SortDirection.ASC)
        }), setOf(query), setOf(if (columns.size == 1) GenerationReason.SINGLE_COLUMN else GenerationReason.EQUALITY_AND_ORDER))
    }

    private fun create(connection: Connection, candidate: IndexCandidate): Long =
        connection.prepareStatement("SELECT indexrelid FROM hypopg_create_index(?)").use { statement ->
            statement.setString(1, candidate.ddl)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getLong(1) }
        }

    private fun reset(connection: Connection) = execute(connection, "SELECT hypopg_reset()")
    private fun execute(connection: Connection, sql: String) = connection.createStatement().use { it.execute(sql) }
    private fun scalar(connection: Connection, sql: String): String = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> assertTrue(rows.next()); rows.getString(1) }
    }
}
