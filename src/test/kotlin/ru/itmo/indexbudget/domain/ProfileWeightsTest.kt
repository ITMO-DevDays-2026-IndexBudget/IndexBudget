package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

class ProfileWeightsTest {
    @Test
    fun `relative weights normalize without changing input`() {
        val profiles = listOf(profile("normal", 9.0), profile("campaign", 1.0))

        assertEquals(mapOf("normal" to 0.9, "campaign" to 0.1), ProfileWeights.normalize(profiles))
        assertEquals(listOf(9.0, 1.0), profiles.map { it.weight })
    }

    @ParameterizedTest(name = "profile order [{0}, {1}, {2}]")
    @CsvSource("0,1,2", "0,2,1", "1,0,2", "1,2,0", "2,0,1", "2,1,0")
    fun `normalization is independent of input order for nonassociative sums`(first: Int, second: Int, third: Int) {
        val profiles = listOf(profile("a", 1e16), profile("b", 1.0), profile("c", 1.0))
        val reordered = listOf(profiles[first], profiles[second], profiles[third])

        // (1e16 + 1) + 1 и (1 + 1) + 1e16 дают разные Double; порядок по ID фиксирует результат.
        assertEquals(mapOf("a" to 1.0, "b" to 1e-16, "c" to 1e-16), ProfileWeights.normalize(reordered))
    }

    @Test
    fun `a single subnormal weight retains its probability`() {
        assertEquals(mapOf("rare" to 1.0), ProfileWeights.normalize(listOf(profile("rare", Double.MIN_VALUE))))
    }

    @Test
    fun `small representable normalized weight is retained`() {
        val normalized = ProfileWeights.normalize(listOf(profile("rare", Double.MIN_VALUE), profile("usual", 1.0)))
        assertEquals(Double.MIN_VALUE, normalized.getValue("rare"))
        assertEquals(1.0, normalized.getValue("usual"))
    }

    @Test
    fun `normalization underflow identifies the lost profile`() {
        val error = numericFailure(listOf(profile("rare", Double.MIN_VALUE), profile("usual", 2.0)))
        assertEquals("rare", error.profileId)
        assertEquals("profiles.weight", error.details["field"])
    }

    @Test
    fun `finite individual weights cannot overflow their sum silently`() {
        val error = numericFailure(listOf(profile("a", Double.MAX_VALUE), profile("b", Double.MAX_VALUE)))
        assertNull(error.profileId)
        assertEquals("profiles.weight", error.details["field"])
    }

    @ParameterizedTest(name = "invalid weight={0}")
    @MethodSource("invalidWeights")
    fun `invalid weights are explicit numeric errors even outside the JSON reader`(weight: Double) {
        assertEquals("invalid", numericFailure(listOf(profile("invalid", weight))).profileId)
    }

    private fun profile(id: String, weight: Double) = Profile(id, weight, mapOf("q" to 1.0))

    private fun numericFailure(profiles: List<Profile>): AnalysisError {
        val failure = assertThrows(AnalysisException::class.java) { ProfileWeights.normalize(profiles) }
        assertEquals(ErrorCode.NUMERIC_ERROR, failure.error.code)
        return failure.error
    }

    companion object {
        @JvmStatic
        fun invalidWeights(): List<Double> =
            listOf(0.0, -0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
    }
}
