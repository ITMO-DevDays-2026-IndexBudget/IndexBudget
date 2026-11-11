package ru.itmo.indexbudget.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CostEffectTest {
    @Test
    fun `shared effect preserves gain sign ratio and actual zero`() {
        val gain = CostEffect.calculate(200.0, 50.0)
        assertEquals(150.0, gain.gain)
        assertEquals(0.25, gain.ratio)
        assertEquals(75.0, gain.gainPercent)
        assertEquals(RelativeValueStatus.AVAILABLE, gain.relativeStatus)
        assertEquals(-25.0, CostEffect.calculate(200.0, 250.0).gainPercent)
        assertEquals(0.0, CostEffect.calculate(200.0, 200.0).gainPercent)
        assertEquals(100.0, CostEffect.calculate(200.0, 0.0).gainPercent)
    }

    @Test
    fun `zero and missing baselines preserve different reasons and absolute costs`() {
        val zero = CostEffect.calculate(0.0, 2.0)
        assertEquals(RelativeValueStatus.ZERO_BASELINE, zero.relativeStatus)
        assertNull(zero.ratio)
        assertNull(zero.gainPercent)
        assertEquals(-2.0, zero.gain)
        val unknown = CostEffect.calculate(null, 2.0)
        assertEquals(RelativeValueStatus.UNAVAILABLE, unknown.relativeStatus)
        assertEquals(2.0, unknown.cost)
        assertNull(unknown.gain)
    }

    @Test
    fun `positive ratio underflow is unknown while actual zero and subnormal ratios are retained`() {
        val effect = CostEffect.calculate(1e200, 1e-200)
        assertEquals(RelativeValueStatus.OUT_OF_RANGE, effect.relativeStatus)
        assertNull(effect.ratio)
        assertEquals(1e200, effect.baselineCost)
        assertEquals(1e-200, effect.cost)
        assertEquals(100.0, effect.gainPercent)
        val zero = CostEffect.calculate(1e200, 0.0)
        assertEquals(RelativeValueStatus.AVAILABLE, zero.relativeStatus)
        assertEquals(0.0, zero.ratio)
        val subnormal = CostEffect.calculate(1.0, Double.MIN_VALUE)
        assertEquals(RelativeValueStatus.AVAILABLE, subnormal.relativeStatus)
        assertEquals(Double.MIN_VALUE, subnormal.ratio)
    }

    @Test
    fun `unrepresentable relative query effects do not prevent exporting a saved run`() {
        val huge = CostEffect.calculate(1e-200, 1e200)
        assertEquals(RelativeValueStatus.OUT_OF_RANGE, huge.relativeStatus)
        assertNull(huge.ratio)
        assertNull(huge.gainPercent)
        assertEquals(1e200, huge.cost)
        val percentOverflow = CostEffect.calculate(1e308, 0.0)
        assertEquals(0.0, percentOverflow.ratio)
        assertNull(percentOverflow.gainPercent)
        assertEquals(RelativeValueStatus.OUT_OF_RANGE, percentOverflow.relativeStatus)
    }
}
