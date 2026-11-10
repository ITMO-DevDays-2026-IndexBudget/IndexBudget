package ru.itmo.indexbudget.candidates

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.itmo.indexbudget.domain.Profile
import ru.itmo.indexbudget.domain.RunContext

class NormalizedFrequenciesTest {
    @Test
    fun `normalization is stable across frequency and query ID permutations`() {
        val frequencies = mapOf("q0" to 0.0, "q1" to 9_007_199_254_740_992.0, "q2" to 1.0, "q3" to 1.0)
        val orders = listOf(
            listOf("q0", "q1", "q2", "q3"),
            listOf("q3", "q2", "q1", "q0"),
            listOf("q2", "q3", "q0", "q1"),
            listOf("q3", "q1", "q0", "q2"),
        )
        // При суммировании по ID обе единицы округляются после 2^53.
        // Если сложить единицы первыми, итог и нормированные значения изменятся.
        val expected = mapOf("q0" to 0.0, "q1" to 1.0,
            "q2" to 1.1102230246251565e-16, "q3" to 1.1102230246251565e-16)

        for (frequencyOrder in orders) {
            val profile = Profile("p", 1.0, frequencyOrder.associateWith { frequencies.getValue(it) })
            for (queryOrder in orders) {
                val result = normalizedFrequencies(profile, queryOrder.toSet(), RunContext())
                val ordering = "frequencies=$frequencyOrder, queries=$queryOrder"
                assertEquals(expected, result, ordering)
                assertEquals(listOf("q0", "q1", "q2", "q3"), result.keys.toList(), ordering)
            }
        }
    }
}
