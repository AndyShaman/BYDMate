package com.bydmate.app.navdata

/** Field diagnostics for a guided route whose maneuver reads as 0: each lane logs the raw
 *  maneuver value it could not map, once per distinct value per [REPEAT_MS] and never two lines
 *  within [minIntervalMs], so a recorded log shows what to map while neither a 2 -> 0 -> 2 blink
 *  nor a phrase that stays on screen floods it. The repeat lets a recording switched on after an
 *  earlier route still catch the value. */
internal class UnknownManeuverGate(private val minIntervalMs: Long) {
    // Value -> time of its last line, oldest line first.
    private val logged = LinkedHashMap<String, Long>()
    private var lastMs = 0L

    /** True when [value] had no line for [REPEAT_MS] and the floor has passed; its time is then
     *  recorded. A value refused by the floor is not, so a later read can still log it. Past
     *  [MAX_VALUES] the value logged longest ago is forgotten. */
    @Synchronized
    fun take(value: String, nowMs: Long): Boolean {
        if (nowMs - lastMs < minIntervalMs) return false
        val last = logged[value]
        if (last != null && nowMs - last < REPEAT_MS) return false
        logged.remove(value)
        if (logged.size >= MAX_VALUES) logged.remove(logged.keys.first())
        logged[value] = nowMs
        lastMs = nowMs
        return true
    }

    @Synchronized
    fun reset() {
        logged.clear()
        lastMs = 0L
    }

    companion object {
        /** Floor of the notification lane; the a11y feed waits for its tree walk's instead. */
        const val MIN_INTERVAL_MS = 30_000L
        /** The same value gets a line again after this long. */
        const val REPEAT_MS = 300_000L
        /** Values remembered at once; a navigator that changes its phrases cannot grow the map. */
        const val MAX_VALUES = 16
        private const val MAX_TEXT_CHARS = 120

        /** A route runs with a distance on screen, yet the maneuver code is 0. [guidanceActive]
         *  is read last, so a recognised maneuver costs no route-state read. */
        inline fun applies(distanceMeters: Int, maneuverGaode: Int, guidanceActive: () -> Boolean): Boolean =
            distanceMeters > 0 && maneuverGaode == 0 && guidanceActive()

        /** A resource name for a log line: quoted and capped, so null and empty read apart. */
        fun quote(raw: String?): String = when {
            raw == null -> "null"
            raw.length <= MAX_TEXT_CHARS -> "\"$raw\""
            else -> "\"${raw.take(MAX_TEXT_CHARS)}\"…"
        }
    }
}
