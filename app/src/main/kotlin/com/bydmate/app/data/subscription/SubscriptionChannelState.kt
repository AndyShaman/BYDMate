package com.bydmate.app.data.subscription

/**
 * Pure per-fid channel bookkeeping for the observe-only subscription phase.
 * Poll stays the source of truth; this only counts evidence for/against the channel.
 */
class SubscriptionChannelState(val name: String, private val validRange: IntRange) {
    var confirmed = false; private set
    var sick = false; private set
    var eventCount = 0; private set
    var mismatchCount = 0; private set
    var lastValue: Int? = null; private set
    var lastEventAtMs = 0L; private set

    @Synchronized
    fun onEvent(value: Int, nowMs: Long): Boolean {
        eventCount++
        lastEventAtMs = nowMs
        if (value !in validRange) {
            sick = true
            return false
        }
        confirmed = true
        lastValue = value
        return true
    }

    /**
     * Called on every poll tick with the poll's value for the same fid.
     * The value can legitimately change between an event and the next tick, so the
     * counter is an UPPER bound on real divergence — a human reads it from the dump.
     */
    @Synchronized
    fun onPollComparison(pollValue: Int?) {
        val sub = lastValue ?: return
        if (pollValue != null && pollValue != sub) mismatchCount++
    }

    @Synchronized
    fun diagnosticLine(nowMs: Long): String = buildString {
        append("$name: confirmed=$confirmed events=$eventCount mismatches=$mismatchCount sick=$sick")
        append(" last=${lastValue ?: "-"}")
        if (lastEventAtMs > 0) append(" ageMs=${nowMs - lastEventAtMs}")
    }
}
