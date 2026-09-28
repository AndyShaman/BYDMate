package com.bydmate.app.domain.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class RecentTrackTest {

    // Metres on a local plane around Minsk: enough for a few kilometres of track.
    private val lat0 = 53.9
    private val lon0 = 27.56
    private val mPerDegLat = 111_195.0
    private val mPerDegLon = mPerDegLat * cos(Math.toRadians(lat0))

    private fun at(northM: Double, eastM: Double, atMs: Long) =
        TrackPoint(atMs, lat0 + northM / mPerDegLat, lon0 + eastM / mPerDegLon, speedKmh = 50.0)

    /** A drive in legs of (north, east) metres, a fix every [stepM] and 3.6 s apart (50 km/h). */
    private fun drive(vararg legs: Pair<Double, Double>, stepM: Double = 50.0): MutableList<TrackPoint> {
        val track = mutableListOf(at(0.0, 0.0, 0L))
        var n = 0.0
        var e = 0.0
        var t = 0L
        for ((dn, de) in legs) {
            val steps = (Math.hypot(dn, de) / stepM).toInt()
            repeat(steps) {
                n += dn / steps
                e += de / steps
                t += 3_600L
                track += at(n, e, t)
            }
        }
        return track
    }

    private fun assertCourse(expected: Double, actual: Double?, tolerance: Double = 2.0) {
        assertNotNull(actual)
        val diff = TravelCourse.angleDiffDeg(expected, actual!!)
        assertTrue("course $actual, expected $expected", diff <= tolerance)
    }

    @Test fun straight_road_north() {
        assertCourse(0.0, TravelCourse.of(drive(2_000.0 to 0.0)))
    }

    @Test fun straight_road_east() {
        assertCourse(90.0, TravelCourse.of(drive(0.0 to 2_000.0)))
    }

    @Test fun straight_road_south_west() {
        assertCourse(225.0, TravelCourse.of(drive(-1_500.0 to -1_500.0)))
    }

    // The course is the chord of the last kilometre: right after a turn it lies between the old
    // and the new road, a kilometre later it follows the new one.
    @Test fun a_turn_shows_in_the_course_within_a_kilometre() {
        val justTurned = TravelCourse.of(drive(2_000.0 to 0.0, 0.0 to 400.0))!!
        assertTrue("course $justTurned", justTurned in 25.0..45.0)
        assertCourse(90.0, TravelCourse.of(drive(2_000.0 to 0.0, 0.0 to 1_200.0)))
    }

    // A red light: GPS noise around one spot for three minutes does not erase the direction.
    @Test fun a_stop_keeps_the_course_of_the_road_before_it() {
        val track = drive(2_000.0 to 0.0)
        val stopAt = track.last()
        repeat(20) { i ->
            val jitter = if (i % 2 == 0) 5.0 else -5.0
            track += at(2_000.0 + jitter, jitter, stopAt.atMs + (i + 1) * 9_000L)
        }
        assertCourse(0.0, TravelCourse.of(track), tolerance = 3.0)
    }

    @Test fun too_little_movement_is_no_course() {
        assertNull(TravelCourse.of(drive(250.0 to 0.0)))
    }

    @Test fun parked_with_gps_noise_is_no_course() {
        val track = (0 until 60).map { i ->
            val j = (i % 5) * 4.0
            at(j, -j, i * 10_000L)
        }
        assertNull(TravelCourse.of(track))
    }

    @Test fun no_fixes_or_one_fix_is_no_course() {
        assertNull(TravelCourse.of(emptyList()))
        assertNull(TravelCourse.of(listOf(at(0.0, 0.0, 0L))))
    }

    // --- the in-memory buffer the location listener feeds ---

    @Test fun buffer_keeps_only_the_last_ten_minutes() {
        val buffer = RecentTrack()
        buffer.add(at(0.0, 0.0, 0L))
        buffer.add(at(100.0, 0.0, 300_000L))
        buffer.add(at(200.0, 0.0, 600_000L))
        assertEquals(2, buffer.snapshot(nowMs = 660_000L).size)
        assertTrue(buffer.snapshot(nowMs = 1_300_000L).isEmpty())
    }

    // A car parked for more than ten minutes has no course, whatever it drove before.
    @Test fun long_stop_empties_the_window() {
        val buffer = RecentTrack()
        drive(2_000.0 to 0.0).forEach(buffer::add)
        assertNotNull(TravelCourse.of(buffer.snapshot(nowMs = 150_000L)))
        assertNull(TravelCourse.of(buffer.snapshot(nowMs = 150_000L + 11 * 60_000L)))
    }

    @Test fun buffer_is_capped() {
        val buffer = RecentTrack()
        repeat(2_000) { i -> buffer.add(at(i.toDouble(), 0.0, i * 100L)) }
        assertEquals(RecentTrack.MAX_POINTS, buffer.snapshot(nowMs = 200_000L).size)
    }
}
