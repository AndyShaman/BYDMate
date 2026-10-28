package com.bydmate.app.ui.tech

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.autoservice.Pm25Reader
import com.bydmate.app.data.carstatus.CarStatusReader
import com.bydmate.app.data.dtc.DtcScanRepository
import com.bydmate.app.data.dtc.DtcScanState
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.nativestack.VehicleKindMonitor
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.AvgSocProvider
import com.bydmate.app.domain.battery.BatteryCalibration
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

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
) {
    /** The scan button is enabled on a running service in P and while no scan runs. */
    val canScan: Boolean
        get() = false

    val scanLabel: DtcScanLabel
        get() = DtcScanLabel.SCAN

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
    /** Task A: the provider rows only; the block-fault scan widens this. */
    val showDiagnostics: Boolean
        get() = faultCount != null || hasServiceRow
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
    }
}

/**
 * Standing still, the front motor's fid answers -1 rather than 0 (seen on two consecutive
 * frames on the car). That is idle, not motion, so anything in -1..0 reads as a plain 0.
 * Reverse spins the motors properly negative (hundreds of rpm), which passes through.
 */
internal fun rpmForDisplay(raw: Int?): Int? = raw?.let { if (it in -1..0) 0 else it }

/**
 * Some cars never fill the remaining-energy fid and answer a flat 0 (a hybrid at SoC 39%, no push
 * event in 12 minutes). A pack that still holds charge cannot be empty, so 0 next to a SoC above
 * zero is "no data" and renders as «—». Nothing is derived from SoC in its place.
 */
internal fun remainKwhForDisplay(remainKwh: Double?, soc: Int?): Double? =
    remainKwh?.takeUnless { it <= 0.0 && soc != null && soc > 0 }

@HiltViewModel
@Suppress("LongParameterList", "UnusedPrivateProperty") // Hilt constructor; the scan card reads the repository next
class TechPanelViewModel @Inject constructor(
    private val batteryStateRepository: BatteryStateRepository,
    private val avgSocProvider: AvgSocProvider,
    private val settingsRepository: SettingsRepository,
    private val chargeRepository: ChargeRepository,
    private val vehicleKindMonitor: VehicleKindMonitor,
    private val carStatusReader: CarStatusReader,
    private val dtcScanRepository: DtcScanRepository,
    private val pm25Reader: Pm25Reader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TechPanelUiState())
    val uiState: StateFlow<TechPanelUiState> = _uiState.asStateFlow()

    init {
        observeLiveData()
        viewModelScope.launch { loadBatteryState() }
        viewModelScope.launch { loadAvgSoc() }
        viewModelScope.launch { loadCardOrder() }
        viewModelScope.launch { loadFullCycles() }
        observeCalibrationMarks()
        observeCarStatus()
        viewModelScope.launch {
            vehicleKindMonitor.kind.collect { kind ->
                _uiState.update { it.copy(vehicleKind = kind, cardOrder = TechCardOrder.forKind(it.cardOrder, kind)) }
            }
        }
    }

    /** One open hint at a time: tapping the open one closes it, any other replaces it. */
    fun toggleHint(key: String) {
        _uiState.update { it.copy(openHint = if (it.openHint == key) null else key) }
    }

    /**
     * Drops [moved] into [target]'s place and persists the new order. The first completed drag
     * also retires the hint: the driver has just proved they know the gesture. Later drags leave
     * the flag alone — it is already set.
     */
    fun moveCard(moved: TechCard, target: TechCard) {
        val next = TechCardOrder.move(_uiState.value.cardOrder, moved, target)
        if (next == _uiState.value.cardOrder) return
        val retireHint = _uiState.value.showOrderHint
        _uiState.update { it.copy(cardOrder = next, showOrderHint = false) }
        viewModelScope.launch {
            settingsRepository.setTechCardOrder(TechCardOrder.serialize(next))
            if (retireHint) settingsRepository.setTechOrderHintSeen()
        }
    }

    private suspend fun loadCardOrder() {
        val order = TechCardOrder.parse(settingsRepository.getTechCardOrder())
        val hintSeen = settingsRepository.isTechOrderHintSeen()
        _uiState.update {
            it.copy(cardOrder = TechCardOrder.forKind(order, it.vehicleKind), showOrderHint = !hintSeen)
        }
    }

    private fun observeLiveData() {
        viewModelScope.launch {
            // The header's online cue must follow the transport, not a single read taken when
            // the screen opened: a car that drops off the bus keeps showing its last numbers,
            // and coming back has to be visible too (same pairing DashboardViewModel uses).
            TrackingService.lastData
                .combine(TrackingService.vehicleDataConnected) { data, connected -> data to connected }
                .collect { (data, connected) ->
                    _uiState.update {
                        it.copy(
                            autoserviceOnline = connected,
                            soc = data?.soc,
                            remainKwh = remainKwhForDisplay(data?.batteryRemainKwh, data?.soc),
                            batTemp = data?.avgBatTemp,
                            hvVoltage = data?.hvVoltage,
                            powerKw = data?.power,
                            batteryPowerW = data?.batteryPowerW,
                            batteryCapacityKwh = data?.batteryCapacityKwh,
                            voltage12v = data?.voltage12v,
                            insulationKohm = data?.insulationKohm,
                            bmsMaxChargeKw = data?.bmsMaxChargeKw,
                            bmsMaxDischargeAllowKw = data?.bmsMaxDischargeAllowKw,
                            bmsMaxDischargeKw = data?.bmsMaxDischargeKw,
                            cellMin = data?.minCellVoltage,
                            cellMax = data?.maxCellVoltage,
                            motorTempFront = data?.motorTempFront,
                            motorTempRear = data?.motorTempRear,
                            inverterTempFront = data?.inverterTempFront,
                            inverterTempRear = data?.inverterTempRear,
                            motorRpmFront = data?.motorRpmFront,
                            motorRpmRear = data?.motorRpmRear,
                            motorCurrentFront = data?.motorCurrentFront,
                            motorCurrentRear = data?.motorCurrentRear,
                            pedalAccel = data?.pedalAccel,
                            pedalBrake = data?.pedalBrake,
                            compressorW = data?.compressorW,
                            acStatus = data?.acStatus,
                            insideTemp = data?.insideTemp,
                            exteriorTemp = data?.exteriorTemp,
                            tirePressFL = data?.tirePressFL,
                            tirePressFR = data?.tirePressFR,
                            tirePressRL = data?.tirePressRL,
                            tirePressRR = data?.tirePressRR,
                            tyreTempFL = data?.tyreTempFL,
                            tyreTempFR = data?.tyreTempFR,
                            tyreTempRL = data?.tyreTempRL,
                            tyreTempRR = data?.tyreTempRR,
                            engineRpm = data?.engineRpm,
                            fuelPercent = data?.fuelPercent,
                            fuelRangeKm = data?.fuelRangeKm,
                            evRangeKm = data?.evRangeKm,
                            mileageHevKm = data?.mileageHevKm,
                            mileageEvKm = data?.mileageEvKm,
                            fuelTotalL = data?.fuelTotalL,
                            pm25In = data?.pm25In, pm25Out = data?.pm25Out,
                            pm25LevelIn = data?.pm25LevelIn, pm25LevelOut = data?.pm25LevelOut,
                        )
                    }
                }
        }
    }

    /**
     * SoH and the lifetime counters are not in the poller batch — one read on open. The read
     * may only pull the online cue down to false (autoservice did not answer at all); it never
     * pins it to true, that stays the live transport flag's job.
     */
    private suspend fun loadBatteryState() {
        val state = runCatching { batteryStateRepository.refresh() }.getOrNull()
        _uiState.update {
            if (state == null || !state.autoserviceAvailable) {
                it.copy(autoserviceOnline = false)
            } else {
                it.copy(
                    soh = state.sohPercent,
                    lifetimeKm = state.lifetimeKm,
                    lifetimeKwh = state.lifetimeKwh,
                )
            }
        }
    }

    /**
     * Lifetime charged kWh for the «Полных циклов» row — the app's own sum of logged sessions,
     * read once on open. Only the fallback divisor: a car that reports a BMS lifetime counter
     * uses that instead, and one with neither keeps the dash.
     */
    private suspend fun loadFullCycles() {
        val nominal = runCatching { settingsRepository.getBatteryCapacity() }.getOrNull() ?: return
        val stats = runCatching { chargeRepository.getLifetimeStats() }.getOrNull()
        _uiState.update {
            it.copy(lifetimeChargedKwh = stats?.totalKwhAdded, nominalCapacityKwh = nominal)
        }
    }

    /**
     * Last full charge and last calibration off the app's own charge log, kept live: the unplug
     * catch-up writes the row up to ~30 s after the gun comes out, often with this screen open.
     */
    private fun observeCalibrationMarks() {
        viewModelScope.launch {
            chargeRepository.getAllCharges()
                .map { charges -> BatteryCalibration.marks(charges) to charges.size }
                .catch { Log.w(TAG, "calibration marks failed: ${it.message}") }
                .collect { (marks, rows) ->
                    Log.d(TAG, "calibration marks: full=${marks.lastFullChargeTs} cal=${marks.lastCalibrationTs} rows=$rows")
                    _uiState.update {
                        it.copy(lastFullChargeTs = marks.lastFullChargeTs, lastCalibrationTs = marks.lastCalibrationTs)
                    }
                }
        }
    }

    /**
     * Warnings and the service countdown off the stock car status provider: one read on open,
     * then a re-read per change of a watched key. A car without the provider gets no observer.
     */
    private fun observeCarStatus() {
        suspend fun apply(): Boolean {
            val status = runCatching { carStatusReader.read() }.getOrNull()
            _uiState.update {
                it.copy(faultCount = status?.faults, serviceDaysLeft = status?.daysLeft, serviceKmLeft = status?.kmLeft)
            }
            return status != null
        }
        viewModelScope.launch {
            if (!apply()) return@launch
            carStatusReader.changes().collect { apply() }
        }
        logCarStateChanges()
    }

    /**
     * One line per change of what the car-state rows show. PM2.5 enters by level and visibility
     * only: the raw value moves every tick on a car with the sensor, and is in the dump anyway.
     */
    private fun logCarStateChanges() {
        viewModelScope.launch {
            _uiState
                .map { s ->
                    listOf(
                        s.faultCount, s.serviceDaysLeft, s.serviceKmLeft,
                        s.pm25LevelIn, s.pm25LevelOut, s.pm25InShown != null, s.pm25OutShown != null,
                    ) to s
                }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (_, s) ->
                    Log.i(
                        TAG,
                        "carState: faults=${s.faultCount} days=${s.serviceDaysLeft} km=${s.serviceKmLeft} " +
                            "pm25=${s.pm25In}/${s.pm25Out} lvl ${s.pm25LevelIn}/${s.pm25LevelOut}",
                    )
                }
        }
    }

    private suspend fun loadAvgSoc() {
        val avg = runCatching { avgSocProvider.compute() }.getOrNull() ?: return
        _uiState.update {
            it.copy(avgSocSinceCharge = avg.sinceCharge, avgSocAllTime = avg.allTime)
        }
    }

    private companion object {
        const val TAG = "TechPanel"
    }
}
