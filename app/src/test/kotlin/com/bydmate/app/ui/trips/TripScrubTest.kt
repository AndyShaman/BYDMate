package com.bydmate.app.ui.trips

import com.bydmate.app.data.local.entity.TripPointEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripScrubTest {

    /** Point on a meridian: `metres` north of lat 53.0, so distance is easy to reason about. */
    @Suppress("LongParameterList") // one optional field per point column the tests vary
    private fun pt(
        metres: Double = 0.0,
        ts: Long = 0L,
        speed: Double? = null,
        power: Double? = null,
        energy: Double? = null,
        run: Long? = null,
    ) = TripPointEntity(
        tripId = 1,
        timestamp = ts,
        lat = 53.0 + metres / 111_195.0,
        lon = 27.0,
        speedKmh = speed,
        batteryPowerW = power,
        batteryEnergyWh = energy,
        energyRun = run,
    )

    /**
     * Evenly spaced points: [stepM] metres and [stepMs] ms apart. With a [power] the points also
     * carry the cumulative battery counter of a tracker run that drew [power] all along.
     */
    private fun track(
        n: Int,
        stepM: Double = 100.0,
        stepMs: Long = 10_000L,
        speed: Double? = 36.0,
        power: Double? = 10_000.0,
    ) = List(n) {
        pt(
            it * stepM, it * stepMs, speed, power,
            energy = power?.let { w -> it * w * stepMs / 3_600_000.0 },
            run = if (power != null) 1L else null,
        )
    }

    /** Points at the given timestamps carrying the given counter values (run 1). */
    private fun counter(vararg tsWh: Pair<Long, Double?>) =
        tsWh.map { (ts, wh) -> pt(ts = ts, energy = wh, run = if (wh != null) 1L else null) }

    private fun withPower(points: List<TripPointEntity>, powers: List<Double?>) =
        points.mapIndexed { i, p -> p.copy(batteryPowerW = powers[i]) }

    private fun withSpeed(points: List<TripPointEntity>, speeds: List<Double?>) =
        points.mapIndexed { i, p -> p.copy(speedKmh = speeds[i]) }

    private fun assertRel(expected: Double, actual: Double?, rel: Double = 0.005) {
        assertNotNull(actual)
        assertEquals(expected, actual!!, Math.abs(expected) * rel)
    }

    // 1
    @Test
    fun `cumulativeDistanceM sums haversine legs`() {
        val d = TripScrub.cumulativeDistanceM(listOf(pt(0.0), pt(100.0), pt(300.0)))
        assertEquals(3, d.size)
        assertEquals(0.0, d[0], 0.0)
        assertRel(100.0, d[1])
        assertRel(300.0, d[2])
    }

    @Test
    fun `cumulativeDistanceM handles empty and single point`() {
        assertEquals(0, TripScrub.cumulativeDistanceM(emptyList()).size)
        assertArrayEquals(doubleArrayOf(0.0), TripScrub.cumulativeDistanceM(listOf(pt())), 0.0)
    }

    // 2
    @Test
    fun `hasPower counts the battery counter, not the instantaneous power`() {
        assertFalse(TripScrub.hasPower(emptyList()))
        assertFalse(TripScrub.hasPower(listOf(pt(energy = 1.0, run = 1L))))
        // power without a counter does not count
        assertFalse(TripScrub.hasPower(listOf(pt(power = 1000.0), pt(ts = 1000L, power = 2000.0))))
        assertTrue(
            TripScrub.hasPower(
                listOf(pt(energy = 0.0, run = 1L), pt(ts = 1000L, energy = 1.0, run = 1L))
            )
        )
    }

    // 3
    private val timePoints = listOf(pt(ts = 0), pt(ts = 1000), pt(ts = 9000), pt(ts = 10_000))

    @Test
    fun `indexAtFraction picks the nearest timestamp, tie goes to the lower index`() {
        assertEquals(1, TripScrub.indexAtFraction(timePoints, 0.5f))
        assertEquals(2, TripScrub.indexAtFraction(timePoints, 0.85f))
    }

    @Test
    fun `indexAtFraction clamps out of range fractions`() {
        assertEquals(0, TripScrub.indexAtFraction(timePoints, -0.3f))
        assertEquals(3, TripScrub.indexAtFraction(timePoints, 1.7f))
    }

    // 4
    @Test
    fun `fractionOfIndex is the time share of the point`() {
        assertEquals(0.9f, TripScrub.fractionOfIndex(timePoints, 2), 1e-6f)
    }

    @Test
    fun `fractionOfIndex is zero when all timestamps are equal`() {
        val same = listOf(pt(ts = 500), pt(ts = 500), pt(ts = 500))
        for (i in same.indices) assertEquals(0f, TripScrub.fractionOfIndex(same, i), 0f)
    }

    // 5
    @Test
    fun `energyKwh reads constant power from the counter`() {
        val p = track(36, stepMs = 100_000L, power = 10_000.0)
        assertEquals(9.7222222, TripScrub.energyKwh(p, 0, 35)!!, 1e-6)
    }

    // 6
    @Test
    fun `energyKwh is the difference of the counters`() {
        val p = counter(0L to 0.0, 60_000L to 100.0, 120_000L to 250.0)
        assertEquals(0.25, TripScrub.energyKwh(p, 0, 2)!!, 1e-9)
    }

    // 7
    @Test
    fun `energyKwh is negative for regen`() {
        val p = counter(0L to 500.0, 60_000L to 0.0)
        assertEquals(-0.5, TripScrub.energyKwh(p, 0, 1)!!, 1e-9)
    }

    // 8
    @Test
    fun `energyKwh is null when an interval is longer than MAX_GAP_MS`() {
        val p = counter(
            0L to 0.0,
            60_000L to 100.0,
            260_000L to 200.0, // dt = 200 s
            320_000L to 300.0,
        )
        assertNull(TripScrub.energyKwh(p, 0, 3))
    }

    // 9
    @Test
    fun `energyKwh is null when any point in the range has no counter`() {
        val p = counter(0L to 0.0, 10_000L to null, 20_000L to 5.0)
        assertNull(TripScrub.energyKwh(p, 0, 2))
    }

    @Test
    fun `energyKwh is zero for an empty range`() {
        val p = counter(0L to 0.0, 10_000L to 5.0)
        assertEquals(0.0, TripScrub.energyKwh(p, 1, 1)!!, 0.0)
    }

    // 9b
    @Test
    fun `energyKwh is null across two different tracker runs`() {
        val p = listOf(
            pt(ts = 0, energy = 100.0, run = 1L),
            pt(ts = 10_000, energy = 0.0, run = 2L),
        )
        assertNull(TripScrub.energyKwh(p, 0, 1))
    }

    // 10
    @Test
    fun `localConsumption over a symmetric window`() {
        val p = track(40, stepM = 50.0, stepMs = 10_000L, speed = 18.0, power = 5_000.0)
        val d = TripScrub.cumulativeDistanceM(p)
        assertRel(27.78, TripScrub.localConsumption(p, d, 20, 400.0, 15.0))
    }

    // 10b
    @Test
    fun `localConsumption brackets the nearest points beyond the edges of a thinned track`() {
        // 5 points 1 km / 60 s apart: window [1, 3] = 2 km, 2 x 250 Wh
        val p = track(5, stepM = 1000.0, stepMs = 60_000L, speed = 60.0, power = 15_000.0)
        val d = TripScrub.cumulativeDistanceM(p)
        assertRel(25.0, TripScrub.localConsumption(p, d, 2, 400.0, 15.0))
    }

    // 11
    @Test
    fun `localConsumption is null below the minimum speed`() {
        val p = track(40, stepM = 50.0, speed = 18.0, power = 5_000.0)
            .mapIndexed { i, x -> if (i == 20) x.copy(speedKmh = 10.0) else x }
        val d = TripScrub.cumulativeDistanceM(p)
        assertNull(TripScrub.localConsumption(p, d, 20, 400.0, 15.0))
    }

    // 12
    @Test
    fun `localConsumption is null for a window shorter than MIN_WINDOW_KM`() {
        val p = track(2, stepM = 50.0, speed = 18.0, power = 5_000.0)
        val d = TripScrub.cumulativeDistanceM(p)
        assertNull(TripScrub.localConsumption(p, d, 0, 400.0, 15.0))
    }

    // 13
    @Test
    fun `localConsumption is null when the window has a point without a counter`() {
        val p = track(40, stepM = 50.0, speed = 18.0, power = 5_000.0)
            .mapIndexed { i, x -> if (i == 15) x.copy(batteryEnergyWh = null, energyRun = null) else x }
        val d = TripScrub.cumulativeDistanceM(p)
        assertNull(TripScrub.localConsumption(p, d, 20, 400.0, 15.0))
    }

    // 13b
    @Test
    fun `localConsumption is null when the window has an interval over MAX_GAP_MS`() {
        // from point 15 on, everything is 200 s later: the 14 -> 15 interval is 210 s
        val p = track(40, stepM = 50.0, speed = 18.0, power = 5_000.0)
            .mapIndexed { i, x -> if (i >= 15) x.copy(timestamp = x.timestamp + 200_000L) else x }
        val d = TripScrub.cumulativeDistanceM(p)
        assertNull(TripScrub.localConsumption(p, d, 20, 400.0, 15.0))
    }

    // 14
    @Test
    fun `localConsumption is null when speed at the index is unknown`() {
        val p = track(40, stepM = 50.0, speed = 18.0, power = 5_000.0)
            .mapIndexed { i, x -> if (i == 20) x.copy(speedKmh = null) else x }
        val d = TripScrub.cumulativeDistanceM(p)
        assertNull(TripScrub.localConsumption(p, d, 20, 400.0, 15.0))
    }

    // 15
    @Test
    fun `rangeStats of a steady stretch`() {
        val p = track(21)
        val d = TripScrub.cumulativeDistanceM(p)
        val s = TripScrub.rangeStats(p, d, 5, 15)
        assertRel(1.0, s.distanceKm)
        assertEquals(100_000L, s.durationMs)
        assertRel(36.0, s.avgSpeedKmh)
        assertRel(0.2778, s.energyKwh, 0.005)
        assertRel(27.78, s.kwhPer100Km)
        assertRel(0.5, s.shareOfTrip)
    }

    // 16
    @Test
    fun `rangeStats has no average speed when the car stood still`() {
        val p = track(21, speed = 0.0)
        val d = TripScrub.cumulativeDistanceM(p)
        assertNull(TripScrub.rangeStats(p, d, 5, 15).avgSpeedKmh)
    }

    @Test
    fun `rangeStats has no energy when a point in range lacks a counter`() {
        val p = track(21)
            .mapIndexed { i, x -> if (i == 8) x.copy(batteryEnergyWh = null, energyRun = null) else x }
        val d = TripScrub.cumulativeDistanceM(p)
        val s = TripScrub.rangeStats(p, d, 5, 15)
        assertNull(s.energyKwh)
        assertNull(s.kwhPer100Km)
    }

    @Test
    fun `rangeStats keeps energy but drops kwhPer100Km for a range under MIN_WINDOW_KM`() {
        val p = track(21, stepM = 50.0)
        val d = TripScrub.cumulativeDistanceM(p)
        val s = TripScrub.rangeStats(p, d, 5, 6) // 50 m
        assertNotNull(s.energyKwh)
        assertNull(s.kwhPer100Km)
    }

    // 16b
    @Test
    fun `rangeStats has no speed or energy when the range has a gap over MAX_GAP_MS`() {
        // the 7 -> 8 interval is 300 s
        val p = track(21)
            .mapIndexed { i, x -> if (i >= 8) x.copy(timestamp = x.timestamp + 290_000L) else x }
        val d = TripScrub.cumulativeDistanceM(p)
        val s = TripScrub.rangeStats(p, d, 5, 15)
        assertNull(s.avgSpeedKmh)
        assertNull(s.energyKwh)
        assertNull(s.kwhPer100Km)
        assertTrue(s.distanceKm > 0.0)
    }

    // 17
    @Test
    fun `rangeStats swaps a reversed range`() {
        val p = track(21)
        val d = TripScrub.cumulativeDistanceM(p)
        assertEquals(TripScrub.rangeStats(p, d, 5, 15), TripScrub.rangeStats(p, d, 15, 5))
    }

    // 18
    private val ramp = withPower(
        track(5), listOf(1000.0, 2000.0, 3000.0, 4000.0, 5000.0)
    )

    @Test
    fun `smoothedPowerKw averages the neighbours`() {
        assertEquals(3.0, TripScrub.smoothedPowerKw(ramp, 2, 2)!!, 1e-9)
        assertEquals(2.0, TripScrub.smoothedPowerKw(ramp, 0, 2)!!, 1e-9)
    }

    @Test
    fun `smoothedPowerKw skips a null neighbour`() {
        val p = withPower(track(5), listOf(1000.0, null, 3000.0, 4000.0, 5000.0))
        // index 2, radius 2 -> [1000, 3000, 4000, 5000]
        assertEquals(3.25, TripScrub.smoothedPowerKw(p, 2, 2)!!, 1e-9)
    }

    @Test
    fun `smoothedPowerKw is null when the point itself has no power`() {
        val p = withPower(track(5), listOf(1000.0, 2000.0, null, 4000.0, 5000.0))
        assertNull(TripScrub.smoothedPowerKw(p, 2, 2))
    }

    // 19
    @Test
    fun `maxSpeedIndex returns the first maximum`() {
        val p = withSpeed(track(4), listOf(10.0, 50.0, 50.0, 30.0))
        assertEquals(1, TripScrub.maxSpeedIndex(p))
    }

    @Test
    fun `maxSpeedIndex is null when no speed is known`() {
        assertNull(TripScrub.maxSpeedIndex(withSpeed(track(3), listOf(null, null, null))))
    }

    // 20
    @Test
    fun `maxRegenIndex returns the strongest regen`() {
        val p = withPower(track(4), listOf(5000.0, -20_000.0, -35_000.0, 1000.0))
        assertEquals(2, TripScrub.maxRegenIndex(p))
    }

    @Test
    fun `maxRegenIndex is null without negative power`() {
        assertNull(TripScrub.maxRegenIndex(withPower(track(3), listOf(0.0, 1000.0, 2000.0))))
        assertNull(TripScrub.maxRegenIndex(withPower(track(3), listOf(null, null, null))))
    }

    // 21
    @Test
    fun `speedColor returns the exact stop colors`() {
        assertEquals(0xFF6366F1.toInt(), TripScrub.speedColor(0.0))
        assertEquals(0xFF22D3EE.toInt(), TripScrub.speedColor(30.0))
        assertEquals(0xFF4ADE80.toInt(), TripScrub.speedColor(60.0))
        assertEquals(0xFFFDE047.toInt(), TripScrub.speedColor(90.0))
        assertEquals(0xFFFB923C.toInt(), TripScrub.speedColor(120.0))
        assertEquals(0xFFF43F5E.toInt(), TripScrub.speedColor(140.0))
    }

    @Test
    fun `speedColor clamps outside the scale`() {
        assertEquals(TripScrub.speedColor(0.0), TripScrub.speedColor(-5.0))
        assertEquals(TripScrub.speedColor(140.0), TripScrub.speedColor(200.0))
    }

    @Test
    fun `speedColor interpolates each channel with rounding`() {
        assertEquals(0xFF439DF0.toInt(), TripScrub.speedColor(15.0))
    }

    // 22
    @Test
    fun `consumptionColor returns the exact stop colors`() {
        assertEquals(0xFF3B82F6.toInt(), TripScrub.consumptionColor(-20.0))
        assertEquals(0xFF93C5FD.toInt(), TripScrub.consumptionColor(-3.0))
        assertEquals(0xFFCBD5E1.toInt(), TripScrub.consumptionColor(0.0))
        assertEquals(0xFFCBD5E1.toInt(), TripScrub.consumptionColor(18.0))
        assertEquals(0xFFFBBF24.toInt(), TripScrub.consumptionColor(27.0))
        assertEquals(0xFFF97316.toInt(), TripScrub.consumptionColor(40.0))
        assertEquals(0xFFEF4444.toInt(), TripScrub.consumptionColor(60.0))
    }

    @Test
    fun `consumptionColor is flat between 0 and 18, clamps, and marks missing data`() {
        assertEquals(0xFFCBD5E1.toInt(), TripScrub.consumptionColor(10.0))
        assertEquals(0xFFEF4444.toInt(), TripScrub.consumptionColor(100.0))
        assertEquals(0xFF3B82F6.toInt(), TripScrub.consumptionColor(-50.0))
        assertEquals(TripScrub.NO_DATA_COLOR, TripScrub.consumptionColor(null))
    }

    @Test
    fun `north pad leaves the route exactly the map height under the values panel`() {
        // 500 px map, 48 px border, panel bottom at 100 px: the route gets 500 - 96 - 100 = 304 px.
        val k = TripScrub.northPadFraction(mapHeightPx = 500, borderPx = 48, topInsetPx = 100)
        assertEquals(100.0 / 304.0, k, 1e-9)
        // Fitting a box of (1 + k) route heights into 404 px leaves the route 404 / (1 + k) = 304 px.
        assertEquals(304.0, 404.0 / (1 + k), 1e-6)
    }

    @Test
    fun `north pad is zero without a panel and the old 30 percent when the map is too small`() {
        assertEquals(0.0, TripScrub.northPadFraction(mapHeightPx = 500, borderPx = 48, topInsetPx = 0), 0.0)
        assertEquals(0.3, TripScrub.northPadFraction(mapHeightPx = 200, borderPx = 48, topInsetPx = 120), 0.0)
        assertEquals(0.3, TripScrub.northPadFraction(mapHeightPx = 0, borderPx = 48, topInsetPx = 80), 0.0)
    }
}
