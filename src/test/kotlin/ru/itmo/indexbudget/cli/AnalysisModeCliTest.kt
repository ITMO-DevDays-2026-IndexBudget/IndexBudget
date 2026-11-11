package ru.itmo.indexbudget.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.AnalysisRunner
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class AnalysisModeCliTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `old input and each JSON preset configure the same shared context before runner`() {
        for (mode in listOf(null) + AnalysisMode.entries) {
            val expected = mode ?: AnalysisMode.NORMAL
            var calls = 0
            val runner = AnalysisRunner { input, _, context ->
                calls++
                assertEquals(expected, input.analysisMode)
                assertEquals(expected, context.analysisMode)
                assertEquals(expected.limits, context.limits)
                ContractFixtures.completedResult()
            }
            val result = invoke(runner, workload(mode))

            assertEquals(0, result.code)
            assertEquals(1, calls)
            val limits = expected.limits
            assertTrue(result.stderr.contains("analysis_mode=${expected.name.lowercase()} " +
                "command_timeout_millis=${limits.commandTimeout.toMillis()} " +
                "search_timeout_millis=${limits.searchTimeout.toMillis()} " +
                "explain_timeout_millis=2000 connect_timeout_millis=5000 " +
                "max_explain_attempts=${limits.maxExplainAttempts} max_repair_attempts=${limits.maxRepairAttempts}"))
            assertTrue(result.stderr.contains("status=completed"))
            assertTrue(result.stderr.contains("stop_reasons=SEARCH_COMPLETE"))
        }
    }

    @Test
    fun `each CLI preset overrides every JSON preset without changing the source fingerprint`() {
        for (jsonMode in AnalysisMode.entries) {
            val path = workload(jsonMode)
            val original = Files.readString(path)
            val sourceContext = RunContext()
            WorkloadReader().read(path, sourceContext)
            for (cliMode in AnalysisMode.entries) {
                var calls = 0
                val runner = AnalysisRunner { input, _, context ->
                    calls++
                    assertEquals(cliMode, input.analysisMode)
                    assertEquals(cliMode, context.analysisMode)
                    assertEquals(cliMode.limits, context.limits)
                    assertEquals(sourceContext.inputHash, context.inputHash)
                    ContractFixtures.completedResult()
                }

                val result = invoke(runner, path, "--analysis-mode", cliMode.name.lowercase())

                assertEquals(0, result.code)
                assertEquals(1, calls)
                assertEquals(original, Files.readString(path))
            }
        }
    }

    @Test
    fun `late JSON mode selection preserves startup time and uses the chosen budget`() {
        val cases = AnalysisMode.entries.map { it to null } + (AnalysisMode.DEEP to AnalysisMode.FAST)
        for ((jsonMode, override) in cases) {
            var now = 0L
            val context = RunContext(clock = NanoClock { now })
            now = Duration.ofSeconds(100).toNanos()
            val expected = override ?: jsonMode
            val shouldRun = expected == AnalysisMode.DEEP
            var calls = 0
            val runner = AnalysisRunner { input, _, runContext ->
                calls++
                assertTrue(shouldRun, "Expired preset must not reach the runner")
                assertEquals(expected, input.analysisMode)
                assertEquals(Duration.ofSeconds(200), runContext.remainingCommandTime())
                ContractFixtures.completedResult()
            }
            val stderr = StringWriter()
            val options = if (override == null) emptyArray() else arrayOf("--analysis-mode", override.name.lowercase())
            val code = IndexBudgetCli(WorkloadReader(), analysisRunner = runner).execute(
                arrayOf("analyze", "--workload", workload(jsonMode).toString(), "--output-dir",
                    directory.resolve("late-output").toString(), *options),
                err = PrintWriter(stderr, true), context = context,
            )

            assertEquals(if (shouldRun) 0 else 1, code)
            assertEquals(if (shouldRun) 1 else 0, calls)
            assertEquals(expected, context.analysisMode)
            assertEquals(Duration.ofSeconds(100), context.elapsedTime())
            if (!shouldRun) assertTrue(stderr.toString().contains("DEADLINE_EXCEEDED:"))
        }
    }

    @Test
    fun `invalid missing and duplicate CLI mode values are rejected before runner without echoing input`() {
        val runner = AnalysisRunner { _, _, _ -> error("Invalid arguments must not run analysis") }
        for (arguments in listOf(
            arrayOf("--analysis-mode", "FAST"),
            arrayOf("--analysis-mode", "postgresql://user:private-password@host/db"),
            arrayOf("--analysis-mode", ""),
            arrayOf("--analysis-mode"),
            arrayOf("--analysis-mode", "fast", "--analysis-mode", "deep"),
        )) {
            val result = invoke(runner, workload(null), *arguments)

            assertEquals(2, result.code)
            assertTrue(result.stderr.contains("INVALID_INPUT:"))
            assertFalse(result.stderr.contains("private-password"))
        }
    }

    @Test
    fun `CLI override does not accept invalid JSON preset`() {
        val source = Files.readString(workload(null)).replaceFirst("{", "{\"analysis_mode\":null,")
        val path = Files.writeString(directory.resolve("invalid-mode.json"), source)
        val runner = AnalysisRunner { _, _, _ -> error("Invalid JSON must not run analysis") }

        val result = invoke(runner, path, "--analysis-mode", "deep")

        assertEquals(2, result.code)
        assertTrue(result.stderr.contains("analysis_mode"))
        assertTrue(result.stderr.contains("INVALID_INPUT:"))
    }

    @Test
    fun `partial summary preserves counters and stop reason and failed baseline remains failed`() {
        val partial = ContractFixtures.partialResult().let { result ->
            result.copy(diagnostics = result.diagnostics.copy(stopReasons = listOf(StopReason.EXPLAIN_LIMIT)))
        }
        val partialRunner = AnalysisRunner { _, _, context ->
            context.reserveEvaluationAttempt()
            context.reserveExplainAttempt()
            context.recordCompletedEvaluation()
            context.reserveRepairAttempt()
            context.reserveEvaluationAttempt()
            context.reserveExplainAttempt()
            context.recordCacheHit()
            partial
        }

        val result = invoke(partialRunner, workload(AnalysisMode.FAST))

        assertEquals(3, result.code)
        assertTrue(result.stderr.contains("stage=finished status=partial analysis_mode=fast explain_attempts=2 " +
            "evaluation_attempts=2 completed_sets=1 cache_hits=1 repair_attempts=1 stop_reasons=EXPLAIN_LIMIT"))

        val failed = invoke(AnalysisRunner { _, _, _ -> ContractFixtures.failedResult() }, workload(AnalysisMode.DEEP))
        assertEquals(1, failed.code)
        assertTrue(failed.stderr.contains("status=failed analysis_mode=deep"))
        assertTrue(failed.stderr.contains("stop_reasons=EVALUATION_FAILED,EXPLAIN_FAILED"))
    }

    @Test
    fun `help documents defaults precedence all preset limits and fixed search scope`() {
        val stdout = StringWriter()
        val code = IndexBudgetCli(WorkloadReader()).execute(arrayOf("analyze", "--help"), PrintWriter(stdout, true))
        val help = stdout.toString()

        assertEquals(0, code)
        assertTrue(help.contains("--analysis-mode fast|normal|deep"))
        assertTrue(help.contains("default: normal"))
        assertTrue(help.contains("CLI overrides"))
        assertTrue(help.contains("fast:   command 30s, search 20s, EXPLAIN attempts 500, repair attempts 8"))
        assertTrue(help.contains("normal: command 90s, search 60s, EXPLAIN attempts 2000, repair attempts 28"))
        assertTrue(help.contains("deep:   command 300s, search 240s, EXPLAIN attempts 10000, repair attempts 256"))
        assertTrue(help.contains("2s EXPLAIN timeout"))
        assertTrue(help.contains("5s connection timeout"))
        assertTrue(help.contains("up to 16 candidates"))
        assertTrue(help.contains("greedy/fixed-S0 repair"))
    }

    private fun workload(mode: AnalysisMode?): Path {
        val original = Files.readString(Path.of("examples/workload-v2.json"))
        val source = if (mode == null) original else original.replaceFirst("{",
            "{\"analysis_mode\":\"${mode.name.lowercase()}\",")
        return Files.writeString(directory.resolve("workload-${mode?.name ?: "legacy"}.json"), source)
    }

    private fun invoke(runner: AnalysisRunner, workload: Path, vararg options: String): Invocation {
        val stdout = StringWriter()
        val stderr = StringWriter()
        val cli = IndexBudgetCli(WorkloadReader(), analysisRunner = runner)
        val code = cli.execute(arrayOf("analyze", "--workload", workload.toString(), "--output-dir",
            directory.resolve("output").toString(), *options), PrintWriter(stdout, true), PrintWriter(stderr, true))
        return Invocation(code, stderr.toString())
    }

    private data class Invocation(val code: Int, val stderr: String)
}
