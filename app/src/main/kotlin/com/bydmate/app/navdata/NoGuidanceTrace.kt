package com.bydmate.app.navdata

/** Field diagnostics for guidance lost while a route runs (issue #199): the a11y feed logs one
 *  line when a window read without guidance starts a streak, one when a guidance read ends it,
 *  and one when a timer read stops finding the navigator window. The feed fires many times per
 *  second, so every line here is an edge, never a tick; a streak starting within
 *  [LINE_MIN_INTERVAL_MS] of the last start line is only counted, so flapping guidance stays
 *  bounded but visible. */
internal class NoGuidanceTrace {
    // Time of the read that started the streak; 0 = no streak.
    private var streakStartMs = 0L
    // Whether the running streak got its start line; an unlogged one ends without a line too.
    private var streakLogged = false
    private var lastLineMs: Long? = null
    private var skipped = 0
    private var lastDumpMs: Long? = null
    private var navigatorMissing = false

    /** A no-guidance read. When it starts a streak (none is running and a route is guided) and
     *  the last start line is [LINE_MIN_INTERVAL_MS] old, returns how many streak starts were
     *  skipped since that line; null when no line is due. [guidanceActive] is read only when no
     *  streak runs, so a streak costs no route-state read. */
    @Synchronized
    fun startStreak(nowMs: Long, guidanceActive: () -> Boolean): Int? {
        if (streakStartMs != 0L || !guidanceActive()) return null
        streakStartMs = nowMs
        val last = lastLineMs
        streakLogged = last == null || nowMs - last >= LINE_MIN_INTERVAL_MS
        if (!streakLogged) {
            skipped++
            return null
        }
        lastLineMs = nowMs
        return skipped.also { skipped = 0 }
    }

    /** A guidance read: how long the streak it ends lasted, null when none ran or its start
     *  was not logged. */
    @Synchronized
    fun endStreak(nowMs: Long): Long? {
        if (streakStartMs == 0L) return null
        val lasted = nowMs - streakStartMs
        streakStartMs = 0L
        return if (streakLogged) lasted else null
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
        streakLogged = false
        lastLineMs = null
        skipped = 0
        lastDumpMs = null
        navigatorMissing = false
    }

    companion object {
        const val LINE_MIN_INTERVAL_MS = 5_000L
        const val DUMP_MIN_INTERVAL_MS = 60_000L
    }
}
