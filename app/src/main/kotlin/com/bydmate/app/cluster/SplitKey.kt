package com.bydmate.app.cluster

import java.util.concurrent.atomic.AtomicBoolean

/** What the a11y filter should do with the split-screen key (Settings → Разделение экрана). */
enum class SplitKeyDecision { TOGGLE, CONSUME, PASS_THROUGH }

/**
 * Pure gate for the split key, shaped like [starDecision]. [splitKeyCode] 0 = not assigned.
 * - feature off, no key, or another key → PASS_THROUGH (automation rules and native untouched).
 * - the key, feature on: TOGGLE on the first DOWN; CONSUME on its UP and on auto-repeat DOWNs,
 *   so a held button never opens and closes the split in a loop.
 * - [splitCompanions] (other codes of the key's press), feature on and a key set: CONSUME on every
 *   edge, so the stock assistant never sees them.
 */
@Suppress("LongParameterList") // one flat input per gate, so tests pin each one
fun splitKeyDecision(
    keyCode: Int,
    isDown: Boolean,
    repeatCount: Int,
    splitEnabled: Boolean,
    splitKeyCode: Int,
    splitCompanions: Set<Int> = emptySet(),
): SplitKeyDecision {
    if (!splitEnabled || splitKeyCode <= 0) return SplitKeyDecision.PASS_THROUGH
    if (keyCode != splitKeyCode) {
        return if (keyCode in splitCompanions) SplitKeyDecision.CONSUME else SplitKeyDecision.PASS_THROUGH
    }
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
