package ru.itmo.indexbudget.optimizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.*

class RepairNeighborhoodTest {
    private val metrics = MetricsCalculatorImpl()

    @Test
    fun `known single benefit precedes size and ID while unknown singles follow by ID`() {
        val candidates = listOf(
            candidate("S"), candidate("B", 2), candidate("A", 2), candidate("C", 1),
            candidate("D", 0), candidate("E"), candidate("F"),
            candidate("0_unknown", 100), candidate("1_unknown", 0),
        )
        val input = workload()
        val baseline = evaluation(emptyList(), 100.0)
        val singles = mapOf("A" to 80.0, "B" to 80.0, "C" to 80.0,
            "D" to 90.0, "E" to 100.0, "F" to 110.0).map { (id, cost) ->
            listOf(id) to evaluation(listOf(id), cost)
        }.toMap()

        val queues = queues(input, candidates, baseline, evaluation(listOf("S"), 70.0), singles)
        val reversed = queues(input, candidates.reversed(), baseline, evaluation(listOf("S"), 70.0),
            singles.entries.reversed().associate { it.toPair() })

        assertEquals(listOf("C", "A", "B", "D", "E", "F", "0_unknown", "1_unknown"),
            queues[1].map { it.single().id })
        assertEquals(ids(queues), ids(reversed))
    }

    @Test
    fun `robust uses worst positive baseline ratio and profile ID breaks a tie regardless of weight`() {
        val input = workload(
            profiles = listOf(
                Profile("z_profile", 1000.0, mapOf("q1" to 1.0, "q2" to 0.0, "q3" to 0.0)),
                Profile("a_profile", 1.0, mapOf("q1" to 0.0, "q2" to 1.0, "q3" to 0.0)),
                Profile("zero", 1.0, mapOf("q1" to 0.0, "q2" to 0.0, "q3" to 1.0)),
            ),
        )
        val candidates = listOf(candidate("S"), candidate("A"), candidate("B"))
        val baseline = evaluation(emptyList(), 100.0, 1000.0, 0.0)
        val singles = mapOf(
            listOf("A") to evaluation(listOf("A"), 1.0, 950.0, 0.0),
            listOf("B") to evaluation(listOf("B"), 90.0, 100.0, 0.0),
        )
        for (firstCost in listOf(80.0, 90.0)) {
            val queues = queues(input, candidates, baseline,
                evaluation(listOf("S"), firstCost, 900.0, 0.0), singles)

            assertEquals(listOf("B", "A"), queues[1].map { it.single().id })
        }
    }

    @Test
    fun `mean ranks by expected absolute cost rather than mean of profile ratios`() {
        val input = workload(
            objective = Objective.MEAN,
            profiles = listOf(
                Profile("frequent", 9.0, mapOf("q1" to 1.0, "q2" to 0.0)),
                Profile("rare", 1.0, mapOf("q1" to 0.0, "q2" to 1.0)),
            ),
        )
        val candidates = listOf(candidate("S"), candidate("A"), candidate("B"))
        val baseline = evaluation(emptyList(), 100.0, 1000.0)
        val singles = mapOf(
            listOf("A") to evaluation(listOf("A"), 0.0, 1000.0),
            listOf("B") to evaluation(listOf("B"), 100.0, 0.0),
        )

        val queues = queues(input, candidates, baseline, evaluation(listOf("S"), 50.0, 500.0), singles)

        assertEquals(listOf("B", "A"), queues[1].map { it.single().id })
    }

    @Test
    fun `pair ranks precede removed ID and every configuration has canonical IDs`() {
        val input = workload(maxIndexes = 4)
        val candidates = listOf("Z", "S", "B", "A", "C").map { candidate(it) }
        val baseline = evaluation(emptyList(), 100.0)
        val singles = listOf("C" to 60.0, "A" to 70.0, "B" to 80.0).associate { (id, cost) ->
            listOf(id) to evaluation(listOf(id), cost)
        }

        val queues = queues(input, candidates, baseline, evaluation(listOf("S", "Z"), 50.0), singles)

        assertEquals(listOf(listOf("A", "C", "S", "Z"), listOf("B", "C", "S", "Z"),
            listOf("A", "B", "S", "Z")), ids(queues)[0])
        assertEquals(listOf(listOf("C", "Z"), listOf("C", "S"), listOf("A", "Z"),
            listOf("A", "S"), listOf("B", "Z"), listOf("B", "S")), ids(queues)[1])
        assertEquals(listOf(listOf("A", "C", "Z"), listOf("A", "C", "S"),
            listOf("B", "C", "Z"), listOf("B", "C", "S"),
            listOf("A", "B", "Z"), listOf("A", "B", "S")), ids(queues)[2])
    }

    @Test
    fun `empty start includes every pair and all allowed start sizes stay within structural bound`() {
        val candidates = (0 until 16).map { candidate("i${it.toString().padStart(2, '0')}") }
        val input = workload(maxIndexes = 5)
        val baseline = evaluation(emptyList(), 100.0)
        for (selectedCount in 0..5) {
            val selected = candidates.take(selectedCount).map { it.id }
            val queues = queues(input, candidates, baseline, evaluation(selected, 80.0), emptyMap())
            val configurations = ids(queues).flatten()

            assertTrue(configurations.size <= 450)
            assertEquals(configurations.size, configurations.distinct().size)
            assertTrue(configurations.all { it.size <= 5 && it == it.distinct().sorted() })
            if (selectedCount == 0) {
                assertEquals(120, queues[0].size)
                assertTrue(queues[1].isEmpty())
                assertTrue(queues[2].isEmpty())
            }
            if (selectedCount >= 4) assertTrue(queues[0].isEmpty())
            if (selectedCount == 5) assertTrue(queues[2].isEmpty())
        }
    }

    private fun queues(
        input: WorkloadInput,
        candidates: List<IndexCandidate>,
        baseline: EvaluationResult,
        start: EvaluationResult,
        evaluations: Map<List<String>, EvaluationResult>,
    ): List<List<List<IndexCandidate>>> {
        val usage = metrics.assessBudget(candidates.filter { it.id in start.candidateIds }, input).usage
        val evaluated = EvaluatedConfiguration(start, metrics.calculate(input, baseline, start, usage))
        return RepairNeighborhood.queues(input, evaluated, candidates, evaluations, metrics, baseline, RunContext())
    }

    private fun ids(queues: List<List<List<IndexCandidate>>>): List<List<List<String>>> =
        queues.map { queue -> queue.map { set -> set.map { it.id } } }

    private fun workload(
        objective: Objective = Objective.ROBUST,
        profiles: List<Profile> = listOf(Profile("single", 1.0, mapOf("q1" to 1.0))),
        maxIndexes: Int = 5,
    ): WorkloadInput = WorkloadInput(
        queries = profiles.first().callsPerHour.keys.map { QueryInput(it, "SELECT 1") },
        profiles = profiles,
        constraints = Constraints(100_000, null, maxIndexes),
        objective = objective,
    )

    private fun evaluation(ids: List<String>, vararg costs: Double): EvaluationResult {
        val vector = costs.mapIndexed { index, cost -> "q${index + 1}" to cost }.toMap()
        return EvaluationResult(ids, vector, vector.mapValues { ExplainPlan("{}") })
    }

    private fun candidate(id: String, bytes: Long = 1): IndexCandidate {
        val table = TableRef("public", "records")
        return IndexCandidate(id, IndexDefinition(table, listOf(IndexKey(ColumnRef(table, "key_$id")))),
            emptySet(), setOf(GenerationReason.SINGLE_COLUMN), estimatedBytes = bytes)
    }
}
