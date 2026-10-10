package com.bydmate.app.ui.settings

import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.WriteAllowlist

/**
 * READ-side state of the battery warm-up (#311) for the diagnostic dump, from the Charging
 * device (dev=1009): whether the car has the function and whether it would let it start now.
 * Read only.
 */
internal object BatteryPreheatDiagnostics {

    private const val DEV = WriteAllowlist.BATTERY_PREHEAT_DEV

    /** Printed label to fid, all tx=5, in print order. */
    private val FIDS: List<Pair<String, Int>> = listOf(
        "config" to WriteAllowlist.BATTERY_PREHEAT_CONFIG_FID,     // 0 = fitted
        "permit" to WriteAllowlist.BATTERY_PREHEAT_PERMIT_FID,     // 0 = the car lets it start
        "status" to WriteAllowlist.BATTERY_PREHEAT_STATUS_FID,     // 1 = heating, 2 = finished
        "gun" to WriteAllowlist.CHARGE_GUN_STATE_FID,              // 2, 3, 4 = connected
        "heat_power" to WriteAllowlist.BATTERY_HEATING_POWER_FID,  // raw
    )

    fun batchItems(): List<BatchReadItem> = FIDS.map { (_, fid) -> BatchReadItem(TX_GET_INT, DEV, fid) }

    /** One `battery_preheat:` line from raw (status, value) pairs in [FIDS] order. */
    fun line(readings: List<Pair<Int, Int>>?): String {
        if (readings == null || readings.size != FIDS.size) return "battery_preheat: (unavailable)"
        return "battery_preheat: " + FIDS.mapIndexed { i, (name, fid) ->
            val (status, value) = readings[i]
            val rendered = if (status == 0) value.toString() else "(status=$status)"
            "$name[dev=$DEV fid=$fid]=$rendered"
        }.joinToString(" ")
    }

    private const val TX_GET_INT = 5
}
