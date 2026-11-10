package ru.itmo.indexbudget.fixtures

import ru.itmo.indexbudget.domain.ColumnRef
import ru.itmo.indexbudget.domain.Constraints
import ru.itmo.indexbudget.domain.GenerationReason
import ru.itmo.indexbudget.domain.IndexCandidate
import ru.itmo.indexbudget.domain.IndexDefinition
import ru.itmo.indexbudget.domain.IndexKey
import ru.itmo.indexbudget.domain.Objective
import ru.itmo.indexbudget.domain.Profile
import ru.itmo.indexbudget.domain.QueryInput
import ru.itmo.indexbudget.domain.ResolvedQuery
import ru.itmo.indexbudget.domain.ResolvedWorkload
import ru.itmo.indexbudget.domain.TableRef
import ru.itmo.indexbudget.domain.WorkloadInput

/**
 * A self-contained scripted search scenario: the workload (profiles, weights, constraints), the
 * candidate shortlist with known sizes, and the evaluator script (sorted candidate IDs -> costs).
 * Every candidate carries both [IndexCandidate.estimatedBytes] and the budgeted size, where
 * budgeted = ceil(1.2 * estimated); the optimizer compares budgeted bytes to the storage budget.
 */
data class SearchScenario(
    val name: String,
    val workload: WorkloadInput,
    val candidates: List<IndexCandidate>,
    /** Sorted candidate IDs -> cost by query ID; empty list is the baseline. */
    val costs: Map<List<String>, Map<String, Double>>,
) {
    /** Minimal resolution for the fake evaluator, which only needs the query IDs for coverage. */
    fun resolvedWorkload(): ResolvedWorkload = ResolvedWorkload(
        input = workload,
        queries = workload.queries.map {
            ResolvedQuery(it.id, it.sql, emptySet(), emptySet(), emptyList(), emptyList())
        },
    )

    fun evaluator(): ScriptedEvaluator = ScriptedEvaluator(costs)
}

/** Scripted search scenarios for the algorithm, before the HypoPG adapter is connected. */
object SearchScenarios {

    private val recordsTable = ContractFixtures.recordsTable
    private val queries = listOf(
        QueryInput(
            "records_by_group",
            "SELECT id, value FROM public.records WHERE group_id = 42 ORDER BY created_at DESC LIMIT 20",
        ),
        QueryInput(
            "records_by_value",
            "SELECT id, value FROM public.records WHERE value = 7 ORDER BY created_at DESC LIMIT 20",
        ),
    )
    private val profiles = listOf(
        Profile("normal", 0.9, mapOf("records_by_group" to 3000.0, "records_by_value" to 100.0)),
        Profile("campaign", 0.1, mapOf("records_by_group" to 100.0, "records_by_value" to 3000.0)),
    )
    private val baseline = mapOf("records_by_group" to 100.0, "records_by_value" to 100.0)

    /**
     * With a one-index budget the robust- and mean-optimal singles differ: `value` minimizes the
     * worst case, `group_id` minimizes the expected cost, and the price of robustness is positive.
     */
    fun meanVsRobust(): SearchScenario {
        val groupId = singleCandidate("records_group_id", "group_id", estimatedBytes = 1000L, budgetedBytes = 1200L)
        val value = singleCandidate("records_value", "value", estimatedBytes = 1000L, budgetedBytes = 1200L)
        return SearchScenario(
            name = "mean_vs_robust",
            workload = workload(Objective.ROBUST, Constraints(10_000_000L, null, maxIndexes = 1)),
            candidates = listOf(groupId, value),
            costs = mapOf<List<String>, Map<String, Double>>(
                emptyList<String>() to baseline,
                listOf("records_group_id") to mapOf("records_by_group" to 40.0, "records_by_value" to 100.0),
                listOf("records_value") to mapOf("records_by_group" to 100.0, "records_by_value" to 30.0),
            ),
        )
    }

    /**
     * Two singles share an identical worst ratio (exactly equal before rounding) while the mean
     * clearly differs: the cheaper mean is strictly preferred under both objectives, so a move with
     * unchanged worst case and better mean is allowed.
     */
    fun plateau(): SearchScenario {
        val worse = singleCandidate("records_plateau_worse", "value", estimatedBytes = 1000L, budgetedBytes = 1200L)
        val better = singleCandidate("records_plateau_better", "group_id", estimatedBytes = 1000L, budgetedBytes = 1200L)
        return SearchScenario(
            name = "plateau",
            workload = workload(Objective.ROBUST, Constraints(10_000_000L, null, maxIndexes = 1)),
            candidates = listOf(worse, better),
            costs = mapOf<List<String>, Map<String, Double>>(
                emptyList<String>() to baseline,
                listOf("records_plateau_worse") to mapOf("records_by_group" to 80.0, "records_by_value" to 84.0),
                listOf("records_plateau_better") to mapOf("records_by_group" to 50.0, "records_by_value" to 85.0),
            ),
        )
    }

    /**
     * Neither single helps (each equals the baseline), so greedy stays empty; the pair together
     * helps, so a 0→2 repair move from the empty set finds the improvement.
     */
    fun zeroToTwo(): SearchScenario {
        val groupId = singleCandidate("records_group_id", "group_id", estimatedBytes = 1000L, budgetedBytes = 1200L)
        val value = singleCandidate("records_value", "value", estimatedBytes = 1000L, budgetedBytes = 1200L)
        return SearchScenario(
            name = "zero_to_two",
            workload = workload(Objective.ROBUST, Constraints(10_000_000L, null, maxIndexes = 2)),
            candidates = listOf(groupId, value),
            costs = mapOf<List<String>, Map<String, Double>>(
                emptyList<String>() to baseline,
                listOf("records_group_id") to baseline,
                listOf("records_value") to baseline,
                listOf("records_group_id", "records_value") to
                    mapOf("records_by_group" to 60.0, "records_by_value" to 40.0),
            ),
        )
    }

    /**
     * Budget trap with synergy: `i` is the best single, but only the pair without it fits the
     * storage budget, so `{i,j}`/`{i,k}` are inadmissible. Repair's 1→2 replacing `i` with `{j,k}`
     * yields a strictly better admissible set, while every single swap is worse.
     */
    fun oneToTwo(): SearchScenario {
        val i = singleCandidate("records_budget_i", "group_id", estimatedBytes = 40L, budgetedBytes = 48L)
        val j = singleCandidate("records_budget_j", "value", estimatedBytes = 25L, budgetedBytes = 30L)
        val k = singleCandidate("records_budget_k", "created_at", estimatedBytes = 25L, budgetedBytes = 30L)
        return SearchScenario(
            name = "one_to_two",
            workload = workload(Objective.ROBUST, Constraints(60L, null, maxIndexes = 2)),
            candidates = listOf(i, j, k),
            costs = mapOf<List<String>, Map<String, Double>>(
                emptyList<String>() to baseline,
                listOf("records_budget_i") to mapOf("records_by_group" to 80.0, "records_by_value" to 80.0),
                listOf("records_budget_j") to mapOf("records_by_group" to 90.0, "records_by_value" to 90.0),
                listOf("records_budget_k") to mapOf("records_by_group" to 95.0, "records_by_value" to 95.0),
                listOf("records_budget_j", "records_budget_k") to
                    mapOf("records_by_group" to 50.0, "records_by_value" to 50.0),
            ),
        )
    }

    /**
     * The budget-trap example required by #38, without synergy: A(size 6, gain 12), B/C(size 5,
     * gain 9.5), budget 10, joint {B,C} gain 19. `A` is the best single; only the pair `{B,C}`
     * (10 bytes, exactly the budget) is admissible and strictly better, while every single swap is
     * worse. Sizes are budgeted bytes; the estimated sizes are ceil-consistent (5/4/4).
     */
    fun oneToTwoNoSynergy(): SearchScenario {
        val a = singleCandidate("records_trap_a", "group_id", estimatedBytes = 5L, budgetedBytes = 6L)
        val b = singleCandidate("records_trap_b", "value", estimatedBytes = 4L, budgetedBytes = 5L)
        val c = singleCandidate("records_trap_c", "created_at", estimatedBytes = 4L, budgetedBytes = 5L)
        return SearchScenario(
            name = "one_to_two_no_synergy",
            workload = singleQueryWorkload(Objective.ROBUST, Constraints(10L, null, maxIndexes = 2)),
            candidates = listOf(a, b, c),
            costs = mapOf<List<String>, Map<String, Double>>(
                emptyList<String>() to mapOf("q1" to 100.0),
                listOf("records_trap_a") to mapOf("q1" to 88.0),
                listOf("records_trap_b") to mapOf("q1" to 90.5),
                listOf("records_trap_c") to mapOf("q1" to 90.5),
                listOf("records_trap_b", "records_trap_c") to mapOf("q1" to 81.0),
            ),
        )
    }

    /**
     * Overlapping benefit: both singles improve the baseline, but the second adds no marginal
     * improvement after the better one, so the pair is no better than the best single.
     */
    fun overlappingBenefit(): SearchScenario {
        val a = singleCandidate("records_overlap_a", "group_id", estimatedBytes = 10L, budgetedBytes = 12L)
        val b = singleCandidate("records_overlap_b", "value", estimatedBytes = 10L, budgetedBytes = 12L)
        return SearchScenario(
            name = "overlapping_benefit",
            workload = singleQueryWorkload(Objective.ROBUST, Constraints(10_000_000L, null, maxIndexes = 2)),
            candidates = listOf(a, b),
            costs = mapOf<List<String>, Map<String, Double>>(
                emptyList<String>() to mapOf("q1" to 100.0),
                listOf("records_overlap_a") to mapOf("q1" to 60.0),
                listOf("records_overlap_b") to mapOf("q1" to 65.0),
                listOf("records_overlap_a", "records_overlap_b") to mapOf("q1" to 60.0),
            ),
        )
    }

    private fun workload(objective: Objective, constraints: Constraints): WorkloadInput = WorkloadInput(
        schemaVersion = 2,
        objective = objective,
        queries = queries,
        profiles = profiles,
        constraints = constraints,
        tableWriteRates = null,
    )

    private fun singleQueryWorkload(objective: Objective, constraints: Constraints): WorkloadInput = WorkloadInput(
        schemaVersion = 2,
        objective = objective,
        queries = listOf(QueryInput("q1", "SELECT 1")),
        profiles = listOf(Profile("single", 1.0, mapOf("q1" to 1.0))),
        constraints = constraints,
        tableWriteRates = null,
    )

    private fun singleCandidate(
        id: String,
        column: String,
        estimatedBytes: Long,
        budgetedBytes: Long,
    ): IndexCandidate = IndexCandidate(
        id = id,
        definition = IndexDefinition(
            recordsTable,
            listOf(IndexKey(ColumnRef(recordsTable, column))),
        ),
        sourceQueryIds = emptySet(),
        generationReasons = setOf(GenerationReason.SINGLE_COLUMN),
        estimatedBytes = estimatedBytes,
        budgetedBytes = budgetedBytes,
        writeUnitsPerHour = null,
    )
}