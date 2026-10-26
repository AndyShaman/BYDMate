package com.bydmate.app.cluster

import java.util.concurrent.atomic.AtomicBoolean

/** What the a11y filter should do with the split-screen key (Settings → Разделение экрана). */
enum class SplitKeyDecision { TOGGLE, CONSUME, PASS_THROUGH }

/**
 * Pure gate for the split key, shaped like [starDecision]. [splitKeyCode] 0 = not assigned.
 * - feature off, no key, or another key → PASS_THROUGH (automation rules and native untouched).
 * - the key, feature on: TOGGLE on the first DOWN; CONSUME on its UP and on auto-repeat DOWNs,
 *   so a held button never opens and closes the split in a loop.
 */
fun splitKeyDecision(
    keyCode: Int,
    isDown: Boolean,
    repeatCount: Int,
    splitEnabled: Boolean,
    splitKeyCode: Int,
): SplitKeyDecision {
    if (!splitEnabled || splitKeyCode <= 0 || keyCode != splitKeyCode) return SplitKeyDecision.PASS_THROUGH
    return if (isDown && repeatCount == 0) SplitKeyDecision.TOGGLE else SplitKeyDecision.CONSUME
}

/**
 * Serializes the split key's toggle decision with its run: while a key-triggered start or exit is
 * in flight, further presses are ignored, so a double press never starts and then exits mid-launch.
 */
class SplitKeyBusyGuard {
    private val busy = AtomicBoolean(false)

    /** Runs [block] unless a previous run is still going; true = ran, false = ignored. */
    suspend fun runIfIdle(block: suspend () -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        try {
            block()
        } finally {
            busy.set(false)
        }
        return true
    }
}
