package com.bydmate.app.data.trips

import com.bydmate.app.data.local.dao.TripCounterStats

/** One resettable trip counter's persisted anchor: the reset moment plus the live
 *  session's progress captured at that moment, so a mid-drive reset starts from ~zero
 *  immediately instead of waiting for the trip to land in Room. */
data class TripResetState(
    val resetTs: Long,
    val corrKm: Double,
    val corrKwh: Double,
    val corrMs: Long,
    /** Captured when a reset happens while live coverage is degraded (liveWholeSession=false
     *  or no session); the straddling row's km/kWh are then subtracted whole because the
     *  pre-reset partial is unmeasurable; counting effectively restarts from the next trip. */
    val excludeStraddling: Boolean = false,
)

/** Aggregated values behind one TRIP button and its popup. */
data class TripCounterUi(
    val km: Double,
    val kwh: Double,
    val drivingKwh: Double,
    val idleKwh: Double,
    val cost: Double,
    val tripCount: Int,
    val drivingMs: Long,
    val resetTs: Long,
)

object TripCounterMath {

    /** Room stats cover trips whose END lands after the reset, so the trip a reset
     *  happened inside is included whole. The pre-reset correction is subtracted via
     *  two separate paths:
     *
     *  Live path (odometer-based): live km/kWh are counted only when [liveWholeSession]
     *  is true (continuous coverage). A process restart where the session baselines could
     *  not be recovered sets this flag to false; the counter then shows Room-only values
     *  until the next landed row, avoiding any unknown-window inflation. Time (corrMs)
     *  is always subtracted when the session straddles the reset -- the clock is
     *  continuous across restarts, unlike odometer deltas.
     *
     *  Live = not-yet-landed remainder: after the straddling correction, the
     *  landed-session buckets (rows already written to Room for the current session)
     *  are subtracted from the live value, clamped >= 0. This prevents double-counting
     *  when TripRecorder closes a trip at DRIVE->ACC while the live session is still active.
     *
     *  Room path (landed trips): the correction is subtracted only up to the amount
     *  the straddling trip actually contributed (straddlingKm/Kwh/Ms caps). If the
     *  straddling trip never lands (dead energydata, no rows), no Room subtraction
     *  is made -- the correction cannot bite into later trips. Live kWh includes the
     *  running session's standing drain (BMS lifetime counter), so it is attributed
     *  to the driving bucket. */
    fun compute(
        stats: TripCounterStats,
        reset: TripResetState,
        liveKm: Double?,
        liveKwh: Double?,
        sessionStartedAt: Long?,
        liveWholeSession: Boolean,
        nowMs: Long,
        tariff: Double,
    ): TripCounterUi {
        val straddling = sessionStartedAt != null && sessionStartedAt < reset.resetTs
        // Live km/kWh are counted only when the baseline covers the whole session.
        // When false (restored session without baseline), use Room-only values.
        val continuous = liveWholeSession && sessionStartedAt != null
        // live = not-yet-landed remainder: (straddling-corrected live) - landedSession, clamped >= 0.
        fun liveContrib(value: Double?, corr: Double, landed: Double): Double =
            if (!continuous) 0.0
            else value?.let {
                val corrected = if (straddling) it - corr else it
                (corrected - landed).coerceAtLeast(0.0)
            } ?: 0.0
        val liveMs = sessionStartedAt?.let { nowMs - it } ?: 0L
        val liveMsContrib = when {
            sessionStartedAt == null -> 0L
            straddling -> (liveMs - reset.corrMs - stats.landedSessionMs).coerceAtLeast(0L)
            else -> (liveMs - stats.landedSessionMs).coerceAtLeast(0L)
        }
        val kmLive = liveContrib(liveKm, reset.corrKm, stats.landedSessionKm)
        val kwhLive = liveContrib(liveKwh, reset.corrKwh, stats.landedSessionKwh)
        // Subtract the pre-reset correction from Room only once the straddling row actually
        // landed. When excludeStraddling is set (degraded reset: live partial was unknown),
        // subtract the whole straddling row rather than the captured correction so the row
        // is eliminated in full and does not inflate the counter. Cap at the actual row size
        // in the normal path so a lost straddling row cannot bite into later trips.
        val kmSub = if (reset.excludeStraddling) stats.straddlingKm
                    else minOf(reset.corrKm, stats.straddlingKm)
        val kwhSub = if (reset.excludeStraddling) stats.straddlingKwh
                     else minOf(reset.corrKwh, stats.straddlingKwh)
        val msSub = minOf(reset.corrMs, stats.straddlingMs)
        return TripCounterUi(
            km = (stats.totalKm - kmSub).coerceAtLeast(0.0) + kmLive,
            kwh = (stats.totalKwh - kwhSub).coerceAtLeast(0.0) + kwhLive,
            drivingKwh = (stats.drivingKwh - kwhSub).coerceAtLeast(0.0) + kwhLive,
            idleKwh = stats.idleKwh,
            cost = (stats.totalCost - kwhSub * tariff).coerceAtLeast(0.0) + kwhLive * tariff,
            tripCount = if (reset.excludeStraddling)
                (stats.tripCount - stats.straddlingTripCount).coerceAtLeast(0)
            else stats.tripCount,
            drivingMs = (stats.drivingMs - msSub).coerceAtLeast(0L) + liveMsContrib,
            resetTs = reset.resetTs,
        )
    }
}
