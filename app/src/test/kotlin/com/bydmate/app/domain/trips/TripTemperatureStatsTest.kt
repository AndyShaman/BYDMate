package com.bydmate.app.domain.trips

import com.bydmate.app.domain.trips.TripTemperatureStats.State
import com.bydmate.app.domain.trips.TripTemperatureStats.Trip
import com.bydmate.app.domain.trips.TripTemperatureStats.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTemperatureStatsTest {

    private fun trip(temp: Double, kwh: Double, km: Double = 30.0) = Trip(temp, kwh, km)

    /** A winter-to-summer set: three filled bins, a wide span, all three medians present. */
    private fun fullYear(): List<Trip> = listOf(
        trip(-12.0, 34.0), trip(-9.0, 33.0), trip(-6.0, 32.0),
        trip(3.0, 28.0), trip(7.0, 27.0), trip(12.0, 27.0),
        trip(19.0, 23.0), trip(22.0, 22.0), trip(26.0, 24.0),
    )

    @Test
    fun `a full year of trips gets a trend, three medians and the winter verdict`() {
        val s = TripTemperatureStats(fullYear())

        assertEquals(State.OK, s.state)
        assertEquals(9, s.shown)
        assertEquals(33.0, s.medianCold!!, 1e-9)
        assertEquals(27.0, s.medianMild!!, 1e-9)
        assertEquals(23.0, s.medianWarm!!, 1e-9)
        assertEquals(43, s.winterHigherPercent)
        assertEquals(Verdict.WINTER_HIGHER, s.verdict)
        assertTrue(s.trend.size >= TripTemperatureStats.MIN_TREND_BINS)
    }

    @Test
    fun `a cold median below the warm one is not called a winter penalty`() {
        val s = TripTemperatureStats(
            fullYear().map { if (it.tempC < 0.0) it.copy(kwhPer100km = 20.0) else it }
        )

        assertEquals(-13, s.winterHigherPercent)
        assertEquals(Verdict.WINTER_NOT_HIGHER, s.verdict)
    }

    @Test
    fun `under eight trips the screen only reports the count`() {
        val s = TripTemperatureStats(fullYear().take(5))

        assertEquals(State.NOT_ENOUGH, s.state)
        assertEquals(5, s.tripsWithTemp)
        assertEquals(Verdict.NONE, s.verdict)
    }

    /** All trips within 10 degrees: points are still drawn, the trend line is not. */
    @Test
    fun `a narrow temperature range drops the trend line and states the range`() {
        val s = TripTemperatureStats(
            listOf(
                trip(10.0, 24.0), trip(11.0, 25.0), trip(12.0, 23.0), trip(13.0, 26.0),
                trip(15.0, 24.0), trip(17.0, 23.0), trip(18.0, 22.0), trip(20.0, 24.0),
            )
        )

        assertEquals(State.NARROW_RANGE, s.state)
        assertEquals(8, s.shown)
        assertTrue(s.trend.isEmpty())
        assertEquals(10 to 20, s.observedRange)
        assertEquals(Verdict.NARROW_RANGE, s.verdict)
        assertNull(s.medianCold)
    }

    @Test
    fun `short trips are left out of the chart`() {
        val s = TripTemperatureStats(fullYear() + trip(-20.0, 60.0, km = 2.0))

        assertEquals(10, s.tripsWithTemp)
        assertEquals(9, s.shown)
        assertTrue(s.points.none { it.tempC == -20.0 })
    }

    @Test
    fun `long trips get the bigger dot`() {
        val s = TripTemperatureStats(listOf(trip(5.0, 25.0, km = 21.0), trip(5.0, 25.0, km = 6.0)))

        assertEquals(listOf(true, false), s.points.map { it.big })
    }

    @Test
    fun `a range median needs three trips`() {
        val twoCold = listOf(
            trip(-10.0, 34.0), trip(-8.0, 33.0),
            trip(18.0, 23.0), trip(20.0, 22.0), trip(24.0, 24.0),
            trip(5.0, 27.0), trip(6.0, 28.0), trip(7.0, 26.0),
        )
        val s = TripTemperatureStats(twoCold)

        assertNull(s.medianCold)
        assertNull(s.winterHigherPercent)
        assertEquals(23.0, s.medianWarm!!, 1e-9)
    }

    @Test
    fun `the temperature axis is never narrower than twenty degrees`() {
        val s = TripTemperatureStats(listOf(trip(11.0, 24.0), trip(14.0, 25.0)))

        assertTrue(s.tempAxis.max - s.tempAxis.min >= 20.0)
        assertEquals(0.0, s.tempAxis.min % 5.0, 1e-9)
        assertEquals(0.0, s.tempAxis.max % 5.0, 1e-9)
        assertEquals(15.0, s.consumptionAxis.min, 1e-9)
        assertEquals(45.0, s.consumptionAxis.max, 1e-9)
    }

    @Test
    fun `the consumption axis grows past the default window`() {
        val s = TripTemperatureStats(listOf(trip(-15.0, 52.0), trip(20.0, 12.0)))

        assertEquals(10.0, s.consumptionAxis.min, 1e-9)
        assertEquals(55.0, s.consumptionAxis.max, 1e-9)
    }

    @Test
    fun `median takes the middle of an odd list and the mean of an even one`() {
        assertEquals(2.0, TripTemperatureStats.median(listOf(3.0, 1.0, 2.0))!!, 1e-9)
        assertEquals(2.5, TripTemperatureStats.median(listOf(1.0, 2.0, 3.0, 4.0))!!, 1e-9)
        assertNull(TripTemperatureStats.median(emptyList()))
    }
}
