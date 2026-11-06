package com.bydmate.app.ui.trips

import com.bydmate.app.data.local.entity.TripPointEntity

/** Numbers of the trip window under the scrub cursor, kept pure for the unit tests. */
@Suppress("TooManyFunctions") // one small pure function per number of the trip window
internal object TripScrub {

    /** One speed scale for every trip: from this speed on the hottest color. */
    const val SPEED_SCALE_MAX_KMH = 140.0
    /** Half window of «consumption here» under the cursor. */
    const val CURSOR_WINDOW_M = 400.0
    const val CURSOR_MIN_KMH = 15.0
    /** Half window of the consumption coloring of the map track. */
    const val TRACK_WINDOW_M = 150.0
    const val TRACK_MIN_KMH = 5.0
    /** A shorter stretch gives a meaningless kWh/100 km. */
    const val MIN_WINDOW_KM = 0.1
    /** A longer silence between two points is a recording gap. */
    const val MAX_GAP_MS = 120_000L
    const val NO_DATA_COLOR: Int = 0xFF475569.toInt()

    private const val MOVING_MIN_KMH = 1.0
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val FALLBACK_NORTH_PAD = 0.3

    /**
     * How much to stretch the route box north (in route heights) so that a fit with [borderPx]
     * leaves the route the part of the map under the values panel ([topInsetPx] from the top).
     * A map too small for the panel falls back to the old fixed 30 %.
     */
    fun northPadFraction(mapHeightPx: Int, borderPx: Int, topInsetPx: Int): Double {
        if (topInsetPx <= 0) return 0.0
        val free = mapHeightPx - 2 * borderPx - topInsetPx
        return if (free > 0) topInsetPx.toDouble() / free else FALLBACK_NORTH_PAD
    }

    /** Speed scale stops, km/h → ARGB. */
    val SPEED_STOPS: List<Pair<Double, Int>> = listOf(
        0.0 to 0xFF6366F1.toInt(),
        30.0 to 0xFF22D3EE.toInt(),
        60.0 to 0xFF4ADE80.toInt(),
        90.0 to 0xFFFDE047.toInt(),
        120.0 to 0xFFFB923C.toInt(),
        SPEED_SCALE_MAX_KMH to 0xFFF43F5E.toInt(),
    )

    /** Consumption scale stops, kWh/100 km → ARGB; below zero is regen. */
    val CONSUMPTION_STOPS: List<Pair<Double, Int>> = listOf(
        -20.0 to 0xFF3B82F6.toInt(),
        -3.0 to 0xFF93C5FD.toInt(),
        0.0 to 0xFFCBD5E1.toInt(),
        18.0 to 0xFFCBD5E1.toInt(),
        27.0 to 0xFFFBBF24.toInt(),
        40.0 to 0xFFF97316.toInt(),
        60.0 to 0xFFEF4444.toInt(),
    )

    /** Distance from the first point to every point along the track, metres. */
    fun cumulativeDistanceM(points: List<TripPointEntity>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            out[i] = out[i - 1] + haversineM(points[i - 1], points[i])
        }
        return out
    }

    /** True when the trip carries the battery counter, so energy along the track is known. */
    fun hasPower(points: List<TripPointEntity>): Boolean =
        points.count { it.batteryEnergyWh != null } >= 2

    /** The point nearest in time to [fraction] of the trip duration; a tie goes to the lower index. */
    fun indexAtFraction(points: List<TripPointEntity>, fraction: Float): Int {
        if (points.isEmpty()) return 0
        val t0 = points.first().timestamp
        val target = t0 + (points.last().timestamp - t0) * fraction.coerceIn(0f, 1f).toDouble()
        // First index with timestamp >= target.
        var lo = 0
        var hi = points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (points[mid].timestamp < target) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && target - points[lo - 1].timestamp <= points[lo].timestamp - target) return lo - 1
        return lo
    }

    /** Time share of the point within the trip, 0..1; 0 when the trip has no duration. */
    fun fractionOfIndex(points: List<TripPointEntity>, index: Int): Float {
        if (points.isEmpty()) return 0f
        val t0 = points.first().timestamp
        val span = points.last().timestamp - t0
        if (span <= 0) return 0f
        return ((points[index.coerceIn(0, points.lastIndex)].timestamp - t0).toDouble() / span).toFloat()
    }

    /**
     * Net battery energy between two points, kWh, from the cumulative counter. Null when a
     * point has no counter, the counter changes run, or an interval is a recording gap.
     */
    fun energyKwh(points: List<TripPointEntity>, from: Int, to: Int): Double? {
        if (from == to) return 0.0
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        for (j in lo..hi) {
            val p = points[j]
            if (p.batteryEnergyWh == null || p.energyRun == null) return null
            if (j > lo) {
                val prev = points[j - 1]
                if (prev.energyRun != p.energyRun) return null
                if (p.timestamp - prev.timestamp > MAX_GAP_MS) return null
            }
        }
        val e = points[hi].batteryEnergyWh!! - points[lo].batteryEnergyWh!!
        return (if (from <= to) e else -e) / 1000.0
    }

    /**
     * kWh/100 km around [index]: the window reaches the nearest points at least [halfWindowM]
     * away on both sides (or the trip ends). Null when slow, unknown speed, too short or no energy.
     */
    fun localConsumption(
        points: List<TripPointEntity>,
        dist: DoubleArray,
        index: Int,
        halfWindowM: Double,
        minSpeedKmh: Double,
    ): Double? {
        val speed = points[index].speedKmh ?: return null
        if (speed < minSpeedKmh) return null
        var a = index
        while (a > 0 && dist[index] - dist[a] < halfWindowM) a--
        var b = index
        while (b < points.lastIndex && dist[b] - dist[index] < halfWindowM) b++
        val km = (dist[b] - dist[a]) / 1000.0
        if (km < MIN_WINDOW_KM) return null
        val energy = energyKwh(points, a, b) ?: return null
        return energy / km * 100.0
    }

    /** Stats of a selected stretch; a recording gap inside makes speed and energy unknown. */
    fun rangeStats(points: List<TripPointEntity>, dist: DoubleArray, from: Int, to: Int): RangeStats {
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        val distanceKm = (dist[hi] - dist[lo]) / 1000.0
        var gap = false
        var movingMs = 0L
        for (j in lo until hi) {
            val dt = points[j + 1].timestamp - points[j].timestamp
            if (dt > MAX_GAP_MS) {
                gap = true
                continue
            }
            if ((points[j].speedKmh ?: 0.0) >= MOVING_MIN_KMH) movingMs += dt
        }
        val avg = if (!gap && movingMs > 0) distanceKm / (movingMs / 3_600_000.0) else null
        val energy = if (gap) null else energyKwh(points, lo, hi)
        val per100 = if (energy != null && distanceKm >= MIN_WINDOW_KM) energy / distanceKm * 100.0 else null
        val total = dist.lastOrNull() ?: 0.0
        return RangeStats(
            distanceKm = distanceKm,
            durationMs = points[hi].timestamp - points[lo].timestamp,
            avgSpeedKmh = avg,
            energyKwh = energy,
            kwhPer100Km = per100,
            shareOfTrip = if (total > 0) (dist[hi] - dist[lo]) / total else 0.0,
        )
    }

    /** Mean battery power of the point and its known neighbours within [radius], kW. */
    fun smoothedPowerKw(points: List<TripPointEntity>, index: Int, radius: Int = 2): Double? {
        if (points[index].batteryPowerW == null) return null
        var sum = 0.0
        var n = 0
        for (j in maxOf(0, index - radius)..minOf(points.lastIndex, index + radius)) {
            val w = points[j].batteryPowerW ?: continue
            sum += w
            n++
        }
        return sum / n / 1000.0
    }

    /** First point with the highest known speed. */
    fun maxSpeedIndex(points: List<TripPointEntity>): Int? {
        var best: Int? = null
        points.forEachIndexed { i, p ->
            val v = p.speedKmh ?: return@forEachIndexed
            if (best == null || v > points[best!!].speedKmh!!) best = i
        }
        return best
    }

    /** Point with the strongest regen (most negative power); null without any regen. */
    fun maxRegenIndex(points: List<TripPointEntity>): Int? {
        var best: Int? = null
        var bestW = 0.0
        points.forEachIndexed { i, p ->
            val w = p.batteryPowerW ?: return@forEachIndexed
            if (w < bestW) {
                bestW = w
                best = i
            }
        }
        return best
    }

    fun speedColor(kmh: Double): Int = ramp(SPEED_STOPS, kmh)

    fun consumptionColor(kwhPer100: Double?): Int =
        if (kwhPer100 == null) NO_DATA_COLOR else ramp(CONSUMPTION_STOPS, kwhPer100)

    /** Piecewise linear per channel between the stops, clamped to the end colors. */
    private fun ramp(stops: List<Pair<Double, Int>>, value: Double): Int {
        if (value <= stops.first().first) return stops.first().second
        for (k in 1 until stops.size) {
            val (b, cb) = stops[k]
            if (value <= b) {
                val (a, ca) = stops[k - 1]
                val f = (value - a) / (b - a)
                fun ch(shift: Int): Int {
                    val x = (ca shr shift) and 0xFF
                    val y = (cb shr shift) and 0xFF
                    return Math.round(x + (y - x) * f).toInt()
                }
                return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return stops.last().second
    }

    private fun haversineM(a: TripPointEntity, b: TripPointEntity): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h.coerceAtMost(1.0)))
    }
}

/** Stats of a stretch of the trip selected on the chart. */
internal data class RangeStats(
    val distanceKm: Double,
    val durationMs: Long,
    val avgSpeedKmh: Double?,
    val energyKwh: Double?,
    val kwhPer100Km: Double?,
    /** 0..1, stretch distance / whole-trip distance. */
    val shareOfTrip: Double,
)
