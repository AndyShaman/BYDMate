package com.bydmate.app.ui.settings

import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.WriteAllowlist

/**
 * READ-side snapshot of the steering wheel heater for the diagnostic dump. The write is not
 * validated on a car with a heated wheel, so the first field dump has to show what the
 * Setting device (dev=1023) reports there — sentinels included (0 = function absent,
 * 65535 = no CAN link). Levels (config/gear/mode) are read only, nothing writes them.
 */
internal object SteeringHeatDiagnostics {

    /** Printed label to fid, all tx=5 on the state's device, in print order. */
    private val FIDS: List<Pair<String, Int>> = listOf(
        "state" to WriteAllowlist.STEERING_HEAT_STATE_FID,
        "config" to 1116733496,  // 1/4 = on/off only, 2/5 = 3 levels, 3/6 = 5 levels, 0 = judge by state
        "gear" to 1116733465,
        "mode" to 1116733462,
    )

    fun batchItems(): List<BatchReadItem> =
        FIDS.map { (_, fid) -> BatchReadItem(TX_GET_INT, WriteAllowlist.STEERING_HEAT_STATE_DEV, fid) }

    /**
     * One line per fid from raw (status, value) pairs in [FIDS] order. A null [readings]
     * (daemon unreachable, timeout, old daemon) or a length mismatch collapses to one line.
     */
    fun format(readings: List<Pair<Int, Int>>?): List<String> {
        if (readings == null || readings.size != FIDS.size) return listOf("(unavailable)")
        return FIDS.mapIndexed { i, (name, fid) ->
            val (status, value) = readings[i]
            val rendered = if (status == 0) value.toString() else "(status=$status)"
            "$name[dev=${WriteAllowlist.STEERING_HEAT_STATE_DEV} fid=$fid]=$rendered"
        }
    }

    private const val TX_GET_INT = 5
}
