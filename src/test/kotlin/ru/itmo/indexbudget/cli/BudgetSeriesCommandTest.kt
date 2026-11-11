package ru.itmo.indexbudget.cli

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.*
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import tools.jackson.databind.json.JsonMapper
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
    fun `partial points print scoped limits counters stop reasons and saved errors`() {
        val prepLimits = RunLimits(Duration.ofSeconds(11), Duration.ofSeconds(7), Duration.ofMillis(400),
            Duration.ofMillis(900), 17, 2)
        val pointLimits = RunLimits(Duration.ofSeconds(19), Duration.ofSeconds(13), Duration.ofMillis(700),
            Duration.ofMillis(1200), 31, 4)
        val savedError = AnalysisError(ErrorCode.EXPLAIN_FAILED, "A saved evaluation failed.", queryId = "q\n\"quoted")
        val preparation = SearchDiagnostics(limits = prepLimits,
            statistics = RunStatistics(2, 1, 1, 0, 0))
        val partial = ContractFixtures.partialResult().copy(diagnostics = SearchDiagnostics(
            limits = pointLimits, statistics = RunStatistics(9, 4, 3, 2, 1),
            stopReasons = listOf(StopReason.EXPLAIN_LIMIT), errors = listOf(savedError)))
        val runner = BudgetSeriesRunner { _, request, _, supplied ->
            val prep = supplied.child(AnalysisMode.FAST, scope = RunScope.PREPARATION)
            prep.enterPhase(AnalysisPhase.BASELINE)
            prep.reserveEvaluationAttempt()
            prep.reserveExplainAttempt()
            prep.recordCompletedEvaluation()
            val first = supplied.child(AnalysisMode.FAST, scope = RunScope.POINT, ordinal = 1)
            first.enterPhase(AnalysisPhase.BASELINE)
            first.reserveEvaluationAttempt()
            val second = supplied.child(AnalysisMode.FAST, scope = RunScope.POINT, ordinal = 2)
            second.enterPhase(AnalysisPhase.BASELINE)
            BudgetSeriesResult(SeriesStatus.PARTIAL, listOf(
                BudgetSeriesPoint(1, request.values[0], SeriesPointStatus.PARTIAL, result = partial,
                    conditionsMatch = true),
                BudgetSeriesPoint(2, request.values[1], SeriesPointStatus.SKIPPED,
                    error = AnalysisError(ErrorCode.CANCELLED, "Series cancelled."))), preparationDiagnostics = preparation)
        }
        val (code, stderr) = invoke(runner, Path.of("examples/workload-v2.json"), writeSeries(), RunContext(),
            "--analysis-mode", "fast")
        assertEquals(3, code)
        val prep = fields(stderr.lineSequence().single { it.startsWith("stage=series_preparation ") })
        assertEquals("preparation", prep["scope"])
        assertEquals("2", prep["explain_attempts"])
        assertEquals("1", prep["evaluation_attempts"])
        val point = fields(stderr.lineSequence().single { it.startsWith("stage=series_point ") && it.contains("ordinal=1") })
        assertEquals("point", point["scope"])
        assertEquals("partial", point["status"])
        assertEquals("fast", point["analysis_mode"])
        assertEquals("EXPLAIN_LIMIT", point["stop_reasons"])
        assertEquals(mapOf("explain_attempts" to "9", "evaluation_attempts" to "4", "completed_sets" to "3",
            "cache_hits" to "2", "repair_attempts" to "1"), point.filterKeys { it in setOf("explain_attempts",
                "evaluation_attempts", "completed_sets", "cache_hits", "repair_attempts") })
        val limits = fields(stderr.lineSequence().single { it.startsWith("stage=series_point_limits ") })
        assertEquals(mapOf("command_timeout_millis" to "19000", "search_timeout_millis" to "13000",
            "explain_timeout_millis" to "700", "connect_timeout_millis" to "1200", "max_explain_attempts" to "31",
            "max_repair_attempts" to "4"), limits.filterKeys { it.endsWith("_millis") || it.startsWith("max_") })
        val preparationLimits = fields(stderr.lineSequence().single { it.startsWith("stage=series_preparation_limits ") })
        assertEquals("11000", preparationLimits["command_timeout_millis"])
        assertEquals("17", preparationLimits["max_explain_attempts"])
        val errors = stderr.lineSequence().filter { it.startsWith("EXPLAIN_FAILED:") }.toList()
        assertEquals(1, errors.size)
        val error = JsonMapper.builder().build().readTree(errors.single().substringAfter(": "))
        assertEquals(savedError.queryId, error["query_id"].stringValue())
        assertEquals("point", error["scope"].stringValue())
        assertEquals(1, error["ordinal"].intValue())
        val live = stderr.lineSequence().filter { it.startsWith("stage=baseline ") }.map(::fields).toList()
        assertTrue(live.any { it["scope"] == "preparation" && it["evaluation_attempts"] == "1" })
        assertTrue(live.any { it["scope"] == "point" && it["ordinal"] == "1" && it["evaluation_attempts"] == "1" })
        assertTrue(live.any { it["scope"] == "point" && it["ordinal"] == "2" && it["evaluation_attempts"] == "0" })
        val skipped = fields(stderr.lineSequence().single { it.startsWith("stage=series_point ") && it.contains("ordinal=2") })
        assertEquals("CANCELLED", skipped["stop_reasons"])
        assertFalse(skipped.containsKey("explain_attempts"))
    }

    @Test
    fun `preparation and caught point errors print snapshots without duplicate diagnostics`() {
        val failure = AnalysisError(ErrorCode.DB_CONNECTION_ERROR, "Connection unavailable.")
        val diagnostics = SearchDiagnostics(statistics = RunStatistics(3, 2, 1, 0, 0),
            stopReasons = listOf(StopReason.CONNECTION_LOST), errors = listOf(failure))
        for (preparationFailed in listOf(true, false)) {
            val runner = BudgetSeriesRunner { _, request, _, _ ->
                if (preparationFailed) BudgetSeriesResult(SeriesStatus.FAILED, emptyList(),
                    preparationError = failure, preparationDiagnostics = diagnostics)
                else BudgetSeriesResult(SeriesStatus.PARTIAL, listOf(BudgetSeriesPoint(1, request.values[0],
                    SeriesPointStatus.FAILED, runId = "failed-point", error = failure, diagnostics = diagnostics)))
            }
            val (code, stderr) = invoke(runner, Path.of("examples/workload-v2.json"), writeSeries(), RunContext())
            assertEquals(if (preparationFailed) 1 else 3, code)
            val errors = stderr.lineSequence().filter { it.startsWith("DB_CONNECTION_ERROR:") }
                .map { JsonMapper.builder().build().readTree(it.substringAfter(": ")) }.toList()
            assertEquals(1, errors.size)
            assertEquals(if (preparationFailed) "preparation" else "point", errors.single()["scope"].stringValue())
            val stage = if (preparationFailed) "series_preparation" else "series_point"
            val failed = fields(stderr.lineSequence().single { it.startsWith("stage=$stage ") })
            assertEquals("failed", failed["status"])
            assertEquals("3", failed["explain_attempts"])
            assertEquals("2", failed["evaluation_attempts"])
            assertEquals("CONNECTION_LOST,DB_CONNECTION_ERROR", failed["stop_reasons"])
        }
    }

    private fun fields(line: String): Map<String, String> = line.split(' ').mapNotNull { entry ->
        val parts = entry.split('=', limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
    }.toMap()

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
