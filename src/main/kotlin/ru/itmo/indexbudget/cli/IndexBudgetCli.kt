package ru.itmo.indexbudget.cli

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.application.OutputDirectoryValidator
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.domain.AnalysisError
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
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
) {
    private val diagnosticMapper = JsonMapper.builder().build()

    fun execute(
        args: Array<String>,
        out: PrintWriter = PrintWriter(System.out, true),
        err: PrintWriter = PrintWriter(System.err, true),
    ): Int {
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
        try {
            val workloadPath = Path.of(options.getValue("--workload"))
            val outputPath = Path.of(options.getValue("--output-dir"))
            workloadReader.read(workloadPath)
            outputDirectoryValidator.validate(outputPath)
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

        // Never present fixture output as a successful real analysis.
        // SQL analysis, the PostgreSQL session and search are integrated in subsequent tasks.
        return fail(err, ErrorCode.NOT_IMPLEMENTED, "This skeleton has no analysis pipeline yet. No reports were created.")
    }

    private fun fail(err: PrintWriter, code: ErrorCode, message: String): Int {
        return fail(err, AnalysisError(code, message))
    }

    private fun fail(err: PrintWriter, error: AnalysisError): Int {
        val diagnostic = linkedMapOf<String, Any>("code" to error.code.name, "message" to error.message)
        error.queryId?.let { diagnostic["query_id"] = it }
        error.profileId?.let { diagnostic["profile_id"] = it }
        if (error.details.isNotEmpty()) diagnostic["details"] = error.details
        // JSON экранирует переводы строк и управляющие символы в пользовательских IDs.
        err.println("${error.code.name}: ${diagnosticMapper.writeValueAsString(diagnostic)}")
        return error.code.defaultExitCode
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
            The analysis pipeline is not implemented; analyze exits with code 1
            after validation and does not create reports or connect to a database.

            Exit codes: 0 completed/help; 1 failed; 2 invalid input; 3 partial.
            PostgreSQL configuration for the pipeline: INDEXBUDGET_DATABASE_URL.
        """.trimIndent()
    }
}
