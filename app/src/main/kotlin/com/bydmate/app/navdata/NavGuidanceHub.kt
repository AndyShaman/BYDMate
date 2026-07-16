package com.bydmate.app.navdata

import android.util.Log

/** Unified guidance snapshot: ONE source of truth for both the HUD push loop and the
 *  voice agent's get_route_info. Written by NavA11yFeed (a11y thread) and the
 *  notification listener (binder thread), read from coroutines; hence @Synchronized
 *  writers and a @Volatile snapshot.
 *
 *  Semantics (donor-derived):
 *  - field-wise merge: a partial update never wipes known values;
 *  - active expires 90 s after the last update of any source;
 *  - speed limit has its own 30 s freshness (a limit sign must not outlive its road);
 *  - a successful Navigator-window read WITHOUT guidance widgets is an explicit
 *    "route ended" signal: 10 s of that deactivates the snapshot (markNoGuidance). */
object NavGuidanceHub {
    private const val TAG = "NavGuidanceHub"
    const val ACTIVE_TIMEOUT_MS = 90_000L
    const val SPEED_LIMIT_TIMEOUT_MS = 30_000L
    const val NO_GUIDANCE_DEACTIVATE_MS = 10_000L

    enum class Source { A11Y, NOTIFICATION }

    data class Snapshot(
        val active: Boolean = false,
        val maneuverGaode: Int = 0,
        val maneuverGaodeMs: Long = 0L,
        val distanceMeters: Int = 0,
        val road: String = "",
        val etaSeconds: Int = 0,
        val totalDistMeters: Int = 0,
        val speedLimit: Int = 0,
        val speedLimitMs: Long = 0L,
        val lastUpdateMs: Long = 0L,
    )

    @Volatile private var current = Snapshot()
    @Volatile private var noGuidanceSinceMs = 0L
    @Volatile private var lastA11yMs = 0L

    // @Synchronized because expiry writes back: an unsynchronized write here could
    // clobber a concurrent update() with a stale copy.
    @Synchronized
    fun snapshot(nowMs: Long = System.currentTimeMillis()): Snapshot {
        var s = current
        if (s.active && nowMs - s.lastUpdateMs > ACTIVE_TIMEOUT_MS) {
            s = s.copy(active = false)
            current = s
            Log.i(TAG, "guidance inactive: no source updated for ${ACTIVE_TIMEOUT_MS / 1000}s")
        }
        // A started no-guidance streak expires by TIME, not by a second event: after a
        // route ends the Navigator may go silent (window closed, no more a11y events),
        // so the deadline must fire from the reader side (Codex audit fix 2).
        if (s.active && noGuidanceSinceMs != 0L && nowMs - noGuidanceSinceMs >= NO_GUIDANCE_DEACTIVATE_MS) {
            s = s.copy(active = false)
            current = s
            noGuidanceSinceMs = 0L
            Log.i(TAG, "guidance inactive: route ended (no-guidance deadline)")
        }
        if (s.speedLimit > 0 && nowMs - s.speedLimitMs > SPEED_LIMIT_TIMEOUT_MS) {
            s = s.copy(speedLimit = 0)
            current = s
        }
        return s
    }

    @Synchronized
    fun update(data: NavGuidance, source: Source, nowMs: Long = System.currentTimeMillis()) {
        noGuidanceSinceMs = 0L
        val prev = current
        if (!prev.active) Log.i(TAG, "guidance active (source=$source)")
        current = prev.copy(
            active = true,
            maneuverGaode = if (data.maneuverGaode > 0) data.maneuverGaode else prev.maneuverGaode,
            maneuverGaodeMs = if (data.maneuverGaode > 0) nowMs else prev.maneuverGaodeMs,
            distanceMeters = if (data.distanceMeters > 0) data.distanceMeters else prev.distanceMeters,
            road = data.road.ifEmpty { prev.road },
            etaSeconds = if (data.etaSeconds > 0) data.etaSeconds else prev.etaSeconds,
            totalDistMeters = if (data.totalDistMeters > 0) data.totalDistMeters else prev.totalDistMeters,
            speedLimit = if (data.speedLimit > 0) data.speedLimit else prev.speedLimit,
            speedLimitMs = if (data.speedLimit > 0) nowMs else prev.speedLimitMs,
            lastUpdateMs = nowMs,
        )
        if (source == Source.A11Y) lastA11yMs = nowMs
    }

    /** Notification channel entry point (older Navigator builds; the 2026 build posts a
     *  static stub, see NaviScreenReader). Ignores updates carrying nothing useful. */
    @Synchronized
    fun updateFromNotification(
        maneuverRes: String?, distanceText: String?, street: String?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val gaode = NavManeuverCodes.fromNotificationRes(maneuverRes)
        val meters = NavGuidanceParser.parseDistanceText(distanceText)
        if (gaode == 0 && meters == 0 && street.isNullOrBlank()) return
        update(NavGuidance(maneuverGaode = gaode, distanceMeters = meters, road = street ?: ""),
            Source.NOTIFICATION, nowMs)
    }

    @Synchronized
    fun markNoGuidance(nowMs: Long = System.currentTimeMillis()) {
        val s = current
        if (!s.active) { noGuidanceSinceMs = 0L; return }
        if (noGuidanceSinceMs == 0L) { noGuidanceSinceMs = nowMs; return }
        if (nowMs - noGuidanceSinceMs >= NO_GUIDANCE_DEACTIVATE_MS) {
            current = s.copy(active = false)
            noGuidanceSinceMs = 0L
            Log.i(TAG, "guidance inactive: route ended (no-guidance streak)")
        }
    }

    /** Legacy notification channel reported route end (the guidance notification was
     *  removed). A11Y is the authoritative source: if it updated recently the removal
     *  is ignored, otherwise deactivate immediately (Codex audit fix 3). */
    @Synchronized
    fun markNotificationEnded(nowMs: Long = System.currentTimeMillis()) {
        val s = current
        if (!s.active) return
        // Only guard against deactivation when the a11y source has actually updated:
        // if lastA11yMs is 0 the notification channel is the sole source and the
        // removal must be honoured immediately (Codex audit fix 3).
        if (lastA11yMs != 0L && nowMs - lastA11yMs <= NO_GUIDANCE_DEACTIVATE_MS) return
        current = s.copy(active = false)
        noGuidanceSinceMs = 0L
        Log.i(TAG, "guidance inactive: guidance notification removed")
    }

    @Synchronized
    fun reset() {
        current = Snapshot()
        noGuidanceSinceMs = 0L
        lastA11yMs = 0L
    }
}
