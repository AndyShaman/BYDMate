package com.bydmate.app.data.autoservice

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only access to the system autoservice Binder via on-device ADB.
 *
 * Returns null on any error (sentinel, ADB down, parse failure, autoservice
 * unsupported on this firmware). Caller MUST handle null gracefully — do not
 * propagate exceptions for "fid not available".
 */
interface AutoserviceClient {
    /** Best-effort liveness check: ADB connected AND a known fid (SoH) returns a real value. */
    suspend fun isAvailable(): Boolean
    suspend fun getInt(dev: Int, fid: Int): Int?
    suspend fun getFloat(dev: Int, fid: Int): Float?
    suspend fun readBatterySnapshot(): BatteryReading?
    suspend fun readChargingSnapshot(): ChargingReading?
}

@Singleton
class AutoserviceClientImpl @Inject constructor(
    private val adb: AdbOnDeviceClient
) : AutoserviceClient {

    override suspend fun isAvailable(): Boolean {
        // Lazy reconnect: protocol singleton lives in process memory only.
        // After process restart with toggle persisted ON, nothing has called
        // connect() yet — bootstrap here so callers don't need to know.
        if (!adb.isConnected()) {
            val r = adb.connect()
            if (r.isFailure) {
                Log.w(TAG, "isAvailable: connect failed: ${r.exceptionOrNull()?.message}")
                return false
            }
        }
        // SoH is the lightest known-good probe — present on every BMS.
        val soh = getFloat(FidRegistry.DEV_STATISTIC, FidRegistry.FID_SOH)
        if (soh == null) Log.w(TAG, "isAvailable: SoH probe returned null")
        return soh != null
    }

    override suspend fun getInt(dev: Int, fid: Int): Int? {
        val cmd = "service call autoservice ${FidRegistry.TX_GET_INT} i32 $dev i32 $fid"
        val raw = adb.exec(cmd)
        if (raw == null) { Log.w(TAG, "getInt($dev,$fid): exec null"); return null }
        val value = parseParcelInt(raw)
        if (value == null) { Log.w(TAG, "getInt($dev,$fid): parse failed: ${raw.take(160)}"); return null }
        return SentinelDecoder.decodeInt(value)
    }

    override suspend fun getFloat(dev: Int, fid: Int): Float? {
        val cmd = "service call autoservice ${FidRegistry.TX_GET_FLOAT} i32 $dev i32 $fid"
        val raw = adb.exec(cmd)
        if (raw == null) { Log.w(TAG, "getFloat($dev,$fid): exec null"); return null }
        val bits = parseParcelInt(raw)
        if (bits == null) { Log.w(TAG, "getFloat($dev,$fid): parse failed: ${raw.take(160)}"); return null }
        return SentinelDecoder.parseFloatFromShellInt(bits)
    }

    override suspend fun readBatterySnapshot(): BatteryReading? {
        if (!adb.isConnected()) return null
        return BatteryReading(
            sohPercent = getFloat(FidRegistry.DEV_STATISTIC, FidRegistry.FID_SOH),
            socPercent = getFloat(FidRegistry.DEV_STATISTIC, FidRegistry.FID_SOC),
            lifetimeKwh = getFloat(FidRegistry.DEV_STATISTIC, FidRegistry.FID_LIFETIME_KWH),
            lifetimeMileageKm = getFloat(FidRegistry.DEV_STATISTIC, FidRegistry.FID_LIFETIME_MILEAGE),
            voltage12v = getFloat(FidRegistry.DEV_BODYWORK, FidRegistry.FID_OTA_BATTERY_POWER_VOLTAGE),
            readAtMs = System.currentTimeMillis()
        )
    }

    override suspend fun readChargingSnapshot(): ChargingReading? {
        if (!adb.isConnected()) return null
        return ChargingReading(
            gunConnectState = getInt(FidRegistry.DEV_CHARGING, FidRegistry.FID_GUN_CONNECT_STATE),
            chargingType = getInt(FidRegistry.DEV_CHARGING, FidRegistry.FID_CHARGING_TYPE),
            chargeBatteryVoltV = getInt(FidRegistry.DEV_CHARGING, FidRegistry.FID_CHARGE_BATTERY_VOLT),
            batteryType = getInt(FidRegistry.DEV_CHARGING, FidRegistry.FID_BATTERY_TYPE),
            readAtMs = System.currentTimeMillis()
        )
    }

    /**
     * `service call autoservice <tx> i32 <dev> i32 <fid>` produces stdout like:
     *   Result: Parcel(00000000 0000005b   '....[...')
     * The 8-hex-digit token after "Parcel(00000000" is the 32-bit return value.
     */
    private fun parseParcelInt(raw: String): Int? {
        val match = PARCEL_REGEX.find(raw) ?: return null
        return runCatching { match.groupValues[1].toLong(16).toInt() }.getOrNull()
    }

    private companion object {
        const val TAG = "AutoserviceClient"
        val PARCEL_REGEX = Regex("""Parcel\(00000000\s+([0-9a-fA-F]{8})""")
    }
}
