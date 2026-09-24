package com.bydmate.app.ui.trips

import com.bydmate.app.data.local.entity.TripPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripDetailStatsTest {

    private fun pts(vararg p: Pair<Long, Double?>) =
        p.map { (ts, v) -> TripPointEntity(tripId = 1L, timestamp = ts, lat = 0.0, lon = 0.0, speedKmh = v) }

    // ---- moving time ----

    @Test fun `moving time sums intervals that start at a moving fix`() {
        val points = pts(0L to 30.0, 10_000L to 0.0, 70_000L to 20.0, 80_000L to 40.0, 90_000L to 0.0)
        // 0-10 s moving, 10-70 s standing, 70-80 and 80-90 s moving.
        assertEquals(30_000L, TripDetailStats.movingTimeMs(points, 0L, 100_000L))
    }

    @Test fun `gaps longer than 120 s are skipped`() {
        val points = pts(0L to 50.0, 10_000L to 50.0, 400_000L to 50.0, 410_000L to 50.0)
        assertEquals(20_000L, TripDetailStats.movingTimeMs(points, 0L, 420_000L))
    }

    @Test fun `speed below 1 km per h or unknown counts as standing`() {
        val points = pts(0L to 0.5, 10_000L to null, 20_000L to 1.0, 30_000L to 0.0)
        assertEquals(10_000L, TripDetailStats.movingTimeMs(points, 0L, 30_000L))
    }

    @Test fun `too few points, no end, zero or more than the duration give null`() {
        val three = pts(0L to 50.0, 10_000L to 50.0, 20_000L to 50.0)
        assertNull(TripDetailStats.movingTimeMs(three, 0L, 30_000L))

        val four = pts(0L to 50.0, 10_000L to 50.0, 20_000L to 50.0, 30_000L to 50.0)
        assertNull(TripDetailStats.movingTimeMs(four, 0L, null))
        assertNull(TripDetailStats.movingTimeMs(four, 0L, 20_000L))

        val still = pts(0L to 0.0, 10_000L to 0.0, 20_000L to 0.0, 30_000L to 0.0)
        assertNull(TripDetailStats.movingTimeMs(still, 0L, 30_000L))
    }

    // ---- money ----

    @Test fun `cost per 100 km`() {
        assertEquals(3.2079, TripDetailStats.costPer100Km(1.62, 50.5)!!, 1e-4)
    }

    @Test fun `a known zero cost is a value`() {
        assertEquals(0.0, TripDetailStats.costPer100Km(0.0, 50.5)!!, 1e-9)
    }

    @Test fun `cost per 100 km needs a known cost and a positive distance`() {
        assertNull(TripDetailStats.costPer100Km(null, 50.5))
        assertNull(TripDetailStats.costPer100Km(1.62, null))
        assertNull(TripDetailStats.costPer100Km(1.62, 0.0))
    }

    // ---- labels ----

    @Test fun `soc change is signed`() {
        assertEquals("−16%", TripDetailStats.socChangeLabel(80, 64))
        assertEquals("+3%", TripDetailStats.socChangeLabel(50, 53))
        assertEquals("0%", TripDetailStats.socChangeLabel(70, 70))
    }

    @Test fun `outside temperature shows both ends or the one known`() {
        assertEquals("12° → 9°", TripDetailStats.exteriorTempLabel(12, 9))
        assertEquals("-3°", TripDetailStats.exteriorTempLabel(-3, null))
        assertEquals("9°", TripDetailStats.exteriorTempLabel(null, 9))
        assertNull(TripDetailStats.exteriorTempLabel(null, null))
    }
}
