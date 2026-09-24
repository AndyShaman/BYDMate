package com.bydmate.app.ui.trips

import com.bydmate.app.data.local.entity.TripPointEntity

/** Derived numbers of the trip detail card, kept pure for the unit tests. */
internal object TripDetailStats {

    /** A fix standing still reads 0 or GPS noise below this. */
    private const val MOVING_MIN_KMH = 1.0

    /** A longer silence between two fixes is a GPS gap, not driving. */
    private const val MAX_GAP_MS = 120_000L

    private const val MIN_POINTS = 4

    /**
     * Time spent moving: the sum of the intervals that start at a fix with speed of at least
     * 1 km/h, skipping gaps over 120 s. Null when there are too few points, no end, or the
     * result is not a positive part of the trip duration.
     */
    fun movingTimeMs(points: List<TripPointEntity>, startTs: Long, endTs: Long?): Long? {
        if (endTs == null || points.size < MIN_POINTS) return null
        var moving = 0L
        for (i in 0 until points.size - 1) {
            val dt = points[i + 1].timestamp - points[i].timestamp
            if (dt > MAX_GAP_MS) continue
            if ((points[i].speedKmh ?: 0.0) >= MOVING_MIN_KMH) moving += dt
        }
        return moving.takeIf { it > 0 && it <= endTs - startTs }
    }

    /** Cost per 100 km when the cost is known (0 included) and the distance is positive. */
    fun costPer100Km(cost: Double?, distanceKm: Double?): Double? {
        if (cost == null || distanceKm == null) return null
        return (cost / distanceKm * 100.0).takeIf { cost >= 0 && distanceKm > 0 }
    }

    /** SOC change over the trip as a signed label: "−16%" (U+2212), "+3%", "0%". */
    fun socChangeLabel(socStart: Int, socEnd: Int): String {
        val change = socEnd - socStart
        return when {
            change < 0 -> "−${-change}%"
            change > 0 -> "+$change%"
            else -> "0%"
        }
    }

    /** "12° → 9°" for both ends, one value when only one end is known, null for none. */
    fun exteriorTempLabel(start: Int?, end: Int?): String? = when {
        start != null && end != null -> "$start° → $end°"
        start != null -> "$start°"
        end != null -> "$end°"
        else -> null
    }
}
