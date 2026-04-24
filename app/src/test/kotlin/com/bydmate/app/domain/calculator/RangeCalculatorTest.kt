package com.bydmate.app.domain.calculator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeCalculatorTest {

    private fun newCalc(
        capacity: Double = 72.9,
        recentAvg: Double = 18.0,
        carry: Double = 0.0,
    ) = RangeCalculator(
        buffer = StubBuffer(recentAvg),
        capacityProvider = { capacity },
        socInterpolator = StubInterpolator(carry),
    )

    @Test fun `null SOC returns null`() = runBlocking {
        val c = newCalc()
        assertNull(c.estimate(soc = null, totalElecKwh = 1500.0))
    }

    @Test fun `normal case 50 percent at 18 avg`() = runBlocking {
        val c = newCalc(capacity = 72.9, recentAvg = 18.0)
        // 50% × 72.9 = 36.45 kWh; 36.45 / 18 × 100 = 202.5 km
        assertEquals(202.5, c.estimate(soc = 50, totalElecKwh = 1500.0)!!, 0.5)
    }

    @Test fun `carry reduces remaining kwh`() = runBlocking {
        val c = newCalc(capacity = 72.9, recentAvg = 18.0, carry = 0.5)
        // (50% × 72.9 - 0.5) / 18 × 100 ≈ 199.7 km
        assertEquals(199.7, c.estimate(soc = 50, totalElecKwh = 1500.0)!!, 0.5)
    }

    @Test fun `zero avg returns null`() = runBlocking {
        val c = newCalc(recentAvg = 0.0)
        assertNull(c.estimate(soc = 50, totalElecKwh = 1500.0))
    }

    @Test fun `capacity change applies immediately`() = runBlocking {
        var cap = 50.0
        val c = RangeCalculator(
            buffer = StubBuffer(18.0),
            capacityProvider = { cap },
            socInterpolator = StubInterpolator(0.0),
        )
        val before = c.estimate(soc = 50, totalElecKwh = 1500.0)!!
        cap = 72.9
        val after = c.estimate(soc = 50, totalElecKwh = 1500.0)!!
        assertTrue("range increased after capacity bump", after > before * 1.4)
    }
}

private class StubBuffer(private val avg: Double) : ConsumptionAvgSource {
    override suspend fun recentAvgConsumption(): Double = avg
}

private class StubInterpolator(private val carry: Double) : SocInterpolator(
    persistence = NoOpPrefs(),
) {
    override fun carryOver(totalElecKwh: Double?, soc: Int?): Double = carry
}

private class NoOpPrefs : SocInterpolatorPrefs {
    override fun load(): SocInterpolatorState? = null
    override fun save(state: SocInterpolatorState) {}
    override fun clear() {}
}
