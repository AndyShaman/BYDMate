package com.bydmate.app.domain.tracker

import com.bydmate.app.data.automation.PlaceGeometry
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One GPS fix of the recent track. [atMs] is on the monotonic clock (elapsedRealtime). */
data class TrackPoint(val atMs: Long, val lat: Double, val lon: Double, val speedKmh: Double? = null)

/**
 * The GPS fixes of the last [WINDOW_MS], fed by the location listener, for the voice agent's
 * direction of travel. In memory only: TripTracker's points go to the database in batches and
 * carry no monotonic time, so reading the last kilometre from there is neither cheap nor exact.
 */
class RecentTrack {

    private val points = ArrayDeque<TrackPoint>()

    @Synchronized
    fun add(point: TrackPoint) {
        points.addLast(point)
        trim(point.atMs)
    }

    /** The fixes of the last [WINDOW_MS] before [nowMs], oldest first. */
    @Synchronized
    fun snapshot(nowMs: Long): List<TrackPoint> {
        trim(nowMs)
        return points.toList()
    }

    private fun trim(nowMs: Long) {
        while (points.isNotEmpty() && (points.first().atMs < nowMs - WINDOW_MS || points.size > MAX_POINTS)) {
            points.removeFirst()
        }
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L

        /** A fix every 2 s for ten minutes is 300; the cap only guards against a flood. */
        const val MAX_POINTS = 600
    }
}

/** Direction of travel from a recent track: not the instantaneous GPS bearing, which is noise at
 *  low speed and wrong at a stop, but the chord of the last kilometre driven. */
object TravelCourse {

    /** Path length the course is measured over. */
    const val BASE_M = 1_000.0

    /** Below this displacement within the track window there is no direction to speak of. */
    const val MIN_MOVE_M = 300.0

    /** Degrees from north, from the fix about [BASE_M] of driven path back (the oldest fix when
     *  the track is shorter) to the newest one; null when those two lie less than [MIN_MOVE_M]
     *  apart: parked, crawling, or a loop. */
    fun of(track: List<TrackPoint>): Double? {
        if (track.size < 2) return null
        val last = track.last()
        var anchor = track.first()
        var pathM = 0.0
        for (i in track.size - 1 downTo 1) {
            pathM += PlaceGeometry.distanceMeters(track[i - 1].lat, track[i - 1].lon, track[i].lat, track[i].lon)
            if (pathM >= BASE_M) {
                anchor = track[i - 1]
                break
            }
        }
        if (PlaceGeometry.distanceMeters(anchor.lat, anchor.lon, last.lat, last.lon) < MIN_MOVE_M) return null
        return bearingDeg(anchor.lat, anchor.lon, last.lat, last.lon)
    }

    /** Initial great-circle bearing from the first point to the second, 0..360 from north. */
    fun bearingDeg(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val phi1 = Math.toRadians(fromLat)
        val phi2 = Math.toRadians(toLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + FULL_TURN) % FULL_TURN
    }

    /** The smaller angle between two bearings, 0..180. */
    fun angleDiffDeg(a: Double, b: Double): Double {
        val d = abs(a - b) % FULL_TURN
        return if (d > HALF_TURN) FULL_TURN - d else d
    }

    private const val FULL_TURN = 360.0
    private const val HALF_TURN = 180.0
}
