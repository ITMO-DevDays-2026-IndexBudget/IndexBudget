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
        assertEquals("", result.stderr)
    }

    @Test
    fun `valid workload does not pretend analysis has completed`() {
        val output = directory.resolve("report")
        val result = analyze(output)
        assertEquals(1, result.code)
        assertTrue(result.stderr.startsWith("NOT_IMPLEMENTED:"))
        assertFalse(Files.exists(output))
        assertEquals("", result.stdout)
    }

    @Test
    fun `invalid workload does not leak its content into diagnostics`() {
        val input = directory.resolve("invalid.json")
        Files.writeString(input, """{"schema_version":2,"queries":"private-sql-and-password"}""")
        val result = analyze(directory.resolve("report"), input)
        assertEquals(2, result.code)
        assertTrue(result.stderr.startsWith("INVALID_INPUT:"))
        assertFalse(result.stderr.contains("private-sql-and-password"))
    }

    @Test
    fun `legacy schema gets a specific error`() {
        val input = directory.resolve("v1.json")
        Files.writeString(input, """{"schema_version":1}""")
        val result = analyze(directory.resolve("report"), input)
        assertEquals(2, result.code)
        assertTrue(result.stderr.startsWith("UNSUPPORTED_SCHEMA_VERSION:"))
        assertTrue(result.stderr.contains("queries"))
        assertTrue(result.stderr.contains("profiles"))
    }

    @Test
    fun `existing artifacts are rejected and preserved`() {
        val output = Files.createDirectory(directory.resolve("previous-run"))
        val artifact = Files.writeString(output.resolve("indexes.sql"), "previous SQL")

        val result = analyze(output)

        assertEquals(2, result.code)
        assertTrue(result.stderr.startsWith("OUTPUT_DIRECTORY_NOT_EMPTY:"))
        assertTrue(result.stderr.contains("output_dir"))
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
            assertTrue(result.stderr.startsWith("NOT_IMPLEMENTED:"))
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
            assertTrue(result.stderr.startsWith("INVALID_INPUT:"))
            assertTrue(result.stderr.contains("output_dir"))
        }
        assertEquals("retain me", Files.readString(file))
    }

    @Test
    fun `dangling output link is not treated as an unused path`() {
        val target = directory.resolve("missing-target")
        val link = createSymbolicLinkOrSkip(directory.resolve("dangling-link"), target)

        val result = analyze(link)

        assertEquals(2, result.code)
        assertTrue(result.stderr.startsWith("INVALID_INPUT:"))
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
        assertTrue(result.stderr.startsWith("INVALID_INPUT:"))
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
        assertTrue(result.stderr.startsWith("OUTPUT_DIRECTORY_NOT_EMPTY:"))
        assertEquals("previous report", Files.readString(artifact))
    }

    @Test
    fun `read only output directory produces export failure without creating artifacts`() {
        val output = Files.createDirectory(directory.resolve("read-only-output"))

        withReadOnlyDirectory(output) {
            val result = analyze(output)

            assertEquals(1, result.code)
            assertTrue(result.stderr.startsWith("EXPORT_FAILED:"))
            val diagnostic = JsonMapper.builder().build().readTree(result.stderr.substringAfter(": "))
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
            assertTrue(result.stderr.startsWith("EXPORT_FAILED:"))
            val diagnostic = JsonMapper.builder().build().readTree(result.stderr.substringAfter(": "))
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
        assertTrue(result.stderr.startsWith("INVALID_INPUT:"))
        assertEquals(1, result.stderr.lineSequence().count { it.isNotEmpty() })
        assertFalse(result.stderr.contains('\u001b'))
        assertFalse(result.stderr.contains("secret_literal"))
        val diagnostic = JsonMapper.builder().build().readTree(result.stderr.substringAfter(": "))
        assertEquals("INVALID_INPUT", diagnostic.get("code").stringValue())
        assertEquals("q\n\u001b[31m", diagnostic.get("query_id").stringValue())
        assertEquals("night\nmode", diagnostic.get("profile_id").stringValue())
        assertTrue(diagnostic.get("details").get("field").stringValue().contains("calls_per_hour"))
    }

    @Test
    fun `argument and filesystem errors do not echo sensitive paths`() {
        val secret = "postgresql://user:private-password@localhost/db"
        val invalidArguments = invoke("analyze", "--database-url", secret)
        val invalidPath = invoke("analyze", "--workload", "examples/workload-v2.json", "--output-dir", "$secret\u0000")

        for (result in listOf(invalidArguments, invalidPath)) {
            assertEquals(2, result.code)
            assertFalse(result.stderr.contains("private-password"))
        }
    }

    @Test
    fun `missing arguments are usage errors`() {
        val result = invoke("analyze", "--workload", "examples/workload-v2.json")
        assertEquals(2, result.code)
        assertTrue(result.stderr.startsWith("INVALID_INPUT:"))
    }

    @Test
    fun `blank paths are usage errors`() {
        val result = invoke("analyze", "--workload", "examples/workload-v2.json", "--output-dir", "")
        assertEquals(2, result.code)
        assertTrue(result.stderr.startsWith("INVALID_INPUT:"))
    }

    private fun analyze(output: Path, workload: Path = Path.of("examples/workload-v2.json")): Result =
        invoke("analyze", "--workload", workload.toString(), "--output-dir", output.toString())

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

    private fun invoke(vararg args: String): Result {
        val stdout = StringWriter()
        val stderr = StringWriter()
        val code = cli.execute(arrayOf(*args), PrintWriter(stdout, true), PrintWriter(stderr, true))
        return Result(code, stdout.toString(), stderr.toString())
    }

    private data class Result(val code: Int, val stdout: String, val stderr: String)
}
