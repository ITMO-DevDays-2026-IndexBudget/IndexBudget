package ru.itmo.indexbudget.cli

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.*
import ru.itmo.indexbudget.domain.*
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class BudgetSeriesCommandTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `series command delegates one fixed input with overrides and source hash`() {
        val workload = Files.writeString(directory.resolve("workload.json"),
            Files.readString(Path.of("examples/workload-v2.json")).replaceFirst("{", "{\"analysis_mode\":\"fast\","))
        val sourceContext = RunContext()
        WorkloadReader().read(workload, sourceContext)
        val series = writeSeries()
        val context = RunContext()
        var calls = 0
        val runner = BudgetSeriesRunner { input, request, output, supplied ->
            calls++
            assertSame(context, supplied)
            assertEquals(sourceContext.inputHash, supplied.inputHash)
            assertEquals(AnalysisMode.DEEP, input.analysisMode)
            assertEquals(CandidateRanking.BASELINE_COST, input.candidateRanking)
            assertEquals(90L, request.timeoutSeconds)
            assertEquals(listOf(0L, 100L, 200L), request.values.map { it.toLong() })
            assertEquals(directory.resolve("output"), output)
            assertTrue(supplied.remainingCommandTime() <= Duration.ofSeconds(90))
            BudgetSeriesResult(SeriesStatus.PARTIAL, emptyList())
        }
        val (code, stderr) = invoke(runner, workload, series, context,
            "--analysis-mode", "deep", "--candidate-ranking", "baseline_cost", "--series-timeout-seconds", "90")
        assertEquals(3, code)
        assertEquals(1, calls)
        assertTrue(stderr.contains("series_timeout_seconds=90 analysis_mode=deep candidate_ranking=baseline_cost"), stderr)
        assertTrue(stderr.contains("stage=series_finished status=partial"), stderr)
    }

    @Test
    fun `invalid options and request do not start analysis or echo values`() {
        val workload = directory.resolve("missing-workload.json")
        val series = writeSeries()
        val runner = BudgetSeriesRunner { _, _, _, _ -> error("Rejected request must not start analysis") }
        val cases = listOf(
            arrayOf("--private-option", "private-password"),
            arrayOf("--analysis-mode", "private-password"),
            arrayOf("--candidate-ranking", "private-password"),
            arrayOf("--series-timeout-seconds", "0"),
            arrayOf("--series-timeout-seconds", "1.5"),
            arrayOf("--series-timeout-seconds", "86401"),
            arrayOf("--analysis-mode", "normal", "--analysis-mode", "deep"),
        )
        for (options in cases) {
            val (code, stderr) = invoke(runner, workload, series, RunContext(), *options)
            assertEquals(2, code, stderr)
            assertFalse(stderr.contains("private-password"), stderr)
        }
        Files.writeString(series, """{"schema_version":1,"budget":"storage_budget_bytes","values":[1,2]}""")
        assertEquals(2, invoke(runner, workload, series, RunContext()).first)
    }

    @Test
    fun `short series deadline bounds workload validation despite analysis mode selection`() {
        var nanos = 0L
        var active: RunContext? = null
        val context = RunContext(clock = NanoClock {
            // Once the request installs its one-second cap, advance time between checks.
            // Expiry must win over opening the missing workload path or starting analysis.
            if (active?.limits?.commandTimeout == Duration.ofSeconds(1)) nanos += 600_000_000L
            nanos
        }).also { active = it }
        val runner = BudgetSeriesRunner { _, _, _, _ -> error("Expired validation must not start analysis") }
        val (code, stderr) = invoke(runner, directory.resolve("private-password.json"), writeSeries(), context,
            "--series-timeout-seconds", "1", "--analysis-mode", "fast")
        assertEquals(1, code)
        assertTrue(stderr.contains("DEADLINE_EXCEEDED:"), stderr)
        assertFalse(stderr.contains("INVALID_INPUT:"), stderr)
        assertFalse(stderr.contains("private-password"), stderr)
        assertNull(context.inputHash)
        assertEquals(RunStatistics(), context.statistics())
    }

    @Test
    fun `write series requires manual write profile before runner`() {
        val workload = Files.writeString(directory.resolve("workload.json"), """
            {"schema_version":2,"queries":[{"id":"q","sql":"SELECT id FROM public.t WHERE id=1"}],
             "profiles":[{"id":"p","weight":1,"calls_per_hour":{"q":1}}],
             "constraints":{"storage_budget_bytes":100,"write_budget_units_per_hour":null}}
        """.trimIndent())
        val series = Files.writeString(directory.resolve("series.json"),
            """{"schema_version":1,"budget":"write_budget_units_per_hour","values":[0,100,200]}""")
        val runner = BudgetSeriesRunner { _, _, _, _ -> error("Incomplete write profile must not start analysis") }
        val (code, stderr) = invoke(runner, workload, series, RunContext())
        assertEquals(2, code)
        assertTrue(stderr.contains("INVALID_WRITE_PROFILE:"), stderr)
    }

    @Test
    fun `nonempty output remains untouched`() {
        val workload = Path.of("examples/workload-v2.json")
        val series = writeSeries()
        val artifact = Files.writeString(Files.createDirectory(directory.resolve("output")).resolve("series.json"), "previous")
        val runner = BudgetSeriesRunner { _, _, _, _ -> error("Nonempty destination must not start analysis") }
        val (code, stderr) = invoke(runner, workload, series, RunContext())
        assertEquals(2, code)
        assertTrue(stderr.contains("OUTPUT_DIRECTORY_NOT_EMPTY:"), stderr)
        assertEquals("previous", Files.readString(artifact))
    }

    @Test
    fun `help is available from main CLI without database or files`() {
        val stdout = StringWriter()
        val code = IndexBudgetCli(WorkloadReader()).execute(arrayOf("analyze-series", "--help"), PrintWriter(stdout, true))
        assertEquals(0, code)
        assertTrue(stdout.toString().contains("series.json"))
        assertTrue(stdout.toString().contains("--series-timeout-seconds"))
    }

    private fun writeSeries(): Path = Files.writeString(directory.resolve("series.json"),
        """{"schema_version":1,"budget":"storage_budget_bytes","values":[0,100,200],"timeout_seconds":60}""")

    private fun invoke(runner: BudgetSeriesRunner, workload: Path, series: Path, context: RunContext,
        vararg options: String): Pair<Int, String> {
        val stderr = StringWriter()
        val command = BudgetSeriesCommand(WorkloadReader(), runner = runner)
        val cli = IndexBudgetCli(WorkloadReader(), budgetSeriesCommand = command)
        val code = cli.execute(arrayOf("analyze-series", "--workload", workload.toString(), "--series", series.toString(),
            "--output-dir", directory.resolve("output").toString(), *options), PrintWriter(StringWriter(), true), PrintWriter(stderr, true), context)
        return code to stderr.toString()
    }
}
