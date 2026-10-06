package com.bydmate.app.data.push

/**
 * How often a pushed field may write its `applied` line. Discrete states (doors, gear, locks,
 * lights, belts, occupancy, seats, windows) keep one line per second per field: each change is
 * an event someone asks about. Continuous readings (speed, SOC, rpm, cell voltages, currents,
 * temperatures, pressures) change many times a second while driving and were 15-28 thousand
 * lines an hour: one per minute per field shows they are alive. Only the line is rationed;
 * every event still patches the snapshot.
 */
internal object FidPushLogPolicy {

    const val DISCRETE_WINDOW_MS = 1_000L
    const val ANALOG_WINDOW_MS = 60_000L

    /** The continuous FidMap fields. Everything else counts as discrete. */
    val ANALOG_FIELDS: Set<String> = setOf(
        "soc", "speed", "mileage", "power", "totalElecConsumption", "batteryRemainKwh",
        "voltage12v", "maxCellVoltage", "minCellVoltage", "insideTemp", "exteriorTemp",
        "tirePressFL", "tirePressFR", "tirePressRL", "tirePressRR", "maxBatTemp", "minBatTemp",
        "insulationKohm", "motorTempFront", "motorTempRear", "inverterTempFront", "inverterTempRear",
        "hvVoltage", "hvCurrent", "motorCurrentFront", "motorCurrentRear",
        "bmsMaxChargeKw", "bmsMaxDischargeAllowKw", "bmsMaxDischargeKw",
        "motorRpmFront", "motorRpmRear", "compressorW",
        "tyreTempFL", "tyreTempFR", "tyreTempRL", "tyreTempRR",
        "pedalAccel", "pedalBrake", "soh", "lifetimeAvgPhm", "chargeBatteryVolt", "chargingCapacity",
    )

    fun isAnalog(field: String): Boolean = field in ANALOG_FIELDS
}
