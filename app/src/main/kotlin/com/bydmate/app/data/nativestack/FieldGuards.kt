package com.bydmate.app.data.nativestack

/**
 * The domain guards a decoded reading must pass before it reaches [com.bydmate.app.data.remote.DiParsData],
 * and the one derived value both readers compute.
 *
 * They live here rather than inside the poll loop because the push channel lays the same fids into
 * the same snapshot: a guard that existed on one path only would let a pushed value stand where a
 * polled one is dropped, and the two would disagree about the very same fid.
 */
object FieldGuards {

    /**
     * Physical envelope of the INT_RAW tech-panel fids: the sentinel filter passes any plain
     * number, so each reading is held to a range it can physically be in. -39 is the floor for
     * motor/inverter temps: -40 is what the firmware reports for a motor the car does not have
     * (#186).
     */
    val RANGES: Map<String, IntRange> = mapOf(
        "insulationKohm" to 0..65000,
        "motorTempFront" to -39..150,
        "motorTempRear" to -39..150,
        "inverterTempFront" to -39..150,
        "inverterTempRear" to -39..150,
        "hvVoltage" to 0..1000,
        "bmsMaxDischargeKw" to 0..1000,
        "motorRpmFront" to -20000..20000,
        "motorRpmRear" to -20000..20000,
        "compressorW" to 0..20000,
        "tyreTempFL" to -50..150,
        "tyreTempFR" to -50..150,
        "tyreTempRL" to -50..150,
        "tyreTempRR" to -50..150,
        "pedalAccel" to 0..100,
        "pedalBrake" to 0..100,
    )

    /** [value] as the snapshot holds it, or null when the field has a range and it falls outside. */
    fun int(field: String, value: Int): Int? {
        val range = RANGES[field] ?: return value
        return value.takeIf { it in range }
    }

    /** The same for the fields whose guard is a floor or a magnitude rather than a range. */
    fun double(field: String, value: Double): Double? = when (field) {
        // Cell voltages come in mV scaled to V; anything this low means the BMS is not reporting.
        "maxCellVoltage", "minCellVoltage" -> value.takeIf { it > 0.5 }
        "voltage12v" -> value.takeIf { it > 0.0 }
        "bmsMaxChargeKw" -> value.takeIf { it in 0.0..MAX_BMS_KW }
        "motorCurrentFront", "motorCurrentRear" -> value.takeIf { kotlin.math.abs(it) <= MAX_MOTOR_CURRENT_A }
        else -> value
    }

    /** Instant traction-battery power (#153): + draw, − charge. Null while either factor is absent. */
    fun batteryPowerW(hvVoltage: Int?, hvCurrent: Double?): Double? =
        if (hvVoltage != null && hvCurrent != null) hvVoltage * hvCurrent else null

    private const val MAX_BMS_KW = 1000.0
    private const val MAX_MOTOR_CURRENT_A = 2000.0
}
