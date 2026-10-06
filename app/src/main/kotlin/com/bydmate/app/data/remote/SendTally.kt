package com.bydmate.app.data.remote

/**
 * Telemetry sends counted between two summary lines: an ABRP user sends every second while
 * driving, and a line per send (plus one per answer) was thousands an hour. The first send gets a
 * line, then one per [windowMs] with what happened since; failures keep their own lines.
 */
internal class SendTally(private val windowMs: Long = WINDOW_MS) {

    private var ok = 0
    private var failed = 0
    private var sinceMs = -1L

    /** Counts one send; returns the summary when one is due, null otherwise. */
    @Synchronized
    fun record(success: Boolean, tookMs: Long, nowMs: Long): String? {
        if (success) ok++ else failed++
        if (sinceMs >= 0 && nowMs - sinceMs < windowMs) return null
        val span = if (sinceMs < 0) "first" else "${(nowMs - sinceMs) / 1_000}s"
        val line = "ok=$ok fail=$failed over=$span last_ms=$tookMs"
        ok = 0
        failed = 0
        sinceMs = nowMs
        return line
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L
    }
}
