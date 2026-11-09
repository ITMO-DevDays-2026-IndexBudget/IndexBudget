package ru.itmo.indexbudget.cli

import org.springframework.stereotype.Component
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.domain.AnalysisException
import ru.itmo.indexbudget.domain.ErrorCode
import tools.jackson.core.JacksonException
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Component
class IndexBudgetCli(private val workloadReader: WorkloadReader) {
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
            if (option !in setOf("--workload", "--output-dir") || option in options ||
                offset + 1 >= args.size || args[offset + 1].startsWith("--")) {
                return fail(err, ErrorCode.INVALID_INPUT, "Expected --workload <path> --output-dir <path>, once each.")
            }
            options[option] = args[offset + 1]
            offset += 2
        }
        if (options.keys != setOf("--workload", "--output-dir")) {
            return fail(err, ErrorCode.INVALID_INPUT, "Both --workload and --output-dir are required.")
        }
        try {
            val workloadPath = Path.of(options.getValue("--workload"))
            Path.of(options.getValue("--output-dir"))
            workloadReader.read(workloadPath)
        } catch (failure: AnalysisException) {
            return fail(err, failure.error.code, failure.error.message)
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
        err.println("${code.name}: $message")
        return code.defaultExitCode
    }

    companion object {
        private val USAGE = """
            IndexBudget — PostgreSQL index selection CLI

            Usage:
              indexbudget --help
              indexbudget --version
              indexbudget analyze --workload <path> --output-dir <path>

            The current build provides the application skeleton and v2 contracts.
            The analysis pipeline is not implemented; analyze exits with code 1
            after reading the input and does not create reports or connect to a database.

            Exit codes: 0 completed/help; 1 failed; 2 invalid input; 3 partial.
            Future PostgreSQL configuration: INDEXBUDGET_DATABASE_URL.
        """.trimIndent()
    }
}
