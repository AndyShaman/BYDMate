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

    /** A pushed value; true = this is the power-off. Negative values (sentinels) are ignored. */
    fun onEvent(fid: Int, value: Int): Boolean = when {
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

    /**
     * A value read directly (the listener only reports changes, so a daemon started on a running
     * car needs this to know the car is on). Never fires: a 0 read here is only forgotten.
     */
    fun seed(fid: Int, value: Int) {
        when {
            value > 0 -> on += fid
            value == 0 -> on -= fid
        }
    }

    /** True while some fid says the car is on; the next drop to 0 fires. */
    val primed: Boolean get() = on.isNotEmpty()
}
