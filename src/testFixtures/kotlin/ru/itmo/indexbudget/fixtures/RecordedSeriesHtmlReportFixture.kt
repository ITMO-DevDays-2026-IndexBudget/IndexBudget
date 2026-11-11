package ru.itmo.indexbudget.fixtures

import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.HtmlReportRenderer
import ru.itmo.indexbudget.report.HtmlBudgetSeriesRenderer
import ru.itmo.indexbudget.report.JsonReportWriter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/** Saved facts from the updated series HTML, rendered by production renderers without a new DB run. */
object RecordedSeriesHtmlReportFixture {
    private val mapper = JsonMapper.builder().build()
    private val source by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/report/recorded-series-sample.json"))
        stream.use { mapper.readTree(it) }
    }

    fun all(): Map<String, AnalysisReport> = (0..2).associate { "recorded-series-$it" to report(it) }

    fun result(): BudgetSeriesResult = BudgetSeriesResult(SeriesStatus.COMPLETED, (0..2).map { budget ->
        val report = report(budget)
        BudgetSeriesPoint(ordinal = budget + 1, value = BigDecimal(budget), status = SeriesPointStatus.COMPLETED,
            runId = report.runId, result = report.result, report = report,
            conditionsMatch = source["points"][budget]["conditions_match"].booleanValue(),
            artifacts = listOf("report.html", "report.json", "indexes.sql").associateWith { "point-%02d/$it".format(budget + 1) })
    })

    /** Existing series-v1 presentation fields; unavailable hidden facts remain null, never invented hashes. */
    fun summary(result: BudgetSeriesResult): JsonNode {
        val report = requireNotNull(result.points.last().report)
        val reportJson = mapper.readTree(JsonReportWriter().render(report))
        return mapper.valueToTree(linkedMapOf<String, Any?>(
            "schema_version" to 1, "spec_version" to "0.2",
            "series_id" to source["source"]["original_series_id"].stringValue() + "-html-preview",
            "status" to result.status.name.lowercase(),
            "duration_millis" to source["source"]["original_duration_millis"].longValue(), "timeout_seconds" to 600,
            "budget" to SeriesBudget.WRITE.wireName, "values" to listOf(0, 1, 2), "comparison_scope" to "fixed_candidate_pool",
            "source" to mapOf("workload_input_hash" to null, "workload_source" to "manual_json",
                "workload_source_id" to null, "observation_scope" to "catalog_baseline_plans_versions; no database snapshot isolation"),
            "settings" to mapOf("selected_objective" to "robust", "analysis_mode" to "normal", "candidate_ranking" to "frequency",
                "constraints" to reportJson["budgets"], "queries" to source["workload"]["queries"],
                "profiles" to source["workload"]["profiles"], "table_write_rates" to source["workload"]["table_write_rates"]),
            "preparation" to mapOf("run_id" to null, "statistics" to null, "limits" to null,
                "maximum_budgets" to reportJson["budgets"], "conditions" to null,
                "candidate_ids" to report.candidates.candidates.map { it.id },
                "candidates" to report.candidates.candidates.map { candidate -> mapOf(
                    "id" to candidate.id, "schema" to candidate.definition.table.schema, "table" to candidate.definition.table.name,
                    "keys" to candidate.definition.keys.map { mapOf("column" to it.column.name,
                        "direction" to it.direction.name.lowercase(), "nulls" to it.nulls.name.lowercase()) },
                    "included_columns" to emptyList<String>(), "ddl" to candidate.ddl, "estimated_bytes" to candidate.estimatedBytes,
                    "budgeted_bytes" to candidate.budgetedBytes, "write_units_per_hour" to candidate.writeUnitsPerHour) }, "error" to null),
            "points" to result.points.map { point ->
                val json = mapper.readTree(JsonReportWriter().render(requireNotNull(point.report)))
                mapOf("ordinal" to point.ordinal, "budget_value" to point.value, "status" to point.status.name.lowercase(),
                    "run_id" to point.runId, "conditions_match" to point.conditionsMatch, "condition_changes" to emptyList<String>(),
                    "conditions" to null, "selected_index_ids" to point.result?.selected?.evaluation?.candidateIds,
                    "metrics" to json["selected"]["metrics"], "statistics" to json["diagnostics"]["statistics"],
                    "limits" to json["diagnostics"]["limits"], "stop_reasons" to json["diagnostics"]["stop_reasons"],
                    "errors" to json["diagnostics"]["errors"], "artifacts" to point.artifacts, "error" to null)
            },
        ))
    }

    /** Preview files, like ReportPreview: no production export identity is asserted for reconstructed input. */
    fun generate(directory: Path) {
        Files.createDirectories(directory)
        val result = result()
        result.points.forEach { point ->
            val report = requireNotNull(point.report)
            val pointDirectory = Files.createDirectories(directory.resolve("point-%02d".format(point.ordinal)))
            Files.writeString(pointDirectory.resolve("report.html"), HtmlReportRenderer().render(report))
            Files.writeString(pointDirectory.resolve("report.json"), JsonReportWriter().render(report))
            val selectedIds = report.result.selected?.evaluation?.candidateIds.orEmpty()
            Files.writeString(pointDirectory.resolve("indexes.sql"),
                "-- Preview reconstructed from saved HTML; no new database run.\n" +
                    report.candidates.candidates.filter { it.id in selectedIds }.sortedBy { it.id }
                        .joinToString("\n") { checkNotNull(it.ddl) })
        }
        val summary = summary(result)
        val html = HtmlBudgetSeriesRenderer().render(summary, result.points.associate { it.ordinal to requireNotNull(it.report) })
        Files.writeString(directory.resolve("series.json"), summary.toPrettyString())
        Files.writeString(directory.resolve("report.html"), html)
        Files.writeString(directory.resolve("recorded-series.html"), html)
        Files.writeString(directory.resolve("source.json"), source["source"].toPrettyString())
    }

    fun report(writeBudget: Int): AnalysisReport {
        require(writeBudget in 0..2)
        val point = source["points"][writeBudget]
        val inputPath = Files.createTempFile("indexbudget-recorded-series-", ".json")
        val input = try {
            Files.writeString(inputPath, source["workload"].toString())
            val original = WorkloadReader().read(inputPath)
            original.copy(constraints = original.constraints.copy(writeBudgetUnitsPerHour = writeBudget.toDouble()))
        } finally {
            Files.deleteIfExists(inputPath)
        }
        val table = TableRef(source["table"]["schema"].stringValue(), source["table"]["name"].stringValue())
        val candidates = source["indexes"].toList().map { index ->
            IndexCandidate(index["id"].stringValue(),
                IndexDefinition(table, listOf(IndexKey(ColumnRef(table, index["column"].stringValue()),
                    operatorClass = "pg_catalog.int4_ops"))),
                setOf(index["query_id"].stringValue()), setOf(GenerationReason.SINGLE_COLUMN),
                ddl = index["ddl"].stringValue(), estimatedBytes = index["estimated_bytes"].longValue(),
                budgetedBytes = index["budgeted_bytes"].longValue(), writeUnitsPerHour = index["write_units_per_hour"].doubleValue())
        }
        val selectedIds = point["selected_ids"].toList().map { it.stringValue() }.sorted()
        fun evaluation(selected: Boolean): EvaluationResult {
            val prefix = if (selected) "selected" else "baseline"
            return EvaluationResult(if (selected) selectedIds else emptyList(),
                point["queries"].associate { it["id"].stringValue() to it["${prefix}_cost"].doubleValue() },
                point["queries"].associate { it["id"].stringValue() to ExplainPlan(it["${prefix}_plan"].toString()) })
        }
        val baseline = evaluation(false)
        val selected = evaluation(true)
        val calculator = MetricsCalculatorImpl()
        val configuration = EvaluatedConfiguration(selected, calculator.calculate(input, baseline, selected,
            calculator.assessBudget(candidates.filter { it.id in selectedIds }, input).usage))
        val statistics = point["statistics"]
        val decisions = point["decisions"].toList().map { decision ->
            CandidateDecision(CandidateReason.valueOf(decision["reason"].stringValue()),
                candidateId = decision["candidate_id"].stringValue(),
                table = if (decision["table"].booleanValue()) table else null,
                phase = decision["phase"].takeUnless { it.isNull }?.let { AnalysisPhase.valueOf(it.stringValue()) },
                details = decision["details"].properties().associate { it.key to it.value.stringValue() })
        }
        val diagnostics = SearchDiagnostics(
            completedPhases = point["phases"].toList().map { AnalysisPhase.valueOf(it.stringValue()) },
            stopReasons = point["stop_reasons"].toList().map { StopReason.valueOf(it.stringValue()) },
            statistics = RunStatistics(statistics["explain_attempts"].intValue(), statistics["evaluation_attempts"].intValue(),
                statistics["completed_evaluations"].intValue(), statistics["cache_hits"].intValue(), statistics["repair_attempts"].intValue()),
            candidateDecisions = decisions,
            unexaminedConfigurations = point["unexamined_configurations"].takeUnless { it.isNull }?.longValue(),
        )
        val pool = point["fixed_pool_selection"]
        val batch = CandidateBatch(candidates, CandidateDiagnostics(
            localScopes = input.queries.map { LocalGenerationStatistics(it.id, table, 1, 1, 1, 1, 1) },
            candidatesBeforeGlobalDeduplication = 2, candidatesAfterGlobalDeduplication = 2, shortlistedCandidates = 2,
            preparation = CandidatePreparationStatistics(examinedCandidates = 2, sizeAttempts = 2,
                finalShortlistedCandidates = 2, stopReason = CandidatePreparationStopReason.RESERVE_EXHAUSTED),
            preparationScope = CandidatePreparationScope.COMMON_POOL,
            fixedPoolSelection = FixedPoolSelectionStatistics(pool["pool_candidates"].intValue(),
                pool["admissible_candidates"].intValue(), pool["budget_rejected_candidates"].intValue(), pool["size_attempts"].intValue()),
        ))
        val environment = source["environment"]
        return DefaultReportBuilder(environment = EnvironmentInfo(
            environment["application_version"].stringValue(), environment["java_version"].stringValue(),
            environment["kotlin_version"].stringValue(), environment["postgres_version"].stringValue(), environment["hypopg_version"].stringValue(),
        )).build(input, batch, SearchResult.Completed(Objective.ROBUST, baseline, configuration, configuration, diagnostics),
            RunContext(runId = point["original_run_id"].stringValue() + "-html-preview"))
            .copy(durationMillis = point["duration_millis"].longValue(), inputHash = null)
    }
}
