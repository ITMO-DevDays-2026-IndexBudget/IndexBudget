package ru.itmo.indexbudget.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.WorkloadReader
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

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
        val result = invoke("analyze", "--workload", "examples/workload-v2.json", "--output-dir", output.toString())
        assertEquals(1, result.code)
        assertTrue(result.stderr.startsWith("NOT_IMPLEMENTED:"))
        assertFalse(Files.exists(output))
        assertEquals("", result.stdout)
    }

    @Test
    fun `invalid workload does not leak its content into diagnostics`() {
        val input = directory.resolve("invalid.json")
        Files.writeString(input, """{"schema_version":2,"queries":"private-sql-and-password"}""")
        val result = invoke("analyze", "--workload", input.toString(), "--output-dir", directory.resolve("report").toString())
        assertEquals(2, result.code)
        assertTrue(result.stderr.startsWith("INVALID_INPUT:"))
        assertFalse(result.stderr.contains("private-sql-and-password"))
    }

    @Test
    fun `legacy schema gets a specific error`() {
        val input = directory.resolve("v1.json")
        Files.writeString(input, """{"schema_version":1}""")
        val result = invoke("analyze", "--workload", input.toString(), "--output-dir", directory.resolve("report").toString())
        assertEquals(2, result.code)
        assertTrue(result.stderr.startsWith("UNSUPPORTED_SCHEMA_VERSION:"))
    }

    @Test
    fun `missing arguments are usage errors`() {
        val result = invoke("analyze", "--workload", "examples/workload-v2.json")
        assertEquals(2, result.code)
        assertTrue(result.stderr.startsWith("INVALID_INPUT:"))
    }

    private fun invoke(vararg args: String): Result {
        val stdout = StringWriter()
        val stderr = StringWriter()
        val code = cli.execute(arrayOf(*args), PrintWriter(stdout, true), PrintWriter(stderr, true))
        return Result(code, stdout.toString(), stderr.toString())
    }

    private data class Result(val code: Int, val stdout: String, val stderr: String)
}
