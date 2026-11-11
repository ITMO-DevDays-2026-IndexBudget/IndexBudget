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

    /** Different label lengths must never change the physical chart scale. */
    fun fractional(): AnalysisReport {
        val source = completed(objective = Objective.MEAN)
        val input = requireNotNull(source.input)
        val baseline = requireNotNull(source.result.baseline)
        val evaluation = ContractFixtures.meanEvaluation().copy(
            costByQueryId = mapOf("records_by_group" to 100.0 / 3, "records_by_value" to 400.0 / 3),
            plansByQueryId = mapOf("records_by_group" to ExplainPlan("""[{"Plan":{"Total Cost":33.333333333333336}}]"""),
                "records_by_value" to ExplainPlan("""[{"Plan":{"Total Cost":133.33333333333334}}]""")),
        )
        val calculator = MetricsCalculatorImpl()
        val configuration = EvaluatedConfiguration(evaluation, calculator.calculate(input, baseline, evaluation,
            calculator.assessBudget(source.candidates.candidates.filter { it.id in evaluation.candidateIds }, input).usage))
        val result = source.result as SearchResult.Completed
        return source.copy(result = result.copy(bestMean = configuration)).rebuild()
    }

    fun imported(): AnalysisReport {
        val input = WorkloadReader().read(Path.of("examples/workload-v2-provenance.json"))
        return completed().copy(input = input).rebuild()
    }

    fun oneProfile(): AnalysisReport {
        val report = completed(sameIncumbents = true)
        val input = requireNotNull(report.input)
        return report.withProfiles(listOf(input.profiles.first().copy(weight = 1.0)))
    }

    fun threeProfiles(): AnalysisReport {
        val report = regression()
        val input = requireNotNull(report.input)
        return report.withProfiles(input.profiles + Profile("profile_3", 0.5,
            input.queries.associate { it.id to 500.0 }))
    }

    /** Positive baseline with a valid empty recommendation, unlike zero-baseline and failed. */
    fun empty(): AnalysisReport {
        val report = completed(sameIncumbents = true)
        val input = requireNotNull(report.input)
        val baseline = requireNotNull(report.result.baseline)
        val calculator = MetricsCalculatorImpl()
        val configuration = EvaluatedConfiguration(baseline, calculator.calculate(input, baseline, baseline,
            calculator.assessBudget(emptyList(), input).usage))
        return report.copy(candidates = CandidateBatch(emptyList()), result = SearchResult.Completed(
            input.objective, baseline, configuration, configuration,
            SearchDiagnostics(stopReasons = listOf(StopReason.NO_IMPROVEMENT)),
        )).rebuild()
    }

    /** Presentation-only cases: rounding to one decimal must not erase the direction of a change. */
    fun tinyEffect(increase: Boolean = false): AnalysisReport {
        val report = oneProfile()
        val input = requireNotNull(report.input)
        val baseline = requireNotNull(report.result.baseline)
        val selected = requireNotNull(report.result.selected)
        val cost = if (increase) 100.01 else 99.99
        val evaluation = selected.evaluation.copy(
            costByQueryId = input.queries.associate { it.id to cost },
            plansByQueryId = input.queries.associate {
                it.id to ExplainPlan("""[{"Plan":{"Node Type":"Fixture Scan","Total Cost":$cost}}]""")
            },
        )
        val configuration = selected.copy(evaluation = evaluation,
            metrics = MetricsCalculatorImpl().calculate(input, baseline, evaluation, selected.metrics.budgetUsage))
        return report.copy(result = SearchResult.Completed(input.objective, baseline, configuration, configuration,
            report.result.diagnostics)).rebuild()
    }

    /** Same table name in two schemas, long source IDs and unabridged SQL/DDL. */
    fun longNames(): AnalysisReport {
        val source = completed()
        val originalInput = requireNotNull(source.input)
        val queryIds = originalInput.queries.mapIndexed { index, _ -> "observed_query_${index + 1}_" + "abcdef0123456789".repeat(4) }
        val tableName = "records_from_application_events_current_quarter"
        val tables = listOf(TableRef("public", tableName), TableRef("archive", tableName))
        val queries = tables.mapIndexed { index, table -> QueryInput(queryIds[index],
            "SELECT value FROM \"${table.schema}\".\"${table.name}\" WHERE value = ${index + 1}\n" +
                "AND value < 100 ORDER BY value ASC LIMIT 100") }
        val input = originalInput.copy(queries = queries,
            profiles = originalInput.profiles.map { profile -> profile.copy(
                callsPerHour = originalInput.queries.mapIndexed { index, query ->
                    queryIds[index] to profile.callsPerHour.getValue(query.id)
                }.toMap(),
            ) },
            constraints = originalInput.constraints.copy(writeBudgetUnitsPerHour = 20_000.0, maxIndexes = 2),
            tableWriteRates = tables.associate { "${it.schema}.${it.name}" to TableWriteRates(1000.0, 3000.0, 100.0) },
        )
        val candidates = tables.mapIndexed { index, table ->
            val definition = IndexDefinition(table, listOf(IndexKey(ColumnRef(table, "value"),
                operatorClass = "pg_catalog.int4_ops")))
            source.candidates.candidates[index].copy(id = "candidate_${index + 1}_" + "fedcba9876543210".repeat(4),
                definition = definition, sourceQueryIds = setOf(queryIds[index]), ddl = PostgresDdlRenderer().render(definition))
        }
        fun evaluation(ids: List<String>, costs: List<Double>) = EvaluationResult(ids.sorted(),
            queryIds.zip(costs).toMap(), queryIds.zip(costs).associate { (id, cost) ->
                id to ExplainPlan("""[{"Plan":{"Node Type":"Fixture Scan","Total Cost":$cost}}]""")
            })
        val baseline = evaluation(emptyList(), listOf(100.0, 100.0))
        val selected = evaluation(candidates.map { it.id }, listOf(45.0, 55.0))
        val calculator = MetricsCalculatorImpl()
        val configuration = EvaluatedConfiguration(selected, calculator.calculate(input, baseline, selected,
            calculator.assessBudget(candidates, input).usage))
        val annotatedCandidates = candidates.map { candidate -> candidate.copy(
            writeUnitsPerHour = calculator.assessBudget(listOf(candidate), input).usage.writeUnitsPerHour,
        ) }
        return source.copy(input = input, candidates = CandidateBatch(annotatedCandidates),
            result = SearchResult.Completed(input.objective, baseline, configuration, configuration,
                source.result.diagnostics)).rebuild()
    }

    private fun AnalysisReport.withProfiles(profiles: List<Profile>): AnalysisReport {
        val input = requireNotNull(this.input).copy(profiles = profiles)
        val baseline = requireNotNull(result.baseline)
        val calculator = MetricsCalculatorImpl()
        fun configuration(value: EvaluatedConfiguration) = value.copy(metrics = calculator.calculate(
            input, baseline, value.evaluation, value.metrics.budgetUsage))
        return copy(input = input, result = SearchResult.Completed(result.selectedObjective, baseline,
            configuration(requireNotNull(result.bestRobust)), configuration(requireNotNull(result.bestMean)),
            result.diagnostics)).rebuild()
    }

    /** Rebuild every derived field after changing the input/result, preserving synthetic run metadata. */
    private fun AnalysisReport.rebuild(): AnalysisReport = DefaultReportBuilder(environment = environment)
        .build(requireNotNull(input), candidates, result, RunContext(runId = runId))
        .copy(inputHash = inputHash, durationMillis = durationMillis)

    fun all(): Map<String, AnalysisReport> = linkedMapOf(
        "one-profile" to oneProfile(),
        "three-profiles" to threeProfiles(),
        "empty" to empty(),
        "tiny-decrease" to tinyEffect(),
        "tiny-increase" to tinyEffect(increase = true),
        "long-names" to longNames(),
        "recorded-import" to RecordedHtmlReportFixture.report(),
        "different" to completed(),
        "fractional" to fractional(),
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
    ).also { it.putAll(RecordedSeriesHtmlReportFixture.all()) }
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
        BudgetSeriesFixtures.variants.forEach { variant ->
            val target = directory.resolve("series-$variant")
            // Preview owns only these generated directories. Production series still require an empty destination.
            if (Files.exists(target)) Files.walk(target).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
            BudgetSeriesFixtures.generate(target, variant)
        }
        val recordedSeries = directory.resolve("recorded-series")
        if (Files.exists(recordedSeries)) Files.walk(recordedSeries).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
        RecordedSeriesHtmlReportFixture.generate(recordedSeries)
        println("Синтетический отчёт: ${Path.of(args[0]).toAbsolutePath()}")
        println("Варианты для проверки: ${directory.toAbsolutePath()}")
    }
}
