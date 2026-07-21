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
    const val A11Y_PRIORITY_MS = 10_000L

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
        // Camera / PNG fields — appended with defaults so existing positional
        // constructors remain valid (fleet-safety rule).
        val maneuverPng: ByteArray? = null,
        val cameraAlert: String = "",
        val cameraDistanceMeters: Int = 0,
        val cameraIconPng: ByteArray? = null,
    )

    /** Rich notification payload (donor listener merge). applyCamera=false is the
     *  extras fallback - extras carry no camera info, previous camera state persists. */
    data class RichUpdate(
        val maneuverGaode: Int = 0,
        val distanceMeters: Int = 0,
        val road: String = "",
        val etaSeconds: Int = 0,
        val totalDistMeters: Int = 0,
        val maneuverPng: ByteArray? = null,
        val cameraAlert: String = "",
        val cameraDistanceMeters: Int = 0,
        val cameraIconPng: ByteArray? = null,
        val applyCamera: Boolean = true,
    )

    @Volatile private var current = Snapshot()
    @Volatile private var noGuidanceSinceMs = 0L
    @Volatile private var lastA11yMs = 0L

    /** Returns a deactivated copy with PNG/camera fields cleared. Used in all places
     *  where active is set to false so HUD/agent never see a stale camera overlay. */
    private fun deactivated(s: Snapshot): Snapshot = s.copy(
        active = false,
        maneuverPng = null,
        cameraAlert = "",
        cameraDistanceMeters = 0,
        cameraIconPng = null,
    )

    // @Synchronized because expiry writes back: an unsynchronized write here could
    // clobber a concurrent update() with a stale copy.
    @Synchronized
    fun snapshot(nowMs: Long = System.currentTimeMillis()): Snapshot {
        var s = current
        if (s.active && nowMs - s.lastUpdateMs > ACTIVE_TIMEOUT_MS) {
            s = deactivated(s)
            current = s
            Log.i(TAG, "guidance inactive: no source updated for ${ACTIVE_TIMEOUT_MS / 1000}s")
        }
        // A started no-guidance streak expires by TIME, not by a second event: after a
        // route ends the Navigator may go silent (window closed, no more a11y events),
        // so the deadline must fire from the reader side (Codex audit fix 2).
        if (s.active && noGuidanceSinceMs != 0L && nowMs - noGuidanceSinceMs >= NO_GUIDANCE_DEACTIVATE_MS) {
            s = deactivated(s)
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

    /**
     * Rich notification entry (spec §6). While an a11y update is fresher than
     * A11Y_PRIORITY_MS the guidance fields are ignored (a11y wins the field race),
     * but side effects ALWAYS apply: active=true, lastUpdateMs, no-guidance streak
     * reset, camera merge. Camera two-stage rule mirrors the donor listener:
     * alert replaces; empty alert clears distance/icon; non-empty keeps prev gaps.
     */
    @Synchronized
    fun updateFromNotification(rich: RichUpdate, nowMs: Long = System.currentTimeMillis()) {
        noGuidanceSinceMs = 0L
        val prev = current
        if (!prev.active) Log.i(TAG, "guidance active (source=NOTIFICATION)")
        val a11yFresh = lastA11yMs != 0L && nowMs - lastA11yMs <= A11Y_PRIORITY_MS
        val base = if (a11yFresh) prev else prev.copy(
            maneuverGaode = if (rich.maneuverGaode > 0) rich.maneuverGaode else prev.maneuverGaode,
            maneuverGaodeMs = if (rich.maneuverGaode > 0) nowMs else prev.maneuverGaodeMs,
            distanceMeters = if (rich.distanceMeters > 0) rich.distanceMeters else prev.distanceMeters,
            road = rich.road.ifEmpty { prev.road },
            etaSeconds = if (rich.etaSeconds > 0) rich.etaSeconds else prev.etaSeconds,
            totalDistMeters = if (rich.totalDistMeters > 0) rich.totalDistMeters else prev.totalDistMeters,
            maneuverPng = rich.maneuverPng ?: prev.maneuverPng,
        )
        current = base.copy(
            active = true,
            lastUpdateMs = nowMs,
            cameraAlert = if (rich.applyCamera) rich.cameraAlert else prev.cameraAlert,
            cameraDistanceMeters = when {
                !rich.applyCamera -> prev.cameraDistanceMeters
                rich.cameraAlert.isEmpty() -> 0
                else -> rich.cameraDistanceMeters.takeIf { it > 0 } ?: prev.cameraDistanceMeters
            },
            cameraIconPng = when {
                !rich.applyCamera -> prev.cameraIconPng
                rich.cameraAlert.isEmpty() -> null
                else -> rich.cameraIconPng ?: prev.cameraIconPng
            },
        )
    }

    /** Donor removal grace: called by the notification lane's deactivate check when
     *  the guidance notification is gone and nothing refreshed the hub for
     *  ACTIVE_TIMEOUT_MS. Fresh state = no-op. */
    @Synchronized
    fun deactivateFromNotificationGrace(nowMs: Long = System.currentTimeMillis()) {
        val s = current
        if (!s.active) return
        if (nowMs - s.lastUpdateMs < ACTIVE_TIMEOUT_MS) return
        current = deactivated(s)
        noGuidanceSinceMs = 0L
        Log.i(TAG, "guidance inactive: notification removed, grace expired")
    }

    @Synchronized
    fun markNoGuidance(nowMs: Long = System.currentTimeMillis()) {
        val s = current
        if (!s.active) { noGuidanceSinceMs = 0L; return }
        if (noGuidanceSinceMs == 0L) { noGuidanceSinceMs = nowMs; return }
        if (nowMs - noGuidanceSinceMs >= NO_GUIDANCE_DEACTIVATE_MS) {
            current = deactivated(s)
            noGuidanceSinceMs = 0L
            Log.i(TAG, "guidance inactive: route ended (no-guidance streak)")
        }
    }

    @Synchronized
    fun reset() {
        current = Snapshot()
        noGuidanceSinceMs = 0L
        lastA11yMs = 0L
    }
}
