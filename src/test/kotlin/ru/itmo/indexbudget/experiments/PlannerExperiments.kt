package ru.itmo.indexbudget.experiments

import ru.itmo.indexbudget.application.AnalysisPipeline
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.candidates.CandidateGenerator
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.GreedyOptimizer
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.JdbcAnalysisSessionFactory
import ru.itmo.indexbudget.report.*
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.system.exitProcess

/** Проверочный запуск, намеренно отсутствующий в production CLI. */
fun main(args: Array<String>) {
    try {
        require(args.size == 6 && args[0] == "--workload" && args[2] == "--sensitivity" && args[4] == "--output-dir") {
            "Expected --workload PATH --sensitivity PATH --output-dir NEW_PATH"
        }
        PlannerExperiments().run(Path.of(args[1]), Path.of(args[3]), Path.of(args[5]))
    } catch (error: AnalysisException) {
        System.err.println("Experiment failed: ${error.error.code}")
        exitProcess(1)
    } catch (_: Exception) {
        // Paths, SQL and driver exceptions may contain private connection information.
        System.err.println("Experiment failed; check input, empty destination and database availability.")
        exitProcess(1)
    }
}

class PlannerExperiments {
    private val mapper = JsonMapper.builder().build()
    private val metrics = MetricsCalculatorImpl()
    private val comparator = ObjectiveComparatorImpl()

    fun run(workloadPath: Path, sensitivityPath: Path, output: Path) {
        val source = WorkloadReader().read(workloadPath)
        val sensitivity = WorkloadReader().read(sensitivityPath)
        require(source.queries == sensitivity.queries && source.constraints == sensitivity.constraints &&
            source.tableWriteRates == sensitivity.tableWriteRates) { "Holdout may change profiles only" }
        require(!Files.exists(output)) { "Experiment destination must be new" }
        Files.createDirectories(output)
        Files.copy(workloadPath, output.resolve("source-workload.json"))
        Files.copy(sensitivityPath, output.resolve("sensitivity-workload.json"))
        var frozen: CandidateBatch? = null
        val generator = object : CandidateGenerator {
            override fun generate(workload: ResolvedWorkload, catalog: CatalogSnapshot, context: RunContext): CandidateBatch {
                context.checkDeadline()
                return frozen ?: ProfileAwareCandidateGenerator().generate(workload, catalog, context).also { frozen = it }
            }
        }
        val variants = listOf(
            Variant("robust-repair", source.copy(objective = Objective.ROBUST)),
            Variant("mean-repair", source.copy(objective = Objective.MEAN)),
            Variant("robust-greedy", source.copy(objective = Objective.ROBUST), greedy = true),
            Variant("storage-tight", source.copy(objective = Objective.ROBUST,
                constraints = source.constraints.copy(storageBudgetBytes = 1048576))),
            Variant("write-tight", source.copy(objective = Objective.ROBUST,
                constraints = source.constraints.copy(writeBudgetUnitsPerHour = 1000.0))),
            Variant("write-disabled", source.copy(objective = Objective.ROBUST,
                constraints = source.constraints.copy(writeBudgetUnitsPerHour = null))),
            Variant("write-zero", source.copy(objective = Objective.ROBUST,
                constraints = source.constraints.copy(writeBudgetUnitsPerHour = 0.0))),
            Variant("baseline-only", source.copy(objective = Objective.ROBUST,
                constraints = source.constraints.copy(maxIndexes = 0))),
            Variant("write-profile-absent", source.copy(objective = Objective.ROBUST, tableWriteRates = null,
                constraints = source.constraints.copy(writeBudgetUnitsPerHour = null))),
        )
        val summaries = linkedMapOf<String, Map<String, Any?>>()
        val reports = linkedMapOf<String, AnalysisReport>()
        val manifest = linkedMapOf<String, Any?>(
            "schema_version" to 1,
            "expected_seed" to "demo v1; verify with demo/check.sh before experiments",
            "source_sha256" to sha256(Files.readAllBytes(workloadPath)),
            "holdout_sha256" to sha256(Files.readAllBytes(sensitivityPath)),
            "candidate_pool_policy" to "First production shortlist frozen by stable ID for every non-baseline variant",
            "run_order" to variants.map { it.name },
            "comparisons" to mapOf("objective" to listOf("robust-repair", "mean-repair"),
                "strategy" to listOf("robust-greedy", "robust-repair")),
            "limitations" to listOf("Planner cost is not elapsed time; write units are a proxy.",
                "Separate runs can observe database/statistics changes; baseline equality is reported.",
                "No global optimum or speedup is claimed. Partial runs remain partial.",
                "Holdout profiles are evaluated after selection and never enter candidate generation or search."),
        )
        for (variant in variants) {
            val inputFile = output.resolve("${variant.name}-input.json")
            write(inputFile, inputWire(variant.input))
            val context = RunContext()
            val input = WorkloadReader().read(inputFile, context)
            var report: AnalysisReport? = null
            val exporter = ReportExporter { value, path, run ->
                AtomicReportExporter(HtmlReportRenderer()).export(value, path, run)
                report = value
            }
            AnalysisPipeline(JSqlParserSqlAnalyzer(), JdbcAnalysisSessionFactory(), generator, metrics, comparator,
                if (variant.greedy) GreedyOptimizer() else LimitedRepairOptimizer(), DefaultReportBuilder(metrics),
                exporter).run(input, output.resolve(variant.name), context)
            val saved = checkNotNull(report)
            reports[variant.name] = saved
            summaries[variant.name] = summary(saved, sensitivity)
            // Keep an honest manifest if a later run fails or is interrupted.
            manifest["runs"] = summaries
            manifest["frozen_candidate_ids"] = frozen?.candidates?.map { it.id }?.sorted()
            Files.writeString(output.resolve("summary.json"), mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(manifest) + "\n")
            check(saved.result.status != RunStatus.FAILED) { "Experiment run failed; saved report contains diagnostics" }
        }
        val reference = reports.getValue("robust-repair")
        manifest["comparison_checks"] = mapOf(
            "objective_baselines_equal" to (reference.result.baseline?.costByQueryId ==
                reports.getValue("mean-repair").result.baseline?.costByQueryId),
            "strategy_baselines_equal" to (reference.result.baseline?.costByQueryId ==
                reports.getValue("robust-greedy").result.baseline?.costByQueryId),
            "objective_selected_sets_equal" to (reference.result.selected?.evaluation?.candidateIds ==
                reports.getValue("mean-repair").result.selected?.evaluation?.candidateIds),
            "repair_selected_set_equal_to_greedy" to (reference.result.selected?.evaluation?.candidateIds ==
                reports.getValue("robust-greedy").result.selected?.evaluation?.candidateIds),
            "storage_changed_recommendation" to (reference.result.selected?.evaluation?.candidateIds !=
                reports.getValue("storage-tight").result.selected?.evaluation?.candidateIds),
            "write_changed_recommendation" to (reference.result.selected?.evaluation?.candidateIds !=
                reports.getValue("write-tight").result.selected?.evaluation?.candidateIds),
        )
        Files.writeString(output.resolve("summary.json"), mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(manifest) + "\n")
    }

    private fun summary(report: AnalysisReport, holdout: WorkloadInput): Map<String, Any?> {
        val result = report.result
        val selected = result.selected
        val baseline = result.baseline
        val holdoutMetrics = if (selected != null && baseline != null) metrics.calculate(holdout, baseline,
            selected.evaluation, selected.metrics.budgetUsage) else null
        return linkedMapOf(
            "status" to result.status.name.lowercase(), "stop_reasons" to result.diagnostics.stopReasons.map { it.name },
            "duration_millis" to report.durationMillis, "explain_attempts" to result.diagnostics.statistics.explainAttempts,
            "evaluated_sets" to result.diagnostics.statistics.completedEvaluations,
            "repair_attempts" to result.diagnostics.statistics.repairAttempts,
            "selected_ids" to selected?.evaluation?.candidateIds,
            "candidate_ids" to report.candidates.candidates.map { it.id }.sorted(),
            "mean_ratio" to selected?.metrics?.meanRatio, "worst_ratio" to selected?.metrics?.worstRatio,
            "estimated_bytes" to selected?.metrics?.budgetUsage?.estimatedBytes,
            "budgeted_bytes" to selected?.metrics?.budgetUsage?.budgetedBytes,
            "write_units_per_hour" to selected?.metrics?.budgetUsage?.writeUnitsPerHour,
            "holdout_mean_ratio" to holdoutMetrics?.meanRatio, "holdout_worst_ratio" to holdoutMetrics?.worstRatio,
            "environment" to mapOf("application" to report.environment.applicationVersion,
                "java" to report.environment.javaVersion, "kotlin" to report.environment.kotlinVersion,
                "postgres" to report.environment.postgresVersion, "hypopg" to report.environment.hypopgVersion),
        )
    }

    private fun inputWire(input: WorkloadInput): Map<String, Any?> = linkedMapOf(
        "schema_version" to 2, "objective" to input.objective.name.lowercase(),
        "queries" to input.queries.map { mapOf("id" to it.id, "sql" to it.sql) },
        "profiles" to input.profiles.map { mapOf("id" to it.id, "weight" to it.weight, "calls_per_hour" to it.callsPerHour) },
        "constraints" to mapOf("storage_budget_bytes" to input.constraints.storageBudgetBytes,
            "write_budget_units_per_hour" to input.constraints.writeBudgetUnitsPerHour,
            "max_indexes" to input.constraints.maxIndexes),
        "table_write_rates" to input.tableWriteRates?.mapValues { (_, rates) -> mapOf(
            "inserts_per_hour" to rates.insertsPerHour, "updates_per_hour" to rates.updatesPerHour,
            "deletes_per_hour" to rates.deletesPerHour, "source" to "manual") },
    )

    private fun write(path: Path, value: Any) = Files.writeString(path,
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", CREATE_NEW)

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private data class Variant(val name: String, val input: WorkloadInput, val greedy: Boolean = false)
}
