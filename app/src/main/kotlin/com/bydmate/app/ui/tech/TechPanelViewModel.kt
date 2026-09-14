package com.bydmate.app.ui.tech

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.AvgSocProvider
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

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
) {
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
        get() = anyOf(soc, soh, batTemp, hvVoltage, powerKw, batteryPowerW, voltage12v, insulationKohm)
    val showLimitsAndCells: Boolean
        get() = anyOf(bmsMaxChargeKw, bmsMaxDischargeKw, cellMin, cellMax)
    val showMotors: Boolean
        get() = anyOf(
            motorTempFront, motorTempRear, inverterTempFront, inverterTempRear,
            motorRpmFront, motorRpmRear, pedalAccel, pedalBrake,
            motorCurrentFront, motorCurrentRear,
        )
    val showClimate: Boolean
        get() = anyOf(compressorW, acStatus, insideTemp, exteriorTemp)
    val showTyres: Boolean
        get() = anyOf(
            tirePressFL, tirePressFR, tirePressRL, tirePressRR,
            tyreTempFL, tyreTempFR, tyreTempRL, tyreTempRR,
        )
    val showHistory: Boolean
        get() = anyOf(lifetimeKm, lifetimeKwh, avgSocSinceCharge, avgSocAllTime)

    /** [cardOrder] without the cards this car has no data for. */
    val visibleCards: List<TechCard>
        get() = cardOrder.filter { isVisible(it) }

    fun isVisible(card: TechCard): Boolean = when (card) {
        TechCard.BATTERY_NOW -> showBatteryNow
        TechCard.LIMITS -> showLimitsAndCells
        TechCard.HISTORY -> showHistory
        TechCard.MOTORS -> showMotors
        TechCard.CLIMATE -> showClimate
        TechCard.TYRES -> showTyres
    }

    val hasAnyCard: Boolean
        get() = showBatteryNow || showLimitsAndCells || showMotors ||
            showClimate || showTyres || showHistory

    private fun anyOf(vararg values: Any?): Boolean = values.any { it != null }
}

/**
 * Standing still, the front motor's fid answers -1 rather than 0 (seen on two consecutive
 * frames on the car). That is idle, not motion, so anything in -1..0 reads as a plain 0.
 * Reverse spins the motors properly negative (hundreds of rpm), which passes through.
 */
internal fun rpmForDisplay(raw: Int?): Int? = raw?.let { if (it in -1..0) 0 else it }

@HiltViewModel
class TechPanelViewModel @Inject constructor(
    private val batteryStateRepository: BatteryStateRepository,
    private val avgSocProvider: AvgSocProvider,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TechPanelUiState())
    val uiState: StateFlow<TechPanelUiState> = _uiState.asStateFlow()

    init {
        observeLiveData()
        viewModelScope.launch { loadBatteryState() }
        viewModelScope.launch { loadAvgSoc() }
        viewModelScope.launch { loadCardOrder() }
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
        _uiState.update { it.copy(cardOrder = order, showOrderHint = !hintSeen) }
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
                            batTemp = data?.avgBatTemp,
                            hvVoltage = data?.hvVoltage,
                            powerKw = data?.power,
                            batteryPowerW = data?.batteryPowerW,
                            batteryCapacityKwh = data?.batteryCapacityKwh,
                            voltage12v = data?.voltage12v,
                            insulationKohm = data?.insulationKohm,
                            bmsMaxChargeKw = data?.bmsMaxChargeKw,
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

    private suspend fun loadAvgSoc() {
        val avg = runCatching { avgSocProvider.compute() }.getOrNull() ?: return
        _uiState.update {
            it.copy(avgSocSinceCharge = avg.sinceCharge, avgSocAllTime = avg.allTime)
        }
    }
}
