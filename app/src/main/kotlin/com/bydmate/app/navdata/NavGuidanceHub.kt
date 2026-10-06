package com.bydmate.app.navdata

import android.util.Log
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea

/** Unified guidance snapshot: ONE source of truth for both the HUD push loop and the
 *  voice agent's get_route_info. Written by NavA11yFeed (a11y thread) and the
 *  notification listener (binder thread), read from coroutines; hence @Synchronized
 *  writers and a @Volatile snapshot.
 *
 *  Semantics (donor-derived):
 *  - field-wise merge: a partial update never wipes known values;
 *  - active expires 90 s after the last update of any source;
 *  - speed limit has its own 30 s freshness (a limit sign must not outlive its road);
 *  - the maneuver has its own 30 s freshness (a passed turn must not outlive its balloon);
 *  - a read naming another next street without a maneuver of its own drops the held one at
 *    once (#294: a stale arrow outlived its street); a read without a street keeps it (#198);
 *  - a Navigator-window read WITHOUT guidance widgets ends nothing, as in the donor: on some
 *    cars the window loses its widgets mid-route (issue #199), so only silence ends a route;
 *  - a read whose widgets are there but carry no text keeps the route alive (keepAlive).
 *
 *  A route's start and end are one HUD trace line each, the end followed by one route-summary
 *  line of [NavRouteStats] counters: reads and frames are counted, never traced one by one. */
@Suppress("TooManyFunctions") // the sources' writers, the expiries and the route's counters, kept in one place
object NavGuidanceHub {
    private const val TAG = "NavGuidanceHub"
    const val ACTIVE_TIMEOUT_MS = 90_000L
    const val SPEED_LIMIT_TIMEOUT_MS = 30_000L
    /** Maneuver freshness. The donor holds an a11y maneuver 10 s (20 s while a distance
     *  is known) and then falls back to its SEPARATE notification-enum maneuver; here both
     *  lanes write one merged field, and the notification lane refreshes maneuverGaodeMs
     *  only when it parsed a maneuver AND a11y is not fresher - so the donor's window
     *  would drop the arrow during an a11y blind spell. 30 s, same as the speed limit. */
    const val MANEUVER_TIMEOUT_MS = 30_000L
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
        // Where the held maneuver was read (#294): Source name and the raw input behind the code.
        val maneuverSource: String = "",
        val maneuverRaw: String = "",
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
        val maneuverRaw: String = "",
    )

    @Volatile private var current = Snapshot()
    @Volatile private var lastA11yMs = 0L
    private var lastNotifMs = 0L
    private val stats = NavRouteStats()
    private var a11yBlindNow = false

    /** The HUD way (1..3) the output runs, 0 while it is off: for the route-summary line only. */
    @Volatile var hudWay: Int = 0

    /** Trace id of the last guidance-off line: the cause a disarm that follows it links to. */
    @Volatile var lastOffTraceId: Long = 0L
        private set

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
            val ended = s
            s = deactivated(s)
            current = s
            Log.i(TAG, "guidance inactive: no source updated for ${ACTIVE_TIMEOUT_MS / 1000}s")
            traceOff(OFF_SILENCE, ended, nowMs)
        }
        if (s.speedLimit > 0 && nowMs - s.speedLimitMs > SPEED_LIMIT_TIMEOUT_MS) {
            s = s.copy(speedLimit = 0)
            current = s
        }
        // A maneuver is never overwritten by a "no maneuver" read (the field-wise merge
        // keeps prev), so without an age limit a passed turn stays on the glass for the
        // rest of the route. Icon goes with it: donor sends no f8 without a maneuver.
        // Distance is NOT reset - the donor keeps counting it down in that state too.
        if (s.maneuverGaode > 0 && nowMs - s.maneuverGaodeMs > MANEUVER_TIMEOUT_MS) {
            if (s.active) {
                stats.expiries++
                Trace.event(TraceArea.HUD, "maneuver-expired", "after_s" to ((nowMs - s.maneuverGaodeMs) / 1000).toInt(),
                    "src" to s.maneuverSource.ifEmpty { "na" })
            }
            s = s.copy(maneuverGaode = 0, maneuverPng = null, maneuverSource = "", maneuverRaw = "")
            current = s
            Log.i(TAG, "maneuver expired: no maneuver read for ${MANEUVER_TIMEOUT_MS / 1000}s")
        }
        return s
    }

    /** The stored active flag as it is, without applying any expiry: for field diagnostics that
     *  must not write hub state the way [snapshot] does. */
    fun isActiveNow(): Boolean = current.active

    @Synchronized
    fun update(data: NavGuidance, source: Source, nowMs: Long = System.currentTimeMillis()) {
        val prev = dropOnStreetChange(current, data.maneuverGaode, if (data.roadIsNextStreet) data.road else "", source)
        if (!prev.active) {
            Log.i(TAG, "guidance active (source=$source)")
            traceOn(source, nowMs)
        } else {
            stats.refreshed(nowMs - prev.lastUpdateMs)
        }
        if (source == Source.A11Y) stats.a11yGuidance++
        countRead(prev, data.maneuverGaode, data.distanceMeters)
        current = prev.copy(
            active = true,
            maneuverGaode = if (data.maneuverGaode > 0) data.maneuverGaode else prev.maneuverGaode,
            maneuverGaodeMs = if (data.maneuverGaode > 0) nowMs else prev.maneuverGaodeMs,
            maneuverSource = if (data.maneuverGaode > 0) source.label() else prev.maneuverSource,
            maneuverRaw = if (data.maneuverGaode > 0) data.maneuverRaw else prev.maneuverRaw,
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
     * but side effects ALWAYS apply: active=true, lastUpdateMs, camera merge. Camera two-stage rule mirrors the donor listener:
     * alert replaces; empty alert clears distance/icon; non-empty keeps prev gaps.
     */
    @Synchronized
    fun updateFromNotification(rich: RichUpdate, nowMs: Long = System.currentTimeMillis()) {
        // #170: the first player notification after Alice interrupts guidance often has
        // no navigation payload at all (extras fallback returns an all-empty RichUpdate).
        // Activating the hub on that carries no maneuver/distance, so displayDistance()
        // clamps 0 up to its 11 m floor and the HUD flashes a bogus "11m". A truly empty
        // update must be a no-op: leave active/lastUpdateMs untouched.
        if (rich.maneuverGaode == 0 && rich.distanceMeters == 0 && rich.road.isEmpty() &&
            rich.etaSeconds == 0 && rich.totalDistMeters == 0 && rich.cameraAlert.isEmpty()
        ) {
            if (current.active) stats.notifEmpty++
            return
        }
        val prev = current
        if (!prev.active) {
            Log.i(TAG, "guidance active (source=NOTIFICATION)")
            traceOn(Source.NOTIFICATION, nowMs)
        } else {
            stats.refreshed(nowMs - prev.lastUpdateMs)
        }
        lastNotifMs = nowMs
        val a11yFresh = lastA11yMs != 0L && nowMs - lastA11yMs <= A11Y_PRIORITY_MS
        if (a11yFresh) {
            stats.notifIgnored++
        } else {
            stats.notifRich++
            countRead(prev, rich.maneuverGaode, rich.distanceMeters)
        }
        val held = if (a11yFresh) prev else dropOnStreetChange(prev, rich.maneuverGaode, rich.road, Source.NOTIFICATION)
        val base = if (a11yFresh) prev else held.copy(
            maneuverGaode = if (rich.maneuverGaode > 0) rich.maneuverGaode else held.maneuverGaode,
            maneuverGaodeMs = if (rich.maneuverGaode > 0) nowMs else held.maneuverGaodeMs,
            maneuverSource = if (rich.maneuverGaode > 0) Source.NOTIFICATION.label() else held.maneuverSource,
            maneuverRaw = if (rich.maneuverGaode > 0) rich.maneuverRaw else held.maneuverRaw,
            distanceMeters = if (rich.distanceMeters > 0) rich.distanceMeters else held.distanceMeters,
            road = rich.road.ifEmpty { held.road },
            etaSeconds = if (rich.etaSeconds > 0) rich.etaSeconds else held.etaSeconds,
            totalDistMeters = if (rich.totalDistMeters > 0) rich.totalDistMeters else held.totalDistMeters,
            maneuverPng = rich.maneuverPng ?: held.maneuverPng,
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

    /** #294: a held maneuver belongs to the next street it was read with. A read that names
     *  another street and carries no maneuver of its own means that turn is behind: the arrow
     *  goes now instead of after [MANEUVER_TIMEOUT_MS]. Without a street on either side nothing
     *  is known and the arrow stays, as on cars whose reads carry it only now and then (#198);
     *  callers pass "" for a road that is only the current-street fallback. */
    private fun dropOnStreetChange(prev: Snapshot, maneuverGaode: Int, road: String, source: Source): Snapshot {
        if (maneuverGaode > 0 || prev.maneuverGaode <= 0) return prev
        val street = road.trim()
        if (street.isEmpty() || prev.road.isBlank() || street == prev.road.trim()) return prev
        Log.i(TAG, "maneuver dropped: next street changed without a maneuver " +
            "(held=${prev.maneuverGaode} from ${prev.maneuverSource.ifEmpty { "?" }}, read by ${source.label()})")
        if (prev.active) stats.drops++
        return prev.copy(maneuverGaode = 0, maneuverPng = null, maneuverSource = "", maneuverRaw = "")
    }

    private fun Source.label(): String = name.lowercase()

    /** A Navigator window still shows the guidance widgets but they carry no text (issue #199,
     *  the donor counts such a read as guidance): refreshes the liveness of a route that is
     *  active and not yet expired; never starts or revives one, and touches no other field. */
    @Synchronized
    fun keepAlive(nowMs: Long = System.currentTimeMillis()) {
        val s = current
        if (!s.active || nowMs - s.lastUpdateMs > ACTIVE_TIMEOUT_MS) return
        stats.kept++
        stats.refreshed(nowMs - s.lastUpdateMs)
        current = s.copy(lastUpdateMs = nowMs)
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
        Log.i(TAG, "guidance inactive: notification removed, grace expired")
        traceOff(OFF_NOTIF_GRACE, s, nowMs)
    }

    // Counters fed from outside the hub. Each counts only while a route is active, so a finished
    // route's counters stay as they were until the next route starts.

    /** NavA11yFeed: a navigator read without guidance. */
    @Synchronized
    fun countA11yNoGuidance() {
        if (current.active) stats.a11yNoGuidance++
    }

    /** NavA11yFeed: navigator events flow but no window can be read ([blind] true), or it reads again. */
    @Synchronized
    fun a11yBlind(blind: Boolean, nowMs: Long = System.currentTimeMillis()) {
        a11yBlindNow = blind
        if (!current.active) return
        if (blind) stats.blind(nowMs) else stats.seen(nowMs)
    }

    /** The notification listener: a navigator post that carried nothing for the hub. */
    @Synchronized
    fun countNotifEmpty() {
        if (current.active) stats.notifEmpty++
    }

    /** HudPushLoop: one guidance frame and its fireEvent rc. */
    @Synchronized
    fun countFrame(rc: Int) {
        if (current.active) stats.frame(rc)
    }

    /** The route-summary fields of the current route, or of the last one once it ended, as one
     *  line for the dump. Applies no expiry and writes nothing. */
    @Synchronized
    fun routeSummary(nowMs: Long = System.currentTimeMillis()): String {
        if (stats.startMs == 0L) return "(no route)"
        val s = current
        return stats.describe(hudWay, if (s.active) nowMs else s.lastUpdateMs)
    }

    /** A read the hub took: a new maneuver, or a distance without a recognised maneuver. */
    private fun countRead(prev: Snapshot, maneuverGaode: Int, distanceMeters: Int) {
        if (maneuverGaode > 0 && maneuverGaode != prev.maneuverGaode) stats.maneuvers++
        if (UnknownManeuverGate.applies(distanceMeters, maneuverGaode) { true }) stats.unknown++
    }

    private fun traceOn(source: Source, nowMs: Long) {
        stats.reset(nowMs)
        // A route that starts while the a11y feed is blind (guided by notifications) counts it from here.
        if (a11yBlindNow) stats.blind(nowMs)
        Trace.event(TraceArea.HUD, "guidance-on", "src" to source.label())
    }

    /** The route's end, then its summary linked to it; [ended] is the route's last active state. */
    @Suppress("SpreadOperator") // one copy per route
    private fun traceOff(reason: String, ended: Snapshot, nowMs: Long) {
        val endMs = ended.lastUpdateMs
        val id = Trace.event(
            TraceArea.HUD, "guidance-off", "reason" to reason,
            "route_s" to ((endMs - stats.startMs).coerceAtLeast(0L) / 1000).toInt(),
            "last_a11y_ago_s" to agoS(lastA11yMs, nowMs), "last_notif_ago_s" to agoS(lastNotifMs, nowMs),
        )
        lastOffTraceId = id
        Trace.event(TraceArea.HUD, "route-summary", *stats.fields(hudWay, endMs).toTypedArray(), by = id)
    }

    /** Seconds since [ms], `na` when that source never fed the hub. */
    private fun agoS(ms: Long, nowMs: Long): Any = if (ms == 0L) "na" else ((nowMs - ms) / 1000).toInt()

    @Synchronized
    fun reset() {
        current = Snapshot()
        lastA11yMs = 0L
        lastNotifMs = 0L
        lastOffTraceId = 0L
        a11yBlindNow = false
        stats.reset(0L)
    }

    private const val OFF_SILENCE = "silence-90s"
    private const val OFF_NOTIF_GRACE = "notif-grace"
}
