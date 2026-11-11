package ru.itmo.indexbudget.experiments

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*
import ru.itmo.indexbudget.fixtures.ContractFixtures
import tools.jackson.databind.JsonNode

class AnalysisModeExperimentsTest {
    private val experiments = AnalysisModeExperiments()

    @Test
    fun `summary follows the saved objective and effective counters rather than the preset`() {
        val source = ContractFixtures.report(objective = Objective.MEAN)
        val saved = source.copy(
            analysisMode = AnalysisMode.DEEP,
            durationMillis = 1234,
            result = (source.result as SearchResult.Completed).copy(diagnostics = SearchDiagnostics(
                statistics = RunStatistics(47, 19, 17, 3, 7),
                limits = AnalysisMode.DEEP.limits.copy(maxExplainAttempts = 321, maxRepairAttempts = 13),
                stopReasons = listOf(StopReason.SEARCH_COMPLETE),
            )),
        )

        val summary = experiments.summarize(saved)

        assertEquals("completed", summary["status"])
        assertEquals(1234L, summary["duration_millis"])
        assertEquals(listOf("records_group_created_at"), summary["selected_ids"])
        assertEquals(0.2132258064516129, summary["mean_ratio"])
        assertEquals(0.9709677419354839, summary["worst_ratio"])
        assertEquals(629_146L, summary["budgeted_bytes"])
        val statistics = summary["statistics"] as JsonNode
        assertEquals(47, statistics["explain_attempts"].intValue())
        assertEquals(19, statistics["evaluation_attempts"].intValue())
        assertEquals(17, statistics["completed_evaluations"].intValue())
        assertEquals(3, statistics["cache_hits"].intValue())
        assertEquals(7, statistics["repair_attempts"].intValue())
        val limits = summary["limits"] as JsonNode
        assertEquals(321, limits["max_explain_attempts"].intValue())
        assertEquals(13, limits["max_repair_attempts"].intValue())
    }

    @Test
    fun `partial summary retains saved recommendation and failed summary keeps unknown metrics`() {
        val partial = experiments.summarize(ContractFixtures.report(status = RunStatus.PARTIAL))
        assertEquals("partial", partial["status"])
        assertEquals(listOf("EVALUATION_FAILED"), partial["stop_reasons"])
        assertEquals(listOf("records_created_at"), partial["selected_ids"])
        assertEquals(0.5, partial["worst_ratio"])
        assertEquals(2, (partial["statistics"] as JsonNode)["completed_evaluations"].intValue())

        val failed = experiments.summarize(ContractFixtures.report(status = RunStatus.FAILED).copy(input = null))
        assertEquals("failed", failed["status"])
        assertEquals(listOf("EVALUATION_FAILED"), failed["stop_reasons"])
        for (key in listOf("selected_ids", "mean_ratio", "worst_ratio", "budgeted_bytes", "write_units_per_hour")) {
            assertTrue(failed.containsKey(key), key)
            assertNull(failed[key], key)
        }
        assertEquals(1, (failed["statistics"] as JsonNode)["explain_attempts"].intValue())
        assertEquals(0, (failed["statistics"] as JsonNode)["completed_evaluations"].intValue())
    }
}
