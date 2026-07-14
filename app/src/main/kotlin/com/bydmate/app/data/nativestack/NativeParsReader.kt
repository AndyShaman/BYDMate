package com.bydmate.app.data.nativestack

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * ParsReader implementation that fetches live vehicle params from the system
 * autoservice Binder, either via the daemon's batched TX_READ_BATCH transport
 * (once [BatchReadGate] has shadow-validated it) or via on-device ADB, one
 * fid at a time.
 *
 * Returns null if neither transport can produce a live snapshot (ADB
 * disconnected, firmware without autoservice, or all probe fids return
 * sentinel).
 */
@Singleton
class NativeParsReader @Inject constructor(
    private val autoservice: AutoserviceClient,
    private val settings: SettingsRepository,
    private val helperClient: HelperClient,
    private val gate: BatchReadGate,
) : ParsReader {

    private val batchItems: List<BatchReadItem> =
        FidMap.entries.map { BatchReadItem(it.transact, it.device, it.fid) }

    override suspend fun fetch(): DiParsData? = when (gate.mode()) {
        BatchMode.ACTIVE -> fetchViaBatch() ?: fetchViaAdb()
        BatchMode.OFF -> fetchViaAdb()
        BatchMode.VALIDATING -> {
            val adb = fetchViaAdb()
            val batchRaw = helperClient.readBatch(batchItems)
            if (batchRaw == null) {
                gate.recordBatchUnavailable()
            } else {
                gate.recordComparison(adb, assembleSnapshot(decodeBatch(batchRaw)))
            }
            adb // the proven path stays primary until promotion
        }
    }

    private suspend fun fetchViaBatch(): DiParsData? {
        val pairs = helperClient.readBatch(batchItems) ?: return null
        return assembleSnapshot(decodeBatch(pairs))
    }

    /**
     * Decodes raw (status, value) pairs with the EXACT pipeline the ADB path uses:
     * status != 0 → null (parseParcelInt only matches status word 00000000);
     * tx=5 → SentinelDecoder.decodeInt then ParamDecoder scaled/int;
     * tx=7 → SentinelDecoder.parseFloatFromShellInt then ParamDecoder.decodeFloat
     * on the raw IEEE-754 bits — mirroring AutoserviceClient.getInt/getFloat +
     * the per-entry decode in fetchViaAdb.
     */
    private fun decodeBatch(pairs: List<Pair<Int, Int>>): Map<String, Any?> {
        val decoded = mutableMapOf<String, Any?>()
        FidMap.entries.forEachIndexed { i, entry ->
            val (status, word) = pairs[i]
            val value: Any? = if (status != 0) null else when (entry.transact) {
                5 -> SentinelDecoder.decodeInt(word)?.let { raw ->
                    when (entry.decoder) {
                        Decoder.INT_SCALED -> ParamDecoder.decodeScaled(raw, entry.scale)
                        else               -> ParamDecoder.decodeInt(raw, entry.decoder)
                    }
                }
                7 -> SentinelDecoder.parseFloatFromShellInt(word)?.let { f ->
                    ParamDecoder.decodeFloat(java.lang.Float.floatToRawIntBits(f), entry.decoder)
                }
                else -> null
            }
            decoded[entry.field] = value
        }
        return decoded
    }

    private suspend fun fetchViaAdb(): DiParsData? {
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

        return assembleSnapshot(decoded)
    }

    /**
     * Shared tail of both transports: settings capacity, domain guards, liveness
     * gate, and DiParsData assembly. Input is the per-field decoded map produced
     * by either the ADB loop or the daemon batch. Extracted in wave L so the two
     * paths cannot drift.
     */
    private suspend fun assembleSnapshot(decoded: Map<String, Any?>): DiParsData? {
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

        // Indirect rain detection: with rain-sensing auto-wipers enabled the wiper
        // relay only fires when the sensor sees water. In manual wiper mode rain is
        // unknowable from this signal, so keep null rather than a false "dry".
        val wiperRelay = field<Int>("wiperRelay")
        val autoWipers = field<Int>("autoWipers")
        val rain = when {
            autoWipers != 1 || wiperRelay == null -> null
            wiperRelay != 0 -> 1
            else -> 0
        }

        // Charging status: refine the gun-connect state with the BMS's own charging
        // flag so "connected" (gun plugged in, BMS not confirming charge) and
        // "charging" (BMS confirms) are told apart. Preserves the existing
        // 0=none/1=connected/2=charging codes the ChargingStatus trigger + template
        // already depend on — only the source of the value changes.
        val chargeGunState = field<Int>("chargeGunState")
        val bmsState = field<Int>("bmsState")
        val chargingStatus = when {
            chargeGunState == null -> null
            // Not plugged in. gun<2 covers both 1=NONE and the 0 cold-start
            // sentinel (SentinelDecoder passes 0 through unfiltered), matching the
            // gun>=2==connected convention every other consumer already uses
            // (LoopState, AgentTools, AutoserviceChargingDetector, Iternio). A bare
            // ==1 would let a cold-start 0 fall through to connected/charging.
            chargeGunState < 2 -> 0
            bmsState == 1 -> 2
            else -> 1
        }

        return DiParsData(
            soc                 = soc,
            speed               = field<Double>("speed")?.toInt(),
            mileage             = mileage,
            power               = field<Int>("power")?.toDouble(),
            chargeGunState      = chargeGunState,
            maxBatTemp          = maxBatTemp,
            avgBatTemp          = avgBatTemp,
            minBatTemp          = minBatTemp,
            chargingStatus      = chargingStatus,  // derived from chargeGunState + bmsState (see above)
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
            rain                = rain,  // derived from wiperRelay + autoWipers (see above)
            lightLow            = field<Int>("lightLow"),
            drl                 = field<Int>("drl"),
            acDefrostFront      = field<Int>("acDefrostFront"),
            acWindMode          = field<Int>("acWindMode"),
            acCtrlMode          = field<Int>("acCtrlMode"),
            seatHeatDriver      = field<Int>("seatHeatDriver"),
            seatVentDriver      = field<Int>("seatVentDriver"),
            seatHeatPassenger   = field<Int>("seatHeatPassenger"),
            seatVentPassenger   = field<Int>("seatVentPassenger"),
            lightSide           = field<Int>("lightSide"),
            lightHigh           = field<Int>("lightHigh"),
            seatbeltFR          = field<Int>("seatbeltFR"),
            occupancyFL         = field<Int>("occupancyFL"),
            occupancyFR         = field<Int>("occupancyFR"),
            occupancyRL         = field<Int>("occupancyRL"),
            occupancyRM         = field<Int>("occupancyRM"),
            occupancyRR         = field<Int>("occupancyRR"),
            lightLevel          = field<Int>("lightLevel"),
            keyBatteryStatus    = field<Int>("keyBatteryStatus"),
            wiperRelay          = wiperRelay,
            autoWipers          = autoWipers,
            bmsState            = bmsState,
        )
    }
}
