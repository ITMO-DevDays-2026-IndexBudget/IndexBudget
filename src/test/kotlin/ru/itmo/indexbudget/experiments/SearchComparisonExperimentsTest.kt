package ru.itmo.indexbudget.experiments

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.WorkloadReader
import ru.itmo.indexbudget.domain.CandidateRanking
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

class SearchComparisonExperimentsTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `all strategies are compared on identical inputs with reproducible byte-equal summaries`() {
        val experiment = SearchComparisonExperiments()
        experiment.run(temporary.resolve("first"))
        experiment.run(temporary.resolve("second"))
        val first = Files.readString(temporary.resolve("first/summary.json"))
        assertEquals(first, Files.readString(temporary.resolve("second/summary.json")))
        assertEquals(CandidateRanking.FREQUENCY,
            WorkloadReader().read(temporary.resolve("first/workload-v2-wide-rare.json")).candidateRanking)

        val root = JsonMapper.builder().build().readTree(first)
        val scenarios = root["scenarios"]

        // Hand-derived expectations on the small fixtures: the verified optimum is known
        // independently from the vector tables, not from running the search.
        for (objective in listOf("robust", "mean")) {
            val chain = scenarios["chain"]["comparison"]["by_objective"][objective]
            assertEquals(listOf("idx_a", "idx_b", "idx_c"), chain["verified_optimum_ids"].toList().map { node -> node.stringValue() })
            assertTrue(chain["greedy_matches_optimum"].booleanValue(), objective)
            assertTrue(chain["greedy_repair_matches_optimum"].booleanValue(), objective)
            assertEquals(0.0, chain["greedy_mean_ratio_gap_to_optimum"].doubleValue(), 1e-15)

            val trap = scenarios["trap"]["comparison"]["by_objective"][objective]
            assertEquals(listOf("idx_a", "idx_b"), trap["verified_optimum_ids"].toList().map { node -> node.stringValue() })
            assertFalse(trap["greedy_matches_optimum"].booleanValue(), objective)
            assertTrue(trap["greedy_repair_matches_optimum"].booleanValue(), objective)
            assertEquals(0.5, trap["greedy_mean_ratio_gap_to_optimum"].doubleValue(), 1e-15)

            val budget = scenarios["budget_replacement"]["comparison"]["by_objective"][objective]
            assertEquals(listOf("idx_j", "idx_k"), budget["verified_optimum_ids"].toList().map { node -> node.stringValue() })
            assertFalse(budget["greedy_matches_optimum"].booleanValue(), objective)
            assertTrue(budget["greedy_repair_matches_optimum"].booleanValue(), objective)

            val noImprovement = scenarios["no_improvement"]["comparison"]["by_objective"][objective]
            assertEquals(emptyList<String>(), noImprovement["verified_optimum_ids"].toList().map { node -> node.stringValue() })
            assertTrue(noImprovement["greedy_matches_optimum"].booleanValue(), objective)
            assertTrue(noImprovement["greedy_repair_matches_optimum"].booleanValue(), objective)
        }

        // Greedy itself stays on the empty set in the trap and keeps the budget-trapped single.
        assertEquals(emptyList<String>(),
            scenarios["trap"]["runs"]["robust"]["greedy"]["selected_ids"].toList().map { node -> node.stringValue() })
        assertEquals(1.0, scenarios["trap"]["runs"]["robust"]["greedy"]["mean_ratio"].doubleValue(), 1e-15)
        assertEquals(listOf("idx_i"),
            scenarios["budget_replacement"]["runs"]["robust"]["greedy"]["selected_ids"].toList().map { node -> node.stringValue() })

        // The reference never exceeds its declared space, evaluates each admissible subset once
        // and skips budget-inadmissible ones without any evaluation.
        for ((scenario, space, excluded) in listOf(
            Triple("chain", 7L, 0L),
            Triple("trap", 3L, 0L),
            Triple("budget_replacement", 7L, 3L),
            Triple("no_improvement", 3L, 0L),
            Triple("wide_rare", 218L, 0L),
        )) {
            for (objective in listOf("robust", "mean")) {
                val run = scenarios[scenario]["runs"][objective]["exhaustive"]
                assertEquals(true, run["exhaustive"]["complete"].booleanValue(), "$scenario/$objective")
                assertEquals(true, run["exhaustive"]["verified_optimum"].booleanValue(), "$scenario/$objective")
                assertEquals(space, run["exhaustive"]["search_space_subsets"].longValue(), "$scenario/$objective")
                assertEquals(space - excluded, run["exhaustive"]["evaluated_sets"].longValue(), "$scenario/$objective")
                assertEquals(excluded, run["exhaustive"]["budget_excluded_sets"].size().toLong(), "$scenario/$objective")
                assertEquals(space - excluded + 1L, run["evaluator_calls"].longValue(), "$scenario/$objective")
                assertEquals(0L, run["statistics"]["cache_hits"].longValue(), "$scenario/$objective")
                assertEquals(0L, run["statistics"]["repair_attempts"].longValue(), "$scenario/$objective")
            }
        }
        assertEquals(listOf(listOf("idx_i", "idx_j"), listOf("idx_i", "idx_k"), listOf("idx_i", "idx_j", "idx_k")),
            scenarios["budget_replacement"]["runs"]["robust"]["exhaustive"]["exhaustive"]["budget_excluded_sets"]
                .toList().map { node -> node.toList().map { id -> id.stringValue() } })

        // Wide-table scenario: existing caps hold, both incumbents are no worse than any strategy,
        // and the ranking axis is recorded separately from the strategy comparison.
        for (objective in listOf("robust", "mean")) {
            val comparison = scenarios["wide_rare"]["comparison"]["by_objective"][objective]
            val optimumMean = comparison["verified_optimum_mean_ratio"].doubleValue()
            assertTrue(optimumMean <= 1.0, objective)
            assertTrue(comparison["greedy_mean_ratio_gap_to_optimum"].doubleValue() >= 0.0, objective)
            assertTrue(comparison["greedy_repair_mean_ratio_gap_to_optimum"].doubleValue() >= 0.0, objective)
            assertTrue(scenarios["wide_rare"]["runs"][objective]["greedy"]["statistics"]
                ["completed_evaluations"].intValue() <= 218 + 1, objective)
        }
        assertEquals(8, scenarios["wide_rare"]["fixed_pool"].size())
        val ranking = scenarios["wide_rare"]["ranking_composition"]
        assertEquals(16, ranking["frequency_pool_size"].intValue())
        assertEquals(16, ranking["baseline_cost_pool_size"].intValue())
        assertFalse(ranking["added_to_shortlist"].isEmpty)
        assertFalse(ranking["removed_from_shortlist"].isEmpty)
        assertTrue(ranking["same_input_objective_limits_except_ranking"].booleanValue())
        assertEquals("docs/experiments/candidate-ranking-2026-11-11", ranking["reference"].stringValue())
    }
}
