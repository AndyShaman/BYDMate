package com.bydmate.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/**
 * UI state for the Dashboard screen.
 * Combines live vehicle data from TrackingService with
 * today's aggregated trip/charge statistics from the database.
 */
data class DashboardUiState(
    val soc: Int? = null,
    val odometer: Double? = null,
    val totalKmToday: Double = 0.0,
    val totalKwhToday: Double = 0.0,
    val avgConsumption: Double = 0.0,
    val idleDrainKwhToday: Double = 0.0,
    val lastTrip: TripEntity? = null,
    val lastCharge: ChargeEntity? = null,
    val isServiceRunning: Boolean = false,
    val currencySymbol: String = "Br",
    val avgBatTemp: Int? = null,
    val cellVoltageMin: Double? = null,
    val cellVoltageMax: Double? = null,
    val cellVoltageDelta: Double? = null,
    val voltage12v: Double? = null,
    val exteriorTemp: Int? = null,
    val batteryHealthStatus: String = "ok",
    val voltage12vStatus: String = "ok",
    val idleDrainPercent: Double = 0.0,
    val idleDrainRate: Double = 0.0,
    val idleDrainHours: Double = 0.0,
    val batteryHealthExpanded: Boolean = false
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val tripRepository: TripRepository,
    private val chargeRepository: ChargeRepository,
    private val settingsRepository: SettingsRepository,
    private val idleDrainDao: IdleDrainDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeLiveData()
        observeLastTrip()
        observeLastCharge()
        loadCurrency()
        loadTodaySummary()
    }

    /**
     * Collect live DiPars data and service running status
     * from TrackingService companion StateFlows.
     */
    private fun observeLiveData() {
        viewModelScope.launch {
            combine(
                TrackingService.lastData,
                TrackingService.isRunning
            ) { data, running ->
                Pair(data, running)
            }.collect { (data, running) ->
                _uiState.update { current ->
                    current.copy(
                        soc = data?.soc ?: current.soc,
                        odometer = data?.mileage ?: current.odometer,
                        isServiceRunning = running,
                        avgBatTemp = data?.avgBatTemp ?: current.avgBatTemp,
                        cellVoltageMin = data?.minCellVoltage ?: current.cellVoltageMin,
                        cellVoltageMax = data?.maxCellVoltage ?: current.cellVoltageMax,
                        cellVoltageDelta = if (data?.maxCellVoltage != null && data.minCellVoltage != null)
                            data.maxCellVoltage - data.minCellVoltage else current.cellVoltageDelta,
                        voltage12v = data?.voltage12v ?: current.voltage12v,
                        exteriorTemp = data?.exteriorTemp ?: current.exteriorTemp,
                        batteryHealthStatus = calculateBatteryStatus(data, current),
                        voltage12vStatus = calculate12vStatus(data?.voltage12v ?: current.voltage12v)
                    )
                }
            }
        }
    }

    /** Observe the most recent trip from the database. */
    private fun observeLastTrip() {
        viewModelScope.launch {
            tripRepository.getLastTrip().collect { trip ->
                _uiState.update { it.copy(lastTrip = trip) }
            }
        }
    }

    /** Observe the most recent charge session from the database. */
    private fun observeLastCharge() {
        viewModelScope.launch {
            chargeRepository.getLastCharge().collect { charge ->
                _uiState.update { it.copy(lastCharge = charge) }
            }
        }
    }

    /**
     * Load today's trip summary (total km, total kWh, trip count)
     * using Calendar to compute start/end of the current day in millis.
     */
    private fun loadCurrency() {
        viewModelScope.launch {
            val symbol = settingsRepository.getCurrencySymbol()
            _uiState.update { it.copy(currencySymbol = symbol) }
        }
    }

    private fun loadTodaySummary() {
        viewModelScope.launch {
            val (dayStart, dayEnd) = todayRange()
            val summary = tripRepository.getTodaySummary(dayStart, dayEnd)
            val idleDrain = idleDrainDao.getTodayDrainKwh(dayStart, dayEnd)
            val idleDrainHours = idleDrainDao.getTodayDrainHours(dayStart, dayEnd)
            val batteryCapacity = settingsRepository.getBatteryCapacity()
            val avg = if (summary.totalKm > 0) {
                summary.totalKwh / summary.totalKm * 100.0
            } else {
                0.0
            }
            val idleDrainPercent = if (batteryCapacity > 0) idleDrain / batteryCapacity * 100.0 else 0.0
            val idleDrainRate = if (idleDrainHours > 0) idleDrain / idleDrainHours else 0.0
            _uiState.update {
                it.copy(
                    totalKmToday = summary.totalKm,
                    totalKwhToday = summary.totalKwh,
                    avgConsumption = avg,
                    idleDrainKwhToday = idleDrain,
                    idleDrainPercent = idleDrainPercent,
                    idleDrainRate = idleDrainRate,
                    idleDrainHours = idleDrainHours
                )
            }
        }
    }

    /** Returns start and end timestamps (millis) for today. */
    private fun todayRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis

        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val dayEnd = cal.timeInMillis

        return Pair(dayStart, dayEnd)
    }

    /** Refresh today's summary, can be called on pull-to-refresh or screen resume. */
    fun refresh() {
        loadTodaySummary()
    }

    fun toggleBatteryHealthExpanded() {
        _uiState.update { it.copy(batteryHealthExpanded = !it.batteryHealthExpanded) }
    }

    private fun calculateBatteryStatus(
        data: com.bydmate.app.data.remote.DiParsData?,
        current: DashboardUiState
    ): String {
        val maxV = data?.maxCellVoltage ?: current.cellVoltageMax
        val minV = data?.minCellVoltage ?: current.cellVoltageMin
        val delta = if (maxV != null && minV != null) maxV - minV else null
        val temp = data?.avgBatTemp ?: current.avgBatTemp
        if (delta == null && temp == null) return current.batteryHealthStatus
        return when {
            (delta != null && delta > 0.10) || (temp != null && (temp < 5 || temp > 50)) -> "critical"
            (delta != null && delta > 0.05) || (temp != null && (temp < 10 || temp > 45)) -> "warning"
            else -> "ok"
        }
    }

    private fun calculate12vStatus(voltage: Double?): String {
        if (voltage == null) return "ok"
        return when {
            voltage < 11.8 -> "critical"
            voltage < 12.4 -> "warning"
            else -> "ok"
        }
    }
}
