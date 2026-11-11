package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import ru.itmo.indexbudget.candidates.ProfileAwareCandidateGenerator
import ru.itmo.indexbudget.cli.BudgetSeriesCommand
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.optimizer.LimitedRepairOptimizer
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.postgres.AnalysisSessionFactory
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.ReportExporter
import ru.itmo.indexbudget.sql.JSqlParserSqlAnalyzer
import tools.jackson.databind.json.JsonMapper
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

class BudgetSeriesValidationTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @EnumSource(value = ErrorCode::class, names = ["UNSUPPORTED_SQL", "UNKNOWN_COLUMN", "UNKNOWN_TABLE", "INVALID_WRITE_PROFILE"])
    fun `CLI preserves input exit code after preparation failure and saves failed summary`(expected: ErrorCode) {
        val sql = when (expected) {
            ErrorCode.UNSUPPORTED_SQL -> "DELETE FROM public.records"
            ErrorCode.UNKNOWN_COLUMN -> "SELECT missing FROM public.records"
            ErrorCode.UNKNOWN_TABLE -> "SELECT id FROM public.missing"
            else -> "SELECT id FROM public.records"
        }
        val writeTable = if (expected == ErrorCode.INVALID_WRITE_PROFILE) "public.unused" else "public.records"
        val input = Files.writeString(directory.resolve("workload.json"), JsonMapper().writeValueAsString(mapOf(
            "schema_version" to 2,
            "queries" to listOf(mapOf("id" to "q", "sql" to sql)),
            "profiles" to listOf(mapOf("id" to "p", "weight" to 1, "calls_per_hour" to mapOf("q" to 1))),
            "constraints" to mapOf("storage_budget_bytes" to 1000, "write_budget_units_per_hour" to 10),
            "table_write_rates" to mapOf(writeTable to mapOf("inserts_per_hour" to 1, "updates_per_hour" to 0, "deletes_per_hour" to 0)),
        )))
        val series = Files.writeString(directory.resolve("request.json"),
            """{"schema_version":1,"budget":"storage_budget_bytes","values":[0,100,200]}""")
        var opened = 0
        var closed = 0
        val noShutdown = ShutdownCancellation { AutoCloseable {} }
        val analysis = AnalysisPipeline(JSqlParserSqlAnalyzer(), object : AnalysisSessionFactory {
            override fun open(context: RunContext): AnalysisSession {
                opened++
                return object : AnalysisSession {
                    override fun readCatalog(tables: Set<TableName>, context: RunContext) = ContractFixtures.catalog()
                    override fun estimateSize(candidate: IndexCandidate, context: RunContext): Long = error("Input must fail before sizes")
                    override fun evaluate(candidates: List<IndexCandidate>, workload: ResolvedWorkload,
                        context: RunContext): EvaluationResult = error("Input must fail before baseline")
                    override fun cancel() { }
                    override fun close() { closed++ }
                }
            }
        }, ProfileAwareCandidateGenerator(), MetricsCalculatorImpl(), ObjectiveComparatorImpl(), LimitedRepairOptimizer(),
            DefaultReportBuilder(), ReportExporter { _, _, _ -> error("Invalid preparation has no per-point report") },
            shutdownCancellation = noShutdown)
        val command = BudgetSeriesCommand(WorkloadReader(), runner = BudgetSeriesPipeline(analysis, noShutdown))
        val stderr = StringWriter()

        val code = command.execute(arrayOf("analyze-series", "--workload", input.toString(), "--series", series.toString(),
            "--output-dir", directory.resolve("output").toString()), PrintWriter(StringWriter()), PrintWriter(stderr, true), RunContext())

        assertEquals(2, code, stderr.toString())
        assertTrue(stderr.toString().contains(expected.name), stderr.toString())
        val summary = JsonMapper().readTree(Files.readString(directory.resolve("output/series.json")))
        assertEquals("failed", summary["status"].stringValue())
        assertEquals(expected.name, summary["preparation"]["error"]["code"].stringValue())
        assertTrue(summary["points"].all { it["status"].stringValue() == "skipped" })
        assertFalse(Files.exists(directory.resolve("output/point-01")))
        assertEquals(if (expected == ErrorCode.UNSUPPORTED_SQL) 0 else 1, opened)
        assertEquals(opened, closed)
    }
}
