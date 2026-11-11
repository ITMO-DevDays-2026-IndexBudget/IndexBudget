package ru.itmo.indexbudget.experiments

import ru.itmo.indexbudget.application.AnalysisPipeline
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.candidates.CandidateGenerator
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.JdbcAnalysisSessionFactory
import ru.itmo.indexbudget.report.*
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** Сравнение затрат режимов на одном замороженном production-shortlist; вне production CLI. */
fun main(args: Array<String>) {
    try {
        require(args.size == 4 && args[0] == "--workload" && args[2] == "--output-dir") {
            "Expected --workload PATH --output-dir NEW_PATH"
        }
        AnalysisModeExperiments().run(Path.of(args[1]), Path.of(args[3]))
    } catch (error: AnalysisException) {
        System.err.println("Analysis mode experiment failed: ${error.error.code}")
        exitProcess(1)
    } catch (_: Exception) {
        // SQL, paths and driver exceptions can contain private connection settings.
        System.err.println("Analysis mode experiment failed; check input, new destination and database availability.")
        exitProcess(1)
    }
}

class AnalysisModeExperiments {
    private val mapper = JsonMapper.builder().build()
    private val metrics = MetricsCalculatorImpl()
    private val comparator = ObjectiveComparatorImpl()
    private val sessions = JdbcAnalysisSessionFactory()
    private val analyzer = JSqlParserSqlAnalyzer()

    fun run(workloadPath: Path, output: Path) {
        require(!Files.exists(output)) { "Experiment destination must be new" }
        Files.createDirectories(output)
        Files.copy(workloadPath, output.resolve("source-workload.json"))

        // Prepare once before timed runs. Every mode receives identical definitions and provenance.
        val preparation = RunContext(analysisMode = AnalysisMode.DEEP)
        val source = WorkloadReader().read(workloadPath, preparation, AnalysisMode.DEEP)
        val frozen = sessions.open(preparation).use { session ->
            val parsed = analyzer.parse(source, preparation)
            val catalog = session.readCatalog(parsed.referencedTables, preparation)
            val resolved = analyzer.resolve(parsed, catalog, preparation)
            ProfileAwareCandidateGenerator().generate(resolved, catalog, preparation)
        }
        require(frozen.candidates.isNotEmpty() && frozen.candidates.size <= 16) {
            "The experiment requires one nonempty production shortlist of at most 16 candidates"
        }
        val generator = object : CandidateGenerator {
            override fun generate(workload: ResolvedWorkload, catalog: CatalogSnapshot,
                context: RunContext): CandidateBatch {
                context.checkDeadline()
                require(workload.input.queries == source.queries) { "Every mode must preserve shared SQL" }
                return frozen
            }
        }
        val summaries = linkedMapOf<String, Any?>()
        val reports = mutableListOf<AnalysisReport>()
        val manifest = linkedMapOf<String, Any?>(
            "schema_version" to 1,
            "input_hash" to preparation.inputHash,
            "candidate_pool_policy" to "One production shortlist frozen before timed runs; no refill or truncation by mode",
            "preparation_duration_millis" to preparation.elapsedTime().toMillis(),
            "frozen_candidate_ids" to frozen.candidates.map { it.id }.sorted(),
            "run_order" to AnalysisMode.entries.map { it.name.lowercase() },
            "limitations" to listOf(
                "Separate dedicated sessions observe the same read-only database; baseline and pool equality are checked.",
                "Elapsed times include connection, catalog, sizing and analysis, exclude shared pool preparation and file publication.",
                "One timed run per mode; JVM and database caches and fixed run order affect elapsed time.",
                "Planner cost is not measured execution time. No global optimum or strict return-time guarantee is claimed.",
                "Deeper search can retain the same recommendation. Partial remains partial.",
            ),
            "runs" to summaries,
        )
        fun saveManifest() {
            Files.writeString(output.resolve("summary.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n")
        }
        saveManifest()
        for (mode in AnalysisMode.entries) {
            val saved = runMode(mode, workloadPath, output, generator)
            reports += saved
            summaries[mode.name.lowercase()] = summarize(saved)
            saveManifest()
            check(saved.result.status != RunStatus.FAILED) { "A run failed; its diagnostics were saved" }
        }
        val reference = reports.first()
        val baselinesEqual = reports.all {
            it.result.baseline?.costByQueryId == reference.result.baseline?.costByQueryId
        }
        val poolsEqual = reports.all { report ->
            report.candidates.candidates.map { it.id }.sorted() == frozen.candidates.map { it.id }.sorted()
        }
        val hashesEqual = reports.all { it.inputHash == preparation.inputHash }
        manifest["comparison_checks"] = mapOf(
            "baselines_equal" to baselinesEqual,
            "candidate_pools_equal" to poolsEqual,
            "input_hashes_equal" to hashesEqual,
            "selected_sets_equal" to reports.all {
                it.result.selected?.evaluation?.candidateIds == reference.result.selected?.evaluation?.candidateIds
            },
        )
        saveManifest()
        check(baselinesEqual && poolsEqual && hashesEqual) { "Database state, input or candidate pool changed between modes" }
    }

    private fun runMode(
        mode: AnalysisMode,
        workloadPath: Path,
        output: Path,
        generator: CandidateGenerator,
    ): AnalysisReport {
        val context = RunContext(analysisMode = mode)
        val input = WorkloadReader().read(workloadPath, context, mode)
        var report: AnalysisReport? = null
        val exporter = ReportExporter { value, path, run ->
            AtomicReportExporter(HtmlReportRenderer()).export(value, path, run)
            report = value
        }
        AnalysisPipeline(analyzer, sessions, generator, metrics, comparator, LimitedRepairOptimizer(),
            DefaultReportBuilder(metrics), exporter).run(input, output.resolve(mode.name.lowercase()), context)
        return checkNotNull(report)
    }

    /** Uses saved results only; summary construction needs neither JDBC nor artifact publication. */
    internal fun summarize(report: AnalysisReport): Map<String, Any?> {
        val wire = mapper.readTree(JsonReportWriter().render(report))
        val selected = report.result.selected
        return linkedMapOf(
            "status" to report.result.status.name.lowercase(),
            "stop_reasons" to report.result.diagnostics.stopReasons.map { it.name },
            "duration_millis" to report.durationMillis,
            "statistics" to wire["diagnostics"]["statistics"],
            "limits" to wire["diagnostics"]["limits"],
            "candidate_ids" to report.candidates.candidates.map { it.id }.sorted(),
            "selected_ids" to selected?.evaluation?.candidateIds,
            "mean_ratio" to selected?.metrics?.meanRatio,
            "worst_ratio" to selected?.metrics?.worstRatio,
            "budgeted_bytes" to selected?.metrics?.budgetUsage?.budgetedBytes,
            "write_units_per_hour" to selected?.metrics?.budgetUsage?.writeUnitsPerHour,
            "environment" to wire["environment"],
        )
    }
}
