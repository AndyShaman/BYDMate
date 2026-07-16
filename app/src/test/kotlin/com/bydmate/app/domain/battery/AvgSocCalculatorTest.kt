package com.bydmate.app.domain.battery

import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.domain.battery.AvgSocCalculator.SocPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvgSocCalculatorTest {

    @Test
    fun `holds last soc between points and weights by time`() {
        val pts = listOf(SocPoint(0L, 80), SocPoint(100L, 40))
        // 0..100 at 80, 100..200 at 40 -> 60
        assertEquals(60, AvgSocCalculator.averageSince(pts, 0L, 200L))
    }

    @Test
    fun `window starting mid-segment holds the previous soc`() {
        val pts = listOf(SocPoint(0L, 80), SocPoint(100L, 40))
        // 50..100 at 80, 100..150 at 40 -> 60
        assertEquals(60, AvgSocCalculator.averageSince(pts, 50L, 150L))
    }

    @Test
    fun `single point extends to now`() {
        val pts = listOf(SocPoint(10L, 55))
        assertEquals(55, AvgSocCalculator.averageSince(pts, 0L, 1000L))
    }

    @Test
    fun `no points or empty window yields null`() {
        assertNull(AvgSocCalculator.averageSince(emptyList(), 0L, 100L))
        assertNull(AvgSocCalculator.averageSince(listOf(SocPoint(0L, 50)), 100L, 100L))
        // all points after the window end
        assertNull(AvgSocCalculator.averageSince(listOf(SocPoint(500L, 50)), 0L, 100L))
    }

    @Test
    fun `buildPoints merges trips and charges sorted with nulls skipped`() {
        val trips = listOf(
            TripEntity(startTs = 300L, endTs = 400L, socStart = 70, socEnd = 60),
            TripEntity(startTs = 500L, endTs = null, socStart = 58, socEnd = null), // open trip
            TripEntity(startTs = 600L, endTs = 700L, socStart = null, socEnd = null), // no soc
        )
        val charges = listOf(
            ChargeEntity(startTs = 100L, endTs = 200L, socStart = 40, socEnd = 80),
        )
        val pts = AvgSocCalculator.buildPoints(trips, charges)
        assertEquals(
            listOf(SocPoint(100L, 40), SocPoint(200L, 80), SocPoint(300L, 70), SocPoint(400L, 60), SocPoint(500L, 58)),
            pts
        )
    }

    @Test
    fun `out of range soc values are dropped`() {
        val trips = listOf(TripEntity(startTs = 0L, endTs = 10L, socStart = 101, socEnd = -1))
        assertEquals(emptyList<SocPoint>(), AvgSocCalculator.buildPoints(trips, emptyList()))
    }
}
