package com.bydmate.app.navdata

/** Field diagnostics for a guided route whose maneuver reads as 0: each lane logs the raw
 *  maneuver value it could not map, once per distinct value and never two lines within
 *  [minIntervalMs], so a recorded log shows what to map while neither a 2 -> 0 -> 2 blink nor
 *  a phrase that stays on screen floods it. */
internal class UnknownManeuverGate(private val minIntervalMs: Long) {
    private val seen = HashSet<String>()
    private var lastMs = 0L

    /** True when [value] was not logged this episode and the floor has passed; the value is then
     *  remembered. A value refused by the floor is not, so a later read can still log it. */
    @Synchronized
    fun take(value: String, nowMs: Long): Boolean {
        if (value in seen || seen.size >= MAX_VALUES || nowMs - lastMs < minIntervalMs) return false
        seen.add(value)
        lastMs = nowMs
        return true
    }

    @Synchronized
    fun reset() {
        seen.clear()
        lastMs = 0L
    }

    companion object {
        /** Floor of the notification lane; the a11y feed waits for its tree walk's instead. */
        const val MIN_INTERVAL_MS = 30_000L
        /** Distinct values per episode, so a navigator that changes its phrases cannot grow the set. */
        const val MAX_VALUES = 16
        private const val MAX_TEXT_CHARS = 120

        /** A route runs with a distance on screen, yet the maneuver code is 0. */
        fun applies(guidanceActive: Boolean, distanceMeters: Int, maneuverGaode: Int): Boolean =
            guidanceActive && distanceMeters > 0 && maneuverGaode == 0

        /** Raw text for a log line: quoted and capped, so null and empty read apart. */
        fun quote(raw: String?): String = when {
            raw == null -> "null"
            raw.length <= MAX_TEXT_CHARS -> "\"$raw\""
            else -> "\"${raw.take(MAX_TEXT_CHARS)}\"…"
        }
    }
}
