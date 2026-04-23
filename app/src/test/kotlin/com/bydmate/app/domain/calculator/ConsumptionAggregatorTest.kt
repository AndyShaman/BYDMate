package com.bydmate.app.domain.calculator

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ConsumptionAggregatorTest {

    @Before fun reset() { ConsumptionAggregator.reset() }
    @After fun teardown() { ConsumptionAggregator.reset() }

    private val baselineEma = 18.0
    private fun sample(now: Long, tripStart: Long?, km: Double?, kwh: Double?) {
        ConsumptionAggregator.onSample(now, tripStart, km, kwh, baselineEma)
    }

    @Test fun `no trip — show baseline EMA without arrow`() {
        sample(now = 1_000, tripStart = null, km = 12_345.0, kwh = 1500.0)
        val state = ConsumptionAggregator.state.value
        assertEquals(baselineEma, state.displayValue!!, 0.001)
        assertEquals(Trend.NONE, state.trend)
    }

    @Test fun `no trip and no history — display null`() {
        ConsumptionAggregator.onSample(1_000, null, 0.0, 0.0, baselineEma = 0.0)
        val state = ConsumptionAggregator.state.value
        assertNull(state.displayValue)
        assertEquals(Trend.NONE, state.trend)
    }

    @Test fun `active trip under 1 km — display null, no arrow`() {
        val start = 1_000L
        sample(now = start, tripStart = start, km = 12_345.0, kwh = 1500.0)
        sample(now = start + 60_000, tripStart = start, km = 12_345.2, kwh = 1500.02)   // tripKm=0.2
        val state = ConsumptionAggregator.state.value
        assertNull(state.displayValue)
        assertEquals(Trend.NONE, state.trend)
    }

    @Test fun `active trip 1 to 2 km — show trip-avg, no arrow`() {
        val start = 0L
        sample(now = start, tripStart = start, km = 100.0, kwh = 1000.0)
        // 1.5 km driven, 0.3 kWh → 20 kWh/100km
        sample(now = start + 120_000, tripStart = start, km = 101.5, kwh = 1000.3)
        val state = ConsumptionAggregator.state.value
        assertEquals(20.0, state.displayValue!!, 0.001)
        assertEquals(Trend.NONE, state.trend)   // below 2 km → trend stays NONE
    }

    @Test fun `active trip above 2 km — show trip-avg`() {
        val start = 0L
        sample(now = start, tripStart = start, km = 100.0, kwh = 1000.0)
        // 2 km driven, 0.32 kWh → 16 kWh/100km
        sample(now = start + 240_000, tripStart = start, km = 102.0, kwh = 1000.32)
        val state = ConsumptionAggregator.state.value
        assertEquals(16.0, state.displayValue!!, 0.001)
    }

    @Test fun `tiny distance 50 m — display null even with time elapsed`() {
        val start = 0L
        sample(now = start, tripStart = start, km = 100.0, kwh = 1000.0)
        sample(now = start + 240_000, tripStart = start, km = 100.05, kwh = 1000.01)   // tripKm=0.05
        val state = ConsumptionAggregator.state.value
        assertNull(state.displayValue)
        assertEquals(Trend.NONE, state.trend)
    }

    @Test fun `trend stays FLAT for 58 sec of below-threshold ratio, then DOWN`() {
        val start = 0L
        sample(start, start, 100.0, 1000.0)
        // tripKm=10 ≥ 2.0 → trend gate open. ratio = 15/18 = 0.833 → candidate DOWN
        sample(start + 240_000, start, 110.0, 1001.5)   // 15 kWh/100km
        val tBase = start + 240_000
        // 58 sec later — still candidate DOWN, but not committed yet
        sample(tBase + 58_000, start, 120.0, 1003.0)    // 15 kWh/100km
        assertEquals(Trend.NONE, ConsumptionAggregator.state.value.trend)
        // 62 sec total — debounce satisfied → DOWN
        sample(tBase + 62_000, start, 121.0, 1003.15)   // 15 kWh/100km
        assertEquals(Trend.DOWN, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `trend resets if candidate bounces back within debounce`() {
        val start = 0L
        sample(start, start, 100.0, 1000.0)
        val tBase = start + 240_000
        sample(tBase, start, 110.0, 1001.5)              // ratio 0.833 — candidate DOWN
        sample(tBase + 30_000, start, 111.0, 1001.7)     // still DOWN candidate
        // Now driving jumps to high consumption — trip-avg climbs into band
        sample(tBase + 40_000, start, 120.0, 1003.7)
        // Candidate flipped — not committed
        assertEquals(Trend.NONE, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `hysteresis holds DOWN when ratio drifts to 0 96`() {
        val start = 0L
        sample(start, start, 100.0, 1000.0)
        val tBase = start + 240_000
        // ratio 0.833 → candidate DOWN, after 61 sec committed
        sample(tBase, start, 110.0, 1001.5)
        sample(tBase + 61_000, start, 121.0, 1003.15)     // still 15 kWh/100km
        assertEquals(Trend.DOWN, ConsumptionAggregator.state.value.trend)
        // ratio drifts to 0.96 (still < 0.97 exit threshold) → hold DOWN
        sample(tBase + 120_000, start, 200.0, 1017.28)    // 17.28 kWh/100km, ratio 0.96
        assertEquals(Trend.DOWN, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `reset clears trip state`() {
        val start = 0L
        sample(start, start, 100.0, 1000.0)
        sample(start + 240_000, start, 110.0, 1001.5)
        ConsumptionAggregator.reset()
        assertEquals(Trend.NONE, ConsumptionAggregator.state.value.trend)
        assertNull(ConsumptionAggregator.state.value.displayValue)
    }
}
