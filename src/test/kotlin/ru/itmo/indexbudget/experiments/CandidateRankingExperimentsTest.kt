package ru.itmo.indexbudget.experiments

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.domain.CandidateRanking
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

class CandidateRankingExperimentsTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `complete cost fixtures show both ranking outcomes with equal pools and reproducible counters`() {
        val experiment = CandidateRankingExperiments()
        experiment.run(temporary.resolve("first"))
        experiment.run(temporary.resolve("second"))
        val first = Files.readString(temporary.resolve("first/summary.json"))
        assertEquals(first, Files.readString(temporary.resolve("second/summary.json")))
        assertEquals(CandidateRanking.BASELINE_COST,
            WorkloadReader().read(temporary.resolve("first/workload-v2.json")).candidateRanking)
        val summary = JsonMapper.builder().build().readTree(first)
        val scenarios = summary["scenarios"]
        assertEquals("baseline_cost", scenarios["rare_improves"]["comparison"]["winner_on_this_fixture"].stringValue())
        assertEquals("frequency", scenarios["rare_unchanged"]["comparison"]["winner_on_this_fixture"].stringValue())
        val improves = scenarios["rare_improves"]["runs"]
        assertEquals(10100.0 / 11000.0, improves["frequency"]["mean_ratio"].doubleValue(), 1e-15)
        assertEquals(1500.0 / 11000.0, improves["baseline_cost"]["mean_ratio"].doubleValue(), 1e-15)
        val unchanged = scenarios["rare_unchanged"]["runs"]
        assertEquals(10010.0 / 11000.0, unchanged["frequency"]["mean_ratio"].doubleValue(), 1e-15)
        assertEquals(1.0, unchanged["baseline_cost"]["mean_ratio"].doubleValue(), 1e-15)
        for (scenario in listOf("rare_improves", "rare_unchanged")) {
            for (ranking in listOf("frequency", "baseline_cost")) {
                val run = scenarios[scenario]["runs"][ranking]
                assertEquals(16, run["final_pool_size"].intValue())
                assertEquals(16, run["size_attempts"].intValue())
                assertEquals(0, run["statistics"]["actual_explain_attempts"].intValue())
                assertTrue(run["statistics"]["completed_synthetic_evaluations"].intValue() > 16)
                val baselineOnly = scenario == "rare_unchanged" && ranking == "baseline_cost"
                assertEquals(if (baselineOnly) 0 else 1, run["selected_ids"].size())
                assertEquals(if (baselineOnly) 0 else 120, run["selected_budgeted_bytes"].intValue())
                assertEquals(if (baselineOnly) 0.0 else 3.0, run["selected_write_units_per_hour"].doubleValue())
            }
        }
    }
}
