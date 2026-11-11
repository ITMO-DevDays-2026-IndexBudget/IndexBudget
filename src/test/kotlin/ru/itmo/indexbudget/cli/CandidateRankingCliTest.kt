package ru.itmo.indexbudget.cli

import org.junit.jupiter.api.Assertions.*
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

class CandidateRankingCliTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `legacy default JSON selection and CLI precedence preserve source hash`() {
        for (jsonRanking in listOf(null) + CandidateRanking.entries) {
            val source = workload(jsonRanking?.let { "\"${it.wireName}\"" })
            val sourceContext = RunContext()
            val parsed = WorkloadReader().read(source, sourceContext)
            assertEquals(jsonRanking ?: CandidateRanking.FREQUENCY, parsed.candidateRanking)
            for (override in listOf(null) + CandidateRanking.entries) {
                val context = RunContext()
                val expected = override ?: jsonRanking ?: CandidateRanking.FREQUENCY
                val runner = AnalysisRunner { input, _, actualContext ->
                    assertEquals(expected, input.candidateRanking)
                    assertEquals(sourceContext.inputHash, actualContext.inputHash)
                    assertEquals(AnalysisMode.NORMAL, actualContext.analysisMode)
                    ContractFixtures.completedResult()
                }
                val extra = override?.let { arrayOf("--candidate-ranking", it.wireName) } ?: emptyArray()
                val (code, stderr) = invoke(runner, source, context, *extra)
                assertEquals(0, code)
                assertEquals(2, Regex("candidate_ranking=${expected.wireName}").findAll(stderr).count())
            }
        }
    }

    @Test
    fun `invalid JSON selection remains invalid under CLI override`() {
        val runner = AnalysisRunner { _, _, _ -> error("Invalid input must not run") }
        for (value in listOf("null", "1", "true", "{}", "\"\"", "\"cost\"", "\"BASELINE_COST\"")) {
            val (code, stderr) = invoke(runner, workload(value), RunContext(), "--candidate-ranking", "baseline_cost")
            assertEquals(2, code, value)
            assertTrue(stderr.contains("candidate_ranking"), stderr)
            assertTrue(stderr.contains("INVALID_INPUT:"), stderr)
        }
    }

    @Test
    fun `invalid missing and duplicate CLI ranking fail before runner`() {
        val runner = AnalysisRunner { _, _, _ -> error("Invalid option must not run") }
        for (arguments in listOf(
            arrayOf("--candidate-ranking", "private-password"),
            arrayOf("--candidate-ranking", ""), arrayOf("--candidate-ranking"),
            arrayOf("--candidate-ranking", "frequency", "--candidate-ranking", "baseline_cost"),
        )) {
            val (code, stderr) = invoke(runner, workload(null), RunContext(), *arguments)
            assertEquals(2, code)
            assertTrue(stderr.contains("INVALID_INPUT:"))
            assertFalse(stderr.contains("private-password"))
        }
    }

    @Test
    fun `help describes strategy default and experimental selection`() {
        val stdout = StringWriter()
        assertEquals(0, IndexBudgetCli(WorkloadReader()).execute(arrayOf("--help"), PrintWriter(stdout, true)))
        assertTrue(stdout.toString().contains("--candidate-ranking frequency|baseline_cost"))
        assertTrue(stdout.toString().contains("default: frequency"))
        assertTrue(stdout.toString().contains("experimental"))
    }

    private fun workload(value: String?): Path {
        val original = Files.readString(Path.of("examples/workload-v2.json"))
        val source = if (value == null) original else original.replaceFirst("{", "{\"candidate_ranking\":$value,")
        return Files.writeString(directory.resolve("workload.json"), source)
    }

    private fun invoke(runner: AnalysisRunner, workload: Path, context: RunContext, vararg options: String): Pair<Int, String> {
        val stderr = StringWriter()
        val code = IndexBudgetCli(WorkloadReader(), analysisRunner = runner).execute(
            arrayOf("analyze", "--workload", workload.toString(), "--output-dir",
                directory.resolve("output").toString(), *options),
            PrintWriter(StringWriter(), true), PrintWriter(stderr, true), context)
        return code to stderr.toString()
    }
}
