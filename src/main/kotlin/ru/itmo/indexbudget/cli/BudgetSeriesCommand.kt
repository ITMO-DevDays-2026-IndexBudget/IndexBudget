package ru.itmo.indexbudget.cli

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.application.*
import ru.itmo.indexbudget.domain.*
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration

/** Один вход и одна настройка поиска для всех точек; вычисления принадлежат application. */
@Component
class BudgetSeriesCommand(
    private val workloadReader: WorkloadReader,
    private val seriesReader: BudgetSeriesReader = BudgetSeriesReader(),
    private val outputDirectoryValidator: OutputDirectoryValidator = OutputDirectoryValidator(),
    private val runner: BudgetSeriesRunner = BudgetSeriesRunner { _, _, _, _ ->
        throw AnalysisException(AnalysisError(ErrorCode.NOT_IMPLEMENTED, "Budget series analysis is not connected."))
    },
) {
    private val mapper = JsonMapper.builder().build()

    fun execute(args: Array<String>, out: PrintWriter, err: PrintWriter, context: RunContext): Int {
        if (args.contentEquals(arrayOf("analyze-series", "--help"))) {
            out.println(HELP)
            return 0
        }
        context.observeProgress { progress ->
            val counters = progress.statistics
            err.println("stage=${progress.phase.name.lowercase()} explain_attempts=${counters.explainAttempts} " +
                "completed_sets=${counters.completedEvaluations} cache_hits=${counters.cacheHits}")
        }
        return try {
            val options = options(args)
            val mode = options["--analysis-mode"]?.let { value ->
                AnalysisMode.entries.firstOrNull { it.name.lowercase() == value }
                    ?: invalid("--analysis-mode must be fast, normal or deep.")
            }
            val ranking = options["--candidate-ranking"]?.let { value ->
                CandidateRanking.entries.firstOrNull { it.wireName == value }
                    ?: invalid("--candidate-ranking must be frequency or baseline_cost.")
            }
            val timeout = options["--series-timeout-seconds"]?.let { value ->
                value.toLongOrNull()?.takeIf { it in 1L..86400L }
                    ?: invalid("--series-timeout-seconds must be an integer from 1 to 86400.")
            }
            context.selectAnalysisMode(mode ?: AnalysisMode.DEEP)
            context.enterPhase(AnalysisPhase.VALIDATION)
            val series = seriesReader.read(Path.of(options.getValue("--series")), context)
                .let { if (timeout == null) it else it.copy(timeoutSeconds = timeout) }
            // The series clock already includes startup and reading the series request.
            // Apply its deadline before workload validation; mode selection preserves this cap.
            context.selectSeriesTimeout(Duration.ofSeconds(series.timeoutSeconds))
            val input = workloadReader.read(Path.of(options.getValue("--workload")), context, mode, ranking)
            if (series.budget == SeriesBudget.WRITE && input.tableWriteRates == null) {
                throw AnalysisException(AnalysisError(ErrorCode.INVALID_WRITE_PROFILE,
                    "A write budget series requires a complete write profile.", details = mapOf("field" to "table_write_rates")))
            }
            series.validate(input)
            val output = Path.of(options.getValue("--output-dir"))
            outputDirectoryValidator.validate(output)
            context.checkDeadline()
            context.completePhase()
            err.println("stage=series budget=${series.budget.wireName} points=${series.values.size} " +
                "series_timeout_seconds=${series.timeoutSeconds} analysis_mode=${input.analysisMode.name.lowercase()} " +
                "candidate_ranking=${input.candidateRanking.wireName}")
            val result = runner.run(input, series, output, context)
            result.points.forEach { point ->
                err.println("stage=series_point ordinal=${point.ordinal} status=${point.status.name.lowercase()} " +
                    "conditions_match=${point.conditionsMatch ?: "unknown"}")
                point.error?.let { diagnostic(err, it) }
            }
            result.preparationError?.let { diagnostic(err, it) }
            err.println("stage=series_finished status=${result.status.name.lowercase()}")
            result.exitCode
        } catch (failure: AnalysisException) {
            fail(err, failure.error)
        } catch (_: InvalidPathException) {
            fail(err, AnalysisError(ErrorCode.INVALID_INPUT, "Invalid workload, series or output path."))
        } catch (_: IOException) {
            fail(err, AnalysisError(ErrorCode.INVALID_INPUT, "Cannot read the workload or series file."))
        } catch (_: JacksonException) {
            fail(err, AnalysisError(ErrorCode.INVALID_INPUT, "Invalid workload or series JSON."))
        } catch (_: IllegalArgumentException) {
            fail(err, AnalysisError(ErrorCode.INVALID_INPUT, "Invalid workload or series options."))
        } catch (_: Exception) {
            fail(err, AnalysisError(ErrorCode.INTERNAL_ERROR, "Budget series failed unexpectedly."))
        }
    }

    private fun options(args: Array<String>): Map<String, String> {
        val options = linkedMapOf<String, String>()
        var index = 1
        while (index < args.size) {
            val option = args[index]
            val value = args.getOrNull(index + 1)
            if (option !in OPTIONS || option in options || value == null || value.startsWith("--") || value.isBlank()) {
                invalid("Invalid series options; see analyze-series --help.")
            }
            options[option] = value
            index += 2
        }
        if (!options.keys.containsAll(REQUIRED_OPTIONS)) invalid("--workload, --series and --output-dir are required.")
        return options
    }

    private fun invalid(message: String): Nothing = throw AnalysisException(AnalysisError(ErrorCode.INVALID_INPUT, message))

    private fun fail(err: PrintWriter, error: AnalysisError): Int {
        diagnostic(err, error)
        return error.code.defaultExitCode
    }

    private fun diagnostic(err: PrintWriter, error: AnalysisError) {
        val safe = linkedMapOf<String, Any>("code" to error.code.name, "message" to error.message)
        if (error.details.isNotEmpty()) safe["details"] = error.details
        error.queryId?.let { safe["query_id"] = it }
        error.profileId?.let { safe["profile_id"] = it }
        err.println("${error.code.name}: ${mapper.writeValueAsString(safe)}")
    }

    companion object {
        private val REQUIRED_OPTIONS = setOf("--workload", "--series", "--output-dir")
        private val OPTIONS = REQUIRED_OPTIONS + setOf("--analysis-mode", "--candidate-ranking", "--series-timeout-seconds")
        val HELP = """
            indexbudget analyze-series --workload <workload-v2.json> --series <series-v1.json> --output-dir <empty directory>
            Optional: --analysis-mode fast|normal|deep --candidate-ranking frequency|baseline_cost
                      --series-timeout-seconds <1..86400> (overrides JSON timeout_seconds; default 600)
            Series JSON v1: {"schema_version":1,"budget":"storage_budget_bytes","values":[0,1048576,4194304],"timeout_seconds":600}
            budget: storage_budget_bytes (integer bytes) or write_budget_units_per_hour (finite numbers).
            Supply 3 to 5 distinct nonnegative budgets; their input order is preserved.
            SQL, profiles, objective, ranking, search mode and other budgets remain fixed.
            Prepare one pool of up to 16 candidates at the maximum budget, with refill only during preparation.
            Every point uses a fresh RunContext, JDBC session and cache, and publishes separate JSON/HTML/SQL.
            series.json records point statuses, selected indexes, metrics, budgets and relative artifact links.
            Baseline/catalog changes are marked as non-comparable; planner cost is not runtime.
            Series deadline and cancellation preserve earlier published points; later points are marked stopped.
            Connection: INDEXBUDGET_DATABASE_URL. No physical indexes or EXPLAIN ANALYZE.
            Exit: 0 completed; 3 partial; 2 invalid input; 1 failed.
        """.trimIndent()
    }
}
