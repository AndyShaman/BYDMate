package com.bydmate.app.domain.battery

import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.TripEntity

/**
 * Time-weighted average SOC from already-recorded trip/charge events (issue #93).
 *
 * The head unit sleeps whenever the car is parked, so live SOC sampling is
 * impossible; between recorded events the SOC barely moves. A step function held
 * at the last known SOC between event points is therefore an honest
 * approximation: parked time (the dominant share) is exact by construction.
 */
object AvgSocCalculator {

    data class SocPoint(val ts: Long, val soc: Int)

    /** Flatten trips and charges into a time-ordered list of known SOC points. */
    fun buildPoints(trips: List<TripEntity>, charges: List<ChargeEntity>): List<SocPoint> {
        val out = ArrayList<SocPoint>()
        for (t in trips) {
            t.socStart?.let { out.add(SocPoint(t.startTs, it)) }
            if (t.endTs != null && t.socEnd != null) out.add(SocPoint(t.endTs, t.socEnd))
        }
        for (c in charges) {
            c.socStart?.let { out.add(SocPoint(c.startTs, it)) }
            if (c.endTs != null && c.socEnd != null) out.add(SocPoint(c.endTs, c.socEnd))
        }
        return out.filter { it.soc in 0..100 }.sortedBy { it.ts }
    }

    /**
     * Step-hold time-weighted average over [fromTs, nowTs]. The SOC at fromTs is the
     * last point at or before it; the last point extends to nowTs. Null when nothing
     * is known inside the window.
     *
     * `points` must be sorted ascending by ts, as produced by [buildPoints].
     */
    fun averageSince(points: List<SocPoint>, fromTs: Long, nowTs: Long): Int? {
        if (nowTs <= fromTs) return null
        val relevant = points.filter { it.ts <= nowTs }
        if (relevant.isEmpty()) return null
        var weighted = 0.0
        var covered = 0L
        for (i in relevant.indices) {
            val segStart = maxOf(relevant[i].ts, fromTs)
            val segEnd = minOf(if (i + 1 < relevant.size) relevant[i + 1].ts else nowTs, nowTs)
            if (segEnd <= segStart) continue
            weighted += relevant[i].soc.toDouble() * (segEnd - segStart)
            covered += segEnd - segStart
        }
        if (covered <= 0L) return null
        return Math.round(weighted / covered).toInt()
    }
}
