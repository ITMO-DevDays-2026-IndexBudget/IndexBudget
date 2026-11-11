package ru.itmo.indexbudget.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.report.DefaultReportBuilder
import ru.itmo.indexbudget.report.JsonReportWriter
import ru.itmo.indexbudget.fixtures.ContractFixtures
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

class WorkloadProvenanceTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val reader = WorkloadReader()

    @Test
    fun `optional provenance preserves manual workloads and is part of input hash`() {
        val manual = reader.read(Path.of("examples/workload-v2.json"), RunContext())
        assertNull(manual.provenance)
        val first = RunContext()
        val source = example()
        val imported = read(source, first)
        assertEquals(manual.queries, imported.queries)
        assertEquals(manual.profiles, imported.profiles)
        assertEquals(manual.constraints, imported.constraints)
        assertEquals(2, imported.provenance?.windows?.size)
        assertEquals(QueryExampleSource.USER_SUPPLIED_LITERALS, imported.provenance?.queryExamples?.first()?.source)
        provenance(source).put("selection_rule", "Другое правило")
        val second = RunContext()
        read(source, second)
        assertNotEquals(first.inputHash, second.inputHash)
        source.putNull("provenance")
        assertNull(read(source).provenance)
    }

    @Test
    fun `provenance survives mode and candidate ranking selection and CLI overrides in the shared report`() {
        val source = example().put("analysis_mode", "fast").put("candidate_ranking", "baseline_cost")
        val path = directory.resolve("workload.json")
        Files.writeString(path, mapper.writeValueAsString(source))
        assertEquals(CandidateRanking.BASELINE_COST, reader.read(path).candidateRanking)
        val context = RunContext()
        val input = reader.read(path, context, analysisModeOverride = AnalysisMode.DEEP,
            candidateRankingOverride = CandidateRanking.FREQUENCY)
        assertEquals(AnalysisMode.DEEP, input.analysisMode)
        assertEquals(AnalysisMode.DEEP, context.analysisMode)
        assertEquals(CandidateRanking.FREQUENCY, input.candidateRanking)
        assertNotNull(input.provenance)
        val report = DefaultReportBuilder().build(input, ContractFixtures.report().candidates,
            ContractFixtures.completedResult(), context)
        val output = mapper.readTree(JsonReportWriter().render(report))
        assertEquals("deep", output["analysis_mode"].stringValue())
        assertEquals("frequency", output["candidate_ranking"].stringValue())
        assertEquals(input.provenance?.sourceId.toString(), output["workload_provenance"]["source_id"].stringValue())
        assertEquals(50.0, output["summary"]["mean_effect"]["gain_percent"].doubleValue())
    }

    @Test
    fun `report provenance can be replayed without inventing unknown coverage`() {
        val source = example()
        val window = provenance(source)["windows"][0] as ObjectNode
        window.put("complete", false)
        val coverage = window["coverage"] as ObjectNode
        coverage.putNull("selected_calls")
        coverage.putNull("available_calls")
        coverage.put("selected_exec_time_millis", 0.0)
        coverage.put("available_exec_time_millis", 0.0)
        val input = read(source)
        val report = DefaultReportBuilder().build(input, ContractFixtures.report().candidates,
            ContractFixtures.completedResult(), RunContext())
        val output = mapper.readTree(JsonReportWriter().render(report))
        source.set("provenance", output["workload_provenance"])
        assertEquals(input.provenance, read(source).provenance)
        assertNull(input.provenance?.windows?.first()?.coverage?.availableCalls)
        assertEquals(0.0, input.provenance?.windows?.first()?.coverage?.availableExecTimeMillis)
        assertFalse(requireNotNull(input.provenance).windows.first().complete)
    }

    @Test
    fun `invalid provenance is rejected before analysis without exposing supplied secrets`() {
        val invalidEdits: List<(ObjectNode) -> Unit> = listOf(
            { it.put("schema_version", 2) },
            { it.put("source", "other") },
            { it.put("source_id", "postgresql://user:secret@host/db") },
            { it.put("source_id", "1-1-1-1-1") },
            { it.put("selection_rule", " ") },
            { it.put("connection_url", "postgresql://user:secret@host/db") },
            { (it["windows"][0] as ObjectNode).put("profile_id", "absent") },
            { (it["windows"][0] as ObjectNode).put("profile_id", "campaign") },
            { (it["windows"][0] as ObjectNode).put("started_at", "2026-11-10T09:00:00") },
            { (it["windows"][0] as ObjectNode).put("finished_at", "2026-11-10T08:00:00Z") },
            { (it["windows"][0] as ObjectNode).put("complete", "true") },
            { (it["windows"][0]["coverage"] as ObjectNode).put("denominator", "all_server_traffic") },
            { (it["windows"][0]["coverage"] as ObjectNode).put("selected_calls", -1) },
            { (it["windows"][0]["coverage"] as ObjectNode).put("available_calls", 1) },
            { (it["windows"][0]["coverage"] as ObjectNode).put("selected_calls", 1.5) },
            { (it["windows"][0]["coverage"] as ObjectNode).put("selected_exec_time_millis", -1) },
            { (it["windows"][0]["coverage"] as ObjectNode).put("available_exec_time_millis", 1) },
            { (it["windows"][0]["coverage"] as ObjectNode).remove("available_calls") },
            { (it["query_examples"][0] as ObjectNode).put("query_id", "absent") },
            { (it["query_examples"][0] as ObjectNode).put("source", "guessed_parameters") },
        )
        invalidEdits.forEachIndexed { index, edit ->
            val source = example()
            edit(provenance(source))
            val error = assertThrows(AnalysisException::class.java, { read(source) }, "case $index").error
            assertTrue(error.details.getValue("field").startsWith("provenance"), "case $index")
            assertFalse(error.toString().contains("secret"))
            assertFalse(error.toString().contains("postgresql://"))
        }
    }

    @Test
    fun `nonrepresentable observed time is rejected rather than converted to zero or infinity`() {
        for (value in listOf("1e400", "1e-400")) {
            val json = Files.readString(Path.of("examples/workload-v2-provenance.json"))
                .replace("12000.0", value)
            val path = directory.resolve("workload.json")
            Files.writeString(path, json)
            assertThrows(AnalysisException::class.java) { reader.read(path) }
        }
    }

    private fun example() = mapper.readTree(Files.readString(Path.of("examples/workload-v2-provenance.json"))) as ObjectNode
    private fun provenance(source: ObjectNode) = source["provenance"] as ObjectNode
    private fun read(source: ObjectNode, context: RunContext = RunContext()): WorkloadInput {
        val path = directory.resolve("workload.json")
        Files.writeString(path, mapper.writeValueAsString(source))
        return reader.read(path, context)
    }
}
