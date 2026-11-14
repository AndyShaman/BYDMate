package com.bydmate.app.ui.tech

import com.bydmate.app.data.dtc.DtcBlockName
import com.bydmate.app.data.dtc.DtcDescription
import com.bydmate.app.data.dtc.DtcErasePhase
import com.bydmate.app.data.dtc.DtcEraseState
import com.bydmate.app.data.dtc.DtcFound
import com.bydmate.app.data.dtc.DtcParser
import com.bydmate.app.data.dtc.DtcScanPhase
import com.bydmate.app.data.dtc.DtcScanState
import com.bydmate.app.data.dtc.DtcScanSummary
import com.bydmate.app.data.dtc.DtcSendResult
import com.bydmate.app.data.nativestack.VehicleKind

/** Caption of the «Сканировать» button. */
enum class DtcScanLabel { SCAN, SCANNING, ONLY_PARK }

/**
 * Live state of the «Техника» screen. Everything vehicle-side rides the poller that already
 * feeds the Dashboard (TrackingService.lastData) — this screen adds no second loop. A null
 * value is rendered as «—»; a card whose every value is null is not drawn at all, which is
 * what firmwares outside Leopard 3 will mostly produce.
 */
data class TechPanelUiState(
    val autoserviceOnline: Boolean? = null,
    /** Key of the single open «?» hint, null when none is open. */
    val openHint: String? = null,
    /** Cards in the order they are drawn, hidden ones included. */
    val cardOrder: List<TechCard> = TechCardOrder.DEFAULT,
    /** Shown until the driver drags a card for the first time. */
    val showOrderHint: Boolean = false,
    // Батарея · сейчас
    val soc: Int? = null,
    /** Energy left in the pack as the BMS reports it, kWh — not derived from SoC. */
    val remainKwh: Double? = null,
    val soh: Float? = null,
    val batTemp: Int? = null,
    /** Coldest and warmest spot of the pack, °C; null on cars that report only the average. */
    val batTempMin: Int? = null,
    val batTempMax: Int? = null,
    val hvVoltage: Int? = null,
    val powerKw: Double? = null,
    val batteryPowerW: Double? = null,
    val batteryCapacityKwh: Double? = null,
    val voltage12v: Double? = null,
    val insulationKohm: Int? = null,
    // Лимиты BMS + Ячейки
    val bmsMaxChargeKw: Double? = null,
    val bmsMaxDischargeAllowKw: Double? = null,
    val bmsMaxDischargeKw: Int? = null,
    val cellMin: Double? = null,
    val cellMax: Double? = null,
    // Моторы и инверторы
    val motorTempFront: Int? = null,
    val motorTempRear: Int? = null,
    val inverterTempFront: Int? = null,
    val inverterTempRear: Int? = null,
    val motorRpmFront: Int? = null,
    val motorRpmRear: Int? = null,
    val motorCurrentFront: Float? = null,
    val motorCurrentRear: Float? = null,
    val pedalAccel: Int? = null,
    val pedalBrake: Int? = null,
    // Климат
    val compressorW: Int? = null,
    val acStatus: Int? = null,
    val insideTemp: Int? = null,
    val exteriorTemp: Int? = null,
    // Шины
    val tirePressFL: Int? = null,
    val tirePressFR: Int? = null,
    val tirePressRL: Int? = null,
    val tirePressRR: Int? = null,
    val tyreTempFL: Int? = null,
    val tyreTempFR: Int? = null,
    val tyreTempRL: Int? = null,
    val tyreTempRR: Int? = null,
    // Батарея · история
    val lifetimeKm: Float? = null,
    val lifetimeKwh: Float? = null,
    val avgSocSinceCharge: Int? = null,
    val avgSocAllTime: Int? = null,
    /** Everything the app has ever seen charged into the pack, kWh; null until the first session. */
    val lifetimeChargedKwh: Double? = null,
    /** Pack size from settings, the divisor of the full-cycle count. */
    val nominalCapacityKwh: Double = 0.0,
    /** End of the latest logged charge that reached 100 % (#359); null when none is known. */
    val lastFullChargeTs: Long? = null,
    /** End of the latest logged 9 %-or-lower to 100 % charge; null when none is known. */
    val lastCalibrationTs: Long? = null,
    // DM-i hybrid (#184): the car's own fuel readings, polled only on a confirmed hybrid.
    val vehicleKind: VehicleKind = VehicleKind.UNKNOWN,
    val engineRpm: Int? = null,
    val fuelPercent: Int? = null,
    val fuelRangeKm: Int? = null,
    val evRangeKm: Int? = null,
    val mileageHevKm: Int? = null,
    val mileageEvKm: Int? = null,
    val fuelTotalL: Double? = null,
    // Состояние машины: PM2.5 (value, then the sensor's own 0..6 level)
    val pm25In: Int? = null,
    val pm25Out: Int? = null,
    val pm25LevelIn: Int? = null,
    val pm25LevelOut: Int? = null,
    // Диагностика: stock car status provider
    val faultCount: Int? = null,
    val serviceDaysLeft: Int? = null,
    val serviceKmLeft: Int? = null,
    // Диагностика: manual block-fault scan
    val gear: Int? = null,
    val serviceRunning: Boolean = false,
    val dtcScan: DtcScanState = DtcScanState(),
    /** The last finished scan, parsed; null before the first one. */
    val dtcSummary: DtcScanSummary? = null,
    /** When [dtcSummary] was taken, epoch ms. */
    val dtcCheckedAt: Long? = null,
    /** Descriptions of [dtcSummary]'s codes that have one, by model + block + code. */
    val dtcDescriptions: Map<DtcFound, DtcDescription> = emptyMap(),
    val dtcDialogOpen: Boolean = false,
    /** «Отправить в Telegram» shows only with a bot bound; read when the dialog opens. */
    val dtcTelegramReady: Boolean = false,
    val dtcSending: Boolean = false,
    /** Result of the last send from the open dialog, null until one is made. */
    val dtcSendResult: DtcSendResult? = null,
    /** The last erase of this process: progress, then a result per block. */
    val dtcErase: DtcEraseState? = null,
    /** When the last erase whose outcome is unknown ended, epoch ms. */
    val dtcErasedAt: Long? = null,
    /** Block name per address of the saved scan, rows or not (erase result lines need them). */
    val dtcBlocks: Map<Int, DtcBlockName> = emptyMap(),
    /** Blocks of unknown erase result since the saved scan: no erase of them until a new scan. */
    val dtcEraseLocked: Set<Int> = emptySet(),
) {
    val dtcErasing: Boolean
        get() = dtcErase?.phase == DtcErasePhase.ERASING

    /** The scan button is enabled on a running service in P and while no scan or erase runs. */
    val canScan: Boolean
        get() = gear == GEAR_PARK && serviceRunning && dtcScan.phase != DtcScanPhase.SCANNING && !dtcErasing

    /** An erase of unknown outcome ran after the saved scan: its list may be out of date until a new scan. */
    val dtcErasedSinceScan: Boolean
        get() = dtcErasedAt != null && dtcCheckedAt != null && dtcErasedAt >= dtcCheckedAt

    /** The erase buttons show with an erasable block on display and no erase of unknown outcome since the scan. */
    val showErase: Boolean
        get() = dtcSummary?.found.orEmpty().any { canEraseBlock(it.addr) } && !dtcErasedSinceScan

    /** False when the group of [addr] (a merged one included) holds a block of [dtcEraseLocked]. */
    fun canEraseBlock(addr: Int): Boolean {
        val group = DtcParser.groups(dtcSummary?.found.orEmpty()).firstOrNull { addr in it.addrs }?.addrs ?: listOf(addr)
        return group.none { it in dtcEraseLocked }
    }

    val scanLabel: DtcScanLabel
        get() = when {
            dtcScan.phase == DtcScanPhase.SCANNING -> DtcScanLabel.SCANNING
            canScan -> DtcScanLabel.SCAN
            else -> DtcScanLabel.ONLY_PARK
        }

    /** PM2.5 value to draw: only with a valid level 1..6, null otherwise. */
    val pm25InShown: Int?
        get() = pm25In?.takeIf { pm25LevelIn in PM25_LEVELS }
    val pm25OutShown: Int?
        get() = pm25Out?.takeIf { pm25LevelOut in PM25_LEVELS }

    /** True when the «До ТО» row has at least one half to show. */
    val hasServiceRow: Boolean
        get() = serviceDaysLeft != null || serviceKmLeft != null

    val isHybrid: Boolean
        get() = vehicleKind == VehicleKind.HYBRID

    val cellDelta: Double?
        get() = if (cellMin != null && cellMax != null) cellMax - cellMin else null

    /**
     * Share of the pack drained (or filled) per hour at the current power. 1% of the pack is
     * capacity/100 kWh = capacity×10 W·h, so W / (capacity×10) is %/h. Needs the capacity the
     * app already knows from settings — never a guessed pack size.
     */
    val percentPerHour: Double?
        get() = if (batteryPowerW != null && batteryCapacityKwh != null && batteryCapacityKwh > 0.0) {
            batteryPowerW / (batteryCapacityKwh * 10.0)
        } else null

    /** Min to max next to the average, as in the Telegram report: only when both are known and differ. */
    val batTempRange: Pair<Int, Int>?
        get() {
            if (batTemp == null) return null
            val min = batTempMin ?: return null
            val max = batTempMax ?: return null
            return if (min != max) min to max else null
        }

    val showBatteryNow: Boolean
        get() = anyOf(
            soc, remainKwh, soh, batTemp, hvVoltage, powerKw, batteryPowerW,
            voltage12v, insulationKohm,
        )
    val showLimitsAndCells: Boolean
        get() = anyOf(bmsMaxChargeKw, bmsMaxDischargeAllowKw, bmsMaxDischargeKw, cellMin, cellMax)
    val showMotors: Boolean
        get() = anyOf(
            motorTempFront, motorTempRear, inverterTempFront, inverterTempRear,
            motorRpmFront, motorRpmRear, pedalAccel, pedalBrake,
            motorCurrentFront, motorCurrentRear, engineRpm,
        )
    val showClimate: Boolean
        get() = anyOf(compressorW, acStatus, insideTemp, exteriorTemp, pm25InShown, pm25OutShown)
    val showFuel: Boolean
        get() = isHybrid && anyOf(
            fuelPercent, fuelRangeKm, evRangeKm, engineRpm, mileageHevKm, mileageEvKm, fuelTotalL,
        )
    val showTyres: Boolean
        get() = anyOf(
            tirePressFL, tirePressFR, tirePressRL, tirePressRR,
            tyreTempFL, tyreTempFR, tyreTempRL, tyreTempRR,
        )
    /** Provider rows, or a car that reports its gear (the scan needs it), or a saved scan. */
    val showDiagnostics: Boolean
        get() = faultCount != null || hasServiceRow || gear != null || dtcSummary != null
    val showHistory: Boolean
        get() = anyOf(
            lifetimeKm, lifetimeKwh, avgSocSinceCharge, avgSocAllTime,
            // The «Полных циклов» row lives here; a car that logged charges but answers no
            // lifetime counter still has a number to show. A zero sum has nothing to divide.
            lifetimeChargedKwh?.takeIf { it > 0.0 },
            lastFullChargeTs, lastCalibrationTs,
        )

    /** [cardOrder] without the cards this car has no data for. */
    val visibleCards: List<TechCard>
        get() = cardOrder.filter { isVisible(it) }

    fun isVisible(card: TechCard): Boolean = when (card) {
        TechCard.BATTERY_NOW -> showBatteryNow
        TechCard.LIMITS -> showLimitsAndCells
        TechCard.HISTORY -> showHistory
        TechCard.MOTORS -> showMotors
        TechCard.CAR_STATE -> showClimate || showTyres
        TechCard.FUEL -> showFuel
        TechCard.DIAGNOSTICS -> showDiagnostics
    }

    val hasAnyCard: Boolean
        get() = showBatteryNow || showLimitsAndCells || showMotors ||
            showClimate || showTyres || showHistory || showFuel || showDiagnostics

    private fun anyOf(vararg values: Any?): Boolean = values.any { it != null }

    private companion object {
        /** The sensor's own grades; 0 is its "invalid", so a car without the sensor shows nothing. */
        val PM25_LEVELS = 1..6
        const val GEAR_PARK = 1
    }
}

/**
 * Standing still, the front motor's fid answers -1 rather than 0 (seen on two consecutive
 * frames on the car). That is idle, not motion, so anything in -1..0 reads as a plain 0.
 * Reverse spins the motors properly negative (hundreds of rpm), which passes through.
 */
internal fun rpmForDisplay(raw: Int?): Int? = raw?.let { if (it in -1..0) 0 else it }

/**
 * The car never reports the inverter below 30° (#403): on a cold start at 4° outside with the
 * motors at 8-11° both inverters read 30, and in a drive they rise to 36-37 and fall back to 30.
 * So 30 means "30 or colder" and shows as unknown; anything else passes through.
 */
internal fun inverterTempForDisplay(raw: Int?): Int? = raw?.takeIf { it != INVERTER_TEMP_FLOOR_C }

private const val INVERTER_TEMP_FLOOR_C = 30

/**
 * Some cars never fill the remaining-energy fid and answer a flat 0 (a hybrid at SoC 39%, no push
 * event in 12 minutes). A pack that still holds charge cannot be empty, so 0 next to a SoC above
 * zero is "no data" and renders as «—». Nothing is derived from SoC in its place.
 */
internal fun remainKwhForDisplay(remainKwh: Double?, soc: Int?): Double? =
    remainKwh?.takeUnless { it <= 0.0 && soc != null && soc > 0 }
