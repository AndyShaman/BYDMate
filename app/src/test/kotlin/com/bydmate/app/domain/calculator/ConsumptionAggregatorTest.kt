package com.bydmate.app.domain.calculator

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * v2.5.2: Aggregator no longer derives display from recentAvg. Caller passes
 * displayValue explicitly (computed by BigNumberCalculator). Trend logic
 * unchanged: ratio = shortAvg / recentAvg with +-10% bands and 30-sec debounce.
 */
class ConsumptionAggregatorTest {

    @Before fun reset() { ConsumptionAggregator.reset() }
    @After fun teardown() { ConsumptionAggregator.reset() }

    @Test fun `null short, display passes through, trend NONE`() {
        ConsumptionAggregator.onSample(now = 0L, displayValue = 22.5, recentAvg = 18.0, shortAvg = null)
        val s = ConsumptionAggregator.state.value
        assertEquals(22.5, s.displayValue!!, 0.001)
        assertEquals(Trend.NONE, s.trend)
    }

    @Test fun `null displayValue renders prochirk`() {
        ConsumptionAggregator.onSample(now = 0L, displayValue = null, recentAvg = 18.0, shortAvg = 18.0)
        val s = ConsumptionAggregator.state.value
        assertNull(s.displayValue)
        assertEquals(Trend.NONE, s.trend)
    }

    @Test fun `recent zero, display passes, trend NONE`() {
        ConsumptionAggregator.onSample(now = 0L, displayValue = 22.5, recentAvg = 0.0, shortAvg = 18.0)
        val s = ConsumptionAggregator.state.value
        assertEquals(22.5, s.displayValue!!, 0.001)
        assertEquals(Trend.NONE, s.trend)
    }

    @Test fun `ratio inside band, trend FLAT after debounce`() {
        var now = 0L
        ConsumptionAggregator.onSample(now, displayValue = 22.5, recentAvg = 18.0, shortAvg = 19.0)
        now += 35_000
        ConsumptionAggregator.onSample(now, displayValue = 22.5, recentAvg = 18.0, shortAvg = 19.0)
        assertEquals(Trend.FLAT, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `short below 0_90, DOWN after debounce`() {
        var now = 0L
        ConsumptionAggregator.onSample(now, displayValue = 22.5, recentAvg = 20.0, shortAvg = 17.0)
        now += 35_000
        ConsumptionAggregator.onSample(now, displayValue = 22.5, recentAvg = 20.0, shortAvg = 17.0)
        assertEquals(Trend.DOWN, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `short above 1_10, UP after debounce`() {
        var now = 0L
        ConsumptionAggregator.onSample(now, displayValue = 22.5, recentAvg = 20.0, shortAvg = 23.0)
        now += 35_000
        ConsumptionAggregator.onSample(now, displayValue = 22.5, recentAvg = 20.0, shortAvg = 23.0)
        assertEquals(Trend.UP, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `trend stays NONE before debounce expires`() {
        ConsumptionAggregator.onSample(now = 0, displayValue = 22.5, recentAvg = 20.0, shortAvg = 23.0)
        ConsumptionAggregator.onSample(now = 5_000, displayValue = 22.5, recentAvg = 20.0, shortAvg = 23.0)
        assertEquals(Trend.NONE, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `flapping candidate resets debounce timer`() {
        ConsumptionAggregator.onSample(now = 0, displayValue = 22.5, recentAvg = 20.0, shortAvg = 23.0)
        ConsumptionAggregator.onSample(now = 20_000, displayValue = 22.5, recentAvg = 20.0, shortAvg = 17.0)
        ConsumptionAggregator.onSample(now = 35_000, displayValue = 22.5, recentAvg = 20.0, shortAvg = 17.0)
        assertEquals(Trend.NONE, ConsumptionAggregator.state.value.trend)
        ConsumptionAggregator.onSample(now = 70_000, displayValue = 22.5, recentAvg = 20.0, shortAvg = 17.0)
        assertEquals(Trend.DOWN, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `displayValue independent of recentAvg`() {
        ConsumptionAggregator.onSample(now = 0, displayValue = 99.9, recentAvg = 18.0, shortAvg = 18.0)
        assertEquals(99.9, ConsumptionAggregator.state.value.displayValue!!, 0.001)
    }

    @Test fun `reset wipes everything`() {
        ConsumptionAggregator.onSample(now = 0, displayValue = 22.5, recentAvg = 20.0, shortAvg = 17.0)
        ConsumptionAggregator.onSample(now = 35_000, displayValue = 22.5, recentAvg = 20.0, shortAvg = 17.0)
        ConsumptionAggregator.reset()
        val s = ConsumptionAggregator.state.value
        assertNull(s.displayValue)
        assertEquals(Trend.NONE, s.trend)
    }
}
