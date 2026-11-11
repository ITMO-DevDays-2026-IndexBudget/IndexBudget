package ru.itmo.indexbudget.fixtures

import ru.itmo.indexbudget.candidates.PostgresDdlRenderer
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.HtmlReportRenderer
import ru.itmo.indexbudget.report.JsonReportWriter
import ru.itmo.indexbudget.application.WorkloadReader
import java.nio.file.Files
import java.nio.file.Path

/** Presentation cases only; no database or production search is simulated by the preview command. */
object HtmlReportFixtures {
    fun completed(sameIncumbents: Boolean = false, objective: Objective = Objective.ROBUST): AnalysisReport {
        val report = ContractFixtures.report(sameIncumbents = sameIncumbents, objective = objective)
        val catalog = ContractFixtures.catalog().tables.single()
        val renderer = PostgresDdlRenderer()
        return report.copy(candidates = report.candidates.copy(candidates = report.candidates.candidates.map { candidate ->
            val definition = candidate.definition.copy(keys = candidate.definition.keys.map { key ->
                key.copy(operatorClass = catalog.columns.single { it.name == key.column.name }.defaultOperatorClass)
            })
            candidate.copy(definition = definition, ddl = renderer.render(definition))
        }))
    }

    fun covering(): AnalysisReport {
        val report = completed(sameIncumbents = true)
        val selected = requireNotNull(report.result.selected)
        val candidates = report.candidates.candidates.map { candidate ->
            val definition = candidate.definition.copy(includedColumns = listOf(ColumnRef(candidate.definition.table, "payload")))
            candidate.copy(definition = definition, ddl = PostgresDdlRenderer().render(definition),
                generationReasons = candidate.generationReasons + GenerationReason.COVERING_PROJECTION)
        }
        val calculator = MetricsCalculatorImpl()
        val input = requireNotNull(report.input)
        val assessment = calculator.assessBudget(candidates.filter { it.id in selected.evaluation.candidateIds }, input)
        val configuration = selected.copy(metrics = calculator.calculate(input, requireNotNull(report.result.baseline),
            selected.evaluation, assessment.usage))
        val annotated = candidates.map { candidate ->
            val usage = calculator.assessBudget(listOf(candidate), input).usage
            candidate.copy(writeUnitsPerHour = usage.writeUnitsPerHour, budgetedBytes = usage.budgetedBytes)
        }
        return report.copy(candidates = report.candidates.copy(candidates = annotated),
            result = SearchResult.Completed(Objective.ROBUST, requireNotNull(report.result.baseline),
                configuration, configuration, report.result.diagnostics)).rebuild()
    }

    fun zeroBaseline(): AnalysisReport {
        val report = completed()
        val input = requireNotNull(report.input)
        val baseline = EvaluationResult(emptyList(), input.queries.associate { it.id to 0.0 },
            input.queries.associate { it.id to ExplainPlan("""[{"Plan":{"Node Type":"Fixture Scan","Total Cost":0}}]""") })
        val calculator = MetricsCalculatorImpl()
        val configuration = EvaluatedConfiguration(baseline, calculator.calculate(input, baseline, baseline, calculator.assessBudget(emptyList(), input).usage))
        return report.copy(result = SearchResult.Completed(Objective.ROBUST, baseline, configuration, configuration,
            SearchDiagnostics(completedPhases = listOf(AnalysisPhase.VALIDATION, AnalysisPhase.INTROSPECTION, AnalysisPhase.BASELINE),
                stopReasons = listOf(StopReason.ZERO_BASELINE), statistics = RunStatistics(explainAttempts = 2, evaluationAttempts = 1, completedEvaluations = 1))),
            candidates = CandidateBatch(emptyList()), priceOfRobustness = 0.0,
            baselineMetrics = configuration.metrics, incumbentsCoincide = true).rebuild()
    }

    fun wide(): AnalysisReport {
        val report = completed()
        val table = ContractFixtures.wideTable
        val queryId = "wide_100_columns"
        val sql = "SELECT * FROM public.wide_records\nWHERE " + (1..100).joinToString("\n  AND ") { "c$it = $it" }
        val input = WorkloadInput(
            queries = listOf(QueryInput(queryId, sql)),
            profiles = listOf(Profile("normal", 0.9, mapOf(queryId to 3000.0)), Profile("campaign", 0.1, mapOf(queryId to 100.0))),
            constraints = Constraints(67_108_864, 20_000.0, maxIndexes = 2),
            tableWriteRates = mapOf("public.wide_records" to TableWriteRates(1000.0, 3000.0, 100.0)),
        )
        val candidates = (1..16).map { index ->
            val columns = if (index <= 8) listOf("c$index") else listOf("c1", "c${index - 7}")
            val definition = IndexDefinition(table, columns.map { IndexKey(ColumnRef(table, it), operatorClass = "pg_catalog.int4_ops") })
            IndexCandidate(
                id = if (index == 16) "wide_not_evaluated" else "wide_$index", definition = definition,
                sourceQueryIds = setOf(queryId), generationReasons = setOf(if (index <= 8) GenerationReason.SINGLE_COLUMN else GenerationReason.TWO_EQUALITIES),
                ddl = PostgresDdlRenderer().render(definition), estimatedBytes = if (index == 16) null else 262_144L,
                budgetedBytes = if (index == 16) null else 314_573L, writeUnitsPerHour = if (index == 16) null else if (index <= 8) 4100.0 else 8200.0,
            )
        }
        fun evaluation(ids: List<String>, cost: Double) = EvaluationResult(ids, mapOf(queryId to cost),
            mapOf(queryId to ExplainPlan("""[{"Plan":{"Node Type":"Fixture Scan","Total Cost":$cost}}]""")))
        val baseline = evaluation(emptyList(), 100.0)
        val selected = evaluation(listOf("wide_1"), 50.0)
        val calculator = MetricsCalculatorImpl()
        val configuration = EvaluatedConfiguration(selected, calculator.calculate(input, baseline, selected,
            calculator.assessBudget(candidates.take(1), input).usage))
        val decisions = listOf(
            CandidateDecision(CandidateReason.COLUMN_SHORTLIST_TRUNCATED, queryId = queryId, table = table, phase = AnalysisPhase.CANDIDATES),
            CandidateDecision(CandidateReason.LOCAL_CANDIDATE_LIMIT, queryId = queryId, table = table, phase = AnalysisPhase.CANDIDATES),
            CandidateDecision(CandidateReason.GLOBAL_CANDIDATE_LIMIT, queryId = queryId, table = table, phase = AnalysisPhase.CANDIDATES),
            CandidateDecision(CandidateReason.HYPOTHETICAL_INDEX_ERROR, candidateId = "wide_not_evaluated", phase = AnalysisPhase.CANDIDATES),
            CandidateDecision(CandidateReason.NOT_EVALUATED_DUE_TO_LIMIT, candidateId = "wide_15", phase = AnalysisPhase.REPAIR,
                details = mapOf("scope" to "Остались непроверенные комбинации с этим кандидатом")),
        )
        return report.copy(input = input, durationMillis = 2000,
            result = SearchResult.Partial(Objective.ROBUST, baseline, configuration, configuration,
                SearchDiagnostics(stopReasons = listOf(StopReason.REPAIR_LIMIT), candidateDecisions = decisions,
                    statistics = RunStatistics(explainAttempts = 30, evaluationAttempts = 30, completedEvaluations = 30, repairAttempts = 28),
                    completedPhases = listOf(AnalysisPhase.VALIDATION, AnalysisPhase.INTROSPECTION, AnalysisPhase.BASELINE, AnalysisPhase.CANDIDATES, AnalysisPhase.GREEDY))),
            priceOfRobustness = 0.0, incumbentsCoincide = true,
            baselineMetrics = calculator.calculate(input, baseline, baseline, calculator.assessBudget(emptyList(), input).usage),
            normalizedProfileWeights = ProfileWeights.normalize(input.profiles),
            candidates = CandidateBatch(candidates, CandidateDiagnostics(
                localScopes = listOf(LocalGenerationStatistics(queryId, table, 100, 16, 256, 256, 32, (17..100).map { ColumnRef(table, "c$it") })),
                candidatesBeforeGlobalDeduplication = 32, candidatesAfterGlobalDeduplication = 32, shortlistedCandidates = 16,
                decisions = decisions, warnings = listOf(AnalysisWarning(WarningCode.COLUMN_SHORTLIST_TRUNCATED,
                    "Синтетический широкий workload: SQL сохранён целиком, колонки ограничены до генерации пар.", queryId = queryId)),
            ))).rebuild()
    }

    fun regression(): AnalysisReport {
        val report = completed()
        val input = requireNotNull(report.input)
        val baseline = requireNotNull(report.result.baseline)
        val evaluation = ContractFixtures.robustEvaluation().copy(
            costByQueryId = mapOf("records_by_group" to 20.0, "records_by_value" to 120.0),
            plansByQueryId = mapOf("records_by_group" to ExplainPlan("""[{"Plan":{"Total Cost":20}}]"""),
                "records_by_value" to ExplainPlan("""[{"Plan":{"Total Cost":120}}]""")),
        )
        val calculator = MetricsCalculatorImpl()
        val chosen = EvaluatedConfiguration(evaluation, calculator.calculate(input, baseline, evaluation,
            calculator.assessBudget(report.candidates.candidates.take(1), input).usage))
        val baselineConfig = EvaluatedConfiguration(baseline, calculator.calculate(input, baseline, baseline, calculator.assessBudget(emptyList(), input).usage))
        return report.copy(input = input.copy(objective = Objective.MEAN), result = SearchResult.Completed(Objective.MEAN, baseline, baselineConfig, chosen,
            report.result.diagnostics.copy(statistics = RunStatistics(explainAttempts = 4, evaluationAttempts = 2, completedEvaluations = 2))),
            priceOfRobustness = baselineConfig.metrics.meanCost - chosen.metrics.meanCost).rebuild()
    }

    fun imported(): AnalysisReport {
        val input = WorkloadReader().read(Path.of("examples/workload-v2-provenance.json"))
        return completed().copy(input = input).rebuild()
    }

    /** Rebuild every derived field after changing the input/result, preserving synthetic run metadata. */
    private fun AnalysisReport.rebuild(): AnalysisReport = DefaultReportBuilder(environment = environment)
        .build(requireNotNull(input), candidates, result, RunContext(runId = runId))
        .copy(inputHash = inputHash, durationMillis = durationMillis)

    fun all(): Map<String, AnalysisReport> = linkedMapOf(
        "different" to completed(),
        "include" to covering(),
        "imported" to imported(),
        "same" to completed(sameIncumbents = true),
        "mean" to completed(objective = Objective.MEAN),
        "partial" to ContractFixtures.report(status = RunStatus.PARTIAL),
        "failed" to ContractFixtures.report(status = RunStatus.FAILED),
        "no-write" to ContractFixtures.report(withWriteProfile = false),
        "zero-baseline" to zeroBaseline(),
        "wide" to wide(),
        "regression" to regression(),
        "missing" to ContractFixtures.report(status = RunStatus.FAILED).copy(input = null, inputHash = null),
    )
}

/** Run via reportPreview; generated files never become dependencies of production analyze. */
object ReportPreview {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected primary HTML path and preview directory" }
        val renderer = HtmlReportRenderer()
        val reports = HtmlReportFixtures.all()
        Files.writeString(Path.of(args[0]), renderer.render(reports.getValue("different")))
        val directory = Files.createDirectories(Path.of(args[1]))
        reports.forEach { (name, report) ->
            Files.writeString(directory.resolve("$name.html"), renderer.render(report))
            Files.writeString(directory.resolve("$name.json"), JsonReportWriter().render(report))
        }
        println("Синтетический отчёт: ${Path.of(args[0]).toAbsolutePath()}")
        println("Варианты для проверки: ${directory.toAbsolutePath()}")
    }
}
