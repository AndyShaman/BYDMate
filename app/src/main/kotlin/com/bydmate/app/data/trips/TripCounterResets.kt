package com.bydmate.app.data.trips

import android.util.Log
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.service.TrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Single owner of the TRIP 1 / TRIP 2 reset anchors, shared by the dashboard (manual
 *  long-press reset) and the tracking service (auto-reset after charging), so a reset
 *  from either side reaches the counters on screen. */
@Singleton
class TripCounterResets @Inject constructor(
    private val settings: SettingsRepository,
) {
    private val trip1 = MutableStateFlow<TripResetState?>(null)
    private val trip2 = MutableStateFlow<TripResetState?>(null)

    // A manual and an automatic reset of the same counter must not interleave between
    // the settings write and the flow update.
    private val mutex = Mutex()

    private fun flow(n: Int): MutableStateFlow<TripResetState?> {
        require(n in 1..2) { "trip counter must be 1 or 2, was $n" }
        return if (n == 1) trip1 else trip2
    }

    fun state(n: Int): StateFlow<TripResetState?> = flow(n).asStateFlow()

    /** Seeds the anchors from settings; a counter already loaded or reset is left alone. */
    suspend fun load() = mutex.withLock {
        for (n in 1..2) {
            if (flow(n).value == null) {
                val stored = settings.getTripResetState(n)
                flow(n).compareAndSet(null, stored)
            }
        }
    }

    /** Instant reset, no confirmation (approved design). Captures the live
     *  session's current progress as the correction so the counter restarts from ~zero
     *  immediately even mid-drive. When coverage is degraded the live partials are not a
     *  valid pre-reset measurement: store zero corrections and mark the straddling row for
     *  whole-row exclusion instead (counting restarts from the next trip). */
    suspend fun reset(n: Int, now: Long = System.currentTimeMillis()) = mutex.withLock {
        val continuous = TrackingService.liveWholeSession.value &&
            TrackingService.sessionStartedAt.value != null
        val state = TripResetState(
            resetTs = now,
            corrKm = if (continuous) TrackingService.tripDistanceKm.value ?: 0.0 else 0.0,
            corrKwh = if (continuous) TrackingService.tripKwhConsumed.value ?: 0.0 else 0.0,
            corrMs = TrackingService.sessionStartedAt.value?.let { now - it } ?: 0L,
            excludeStraddling = !continuous,
        )
        settings.setTripResetState(n, state)
        flow(n).value = state
    }

    /** Reset that keeps the whole current ignition session. A charge recorded at service
     *  start or by the tick retry happened while the car was asleep or parked, and the gun
     *  blocks driving, so everything in the current session, including a Room row that
     *  started before the anchor, is post-charge and must count whole: zero corrections,
     *  no straddling exclusion. */
    suspend fun resetWholeSession(n: Int, now: Long = System.currentTimeMillis()) = mutex.withLock {
        val state = TripResetState(
            resetTs = now,
            corrKm = 0.0,
            corrKwh = 0.0,
            corrMs = 0L,
            excludeStraddling = false,
        )
        settings.setTripResetState(n, state)
        flow(n).value = state
    }

    /** Applies each counter's auto-reset mode to a just-recorded [charge] (#235).
     *  [wholeSession] = the charge was found by catch-up (service start or tick retry),
     *  so the current session is entirely post-charge; false = live gun-disconnect edge,
     *  where the session may have started before the charge (manual-reset semantics). */
    suspend fun applyAfterCharge(charge: ChargeEntity, wholeSession: Boolean) {
        val now = System.currentTimeMillis()
        for (n in 1..2) {
            val mode = settings.getTripAutoResetMode(n)
            val decision = mode.shouldReset(charge)
            Log.i(TAG, "TripAutoReset: trip$n mode=${mode.key} charge#${charge.id} type=${charge.type} " +
                "soc=${charge.socStart}->${charge.socEnd} origin=${if (wholeSession) "catchup" else "gun_edge"} " +
                "-> ${if (decision) "reset" else "skip"}")
            if (decision) {
                if (wholeSession) resetWholeSession(n, now) else reset(n, now)
            }
        }
    }

    private companion object {
        const val TAG = "TripCounterResets"
    }
}
