package ru.itmo.indexbudget.cli

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.application.AnalysisRunner
import ru.itmo.indexbudget.application.OutputDirectoryValidator
import ru.itmo.indexbudget.application.UnavailableAnalysisRunner
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.AnalysisMode
import ru.itmo.indexbudget.domain.AnalysisPhase
import ru.itmo.indexbudget.domain.CandidateRanking
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.SearchResult
import tools.jackson.core.JacksonException
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Component
class IndexBudgetCli(
    private val workloadReader: WorkloadReader,
    private val outputDirectoryValidator: OutputDirectoryValidator = OutputDirectoryValidator(),
    private val analysisRunner: AnalysisRunner = UnavailableAnalysisRunner(),
    private val statisticsCommand: StatisticsCommand = StatisticsCommand(),
    private val budgetSeriesCommand: BudgetSeriesCommand = BudgetSeriesCommand(workloadReader),
    private val importWorkloadCommand: ImportWorkloadCommand = ImportWorkloadCommand(),
) {

    fun execute(
        args: Array<String>,
        out: PrintWriter = PrintWriter(System.out, true),
        err: PrintWriter = PrintWriter(System.err, true),
        context: RunContext = RunContext(),
    ): Int {
        if (args.firstOrNull() == "stats") return statisticsCommand.execute(args, out, err)
        if (args.firstOrNull() == "analyze-series") return budgetSeriesCommand.execute(args, out, err, context)
        if (args.firstOrNull() == "import-workload") return importWorkloadCommand.execute(args, out, err)
        context.observeProgress { CliDiagnostics.progress(err, it) }
        if (args.isEmpty() || args.contentEquals(arrayOf("--help")) ||
            args.contentEquals(arrayOf("analyze", "--help"))) {
            out.println(USAGE)
            return 0
        }
        if (args.contentEquals(arrayOf("--version"))) {
            out.println("indexbudget 0.1.0-SNAPSHOT")
            return 0
        }
        if (args.first() != "analyze") {
            return fail(err, ErrorCode.INVALID_INPUT, "Expected analyze, analyze-series, stats, --help or --version.")
        }
        val options = linkedMapOf<String, String>()
        var offset = 1
        while (offset < args.size) {
            val option = args[offset]
            val value = args.getOrNull(offset + 1)
            if (option !in ANALYZE_OPTIONS || option in options ||
                value == null || value.startsWith("--") || value.isBlank()) {
                return fail(err, ErrorCode.INVALID_INPUT,
                    "Expected --workload <path> --output-dir <path> [--analysis-mode fast|normal|deep] " +
                        "[--candidate-ranking frequency|baseline_cost], once each.")
            }
            options[option] = value
            offset += 2
        }
        if (!options.keys.containsAll(REQUIRED_ANALYZE_OPTIONS)) {
            return fail(err, ErrorCode.INVALID_INPUT, "Both --workload and --output-dir are required.")
        }
        val modeOverride = options["--analysis-mode"]?.let { value ->
            AnalysisMode.entries.firstOrNull { it.name.lowercase() == value }
                ?: return fail(err, ErrorCode.INVALID_INPUT, "--analysis-mode must be fast, normal or deep.")
        }
        val rankingOverride = options["--candidate-ranking"]?.let { value ->
            CandidateRanking.entries.firstOrNull { it.wireName == value }
                ?: return fail(err, ErrorCode.INVALID_INPUT, "--candidate-ranking must be frequency or baseline_cost.")
        }
        var effectiveRanking: CandidateRanking? = null
        val (input, outputPath) = try {
            // JSON may request deep; use the maximum only until the reader resolves its mode.
            // Applying the selected preset keeps elapsed startup/reading time on the same clock.
            context.selectAnalysisMode(modeOverride ?: AnalysisMode.DEEP)
            context.enterPhase(AnalysisPhase.VALIDATION)
            val workloadPath = Path.of(options.getValue("--workload"))
            val outputPath = Path.of(options.getValue("--output-dir"))
            val input = workloadReader.read(workloadPath, context, modeOverride, rankingOverride)
            effectiveRanking = input.candidateRanking
            context.checkDeadline()
            outputDirectoryValidator.validate(outputPath)
            context.checkDeadline()
            context.completePhase()
            input to outputPath
        } catch (failure: AnalysisException) {
            return failValidation(err, context, failure.error, effectiveRanking)
        } catch (_: InvalidPathException) {
            return failValidation(err, context,
                AnalysisError(ErrorCode.INVALID_INPUT, "Invalid workload or output path."), effectiveRanking)
        } catch (_: IOException) {
            return failValidation(err, context,
                AnalysisError(ErrorCode.INVALID_INPUT, "Cannot read the workload file."), effectiveRanking)
        } catch (_: JacksonException) {
            return failValidation(err, context,
                AnalysisError(ErrorCode.INVALID_INPUT, "Workload JSON does not match the v2 input structure."), effectiveRanking)
        } catch (_: IllegalArgumentException) {
            return failValidation(err, context,
                AnalysisError(ErrorCode.INVALID_INPUT, "Workload JSON does not match the v2 input structure."), effectiveRanking)
        }
        printRunLimits(err, context, input.candidateRanking)
        err.println("stage=validation index_methods=" + input.indexMethods.sortedBy { it.ordinal }.joinToString(",") { it.wireName })

        return try {
            val result = analysisRunner.run(input, outputPath, context)
            val reasons = result.diagnostics.stopReasons.map { it.name } +
                listOfNotNull((result as? SearchResult.Failed)?.error?.code?.name)
            printSummary(err, context, result.status.name.lowercase(), reasons.distinct(), input.candidateRanking)
            if (result is SearchResult.Failed) {
                fail(err, result.error)
            } else {
                if (result is SearchResult.Partial) {
                    result.diagnostics.errors.forEach { printDiagnostic(err, it) }
                }
                result.status.exitCode
            }
        } catch (failure: AnalysisException) {
            printSummary(err, context, "failed", listOf(failure.error.code.name), input.candidateRanking)
            fail(err, failure.error)
        } catch (_: Exception) {
            printSummary(err, context, "failed", listOf(ErrorCode.INTERNAL_ERROR.name), input.candidateRanking)
            fail(err, ErrorCode.INTERNAL_ERROR, "Analysis failed unexpectedly.")
        }
    }

    private fun printRunLimits(err: PrintWriter, context: RunContext, ranking: CandidateRanking? = null) {
        CliDiagnostics.limits(err, context.analysisMode, context.limits, ranking)
    }

    private fun failValidation(
        err: PrintWriter, context: RunContext, error: AnalysisError, ranking: CandidateRanking?,
    ): Int {
        printRunLimits(err, context, ranking)
        printSummary(err, context, "failed", listOf(error.code.name), ranking)
        return fail(err, error)
    }

    private fun printSummary(
        err: PrintWriter, context: RunContext, status: String, stopReasons: List<String>, ranking: CandidateRanking? = null,
    ) {
        CliDiagnostics.summary(err, context.analysisMode, context.statistics(), status, stopReasons, ranking)
    }

    private fun fail(err: PrintWriter, code: ErrorCode, message: String): Int {
        return fail(err, AnalysisError(code, message))
    }

    private fun fail(err: PrintWriter, error: AnalysisError): Int {
        printDiagnostic(err, error)
        return error.code.defaultExitCode
    }

    private fun printDiagnostic(err: PrintWriter, error: AnalysisError) {
        CliDiagnostics.error(err, error)
    }

    companion object {
        private val REQUIRED_ANALYZE_OPTIONS = setOf("--workload", "--output-dir")
        private val ANALYZE_OPTIONS = REQUIRED_ANALYZE_OPTIONS + setOf("--analysis-mode", "--candidate-ranking")
        private val USAGE = """
            IndexBudget — PostgreSQL index selection CLI

            Usage:
              indexbudget --help
              indexbudget --version
              indexbudget analyze --workload <path> --output-dir <path> [--analysis-mode fast|normal|deep] [--candidate-ranking frequency|baseline_cost]
              indexbudget analyze-series --help
              indexbudget stats --help
              indexbudget import-workload --help

            Validates JSON v2, shared queries, frequency profiles and budgets.
            Optional index_methods: nonempty subset of ["btree", "brin", "hash"]; default ["btree"], shared limits.
            Optional manual_indexes: 1–5 structured B-tree/BRIN/hash definitions; method defaults to btree.
            BRIN: one range WHERE column, default parameters/opclass, no INCLUDE or sort options.
            Hash: one simple equality WHERE column, default parameters/opclass, no INCLUDE or sort options.
            Use a new or empty output directory for each run.
            Runs SQL resolution, bounded shortlist, HypoPG, greedy + repair and HTML/JSON/SQL export.
            Requires a prepared PostgreSQL database with HypoPG; analysis creates no physical indexes.
            Progress on stderr contains stages and actual EXPLAIN/set/cache counters.
            Candidate preparation starts with up to 16 candidates and a reserve of up to 48.
            Budget rejections are refilled from that reserve, with at most 32 size calls,
            including failed calls, under the shared search/command deadline.

            Analysis modes (CLI overrides optional JSON analysis_mode; default: normal):
              fast:   command 30s, search 20s, EXPLAIN attempts 500, repair attempts 8.
              normal: command 90s, search 60s, EXPLAIN attempts 2000, repair attempts 28.
              deep:   command 300s, search 240s, EXPLAIN attempts 10000, repair attempts 256.
            Every mode uses a 2s EXPLAIN timeout and a 5s connection timeout, capped by remaining time.
            Search budget starts before baseline. Modes keep the same pool of up to 16 candidates
            and greedy/fixed-S0 repair. Limits are budgets; exact runtime or better quality is not guaranteed.
            Candidate ranking (CLI overrides optional JSON candidate_ranking; default: frequency):
              frequency: normalized scenario frequencies (original strategy).
              baseline_cost: frequencies multiplied by saved baseline planner costs (experimental).
            Ranking orders scenario queues only; scenario quotas, arity diversity and search criteria stay the same.
            Final stderr summary includes all counters and stop reasons.

            Exit codes: 0 completed/help; 1 failed; 2 invalid input; 3 partial.
            PostgreSQL configuration for the pipeline: INDEXBUDGET_DATABASE_URL.
        """.trimIndent()
    }
}
