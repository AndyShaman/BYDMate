package com.bydmate.app.data.push

import com.bydmate.app.data.nativestack.Decoder
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.nativestack.FidEntry
import com.bydmate.app.data.nativestack.FidMap
import com.bydmate.app.data.nativestack.FieldGuards
import com.bydmate.app.data.nativestack.ParamDecoder
import com.bydmate.app.data.remote.DiParsData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Lays a pushed fid value into the live snapshot. The decoder and the domain guard are the ones
 * the poll already uses for that field ([FidMap] plus [FieldGuards]), so a pushed value and a
 * polled value of the same fid can never disagree about units or about what counts as garbage.
 *
 * Every READ address the app knows is subscribed, but only the fields the poll assigns straight
 * into [DiParsData] are patched here. The rest reach their consumers through the event flow and
 * the dump: fields with no snapshot property at all (bsdLeft/bsdRight, the seat candidates, the
 * one-shot snapshots), and the fields the poll derives something else from — driveMode (held
 * through a switch), chargeGunState/bmsState (chargingStatus), wiperRelay/autoWipers (rain),
 * maxBatTemp/minBatTemp (avgBatTemp) and windowRRGen3 (the generation fallback). For those the
 * poll stays the only writer.
 */
object FidPushApplier {

    /** Every field of [FidMap], in the order the map declares them. */
    val PUSH_FIELDS: List<String> = FidMap.all.map { it.field }

    /**
     * Returns the patched snapshot, or null when the field is not patched from push or the value
     * did not survive its decoder or guard (sentinel, out of range) — in both cases the poll stays
     * the last word.
     */
    fun apply(data: DiParsData, field: String, intValue: Int, doubleValue: Double): DiParsData? {
        val entry = FidMap.byField[field] ?: return null
        INT_SETTERS[field]?.let { setter ->
            val value = decodeInt(entry, intValue) ?: return null
            return setter(data, value)
        }
        DOUBLE_SETTERS[field]?.let { setter ->
            val value = decodeDouble(entry, intValue, doubleValue) ?: return null
            return setter(data, value)
        }
        return null
    }

    /**
     * Folds a pushed value into the live snapshot and reports whether it changed anything. The
     * patch is built inside [MutableStateFlow.update], so a poll publishing a fresher snapshot
     * while we decode is re-read instead of being rolled back to the one we started from.
     */
    fun patch(
        flow: MutableStateFlow<DiParsData?>,
        field: String,
        intValue: Int,
        doubleValue: Double,
    ): Boolean {
        var applied = false
        flow.update { current ->
            applied = false
            if (current == null) {
                current
            } else {
                apply(current, field, intValue, doubleValue)?.also { applied = true } ?: current
            }
        }
        return applied
    }

    /** tx 5 int decode plus the field's guard — the tail of NativeParsReader.decodeTx5. */
    private fun decodeInt(entry: FidEntry, rawInt: Int): Int? =
        ParamDecoder.decodeInt(rawInt, entry.decoder)?.let { FieldGuards.int(entry.field, it) }

    /**
     * The double-valued fields. tx 7 carries the reading in the event's doubleValue,
     * which holds the very float the poll reads with transact 7 — converting it back to its
     * IEEE-754 bits runs it through the same sentinel filter and the same decoder. tx 5 fields
     * that decode to a double are INT_SCALED, and their scale is the one in force for the
     * address the subscription was built from.
     */
    private fun decodeDouble(entry: FidEntry, rawInt: Int, doubleValue: Double): Double? {
        val decoded = when {
            entry.transact == 7 ->
                ParamDecoder.decodeFloat(
                    java.lang.Float.floatToRawIntBits(doubleValue.toFloat()),
                    entry.decoder,
                )
            entry.decoder == Decoder.INT_SCALED ->
                ParamDecoder.decodeScaled(rawInt, FidAddresses.scale(entry.field))
            else -> null
        }
        return decoded?.let { FieldGuards.double(entry.field, it) }
    }

    /** Fields the poll assigns from a decoded int, and where each one goes. */
    private val INT_SETTERS: Map<String, (DiParsData, Int) -> DiParsData> = mapOf(
        // Core drive + energy
        "power" to { d, v -> d.copy(power = v.toDouble()) },
        "gear" to { d, v -> d.copy(gear = v) },
        "powerState" to { d, v -> d.copy(powerState = v) },
        "workMode" to { d, v -> d.copy(workMode = v) },
        // Climate
        "acStatus" to { d, v -> d.copy(acStatus = v) },
        "acTemp" to { d, v -> d.copy(acTemp = v) },
        "fanLevel" to { d, v -> d.copy(fanLevel = v) },
        "acCirc" to { d, v -> d.copy(acCirc = v) },
        "acDefrostFront" to { d, v -> d.copy(acDefrostFront = v) },
        "acWindMode" to { d, v -> d.copy(acWindMode = v) },
        "acCtrlMode" to { d, v -> d.copy(acCtrlMode = v) },
        "insideTemp" to { d, v -> d.copy(insideTemp = v) },
        "exteriorTemp" to { d, v -> d.copy(exteriorTemp = v) },
        "compressorW" to { d, v -> d.copy(compressorW = v) },
        // Seats
        "seatHeatDriver" to { d, v -> d.copy(seatHeatDriver = v) },
        "seatVentDriver" to { d, v -> d.copy(seatVentDriver = v) },
        "seatHeatPassenger" to { d, v -> d.copy(seatHeatPassenger = v) },
        "seatVentPassenger" to { d, v -> d.copy(seatVentPassenger = v) },
        // Body
        "doorFL" to { d, v -> d.copy(doorFL = v) },
        "doorFR" to { d, v -> d.copy(doorFR = v) },
        "doorRL" to { d, v -> d.copy(doorRL = v) },
        "doorRR" to { d, v -> d.copy(doorRR = v) },
        "windowFL" to { d, v -> d.copy(windowFL = v) },
        "windowFR" to { d, v -> d.copy(windowFR = v) },
        "windowRL" to { d, v -> d.copy(windowRL = v) },
        "windowRR" to { d, v -> d.copy(windowRR = v) },
        "sunroof" to { d, v -> d.copy(sunroof = v) },
        "trunk" to { d, v -> d.copy(trunk = v) },
        "frontTrunk" to { d, v -> d.copy(frontTrunk = v) },
        "hood" to { d, v -> d.copy(hood = v) },
        "lockFL" to { d, v -> d.copy(lockFL = v) },
        // Lights
        "lightLow" to { d, v -> d.copy(lightLow = v) },
        "lightHigh" to { d, v -> d.copy(lightHigh = v) },
        "lightSide" to { d, v -> d.copy(lightSide = v) },
        "drl" to { d, v -> d.copy(drl = v) },
        "turnSignal" to { d, v -> d.copy(turnSignal = v) },
        "lightLevel" to { d, v -> d.copy(lightLevel = v) },
        // Cabin sensors
        "seatbeltFL" to { d, v -> d.copy(seatbeltFL = v) },
        "seatbeltFR" to { d, v -> d.copy(seatbeltFR = v) },
        "occupancyFL" to { d, v -> d.copy(occupancyFL = v) },
        "occupancyFR" to { d, v -> d.copy(occupancyFR = v) },
        "occupancyRL" to { d, v -> d.copy(occupancyRL = v) },
        "occupancyRM" to { d, v -> d.copy(occupancyRM = v) },
        "occupancyRR" to { d, v -> d.copy(occupancyRR = v) },
        "keyBatteryStatus" to { d, v -> d.copy(keyBatteryStatus = v) },
        // Tires
        "tirePressFL" to { d, v -> d.copy(tirePressFL = v) },
        "tirePressFR" to { d, v -> d.copy(tirePressFR = v) },
        "tirePressRL" to { d, v -> d.copy(tirePressRL = v) },
        "tirePressRR" to { d, v -> d.copy(tirePressRR = v) },
        "tyreTempFL" to { d, v -> d.copy(tyreTempFL = v) },
        "tyreTempFR" to { d, v -> d.copy(tyreTempFR = v) },
        "tyreTempRL" to { d, v -> d.copy(tyreTempRL = v) },
        "tyreTempRR" to { d, v -> d.copy(tyreTempRR = v) },
        // Tech panel
        "insulationKohm" to { d, v -> d.copy(insulationKohm = v) },
        "motorTempFront" to { d, v -> d.copy(motorTempFront = v) },
        "motorTempRear" to { d, v -> d.copy(motorTempRear = v) },
        "inverterTempFront" to { d, v -> d.copy(inverterTempFront = v) },
        "inverterTempRear" to { d, v -> d.copy(inverterTempRear = v) },
        // batteryPowerW is the product of the two: a patched factor recomputes it, or the card
        // would show a power that belongs to neither reading.
        "hvVoltage" to { d, v ->
            d.copy(hvVoltage = v, batteryPowerW = FieldGuards.batteryPowerW(v, d.hvCurrent))
        },
        "bmsMaxDischargeKw" to { d, v -> d.copy(bmsMaxDischargeKw = v) },
        "motorRpmFront" to { d, v -> d.copy(motorRpmFront = v) },
        "motorRpmRear" to { d, v -> d.copy(motorRpmRear = v) },
        "pedalAccel" to { d, v -> d.copy(pedalAccel = v) },
        "pedalBrake" to { d, v -> d.copy(pedalBrake = v) },
        // Charging plug diagnostics (no logic depends on them yet)
        "chargerConnectState" to { d, v -> d.copy(chargerConnectState = v) },
        "chargeConnectIndicator" to { d, v -> d.copy(chargeConnectIndicator = v) },
    )

    /** Fields the poll assigns from a decoded double (tx 7, or tx 5 with a scale). */
    private val DOUBLE_SETTERS: Map<String, (DiParsData, Double) -> DiParsData> = mapOf(
        "soc" to { d, v -> d.copy(soc = v.toInt()) },
        "speed" to { d, v -> d.copy(speed = v.toInt()) },
        "mileage" to { d, v -> d.copy(mileage = v) },
        "totalElecConsumption" to { d, v -> d.copy(totalElecConsumption = v) },
        "voltage12v" to { d, v -> d.copy(voltage12v = v) },
        "maxCellVoltage" to { d, v -> d.copy(maxCellVoltage = v) },
        "minCellVoltage" to { d, v -> d.copy(minCellVoltage = v) },
        "hvCurrent" to { d, v ->
            d.copy(hvCurrent = v, batteryPowerW = FieldGuards.batteryPowerW(d.hvVoltage, v))
        },
        "bmsMaxChargeKw" to { d, v -> d.copy(bmsMaxChargeKw = v) },
        "motorCurrentFront" to { d, v -> d.copy(motorCurrentFront = v.toFloat()) },
        "motorCurrentRear" to { d, v -> d.copy(motorCurrentRear = v.toFloat()) },
    )
}
