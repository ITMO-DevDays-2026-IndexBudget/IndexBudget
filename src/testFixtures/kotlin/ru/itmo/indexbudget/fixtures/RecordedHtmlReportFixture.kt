package ru.itmo.indexbudget.fixtures

import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.HtmlReportRenderer
import ru.itmo.indexbudget.report.JsonReportWriter
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * Saved facts from the user-provided report.html, not a new database run.
 * SQL costs/plans and observation metadata are retained. Frequencies and relative weights
 * have only the precision displayed by that HTML; derived metrics use the shared calculator.
 */
object RecordedHtmlReportFixture {
    private val mapper = JsonMapper.builder().build()
    private val source by lazy {
        val resource = checkNotNull(javaClass.getResourceAsStream("/report/recorded-import-sample.json"))
        resource.use { mapper.readTree(it) }
    }

    fun sourceDescription(): String = source["source"].toPrettyString()

    fun report(): AnalysisReport {
        // Exercise the same provenance validation as analyze, without any SQL/JDBC capability.
        val workloadPath = Files.createTempFile("indexbudget-recorded-workload-", ".json")
        val input = try {
            Files.writeString(workloadPath, source["workload"].toString())
            WorkloadReader().read(workloadPath)
        } finally {
            Files.deleteIfExists(workloadPath)
        }
        val table = TableRef(source["table"]["schema"].stringValue(), source["table"]["name"].stringValue())
        val candidates = source["indexes"].toList().map { index ->
            IndexCandidate(
                id = index["id"].stringValue(),
                definition = IndexDefinition(table, listOf(IndexKey(ColumnRef(table, index["column"].stringValue()),
                    operatorClass = "pg_catalog.int4_ops"))),
                sourceQueryIds = setOf(index["query_id"].stringValue()),
                generationReasons = setOf(GenerationReason.SINGLE_COLUMN),
                ddl = index["ddl"].stringValue(),
                estimatedBytes = index["estimated_bytes"].longValue(),
                budgetedBytes = index["budgeted_bytes"].longValue(),
            )
        }
        fun evaluation(selected: Boolean): EvaluationResult {
            val prefix = if (selected) "selected" else "baseline"
            return EvaluationResult(
                if (selected) candidates.map { it.id }.sorted() else emptyList(),
                source["queries"].associate { it["id"].stringValue() to it["${prefix}_cost"].doubleValue() },
                source["queries"].associate { it["id"].stringValue() to ExplainPlan(it["${prefix}_plan"].toString()) },
            )
        }
        val baseline = evaluation(false)
        val selected = evaluation(true)
        val calculator = MetricsCalculatorImpl()
        val configuration = EvaluatedConfiguration(selected,
            calculator.calculate(input, baseline, selected, calculator.assessBudget(candidates, input).usage))
        val diagnostics = SearchDiagnostics(
            completedPhases = listOf(AnalysisPhase.VALIDATION, AnalysisPhase.INTROSPECTION, AnalysisPhase.BASELINE,
                AnalysisPhase.CANDIDATES, AnalysisPhase.GREEDY, AnalysisPhase.REPAIR),
            stopReasons = listOf(StopReason.NO_IMPROVEMENT, StopReason.SEARCH_COMPLETE),
            statistics = RunStatistics(explainAttempts = 8, evaluationAttempts = 4, completedEvaluations = 4),
            unexaminedConfigurations = 0,
        )
        val batch = CandidateBatch(candidates, CandidateDiagnostics(
            localScopes = input.queries.sortedBy { it.id }.map {
                LocalGenerationStatistics(it.id, table, 1, 1, 1, 1, 1)
            },
            candidatesBeforeGlobalDeduplication = 2,
            candidatesAfterGlobalDeduplication = 2,
            shortlistedCandidates = 2,
            preparation = CandidatePreparationStatistics(examinedCandidates = 2, sizeAttempts = 2,
                finalShortlistedCandidates = 2, stopReason = CandidatePreparationStopReason.RESERVE_EXHAUSTED),
        ))
        val environment = source["environment"]
        return DefaultReportBuilder(environment = EnvironmentInfo(
            environment["application_version"].stringValue(), environment["java_version"].stringValue(),
            environment["kotlin_version"].stringValue(),
        )).build(input, batch, SearchResult.Completed(Objective.ROBUST, baseline, configuration, configuration, diagnostics),
            RunContext(runId = source["source"]["original_run_id"].stringValue() + "-html-preview"))
            .copy(durationMillis = source["duration_millis"].longValue(), inputHash = null)
    }

    /** Optional standalone entry point using the testFixtures runtime classpath; no DB is contacted. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Expected preview output directory" }
        val output = Files.createDirectories(Path.of(args.single()))
        val report = report()
        Files.writeString(output.resolve("recorded-import.html"), HtmlReportRenderer().render(report))
        Files.writeString(output.resolve("recorded-import.json"), JsonReportWriter().render(report))
        Files.writeString(output.resolve("recorded-import-source.json"), sourceDescription())
        println("Повторный рендер сохранённых данных HTML, без нового анализа БД: ${output.toAbsolutePath()}")
    }
}
