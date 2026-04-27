package com.bydmate.app.data.autoservice

/**
 * One snapshot of charging-related autoservice fids.
 *
 * gunConnectState: 1=NONE, 2=AC, 3=DC, 4=GB_DC. Survives DiLink sleep
 * as long as the gun stays physically inserted. Resets to 1 after gun
 * removal.
 *
 * chargingType: handshake result. Resets to 1 (DEFAULT) after charging
 * finalizes — do not rely on this for catch-up classification, use
 * gunConnectState or the kwh/h heuristic.
 *
 * batteryType: 1=IRON/LFP (Leopard 3), 2=NCM. Informational.
 *
 * chargingCapacityKwh: per-session counter from BMS. Persists across DiLink
 * power-cycle; resets on new charging session (gun reconnect or BMS reset).
 * Use for cascade-B/C charge detection. May be null if autoservice is unavailable.
 *
 * bmsState: BMS-reported charging state. 1=CHARGING, 2=FINISH, 13=PAUSE.
 * Other values observed but not used in detection logic.
 */
data class ChargingReading(
    val gunConnectState: Int?,
    val chargingType: Int?,
    val chargeBatteryVoltV: Int?,
    val batteryType: Int?,
    val chargingCapacityKwh: Float?,
    val bmsState: Int?,
    val readAtMs: Long
)
