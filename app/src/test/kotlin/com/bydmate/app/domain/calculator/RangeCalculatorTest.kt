package com.bydmate.app.domain.calculator

import com.bydmate.app.data.repository.SettingsRepository

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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

    @Test fun `detailed exposes avg capacity and remaining kwh`() = runTest {
        val c = newCalc()
        val est = c.estimateDetailed(50, null)!!
        assertEquals(18.0, est.avgKwhPer100, 1e-9)
        assertEquals(72.9, est.capacityKwh, 1e-9)
        assertEquals(36.45, est.remainingKwh, 1e-9)
        assertEquals(est.remainingKwh / est.avgKwhPer100 * 100.0, est.rangeKm, 1e-9)
    }

    @Test fun `detailed null on null soc`() = runTest {
        val c = newCalc()
        assertNull(c.estimateDetailed(null, null))
    }

    @Test fun `estimate delegates to detailed`() = runTest {
        val c = newCalc()
        assertEquals(c.estimateDetailed(50, null)!!.rangeKm, c.estimate(50, null)!!, 1e-9)
    }

    // Sentinel / non-finite guard tests (MAJOR 1)
    @Test fun `soc above 100 returns null`() = runBlocking {
        val c = newCalc()
        assertNull(c.estimate(soc = 101, totalElecKwh = 1500.0))
    }

    @Test fun `NaN capacity returns null`() = runBlocking {
        val c = newCalc(capacity = Double.NaN)
        assertNull(c.estimate(soc = 50, totalElecKwh = 1500.0))
    }

    @Test fun `Infinity capacity returns null`() = runBlocking {
        val c = newCalc(capacity = Double.POSITIVE_INFINITY)
        assertNull(c.estimate(soc = 50, totalElecKwh = 1500.0))
    }

    @Test fun `NaN carry returns null`() = runBlocking {
        val c = newCalc(carry = Double.NaN)
        assertNull(c.estimate(soc = 50, totalElecKwh = 1500.0))
    }

    @Test fun `finite but absurd capacity returns null`() = runBlocking {
        val c = newCalc(capacity = Double.MAX_VALUE)
        assertNull(c.estimate(soc = 100, totalElecKwh = 1500.0))
    }

    @Test fun `capacity above sane bound returns null`() = runBlocking {
        val c = newCalc(capacity = 1001.0)
        assertNull(c.estimate(soc = 50, totalElecKwh = 1500.0))
    }

    @Test fun `capacity below sane bound returns null`() = runBlocking {
        val c = newCalc(capacity = 0.5)
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
    @Test fun `manual mode uses supplied battery temperature`() = runTest {
        val table = listOf(
            SettingsRepository.ManualRangePoint(temperatureC = -20, consumptionKwhPer100Km = 30.0),
            SettingsRepository.ManualRangePoint(temperatureC = 20, consumptionKwhPer100Km = 10.0),
        )
        val c = RangeCalculator(
            buffer = StubBuffer(18.0),
            capacityProvider = { 60.0 },
            socInterpolator = StubInterpolator(0.0),
            methodProvider = { SettingsRepository.RANGE_CALC_MANUAL },
            manualTableProvider = { table },
        )

        val cold = c.estimate(soc = 50, totalElecKwh = null, batteryTempC = -20)!!
        val warm = c.estimate(soc = 50, totalElecKwh = null, batteryTempC = 20)!!

        assertEquals(100.0, cold, 1e-9)
        assertEquals(300.0, warm, 1e-9)
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
