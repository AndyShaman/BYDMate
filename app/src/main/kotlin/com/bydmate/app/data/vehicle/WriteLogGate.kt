package com.bydmate.app.data.vehicle

/**
 * Which daemon writes get their own logcat line. A user action (hazard, lock, a rule step) is a
 * sparse write and always has one; a streaming writer (the music card's progress every 1.5 s,
 * the card repeat, HUD guidance on CAN) wrote up to 11 thousand lines an hour. A fid written
 * again within [streamGapMs] of its previous write is streaming: it gets a line once per
 * [quietWindowMs], carrying how many writes went unlogged. A refused write or a changed status
 * is always logged.
 */
internal class WriteLogGate(
    private val streamGapMs: Long = STREAM_GAP_MS,
    private val quietWindowMs: Long = QUIET_WINDOW_MS,
) {
    private class FidState(var lastWriteMs: Long, var lastLineMs: Long, var lastStatus: Int?, var quiet: Int)

    private val fids = HashMap<String, FidState>()

    /**
     * Records one write of [key] (device and fid). Null when it stays unlogged; otherwise the
     * number of writes of that fid left unlogged since its previous line.
     */
    @Synchronized
    fun onWrite(key: String, status: Int?, accepted: Boolean, nowMs: Long): Int? {
        val state = fids[key]
        if (state == null) {
            if (fids.size >= MAX_FIDS) fids.clear()
            fids[key] = FidState(nowMs, nowMs, status, 0)
            return 0
        }
        val streaming = nowMs - state.lastWriteMs < streamGapMs
        state.lastWriteMs = nowMs
        val log = !streaming || !accepted || status != state.lastStatus || nowMs - state.lastLineMs >= quietWindowMs
        state.lastStatus = status
        if (!log) {
            state.quiet++
            return null
        }
        val quiet = state.quiet
        state.quiet = 0
        state.lastLineMs = nowMs
        return quiet
    }

    companion object {
        const val STREAM_GAP_MS = 15_000L
        const val QUIET_WINDOW_MS = 60_000L
        /** The app writes a few dozen distinct fids; a bound against a runaway caller. */
        private const val MAX_FIDS = 256
    }
}
