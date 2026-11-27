package com.bydmate.app.cluster

/** Verdict returned by [SteeringDoublePress.onDown] for a steering-wheel key bound to a double press. */
enum class DoublePressVerdict { PENDING, DOUBLE }

/**
 * Pure DOWN-to-DOWN double-press detector for steering-wheel keys (#451).
 *
 * Measuring DOWN to DOWN handles both firmware kinds uniformly: keys that emit DOWN on physical
 * press and UP on release, and MCU-buffered keys (such as Leopard 3 stars 305/351) that emit
 * DOWN and UP together on release. Each keycode tracks its own pending press independently.
 */
class SteeringDoublePress {

    private val pendingDownAt = mutableMapOf<Int, Long>()

    /**
     * Records a first DOWN (`repeatCount == 0`) of [keyCode] at [atMs].
     * Returns [DoublePressVerdict.DOUBLE] when a prior press of the same key is still pending
     * within [WINDOW_MS] (clearing the pending press), else stores this press as pending and
     * returns [DoublePressVerdict.PENDING].
     */
    fun onDown(keyCode: Int, atMs: Long): DoublePressVerdict {
        val prev = pendingDownAt[keyCode]
        if (prev != null && atMs - prev in 0..WINDOW_MS) {
            pendingDownAt.remove(keyCode)
            return DoublePressVerdict.DOUBLE
        }
        pendingDownAt[keyCode] = atMs
        return DoublePressVerdict.PENDING
    }

    /**
     * Evaluates a scheduled deadline check for [keyCode] at [atMs].
     * Returns true (fire single press) and clears the pending press when a press is still pending
     * and has reached [WINDOW_MS]; returns false otherwise (already consumed as double or replaced).
     */
    fun onDeadline(keyCode: Int, atMs: Long): Boolean {
        val pendingAt = pendingDownAt[keyCode] ?: return false
        if (atMs - pendingAt < WINDOW_MS) return false
        pendingDownAt.remove(keyCode)
        return true
    }

    /** Clears all pending presses (called when the accessibility service unbinds or destroys). */
    fun clear() {
        pendingDownAt.clear()
    }

    companion object {
        const val WINDOW_MS = 450L
    }
}
