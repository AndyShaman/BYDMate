package com.bydmate.app.navdata

/** Field diagnostics for guidance lost while a route runs (issue #199): the a11y feed logs one
 *  line when a window read without guidance starts a streak, one when a guidance read ends it,
 *  and one when a timer read stops finding the navigator window. The feed fires many times per
 *  second, so every line here is an edge, never a tick. */
internal class NoGuidanceTrace {
    // Time of the read that started the streak; 0 = no streak.
    private var streakStartMs = 0L
    private var lastDumpMs: Long? = null
    private var navigatorMissing = false

    /** True when this no-guidance read starts a streak: none is running and a route is guided.
     *  [guidanceActive] is read only when no streak runs, so a streak costs no route-state read. */
    @Synchronized
    fun startsStreak(nowMs: Long, guidanceActive: () -> Boolean): Boolean {
        if (streakStartMs != 0L || !guidanceActive()) return false
        streakStartMs = nowMs
        return true
    }

    /** A guidance read: how long the streak it ends lasted, null when none ran. */
    @Synchronized
    fun endStreak(nowMs: Long): Long? {
        if (streakStartMs == 0L) return null
        val lasted = nowMs - streakStartMs
        streakStartMs = 0L
        return lasted
    }

    /** True when the id walk may run: [DUMP_MIN_INTERVAL_MS] since the last one. */
    @Synchronized
    fun takeDump(nowMs: Long): Boolean {
        val last = lastDumpMs
        if (last != null && nowMs - last < DUMP_MIN_INTERVAL_MS) return false
        lastDumpMs = nowMs
        return true
    }

    /** A timer read found no navigator window: true on the first one since a window was found. */
    @Synchronized
    fun navigatorMissing(): Boolean {
        if (navigatorMissing) return false
        navigatorMissing = true
        return true
    }

    @Synchronized
    fun navigatorFound() {
        navigatorMissing = false
    }

    @Synchronized
    fun reset() {
        streakStartMs = 0L
        lastDumpMs = null
        navigatorMissing = false
    }

    companion object {
        const val DUMP_MIN_INTERVAL_MS = 60_000L
    }
}
