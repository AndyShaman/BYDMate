package com.bydmate.app.domain.calculator

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Aggregator after v2.5.0 refactor: stateless trend computer, no session/buffer
 * logic. TrackingService feeds it the already-computed recent and short averages
 * from OdometerConsumptionBuffer.
 */
class ConsumptionAggregatorTest {

    @Before fun reset() { ConsumptionAggregator.reset() }
    @After fun teardown() { ConsumptionAggregator.reset() }

    @Test fun `null short — display set, trend NONE`() {
        ConsumptionAggregator.onSample(now = 0L, recentAvg = 18.0, shortAvg = null)
        val s = ConsumptionAggregator.state.value
        assertEquals(18.0, s.displayValue!!, 0.001)
        assertEquals(Trend.NONE, s.trend)
    }

    @Test fun `recent zero — display null, trend NONE`() {
        ConsumptionAggregator.onSample(now = 0L, recentAvg = 0.0, shortAvg = 18.0)
        val s = ConsumptionAggregator.state.value
        assertNull(s.displayValue)
        assertEquals(Trend.NONE, s.trend)
    }

    @Test fun `ratio inside band — trend FLAT after debounce`() {
        var now = 0L
        ConsumptionAggregator.onSample(now, 18.0, 19.0)
        now += 35_000
        ConsumptionAggregator.onSample(now, 18.0, 19.0)
        assertEquals(Trend.FLAT, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `short below 0_90 → DOWN after debounce`() {
        var now = 0L
        ConsumptionAggregator.onSample(now, 20.0, 17.0)  // ratio 0.85
        now += 35_000
        ConsumptionAggregator.onSample(now, 20.0, 17.0)
        assertEquals(Trend.DOWN, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `short above 1_10 → UP after debounce`() {
        var now = 0L
        ConsumptionAggregator.onSample(now, 20.0, 23.0)  // ratio 1.15
        now += 35_000
        ConsumptionAggregator.onSample(now, 20.0, 23.0)
        assertEquals(Trend.UP, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `trend stays NONE before debounce expires`() {
        ConsumptionAggregator.onSample(now = 0, recentAvg = 20.0, shortAvg = 23.0)
        ConsumptionAggregator.onSample(now = 5_000, recentAvg = 20.0, shortAvg = 23.0)
        assertEquals(Trend.NONE, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `flapping candidate resets debounce timer`() {
        ConsumptionAggregator.onSample(now = 0, recentAvg = 20.0, shortAvg = 23.0)
        ConsumptionAggregator.onSample(now = 20_000, recentAvg = 20.0, shortAvg = 17.0)
        ConsumptionAggregator.onSample(now = 35_000, recentAvg = 20.0, shortAvg = 17.0)
        assertEquals(Trend.NONE, ConsumptionAggregator.state.value.trend)
        ConsumptionAggregator.onSample(now = 70_000, recentAvg = 20.0, shortAvg = 17.0)
        assertEquals(Trend.DOWN, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `reset wipes everything`() {
        ConsumptionAggregator.onSample(now = 0, recentAvg = 20.0, shortAvg = 17.0)
        ConsumptionAggregator.onSample(now = 35_000, recentAvg = 20.0, shortAvg = 17.0)
        ConsumptionAggregator.reset()
        val s = ConsumptionAggregator.state.value
        assertNull(s.displayValue)
        assertEquals(Trend.NONE, s.trend)
    }
}
