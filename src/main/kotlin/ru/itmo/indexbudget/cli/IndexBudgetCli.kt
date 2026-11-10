package ru.itmo.indexbudget.cli

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.application.AnalysisRunner
import ru.itmo.indexbudget.application.OutputDirectoryValidator
import ru.itmo.indexbudget.application.UnavailableAnalysisRunner
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.AnalysisPhase
import ru.itmo.indexbudget.domain.ErrorCode
import ru.itmo.indexbudget.domain.RunContext
import ru.itmo.indexbudget.domain.SearchResult
import ru.itmo.indexbudget.domain.WorkloadInput
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Component
class IndexBudgetCli(
    private val workloadReader: WorkloadReader,
    private val outputDirectoryValidator: OutputDirectoryValidator = OutputDirectoryValidator(),
    private val analysisRunner: AnalysisRunner = UnavailableAnalysisRunner(),
) {
    private val diagnosticMapper = JsonMapper.builder().build()

    fun execute(
        args: Array<String>,
        out: PrintWriter = PrintWriter(System.out, true),
        err: PrintWriter = PrintWriter(System.err, true),
        context: RunContext = RunContext(),
    ): Int {
        context.observeProgress { progress ->
            val counters = progress.statistics
            err.println("stage=${progress.phase.name.lowercase()} explain_attempts=${counters.explainAttempts} " +
                "completed_sets=${counters.completedEvaluations} cache_hits=${counters.cacheHits}")
        }
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
            return fail(err, ErrorCode.INVALID_INPUT, "Expected analyze, --help or --version.")
        }
        val options = linkedMapOf<String, String>()
        var offset = 1
        while (offset < args.size) {
            val option = args[offset]
            val value = args.getOrNull(offset + 1)
            if (option !in ANALYZE_OPTIONS || option in options ||
                value == null || value.startsWith("--") || value.isBlank()) {
                return fail(err, ErrorCode.INVALID_INPUT, "Expected --workload <path> --output-dir <path>, once each.")
            }
            options[option] = value
            offset += 2
        }
        if (options.keys != ANALYZE_OPTIONS) {
            return fail(err, ErrorCode.INVALID_INPUT, "Both --workload and --output-dir are required.")
        }
        val input: WorkloadInput
        val outputPath: Path
        try {
            context.enterPhase(AnalysisPhase.VALIDATION)
            val workloadPath = Path.of(options.getValue("--workload"))
            outputPath = Path.of(options.getValue("--output-dir"))
            input = workloadReader.read(workloadPath, context)
            context.checkDeadline()
            outputDirectoryValidator.validate(outputPath)
            context.checkDeadline()
            context.completePhase()
        } catch (failure: AnalysisException) {
            return fail(err, failure.error)
        } catch (_: InvalidPathException) {
            return fail(err, ErrorCode.INVALID_INPUT, "Invalid workload or output path.")
        } catch (_: IOException) {
            return fail(err, ErrorCode.INVALID_INPUT, "Cannot read the workload file.")
        } catch (_: JacksonException) {
            return fail(err, ErrorCode.INVALID_INPUT, "Workload JSON does not match the v2 input structure.")
        } catch (_: IllegalArgumentException) {
            return fail(err, ErrorCode.INVALID_INPUT, "Workload JSON does not match the v2 input structure.")
        }

        return try {
            val result = analysisRunner.run(input, outputPath, context)
            if (result is SearchResult.Failed) {
                fail(err, result.error)
            } else {
                if (result is SearchResult.Partial) {
                    result.diagnostics.errors.forEach { printDiagnostic(err, it) }
                }
                result.status.exitCode
            }
        } catch (failure: AnalysisException) {
            fail(err, failure.error)
        } catch (_: Exception) {
            fail(err, ErrorCode.INTERNAL_ERROR, "Analysis failed unexpectedly.")
        }
    }

    private fun fail(err: PrintWriter, code: ErrorCode, message: String): Int {
        return fail(err, AnalysisError(code, message))
    }

    private fun fail(err: PrintWriter, error: AnalysisError): Int {
        printDiagnostic(err, error)
        return error.code.defaultExitCode
    }

    private fun printDiagnostic(err: PrintWriter, error: AnalysisError) {
        val diagnostic = linkedMapOf<String, Any>("code" to error.code.name, "message" to error.message)
        error.queryId?.let { diagnostic["query_id"] = it }
        error.profileId?.let { diagnostic["profile_id"] = it }
        if (error.details.isNotEmpty()) diagnostic["details"] = error.details
        // JSON экранирует переводы строк и управляющие символы в пользовательских IDs.
        err.println("${error.code.name}: ${diagnosticMapper.writeValueAsString(diagnostic)}")
    }

    companion object {
        private val ANALYZE_OPTIONS = setOf("--workload", "--output-dir")
        private val USAGE = """
            IndexBudget — PostgreSQL index selection CLI

            Usage:
              indexbudget --help
              indexbudget --version
              indexbudget analyze --workload <path> --output-dir <path>

            Validates JSON v2, shared queries, frequency profiles and budgets.
            Use a new or empty output directory for each run.
            Runs the analysis pipeline when all production components are connected.
            Missing components produce NOT_IMPLEMENTED (exit 1), without reports or a database connection.
            Progress on stderr contains stages and actual EXPLAIN/set/cache counters.

            Exit codes: 0 completed/help; 1 failed; 2 invalid input; 3 partial.
            PostgreSQL configuration for the pipeline: INDEXBUDGET_DATABASE_URL.
        """.trimIndent()
    }
}
