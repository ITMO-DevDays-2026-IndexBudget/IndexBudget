package ru.itmo.indexbudget.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.TestAbortedException
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.application.AnalysisRunner
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class IndexBudgetCliTest {
    @TempDir
    lateinit var directory: Path

    private val cli = IndexBudgetCli(WorkloadReader())

    @Test
    fun `help works without database or input file`() {
        val result = invoke("--help")
        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("indexbudget analyze"))
        assertEquals("", result.diagnosticStderr)
    }

    @Test
    fun `valid workload does not pretend analysis has completed`() {
        val output = directory.resolve("report")
        val result = analyze(output)
        assertEquals(1, result.code)
        assertTrue(result.diagnosticStderr.startsWith("NOT_IMPLEMENTED:"))
        assertFalse(Files.exists(output))
        assertEquals("", result.stdout)
    }

    @Test
    fun `invalid workload does not leak its content into diagnostics`() {
        val input = directory.resolve("invalid.json")
        Files.writeString(input, """
            {"schema_version":2,
             "queries":"private-sql-and-password",
             "profiles":[{"id":"normal","weight":1,"calls_per_hour":{"q1":1}}],
             "constraints":{"storage_budget_bytes":10,"write_budget_units_per_hour":null}}
        """.trimIndent())
        val result = analyze(directory.resolve("report"), input)
        assertEquals(2, result.code)
        assertTrue(result.diagnosticStderr.startsWith("INVALID_INPUT:"))
        assertFalse(result.diagnosticStderr.contains("private-sql-and-password"))
        val diagnostic = JsonMapper.builder().build().readTree(result.diagnosticStderr.substringAfter(": "))
        assertEquals("INVALID_INPUT", diagnostic.get("code").stringValue())
        assertEquals("queries", diagnostic.get("details").get("field").stringValue())
    }

    @Test
    fun `legacy schema gets a specific error`() {
        val input = directory.resolve("v1.json")
        Files.writeString(input, """{"schema_version":1}""")
        val result = analyze(directory.resolve("report"), input)
        assertEquals(2, result.code)
        assertTrue(result.diagnosticStderr.startsWith("UNSUPPORTED_SCHEMA_VERSION:"))
        assertTrue(result.diagnosticStderr.contains("queries"))
        assertTrue(result.diagnosticStderr.contains("profiles"))
    }

    @Test
    fun `existing artifacts are rejected and preserved`() {
        val output = Files.createDirectory(directory.resolve("previous-run"))
        val artifact = Files.writeString(output.resolve("indexes.sql"), "previous SQL")

        val result = analyze(output)

        assertEquals(2, result.code)
        assertTrue(result.diagnosticStderr.startsWith("OUTPUT_DIRECTORY_NOT_EMPTY:"))
        assertTrue(result.diagnosticStderr.contains("output_dir"))
        assertEquals("previous SQL", Files.readString(artifact))
        Files.list(output).use { assertEquals(listOf(artifact), it.toList()) }
    }

    @Test
    fun `empty and nested new output directories pass validation without creating artifacts`() {
        val empty = Files.createDirectory(directory.resolve("empty"))
        val nested = directory.resolve("new-parent/run")

        for (output in listOf(empty, nested)) {
            val result = analyze(output)
            assertEquals(1, result.code)
            assertTrue(result.diagnosticStderr.startsWith("NOT_IMPLEMENTED:"))
        }
        Files.list(empty).use { assertEquals(0L, it.count()) }
        assertFalse(Files.exists(nested.parent))
    }

    @Test
    fun `output file and file parent are rejected without overwriting`() {
        val file = Files.writeString(directory.resolve("not-a-directory"), "retain me")

        for (output in listOf(file, file.resolve("run"))) {
            val result = analyze(output)
            assertEquals(2, result.code)
            assertTrue(result.diagnosticStderr.startsWith("INVALID_INPUT:"))
            assertTrue(result.diagnosticStderr.contains("output_dir"))
        }
        assertEquals("retain me", Files.readString(file))
    }

    @Test
    fun `dangling output link is not treated as an unused path`() {
        val target = directory.resolve("missing-target")
        val link = createSymbolicLinkOrSkip(directory.resolve("dangling-link"), target)

        val result = analyze(link)

        assertEquals(2, result.code)
        assertTrue(result.diagnosticStderr.startsWith("INVALID_INPUT:"))
        assertTrue(Files.isSymbolicLink(link))
        assertFalse(Files.exists(target))
    }

    @Test
    fun `parent traversal after a missing directory cannot bypass existing artifacts`() {
        val occupied = Files.createDirectory(directory.resolve("occupied"))
        val artifact = Files.writeString(occupied.resolve("report.json"), "previous report")
        val missing = directory.resolve("new")
        val output = missing.resolve("../occupied")

        val result = analyze(output)

        assertEquals(2, result.code)
        assertTrue(result.diagnosticStderr.startsWith("INVALID_INPUT:"))
        assertFalse(Files.exists(missing))
        assertEquals("previous report", Files.readString(artifact))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `parent traversal after a symlink follows POSIX semantics`() {
        val actual = Files.createDirectory(directory.resolve("actual"))
        val target = Files.createDirectory(actual.resolve("child"))
        val occupied = Files.createDirectory(actual.resolve("occupied"))
        val artifact = Files.writeString(occupied.resolve("report.json"), "previous report")
        Files.createDirectory(directory.resolve("occupied"))
        val link = createSymbolicLinkOrSkip(directory.resolve("link"), target)

        val result = analyze(link.resolve("../occupied"))

        assertEquals(2, result.code)
        assertTrue(result.diagnosticStderr.startsWith("OUTPUT_DIRECTORY_NOT_EMPTY:"))
        assertEquals("previous report", Files.readString(artifact))
    }

    @Test
    fun `read only output directory produces export failure without creating artifacts`() {
        val output = Files.createDirectory(directory.resolve("read-only-output"))

        withReadOnlyDirectory(output) {
            val result = analyze(output)

            assertEquals(1, result.code)
            assertTrue(result.diagnosticStderr.startsWith("EXPORT_FAILED:"))
            val diagnostic = JsonMapper.builder().build().readTree(result.diagnosticStderr.substringAfter(": "))
            assertEquals("EXPORT_FAILED", diagnostic.get("code").stringValue())
            assertEquals("output_dir", diagnostic.get("details").get("field").stringValue())
            Files.list(output).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `read only parent of a new output directory produces export failure without creating artifacts`() {
        val parent = Files.createDirectory(directory.resolve("read-only-parent"))
        val output = parent.resolve("new-run")

        withReadOnlyDirectory(parent) {
            val result = analyze(output)

            assertEquals(1, result.code)
            assertTrue(result.diagnosticStderr.startsWith("EXPORT_FAILED:"))
            val diagnostic = JsonMapper.builder().build().readTree(result.diagnosticStderr.substringAfter(": "))
            assertEquals("EXPORT_FAILED", diagnostic.get("code").stringValue())
            assertEquals("output_dir", diagnostic.get("details").get("field").stringValue())
            assertFalse(Files.exists(output))
            Files.list(parent).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `semantic error retains field and IDs with control characters escaped`() {
        val input = directory.resolve("negative-frequency.json")
        Files.writeString(input, """
            {"schema_version":2,
             "queries":[{"id":"q\n\u001b[31m","sql":"SELECT secret_literal FROM public.orders"}],
             "profiles":[{"id":"night\nmode","weight":1,"calls_per_hour":{"q\n\u001b[31m":-1}}],
             "constraints":{"storage_budget_bytes":10,"write_budget_units_per_hour":null}}
        """.trimIndent())

        val result = analyze(directory.resolve("run"), input)

        assertEquals(2, result.code)
        assertTrue(result.diagnosticStderr.startsWith("INVALID_INPUT:"))
        assertEquals(1, result.diagnosticStderr.lineSequence().count { it.isNotEmpty() })
        assertFalse(result.diagnosticStderr.contains('\u001b'))
        assertFalse(result.diagnosticStderr.contains("secret_literal"))
        val diagnostic = JsonMapper.builder().build().readTree(result.diagnosticStderr.substringAfter(": "))
        assertEquals("INVALID_INPUT", diagnostic.get("code").stringValue())
        assertEquals("q\n\u001b[31m", diagnostic.get("query_id").stringValue())
        assertEquals("night\nmode", diagnostic.get("profile_id").stringValue())
        assertTrue(diagnostic.get("details").get("field").stringValue().contains("calls_per_hour"))
    }

    @Test
    fun `unknown frequency key retains escaped query ID without exposing values`() {
        val input = directory.resolve("unknown-query-frequency.json")
        Files.writeString(input, """
            {"schema_version":2,
             "queries":[{"id":"q1","sql":"SELECT 'private-sql-literal' FROM public.orders"}],
             "profiles":[{"id":"night","weight":1,"calls_per_hour":{
               "q1":1,"unknown\n\"quoted\"\u001b[31m":"private-frequency-and-password"}}],
             "constraints":{"storage_budget_bytes":10,"write_budget_units_per_hour":null}}
        """.trimIndent())

        val result = analyze(directory.resolve("run"), input)

        assertEquals(2, result.code)
        assertTrue(result.diagnosticStderr.startsWith("INVALID_INPUT:"))
        assertEquals(1, result.diagnosticStderr.lineSequence().count { it.isNotEmpty() })
        assertFalse(result.diagnosticStderr.contains('\u001b'))
        assertFalse(result.diagnosticStderr.contains("private-frequency-and-password"))
        assertFalse(result.diagnosticStderr.contains("private-sql-literal"))
        val diagnostic = JsonMapper.builder().build().readTree(result.diagnosticStderr.substringAfter(": "))
        assertEquals("INVALID_INPUT", diagnostic.get("code").stringValue())
        assertEquals("unknown\n\"quoted\"\u001b[31m", diagnostic.get("query_id").stringValue())
        assertEquals("night", diagnostic.get("profile_id").stringValue())
        assertEquals("profiles[0].calls_per_hour", diagnostic.get("details").get("field").stringValue())
    }

    @Test
    fun `argument and filesystem errors do not echo sensitive paths`() {
        val secret = "postgresql://user:private-password@localhost/db"
        val invalidArguments = invoke("analyze", "--database-url", secret)
        val invalidPath = invoke("analyze", "--workload", "examples/workload-v2.json", "--output-dir", "$secret\u0000")

        for (result in listOf(invalidArguments, invalidPath)) {
            assertEquals(2, result.code)
            assertFalse(result.diagnosticStderr.contains("private-password"))
        }
    }

    @Test
    fun `missing arguments are usage errors`() {
        val result = invoke("analyze", "--workload", "examples/workload-v2.json")
        assertEquals(2, result.code)
        assertTrue(result.diagnosticStderr.startsWith("INVALID_INPUT:"))
    }

    @Test
    fun `blank paths are usage errors`() {
        val result = invoke("analyze", "--workload", "examples/workload-v2.json", "--output-dir", "")
        assertEquals(2, result.code)
        assertTrue(result.diagnosticStderr.startsWith("INVALID_INPUT:"))
    }

    @Test
    fun `runner receives validated input and shared context with real progress counters`() {
        val runner = AnalysisRunner { input, output, context ->
            assertEquals(2, input.schemaVersion)
            assertEquals(directory.resolve("run"), output)
            assertEquals(listOf(AnalysisPhase.VALIDATION), context.completedPhases())
            context.enterPhase(AnalysisPhase.BASELINE)
            context.reserveEvaluationAttempt()
            context.reserveExplainAttempt()
            context.reserveExplainAttempt()
            context.recordCompletedEvaluation()
            context.recordCacheHit()
            ContractFixtures.completedResult()
        }
        val result = analyze(directory.resolve("run"), cli = IndexBudgetCli(WorkloadReader(), analysisRunner = runner))
        assertEquals(0, result.code)
        assertEquals("", result.diagnosticStderr)
        assertTrue(result.progress.first().startsWith("stage=validation "))
        assertTrue(result.progress.contains("stage=baseline explain_attempts=2 completed_sets=1 cache_hits=1"))
        assertEquals("stage=finished status=completed analysis_mode=normal explain_attempts=2 " +
            "evaluation_attempts=1 completed_sets=1 cache_hits=1 repair_attempts=0 stop_reasons=SEARCH_COMPLETE " +
            "candidate_ranking=frequency",
            result.progress.last())
        assertFalse(result.progress.any { '%' in it })
    }

    @Test
    fun `saved partial returns 3 and typed failures retain their exit codes`() {
        val results = listOf(
            ContractFixtures.partialResult() to 3,
            SearchResult.Failed(Objective.ROBUST, AnalysisError(ErrorCode.INVALID_WRITE_PROFILE, "Invalid profile.")) to 2,
            SearchResult.Failed(Objective.ROBUST, AnalysisError(ErrorCode.NUMERIC_ERROR, "Invalid metrics.")) to 1,
        )
        for ((searchResult, expectedCode) in results) {
            val runner = AnalysisRunner { _, _, _ -> searchResult }
            val result = analyze(directory.resolve("run"), cli = IndexBudgetCli(WorkloadReader(), analysisRunner = runner))
            assertEquals(expectedCode, result.code)
            if (searchResult is SearchResult.Failed) {
                assertEquals(1, result.diagnosticStderr.lineSequence().count { it.isNotEmpty() })
                assertTrue(result.diagnosticStderr.startsWith("${searchResult.error.code.name}: "))
            }
        }
    }

    @Test
    fun `partial prints every saved diagnostic with escaped fields and keeps exit code 3`() {
        val queryId = "q\n\u001b[31m"
        val profileId = "night\t\"mode\""
        val message = "EXPLAIN failed.\nSaved result remains available."
        val detail = "cancelled\nretry\tlater\u0000"
        val errors = listOf(
            AnalysisError(
                ErrorCode.EXPLAIN_FAILED,
                message,
                queryId = queryId,
                profileId = profileId,
                details = mapOf("phase" to "repair", "reason" to detail),
            ),
            AnalysisError(ErrorCode.DB_CONNECTION_ERROR, "Analysis session cleanup failed."),
        )
        val partial = ContractFixtures.partialResult().let { it.copy(diagnostics = it.diagnostics.copy(errors = errors)) }
        val runner = AnalysisRunner { _, _, _ -> partial }

        val result = analyze(directory.resolve("run"), cli = IndexBudgetCli(WorkloadReader(), analysisRunner = runner))

        assertEquals(3, result.code)
        assertEquals("", result.stdout)
        val lines = result.diagnosticStderr.lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(2, lines.size)
        assertFalse(result.diagnosticStderr.any { it < ' ' && it != '\n' })
        assertTrue(lines[0].startsWith("EXPLAIN_FAILED: "))
        assertTrue(lines[1].startsWith("DB_CONNECTION_ERROR: "))
        val mapper = JsonMapper.builder().build()
        val explain = mapper.readTree(lines[0].substringAfter(": "))
        assertEquals("EXPLAIN_FAILED", explain.get("code").stringValue())
        assertEquals(message, explain.get("message").stringValue())
        assertEquals(queryId, explain.get("query_id").stringValue())
        assertEquals(profileId, explain.get("profile_id").stringValue())
        assertEquals("repair", explain.get("details").get("phase").stringValue())
        assertEquals(detail, explain.get("details").get("reason").stringValue())
        val cleanup = mapper.readTree(lines[1].substringAfter(": "))
        assertEquals("DB_CONNECTION_ERROR", cleanup.get("code").stringValue())
        assertEquals("Analysis session cleanup failed.", cleanup.get("message").stringValue())
        assertFalse(cleanup.has("query_id"))
        assertFalse(cleanup.has("profile_id"))
        assertFalse(cleanup.has("details"))
    }

    @Test
    fun `partial without saved errors keeps exit code 3 without inventing a diagnostic`() {
        val partial = ContractFixtures.partialResult().let {
            it.copy(diagnostics = it.diagnostics.copy(errors = emptyList()))
        }
        val runner = AnalysisRunner { _, _, _ -> partial }

        val result = analyze(directory.resolve("run"), cli = IndexBudgetCli(WorkloadReader(), analysisRunner = runner))

        assertEquals(3, result.code)
        assertEquals("", result.diagnosticStderr)
    }

    @Test
    fun `export and unexpected failures cannot return partial or expose secrets`() {
        for (failure in listOf(
            AnalysisException(AnalysisError(ErrorCode.EXPORT_FAILED, "Cannot publish analysis artifacts.")),
            IllegalStateException("postgresql://user:private-password@localhost/db"),
        )) {
            val runner = AnalysisRunner { _, _, _ -> throw failure }
            val result = analyze(directory.resolve("run"), cli = IndexBudgetCli(WorkloadReader(), analysisRunner = runner))
            assertEquals(1, result.code)
            assertFalse(result.diagnosticStderr.contains("private-password"))
        }
    }

    @Test
    fun `command budget is not restarted after startup or validation`() {
        var nanos = 0L
        val context = RunContext(clock = NanoClock { nanos })
        nanos = java.time.Duration.ofSeconds(90).toNanos()
        val runner = AnalysisRunner { _, _, _ -> error("Expired command must not run") }
        val stderr = StringWriter()
        val code = IndexBudgetCli(WorkloadReader(), analysisRunner = runner).execute(
            arrayOf("analyze", "--workload", "examples/workload-v2.json", "--output-dir", directory.resolve("run").toString()),
            err = PrintWriter(stderr, true), context = context,
        )
        assertEquals(1, code)
        assertTrue(stderr.toString().contains("DEADLINE_EXCEEDED:"))
    }

    private fun analyze(
        output: Path,
        workload: Path = Path.of("examples/workload-v2.json"),
        cli: IndexBudgetCli = this.cli,
    ): Result = invokeWith(cli, "analyze", "--workload", workload.toString(), "--output-dir", output.toString())

    private fun createSymbolicLinkOrSkip(link: Path, target: Path): Path = try {
        Files.createSymbolicLink(link, target)
    } catch (failure: UnsupportedOperationException) {
        throw TestAbortedException("Файловая система не поддерживает символические ссылки.", failure)
    } catch (failure: IOException) {
        throw TestAbortedException("Создание символической ссылки недоступно в тестовом окружении.", failure)
    } catch (failure: SecurityException) {
        throw TestAbortedException("Тестовому процессу запрещено создавать символические ссылки.", failure)
    }

    private fun withReadOnlyDirectory(path: Path, block: () -> Unit) {
        assumeTrue(Files.getFileStore(path).supportsFileAttributeView("posix"), "Нужны POSIX-права каталога.")
        val originalPermissions = Files.getPosixFilePermissions(path)
        val writePermissions = setOf(
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE,
        )
        try {
            Files.setPosixFilePermissions(path, originalPermissions - writePermissions)
            assumeFalse(Files.isWritable(path), "Процесс сохраняет право записи после chmod, например при запуске от root.")
            block()
        } finally {
            Files.setPosixFilePermissions(path, originalPermissions)
        }
    }

    private fun invoke(vararg args: String): Result = invokeWith(cli, *args)

    private fun invokeWith(cli: IndexBudgetCli, vararg args: String): Result {
        val stdout = StringWriter()
        val stderr = StringWriter()
        val code = cli.execute(arrayOf(*args), PrintWriter(stdout, true), PrintWriter(stderr, true))
        val lines = stderr.toString().lineSequence().filter { it.isNotEmpty() }.toList()
        val (progress, diagnostics) = lines.partition { it.startsWith("stage=") }
        return Result(
            code = code,
            stdout = stdout.toString(),
            diagnosticStderr = diagnostics.joinToString("\n", postfix = if (diagnostics.isEmpty()) "" else "\n"),
            progress = progress,
        )
    }

    private data class Result(
        val code: Int,
        val stdout: String,
        val diagnosticStderr: String,
        val progress: List<String>,
    )
}
