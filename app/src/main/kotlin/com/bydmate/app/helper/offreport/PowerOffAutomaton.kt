package com.bydmate.app.helper.offreport

/**
 * Decides when the car was switched off, from the values of the power fids
 * (`BODYWORK_POWER_LEVEL` and `SET_VEHICLE_STATE`, both drop to 0 in the same millisecond).
 *
 * Fires when a fid goes to 0 after it was non-zero in this power cycle, once per cycle: firing
 * forgets every fid, so the second fid's 0 that follows is not a second power-off. A non-zero
 * value starts the next cycle. A daemon that starts with the car already off has seen nothing
 * non-zero, so it never fires. Each fid counts on its own, because on DM-i hybrids powerState reads
 * 0 on a running car and only the power level moves. Not thread-safe; the owner locks.
 */
internal class PowerOffAutomaton {

    private val on = HashSet<Int>()

    /**
     * Bumped by every pushed event. A direct read races the pushes: the owner takes the generation
     * before reading and applies the seed only when it has not moved, so a value read before a
     * handled power-off cannot re-prime the automaton after it.
     */
    var generation = 0L
        private set

    /** A pushed value; true = this is the power-off. Negative values (sentinels) are ignored. */
    fun onEvent(fid: Int, value: Int): Boolean {
        generation++
        return when {
            value > 0 -> {
                on += fid
                false
            }
            value == 0 && fid in on -> {
                on.clear()
                true
            }
            else -> false
        }
    }

    /**
     * A value read directly (the listener only reports changes, so a daemon started on a running
     * car needs this to know the car is on). It only ever marks a fid as seen on: a 0 read here
     * may be the power-off whose push is still on its way, and forgetting the fid would swallow it.
     * Only a fire (or a new daemon) ends the cycle.
     */
    fun seed(fid: Int, value: Int) {
        if (value > 0) on += fid
    }

    /** True while some fid says the car is on; the next drop to 0 fires. */
    val primed: Boolean get() = on.isNotEmpty()
}
