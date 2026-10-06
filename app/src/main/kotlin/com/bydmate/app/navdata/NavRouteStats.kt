package com.bydmate.app.navdata

import java.util.TreeMap

/**
 * One route's counters behind the hub's `route-summary` trace line: what fed the route, what the
 * reads carried and what the frames answered, counted with an int each instead of a line per read
 * or frame. Reset at guidance-on, frozen at guidance-off, so the dump shows the last route. Not
 * thread-safe: [NavGuidanceHub] guards it with its own lock.
 */
internal class NavRouteStats {
    var startMs = 0L
        private set
    var a11yGuidance = 0
    var a11yNoGuidance = 0
    var kept = 0
    var notifRich = 0
    var notifIgnored = 0
    var notifEmpty = 0
    var maneuvers = 0
    var unknown = 0
    var expiries = 0
    var drops = 0
    var frames = 0
    private val rcs = TreeMap<Int, Int>()
    private var blindMs = 0L
    private var blindSinceMs = 0L
    private var maxGapMs = 0L

    fun reset(nowMs: Long) {
        startMs = nowMs
        a11yGuidance = 0; a11yNoGuidance = 0; kept = 0
        notifRich = 0; notifIgnored = 0; notifEmpty = 0
        maneuvers = 0; unknown = 0; expiries = 0; drops = 0
        frames = 0
        rcs.clear()
        blindMs = 0L; blindSinceMs = 0L; maxGapMs = 0L
    }

    /** The a11y feed lost the window (events flowing, nothing readable); idempotent. */
    fun blind(nowMs: Long) {
        if (blindSinceMs == 0L) blindSinceMs = nowMs
    }

    /** The a11y feed reads again: the blind spell ends. */
    fun seen(nowMs: Long) {
        if (blindSinceMs == 0L) return
        blindMs += (nowMs - blindSinceMs).coerceAtLeast(0L)
        blindSinceMs = 0L
    }

    /** A source refreshed the route [gapMs] after the previous refresh. */
    fun refreshed(gapMs: Long) {
        if (gapMs > maxGapMs) maxGapMs = gapMs
    }

    fun frame(rc: Int) {
        frames++
        rcs[rc] = (rcs[rc] ?: 0) + 1
    }

    /** The route-summary fields as of [endMs]; a blind spell still open counts up to it. */
    fun fields(way: Int, endMs: Long): List<Pair<String, Any>> {
        val blind = blindMs + if (blindSinceMs > 0L) (endMs - blindSinceMs).coerceAtLeast(0L) else 0L
        return listOf(
            "way" to way,
            "dur_s" to ((endMs - startMs).coerceAtLeast(0L) / MS_PER_S).toInt(),
            "a11y_guid" to a11yGuidance,
            "a11y_noguid" to a11yNoGuidance,
            "kept" to kept,
            "blind_ms" to blind.toInt(),
            "notif_rich" to notifRich,
            "notif_ignored" to notifIgnored,
            "notif_empty" to notifEmpty,
            "maneuvers" to maneuvers,
            "unknown" to unknown,
            "expiries" to expiries,
            "drops" to drops,
            "frames" to frames,
            "rc" to rcs.entries.joinToString(",", prefix = "{", postfix = "}") { "${it.key}:${it.value}" },
            "max_gap_s" to (maxGapMs / MS_PER_S).toInt(),
        )
    }

    /** [fields] as one `key=value` line, for the dump. */
    fun describe(way: Int, endMs: Long): String = fields(way, endMs).joinToString(" ") { "${it.first}=${it.second}" }

    private companion object {
        const val MS_PER_S = 1_000L
    }
}
