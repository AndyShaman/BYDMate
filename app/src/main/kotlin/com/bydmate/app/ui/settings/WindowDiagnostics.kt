package com.bydmate.app.ui.settings

import com.bydmate.app.data.vehicle.BatchReadItem

/**
 * READ-side window configuration for the diagnostic dump, from the Bodywork device (dev=1001,
 * the device of the window position and target fids). Shows how the firmware drives the percent
 * windows, next to the target reset every percent write now ends with. Read only.
 */
internal object WindowDiagnostics {

    private const val DEV = 1001

    /** Printed label to fid, all tx=5, in print order. */
    private val FIDS: List<Pair<String, Int>> = listOf(
        "percentage_control_config" to 1222639652,  // BODYWORK_WINDOW_PERCENTAGE_CONTROL_CONFIG
        "control_plan" to 1222639650,               // BODYWORK_WINDOW_CONTROL_PLAN
        "left_front_current_state" to 1222639653,   // BODYWORK_LEFT_FRONT_WINDOW_CURRENT_STATE
    )

    fun batchItems(): List<BatchReadItem> = FIDS.map { (_, fid) -> BatchReadItem(TX_GET_INT, DEV, fid) }

    /**
     * One line per fid from raw (status, value) pairs in [FIDS] order. A null [readings]
     * (daemon unreachable, timeout, old daemon) or a length mismatch collapses to one line.
     */
    fun format(readings: List<Pair<Int, Int>>?): List<String> {
        if (readings == null || readings.size != FIDS.size) return listOf("(unavailable)")
        return FIDS.mapIndexed { i, (name, fid) ->
            val (status, value) = readings[i]
            val rendered = if (status == 0) value.toString() else "(status=$status)"
            "$name[dev=$DEV fid=$fid]=$rendered"
        }
    }

    private const val TX_GET_INT = 5
}
