package ru.itmo.indexbudget.experiments

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.*
import ru.itmo.indexbudget.candidates.PostgresDdlRenderer
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import ru.itmo.indexbudget.postgres.AnalysisSession
import ru.itmo.indexbudget.report.AtomicReportExporter
import ru.itmo.indexbudget.report.HtmlReportRenderer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/** Error paths of the experiment harness, runnable without PostgreSQL. */
class ObservedBudgetVerificationTest {
    @TempDir
    lateinit var directory: Path
    private val mapper = JsonMapper()

    @ParameterizedTest
    @EnumSource(ErrorCode::class, names = ["DB_CONNECTION_ERROR", "DEADLINE_EXCEEDED"])
    fun `initialization failure closes the unregistered session`(code: ErrorCode) {
        val session = mock(AnalysisSession::class.java)
        val context = RunContext()
        val failure = AnalysisException(AnalysisError(code, "Version read failed"))
        doThrow(failure).`when`(session).readDatabaseVersions(context)

        val thrown = assertThrows(AnalysisException::class.java) {
            ObservedBudgetSessionFactory { session }.open(context)
        }

        assertSame(failure, thrown)
        verify(session).close()
    }

    @Test
    fun `cleanup failure is suppressed under the original initialization failure`() {
        val session = mock(AnalysisSession::class.java)
        val context = RunContext()
        val failure = AnalysisException(AnalysisError(ErrorCode.DB_CONNECTION_ERROR, "Version read failed"))
        val cleanup = IllegalStateException("Close failed")
        doThrow(failure).`when`(session).readDatabaseVersions(context)
        doThrow(cleanup).`when`(session).close()

        val thrown = assertThrows(AnalysisException::class.java) {
            ObservedBudgetSessionFactory { session }.open(context)
        }

        assertSame(failure, thrown)
        assertEquals(listOf(cleanup), thrown.suppressed.toList())
        verify(session).close()
    }

    @Test
    fun `successful initialization transfers the open session to the caller`() {
        val session = mock(AnalysisSession::class.java)
        val context = RunContext()
        assertSame(session, ObservedBudgetSessionFactory { session }.open(context))
        verify(session).readDatabaseVersions(context)
        verify(session, never()).close()
    }

    @Test
    fun `matching JSON and SQL with a substituted index fail against the retained result`() {
        val report = report()
        val output = export(report)
        val json = readReport(output)
        val extra = json["candidates"].toList().single { it["id"].asString() !in selectedIds(report) }
        (json["selected_indexes"] as ArrayNode).removeAll().add(extra)
        saveReport(output, json)
        Files.writeString(output.resolve("indexes.sql"), header(report) + extra["ddl"].asString() + "\n")

        assertThrows(AssertionError::class.java) { ObservedBudgetArtifacts.verifyReport(report, output) }
    }

    @Test
    fun `matching extra JSON and SQL index is rejected`() {
        val report = report()
        val output = export(report)
        val json = readReport(output)
        val extra = json["candidates"].toList().single { it["id"].asString() !in selectedIds(report) }
        (json["selected_indexes"] as ArrayNode).add(extra)
        saveReport(output, json)
        Files.writeString(output.resolve("indexes.sql"), Files.readString(output.resolve("indexes.sql")) +
            extra["ddl"].asString() + "\n")

        assertThrows(AssertionError::class.java) { ObservedBudgetArtifacts.verifyReport(report, output) }
    }

    @Test
    fun `retaining the expected ID does not hide a changed definition`() {
        val report = report()
        val output = export(report)
        val json = readReport(output)
        val extra = json["candidates"].toList().single { it["id"].asString() !in selectedIds(report) }
        val selected = json["selected_indexes"][0] as ObjectNode
        selected.set("definition", extra["definition"])
        selected.set("ddl", extra["ddl"])
        saveReport(output, json)
        Files.writeString(output.resolve("indexes.sql"), header(report) + extra["ddl"].asString() + "\n")

        assertThrows(AssertionError::class.java) { ObservedBudgetArtifacts.verifyReport(report, output) }
    }

    @Test
    fun `unchanged IDs and definition do not hide a substituted DDL`() {
        val report = report()
        val output = export(report)
        val json = readReport(output)
        (json["selected_indexes"][0] as ObjectNode).put("ddl", "SELECT 1;")
        saveReport(output, json)
        Files.writeString(output.resolve("indexes.sql"), header(report) + "SELECT 1;\n")

        assertThrows(AssertionError::class.java) { ObservedBudgetArtifacts.verifyReport(report, output) }
    }

    @Test
    fun `empty selection requires an empty SQL command list`() {
        val empty = ContractFixtures.baselineConfiguration()
        val report = report().copy(result = SearchResult.Completed(Objective.ROBUST, empty.evaluation, empty, empty))
        val output = export(report)
        Files.writeString(output.resolve("indexes.sql"), header(report) + "SELECT 1;\n")

        assertThrows(AssertionError::class.java) { ObservedBudgetArtifacts.verifyReport(report, output) }
    }

    @Test
    fun `series IDs must match each retained point instead of another point`() {
        val result = BudgetSeriesResult(SeriesStatus.COMPLETED, listOf(Objective.ROBUST, Objective.MEAN).mapIndexed { i, objective ->
            BudgetSeriesPoint(i + 1, BigDecimal.valueOf(67_108_864L + i), SeriesPointStatus.COMPLETED,
                runId = "run-$i", result = ContractFixtures.completedResult(objective))
        })
        val summary = mapper.valueToTree<ObjectNode>(mapOf("points" to result.points.map { point -> mapOf(
            "ordinal" to point.ordinal, "run_id" to point.runId,
            "selected_index_ids" to checkNotNull(point.result?.selected).evaluation.candidateIds,
        ) }))
        ObservedBudgetArtifacts.verifySeries(result, summary)
        (summary["points"][0] as ObjectNode).set("selected_index_ids", summary["points"][1]["selected_index_ids"])

        assertThrows(AssertionError::class.java) { ObservedBudgetArtifacts.verifySeries(result, summary) }
    }

    private fun report(): AnalysisReport = ContractFixtures.report().let { report -> report.copy(
        candidates = report.candidates.copy(candidates = report.candidates.candidates.map {
            it.copy(ddl = PostgresDdlRenderer().render(it.definition))
        }),
    ) }

    private fun export(report: AnalysisReport): Path {
        val output = directory.resolve("run")
        val context = RunContext(report.runId).also { it.recordInputHash(checkNotNull(report.inputHash)) }
        AtomicReportExporter(HtmlReportRenderer()).export(report, output, context)
        // Positive control for every mutation, including the empty selection.
        ObservedBudgetArtifacts.verifyReport(report, output)
        return output
    }

    private fun selectedIds(report: AnalysisReport) = checkNotNull(report.result.selected).evaluation.candidateIds
    private fun header(report: AnalysisReport) = "-- run_id: ${report.runId}\n-- input_hash: ${report.inputHash}\n"
    private fun readReport(output: Path) = mapper.readTree(Files.readString(output.resolve("report.json"))) as ObjectNode
    private fun saveReport(output: Path, json: ObjectNode) {
        Files.writeString(output.resolve("report.json"), mapper.writeValueAsString(json))
    }
}
