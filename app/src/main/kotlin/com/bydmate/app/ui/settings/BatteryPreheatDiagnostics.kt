package com.bydmate.app.ui.settings

import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.BatteryPreheatChannel
import com.bydmate.app.data.vehicle.WriteAllowlist
import com.bydmate.app.split.readSystemProperty

/**
 * READ-side state of the battery warm-up (#311) for the diagnostic dump, from the Charging
 * device (dev=1009): whether the car has the function and whether it would let it start now,
 * plus what tells a pure EV from a hybrid (dev=1014 and the car type property).
 * Read only.
 */
internal object BatteryPreheatDiagnostics {

    private const val DEV = WriteAllowlist.BATTERY_PREHEAT_DEV
    private const val CAR_DEV = WriteAllowlist.HEV_MILEAGE_DEV

    /** Printed label to (dev, fid), all tx=5, in print order. */
    private val FIDS: List<Pair<String, Pair<Int, Int>>> = listOf(
        "config" to (DEV to WriteAllowlist.BATTERY_PREHEAT_CONFIG_FID),     // 0 = fitted
        "permit" to (DEV to WriteAllowlist.BATTERY_PREHEAT_PERMIT_FID),     // 0 = the car lets it start
        "status" to (DEV to WriteAllowlist.BATTERY_PREHEAT_STATUS_FID),     // 1 = heating, 2 = finished
        "gun" to (DEV to WriteAllowlist.CHARGE_GUN_STATE_FID),              // 2, 3, 4 = connected
        "heat_power" to (DEV to WriteAllowlist.BATTERY_HEATING_POWER_FID),  // raw
        "hev" to (CAR_DEV to WriteAllowlist.HEV_MILEAGE_FID),               // 1048575 = a pure EV
        "fuel" to (CAR_DEV to WriteAllowlist.FUEL_PERCENT_FID),             // raw
    )

    fun batchItems(): List<BatchReadItem> = FIDS.map { (_, address) -> BatchReadItem(TX_GET_INT, address.first, address.second) }

    /**
     * One `battery_preheat:` line from raw (status, value) pairs in [FIDS] order, ended by the
     * car type property [energyType] ("1" EV, "2" hybrid, "0" fuel).
     */
    fun line(
        readings: List<Pair<Int, Int>>?,
        energyType: String? = readSystemProperty(BatteryPreheatChannel.ENERGY_TYPE_PROP),
    ): String {
        if (readings == null || readings.size != FIDS.size) return "battery_preheat: (unavailable)"
        return "battery_preheat: " + FIDS.mapIndexed { i, (name, address) ->
            val (status, value) = readings[i]
            val rendered = if (status == 0) value.toString() else "(status=$status)"
            "$name[dev=${address.first} fid=${address.second}]=$rendered"
        }.joinToString(" ") + " type=${energyType ?: "err"}"
    }

    private const val TX_GET_INT = 5
}
