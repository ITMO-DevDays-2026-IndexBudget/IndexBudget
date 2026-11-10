package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WeightNormalizerTest {

    @Test
    fun `normalizes two weights that already sum to one`() {
        val result = WeightNormalizer.normalize(listOf(profile("a", 0.9), profile("b", 0.1)))
        assertEquals(0.9, result["a"]!!, 1e-15)
        assertEquals(0.1, result["b"]!!, 1e-15)
    }

    @Test
    fun `normalizes arbitrary relative weights to probabilities`() {
        val result = WeightNormalizer.normalize(listOf(profile("a", 2.0), profile("b", 3.0)))
        assertEquals(0.4, result["a"]!!, 1e-15)
        assertEquals(0.6, result["b"]!!, 1e-15)
    }

    @Test
    fun `single profile normalizes to one`() {
        val result = WeightNormalizer.normalize(listOf(profile("only", 7.0)))
        assertEquals(1.0, result["only"]!!, 1e-15)
    }

    @Test
    fun `normalized weights always sum to one`() {
        val cases = listOf(
            listOf(profile("a", 1.0), profile("b", 2.0), profile("c", 3.0)),
            listOf(profile("a", 0.001), profile("b", 999.0)),
            listOf(profile("a", 1e-150), profile("b", 1e150)),
        )
        for (profiles in cases) {
            val result = WeightNormalizer.normalize(profiles)
            assertEquals(1.0, result.values.sum(), 1e-12)
        }
    }

    @Test
    fun `non-finite weight is a numeric error`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val exception = assertThrows(AnalysisException::class.java) {
                WeightNormalizer.normalize(listOf(profile("a", bad), profile("b", 1.0)))
            }
            assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
        }
    }

    @Test
    fun `non-positive weight is a numeric error`() {
        for (bad in listOf(0.0, -1.0)) {
            val exception = assertThrows(AnalysisException::class.java) {
                WeightNormalizer.normalize(listOf(profile("a", bad), profile("b", 1.0)))
            }
            assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
        }
        // a zero weight alongside a positive one reaches the per-weight check and names the profile
        val zero = assertThrows(AnalysisException::class.java) {
            WeightNormalizer.normalize(listOf(profile("a", 0.0), profile("b", 1.0)))
        }
        assertEquals("a", zero.error.profileId)
    }

    @Test
    fun `zero weight sum is a numeric error`() {
        val exception = assertThrows(AnalysisException::class.java) {
            WeightNormalizer.normalize(listOf(profile("a", 0.0), profile("b", 0.0)))
        }
        assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
    }

    @Test
    fun `empty profile list is a numeric error`() {
        val exception = assertThrows(AnalysisException::class.java) {
            WeightNormalizer.normalize(emptyList())
        }
        assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
    }

    @Test
    fun `positive weight lost to underflow is an error not a zero probability`() {
        // a/1e300 underflows to 0.0 in double precision
        val exception = assertThrows(AnalysisException::class.java) {
            WeightNormalizer.normalize(listOf(profile("tiny", 1e-320), profile("huge", 1e300)))
        }
        assertEquals(ErrorCode.NUMERIC_ERROR, exception.error.code)
        assertEquals("tiny", exception.error.profileId)
    }

    @Test
    fun `duplicate profile IDs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            WeightNormalizer.normalize(listOf(profile("a", 1.0), profile("a", 2.0)))
        }
    }

    private fun profile(id: String, weight: Double): Profile =
        Profile(id = id, weight = weight, callsPerHour = emptyMap())
}