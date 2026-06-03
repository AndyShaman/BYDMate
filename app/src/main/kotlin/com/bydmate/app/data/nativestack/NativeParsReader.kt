package com.bydmate.app.data.nativestack

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * ParsReader implementation that fetches live vehicle params from the system
 * autoservice Binder via on-device ADB.
 *
 * Returns null if autoservice is not available (ADB disconnected, firmware
 * without autoservice, or all probe fids return sentinel).
 */
@Singleton
class NativeParsReader @Inject constructor(
    private val autoservice: AutoserviceClient,
    private val settings: SettingsRepository,
) : ParsReader {

    override suspend fun fetch(): DiParsData? {
        if (!autoservice.isAvailable()) return null

        // Decoded values keyed by FidEntry.field.
        val decoded = mutableMapOf<String, Any?>()

        for (entry in FidMap.entries) {
            val value: Any? = when (entry.transact) {
                5 -> {
                    val raw = autoservice.getInt(entry.device, entry.fid)
                    if (raw == null) null
                    else when (entry.decoder) {
                        Decoder.INT_SCALED -> ParamDecoder.decodeScaled(raw, entry.scale)
                        else               -> ParamDecoder.decodeInt(raw, entry.decoder)
                    }
                }
                7 -> {
                    // AutoserviceClient.getFloat already rejects float sentinels (-1.0f, NaN, Inf).
                    // Convert Float back to its raw IEEE-754 bits so ParamDecoder.decodeFloat
                    // can apply its SentinelDecoder path (which also rejects -1.0f etc.).
                    val f = autoservice.getFloat(entry.device, entry.fid)
                    if (f == null) null
                    else ParamDecoder.decodeFloat(java.lang.Float.floatToRawIntBits(f), entry.decoder)
                }
                else -> null
            }
            decoded[entry.field] = value
        }

        // Battery capacity comes from user settings, not from autoservice.
        val batteryCapacityKwh = settings.getBatteryCapacity()

        // ── Domain-level guards ──────────────────────────────────────────────
        // Cell voltages: autoservice raw is in mV scaled to V via INT_SCALED ÷1000.
        // Filter anything ≤ 0.5 V (BMS not reporting).
        @Suppress("UNCHECKED_CAST")
        fun <T> field(name: String): T? = decoded[name] as? T

        val maxCellVoltage = field<Double>("maxCellVoltage")?.takeIf { it > 0.5 }
        val minCellVoltage = field<Double>("minCellVoltage")?.takeIf { it > 0.5 }

        // 12V: autoservice path uses FLOAT_VOLT which returns real volts directly —
        // no dual-unit handling needed. Filter ≤ 0.0 as unavailable.
        val voltage12v = field<Double>("voltage12v")?.takeIf { it > 0.0 }

        val soc = field<Double>("soc")?.toInt()
        val mileage = field<Double>("mileage")

        // Liveness gate: if every primary signal is null, autoservice is unreachable
        // or returning sentinels — treat as a fetch failure so consumers can retry.
        if (soc == null && mileage == null && voltage12v == null) {
            return null
        }

        // Battery temps decode with a -40 offset via INT_TEMP_C_OFS40. avgBatTemp
        // was dropped on the native cutover; restore it as the mean of the two live
        // cell extremes (the dedicated avg fid is a suspected 24h-cached statistic).
        val maxBatTemp = field<Int>("maxBatTemp")
        val minBatTemp = field<Int>("minBatTemp")
        val avgBatTemp = when {
            maxBatTemp != null && minBatTemp != null -> ((maxBatTemp + minBatTemp) / 2.0).roundToInt()
            else -> maxBatTemp ?: minBatTemp
        }

        return DiParsData(
            soc                 = soc,
            speed               = field<Double>("speed")?.toInt(),
            mileage             = mileage,
            power               = field<Int>("power")?.toDouble(),
            chargeGunState      = field<Int>("chargeGunState"),
            maxBatTemp          = maxBatTemp,
            avgBatTemp          = avgBatTemp,
            minBatTemp          = minBatTemp,
            chargingStatus      = null,  // deferred (old fid stuck, new fid not in FidMap yet)
            batteryCapacityKwh  = batteryCapacityKwh,
            totalElecConsumption = field<Double>("totalElecConsumption"),
            voltage12v          = voltage12v,
            maxCellVoltage      = maxCellVoltage,
            minCellVoltage      = minCellVoltage,
            exteriorTemp        = field<Int>("exteriorTemp"),
            gear                = field<Int>("gear"),
            powerState          = field<Int>("powerState"),
            insideTemp          = field<Int>("insideTemp"),
            acStatus            = field<Int>("acStatus"),
            acTemp              = field<Int>("acTemp"),
            fanLevel            = field<Int>("fanLevel"),
            acCirc              = field<Int>("acCirc"),
            doorFL              = field<Int>("doorFL"),
            doorFR              = field<Int>("doorFR"),
            doorRL              = field<Int>("doorRL"),
            doorRR              = field<Int>("doorRR"),
            windowFL            = field<Int>("windowFL"),
            windowFR            = field<Int>("windowFR"),
            windowRL            = field<Int>("windowRL"),
            windowRR            = field<Int>("windowRR"),
            sunroof             = null,  // removed from FidMap: fid returns enum(1=open,2=closed,5=vent) not percent
            trunk               = field<Int>("trunk"),
            hood                = field<Int>("hood"),
            seatbeltFL          = field<Int>("seatbeltFL"),
            lockFL              = field<Int>("lockFL"),
            tirePressFL         = field<Int>("tirePressFL"),
            tirePressFR         = field<Int>("tirePressFR"),
            tirePressRL         = field<Int>("tirePressRL"),
            tirePressRR         = field<Int>("tirePressRR"),
            driveMode           = field<Int>("driveMode"),
            workMode            = field<Int>("workMode"),
            autoPark            = null,  // not in FidMap (unknown source)
            rain                = null,  // not in FidMap (user setting, no live sensor fid)
            lightLow            = field<Int>("lightLow"),
            drl                 = field<Int>("drl"),
        )
    }
}
