package com.bydmate.app.data.push

import com.bydmate.app.data.push.TurnSeries.Event.End
import com.bydmate.app.data.push.TurnSeries.Event.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnSeriesTest {

    private val series = TurnSeries(endAfterMs = 1_500L)

    @Test fun `a blinking signal is one start and one end`() {
        assertEquals(listOf(Start("left")), series.onValue("left", 0L))
        // Every blink pushes off and on again, 400 ms dark.
        for (blink in 1..5) {
            assertEquals(emptyList<TurnSeries.Event>(), series.onValue("off", blink * 800L - 400L))
            assertEquals(emptyList<TurnSeries.Event>(), series.onValue("left", blink * 800L))
        }
        assertEquals(emptyList<TurnSeries.Event>(), series.onValue("off", 4_400L))

        assertNull(series.onTimer(5_000L))
        assertEquals(End("left", blinks = 6, durationMs = 4_400L), series.onTimer(5_900L))
        assertNull(series.onTimer(9_000L))
    }

    @Test fun `a held mask is one start and one end`() {
        assertEquals(listOf(Start("right")), series.onValue("right", 10_000L))
        series.onValue("off", 18_000L)

        assertEquals(End("right", blinks = 1, durationMs = 8_000L), series.onTimer(19_500L))
    }

    @Test fun `the next use after a long dark phase ends the old series first, even without a timer`() {
        series.onValue("left", 0L)
        series.onValue("off", 1_000L)

        assertEquals(listOf(End("left", 1, 1_000L), Start("left")), series.onValue("left", 9_000L))
    }

    @Test fun `switching side ends one series and starts the next`() {
        series.onValue("left", 0L)

        assertEquals(listOf(End("left", 1, 2_000L), Start("hazard")), series.onValue("hazard", 2_000L))
    }

    @Test fun `switching side in a dark phase ends the old series when it went dark`() {
        series.onValue("left", 0L)
        series.onValue("off", 3_000L)

        assertEquals(listOf(End("left", 1, 3_000L), Start("right")), series.onValue("right", 3_300L))
    }

    @Test fun `off or no data outside a series writes nothing`() {
        assertEquals(emptyList<TurnSeries.Event>(), series.onValue("off", 0L))
        assertEquals(emptyList<TurnSeries.Event>(), series.onValue("none", 100L))
        assertNull(series.onTimer(10_000L))
    }

    @Test fun `no data inside a series counts as dark`() {
        series.onValue("left", 0L)
        series.onValue("none", 1_000L)

        assertEquals(End("left", 1, 1_000L), series.onTimer(2_500L))
    }
}
