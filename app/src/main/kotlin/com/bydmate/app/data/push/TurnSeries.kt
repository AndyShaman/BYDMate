package com.bydmate.app.data.push

/**
 * One turn-signal use as two trace events: its start (the side) and its end, however many times
 * the lamp blinks in between. Some cars push every blink (on, off, on, off...), which wrote a
 * line per blink; an off shorter than [endAfterMs] is the blink's dark phase, not the end.
 *
 * The end is only known once the lamp stayed dark long enough, so the caller asks [onTimer]
 * [endAfterMs] after an off (and every later event checks it too). Not thread-safe: the caller
 * guards it.
 */
internal class TurnSeries(private val endAfterMs: Long = END_AFTER_MS) {

    sealed interface Event {
        data class Start(val side: String) : Event
        data class End(val side: String, val blinks: Int, val durationMs: Long) : Event
    }

    private var side: String? = null
    private var startedAtMs = 0L
    private var blinks = 0
    // When the lamp went dark inside the running series; -1 while it is lit.
    private var offSinceMs = -1L

    /** A new value of the signal: [OFF] or a side (`left`, `right`, `hazard`, or a raw number).
     *  [NONE] (no data) counts as dark: it never starts a series. */
    fun onValue(value: String, nowMs: Long): List<Event> {
        val out = mutableListOf<Event>()
        onTimer(nowMs)?.let { out += it }
        val current = side
        when {
            value == OFF || value == NONE -> if (current != null && offSinceMs < 0) offSinceMs = nowMs
            current == null -> start(value, nowMs, out)
            current == value -> if (offSinceMs >= 0) {
                blinks++
                offSinceMs = -1L
            }
            else -> {
                out += end(if (offSinceMs >= 0) offSinceMs else nowMs)
                start(value, nowMs, out)
            }
        }
        return out
    }

    /** The series ends once the lamp has been dark for [endAfterMs]; null while it has not. */
    fun onTimer(nowMs: Long): Event? {
        if (side == null || offSinceMs < 0 || nowMs - offSinceMs < endAfterMs) return null
        return end(offSinceMs)
    }

    private fun start(value: String, nowMs: Long, out: MutableList<Event>) {
        side = value
        startedAtMs = nowMs
        blinks = 1
        offSinceMs = -1L
        out += Event.Start(value)
    }

    /** Ends the running series at [atMs] (the moment the lamp went dark, or a switch of side). */
    private fun end(atMs: Long): Event {
        val ended = Event.End(requireNotNull(side), blinks, atMs - startedAtMs)
        side = null
        offSinceMs = -1L
        return ended
    }

    companion object {
        const val OFF = "off"
        const val NONE = "none"
        /** Longer than the dark phase of a blink (~0.4 s) with room for a slow push. */
        const val END_AFTER_MS = 1_500L
    }
}
