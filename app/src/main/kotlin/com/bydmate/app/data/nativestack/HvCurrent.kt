package com.bydmate.app.data.nativestack

import kotlin.math.abs

/**
 * #429: On some firmwares (Song Plus EV 2025), hvCurrent (dev 1009, fid 1145045016) answers the
 * float sentinel -1.0 on tx 7 while tx 5 carries a live CAN-offset int ((raw - 5000) / 10 A),
 * and push packets carry the live intValue alongside doubleValue 0.0.
 */
object HvCurrent {

    private const val CAN_OFFSET = 5000
    private const val CAN_SCALE = 10f
    private const val MAX_ABS_AMPS = 1000f

    /**
     * Process-only mode flag set by the poll when tx 7 returns sentinel and tx 5 answers a live
     * CAN int, and cleared when tx 7 answers a valid float. Read by [FidPushApplier].
     */
    @Volatile
    var intMode: Boolean = false
        private set

    fun fromCanInt(raw: Int): Float? {
        val amps = (raw - CAN_OFFSET) / CAN_SCALE
        return amps.takeIf { abs(it) <= MAX_ABS_AMPS }
    }

    /** Updates [intMode] and returns true only on the false -> true transition. */
    internal fun setIntMode(enabled: Boolean): Boolean {
        val prev = intMode
        intMode = enabled
        return !prev && enabled
    }

    internal fun reset() {
        intMode = false
    }
}
