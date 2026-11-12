package ru.itmo.indexbudget.fixtures

import ru.itmo.indexbudget.application.*
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.report.AtomicReportExporter
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.HtmlReportRenderer
import java.math.BigDecimal
import java.nio.file.Path

/** Real series orchestration/export with synthetic saved evaluations; no database or timing measurements. */
object BudgetSeriesFixtures {
    val variants = listOf("mixed", "same", "nonmonotonic", "zero-baseline", "no-write", "write", "regression", "observed-write")

    fun generate(directory: Path, variant: String) {
        val source = when (variant) {
            "observed-write" -> HtmlReportFixtures.observedWrite()
            "zero-baseline" -> HtmlReportFixtures.zeroBaseline()
            "regression" -> HtmlReportFixtures.regression()
            "no-write" -> ContractFixtures.report(withWriteProfile = false)
            else -> HtmlReportFixtures.completed()
        }
        val input = requireNotNull(source.input)
        val reference = SeriesConditions("synthetic-catalog", requireNotNull(source.result.baseline).costByQueryId,
            "synthetic-plans", DatabaseVersions("16 synthetic", "1.4 synthetic"), "synthetic-source")
        val parent = RunContext(runId = "synthetic-series-$variant", clock = NanoClock { 0L })
            .also { it.recordInputHash("f".repeat(64)) }
        val request = BudgetSeriesRequest(if (variant in setOf("write", "observed-write")) SeriesBudget.WRITE else SeriesBudget.STORAGE,
            (if (variant == "mixed") listOf("0", "1048576", "2097152", "3145728", "4194304")
            else if (variant in setOf("write", "observed-write")) listOf("10000.5", "20000.75", "30000.125")
            else listOf("3145728", "1048576", "2097152")).map(::BigDecimal))
        var ordinal = 0
        val runner = object : SeriesAnalysisRunner {
            override fun prepareSeriesPool(input: WorkloadInput, context: RunContext): SeriesPreparation {
                source.statementKinds.forEach { (id, kind) -> context.recordStatementKind(id, kind) }
                return SeriesPreparation(PreparedCandidatePool(input, source.candidates, source.candidates.candidates), reference)
            }

            override fun runSeriesPoint(input: WorkloadInput, outputDirectory: Path, context: RunContext,
                pool: PreparedCandidatePool): SeriesPointRun {
                ordinal++
                source.statementKinds.forEach { (id, kind) -> context.recordStatementKind(id, kind) }
                val base = source.result as SearchResult.Completed
                val result = when {
                    variant == "mixed" && ordinal == 1 -> {
                        val baseline = requireNotNull(base.baseline)
                        val calculator = MetricsCalculatorImpl()
                        val config = EvaluatedConfiguration(baseline, calculator.calculate(input, baseline, baseline,
                            calculator.assessBudget(emptyList(), input).usage))
                        base.copy(bestRobust = config, bestMean = config)
                    }
                    variant == "mixed" && ordinal == 3 -> ContractFixtures.partialResult()
                    variant == "mixed" && ordinal == 4 -> {
                        parent.cancel()
                        ContractFixtures.failedResult()
                    }
                    variant == "nonmonotonic" && ordinal != 2 -> base.copy(bestRobust = base.bestMean)
                    else -> base
                }
                val report = DefaultReportBuilder(environment = source.environment)
                    .build(input, source.candidates, result, context)
                AtomicReportExporter(HtmlReportRenderer()).export(report, outputDirectory, context)
                return SeriesPointRun(result,
                    if (variant == "mixed" && ordinal == 3) reference.copy(catalogHash = "synthetic-changed-catalog") else reference,
                    report)
            }
        }
        BudgetSeriesPipeline(runner, ShutdownCancellation { AutoCloseable {} }).run(input, request, directory, parent)
    }
}
