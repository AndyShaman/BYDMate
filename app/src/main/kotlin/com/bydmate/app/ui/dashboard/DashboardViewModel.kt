package com.bydmate.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.remote.DynamicMetric
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
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
enum class DashboardPeriod { TODAY, WEEK, MONTH, YEAR, ALL }

data class DashboardUiState(
    val soc: Int? = null,
    val odometer: Double? = null,
    val speed: Int? = null,
    val period: DashboardPeriod = DashboardPeriod.WEEK,
    val totalKm: Double = 0.0,
    val totalKwh: Double = 0.0,
    val avgConsumption: Double = 0.0,
    val totalCost: Double = 0.0,
    val tripCount: Int = 0,
    // Legacy aliases for backward compat
    val totalKmToday: Double = 0.0,
    val totalKwhToday: Double = 0.0,
    val idleDrainKwhToday: Double = 0.0,
    val lastTrip: TripEntity? = null,
    val recentTrips: List<TripEntity> = emptyList(),
    val isServiceRunning: Boolean = false,
    val currencySymbol: String = "BYN",
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
    val insightTitle: String? = null,
    val insightSummary: String? = null,
    val insightDynamics: List<DynamicMetric> = emptyList(),
    val insightInsights: List<String> = emptyList(),
    val insightTone: String = "good",
    val effectiveInsightTone: String = "good",
    val insightDate: String? = null,
    val insightLoading: Boolean = false,
    val insightError: String? = null,
    val insightExpanded: Boolean = false,
    val hasApiKey: Boolean = false,
    val batteryHealthExpanded: Boolean = false,
    val idleDrainExpanded: Boolean = false,
    val idleDrainKwhWeek: Double = 0.0,
    val idleDrainHoursWeek: Double = 0.0,
    val estimatedRangeKm: Double? = null,
    val diPlusConnected: Boolean = true,
    val idleDrainAvailable: Boolean = true,
    val adbConnected: Boolean? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val tripRepository: TripRepository,
    private val settingsRepository: SettingsRepository,
    private val idleDrainDao: IdleDrainDao,
    private val insightsManager: InsightsManager,
    private val batteryStateRepository: BatteryStateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        cleanupBadIdleDrainData()
        observeLiveData()
        observeLastTrip()
        observeRecentTrips()
        loadCurrency()
        loadInsight()
        loadPeriodSummary()
        viewModelScope.launch { loadAutoserviceFlag() }
    }

    fun setPeriod(period: DashboardPeriod) {
        _uiState.update { it.copy(period = period) }
        loadPeriodSummary()
    }

    /**
     * Collect live DiPars data and service running status
     * from TrackingService companion StateFlows.
     * Range is read directly from TrackingService.lastRangeKm (single source of truth).
     */
    private data class LiveSnapshot(
        val data: com.bydmate.app.data.remote.DiParsData?,
        val running: Boolean,
        val connected: Boolean,
        val rangeKm: Double?,
    )

    private fun observeLiveData() {
        viewModelScope.launch {
            combine(
                TrackingService.lastData,
                TrackingService.isRunning,
                TrackingService.diPlusConnected,
                TrackingService.lastRangeKm,
            ) { data, running, connected, rangeKm ->
                LiveSnapshot(data, running, connected, rangeKm)
            }.collect { snapshot ->
                val data = snapshot.data
                val running = snapshot.running
                val connected = snapshot.connected
                val rangeKm = snapshot.rangeKm

                _uiState.update { current ->
                    val newSoc = data?.soc ?: current.soc
                    current.copy(
                        soc = newSoc,
                        speed = data?.speed ?: current.speed,
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
                        voltage12vStatus = calculate12vStatus(data?.voltage12v ?: current.voltage12v),
                        effectiveInsightTone = com.bydmate.app.data.automation.InsightToneLogic.worst(
                            current.insightTone,
                            com.bydmate.app.data.automation.InsightToneLogic.voltage12vTone(
                                data?.voltage12v ?: current.voltage12v
                            ),
                            com.bydmate.app.data.automation.InsightToneLogic.cellDeltaTone(
                                data?.maxCellVoltage ?: current.cellVoltageMax,
                                data?.minCellVoltage ?: current.cellVoltageMin
                            )
                        ),
                        estimatedRangeKm = rangeKm ?: current.estimatedRangeKm,
                        diPlusConnected = connected
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

    private fun observeRecentTrips() {
        viewModelScope.launch {
            tripRepository.getRecentTrips(7).collect { trips ->
                _uiState.update { it.copy(recentTrips = trips) }
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
            val dataSource = settingsRepository.getDataSource()
            _uiState.update {
                it.copy(
                    currencySymbol = symbol,
                    idleDrainAvailable = dataSource == SettingsRepository.DataSource.ENERGYDATA
                )
            }
        }
    }

    private fun loadPeriodSummary() {
        viewModelScope.launch {
            val period = _uiState.value.period
            val (from, to) = periodRange(period)
            val summary = tripRepository.getPeriodSummary(from, to)
            val avg = if (summary.totalKm > 0) summary.totalKwh / summary.totalKm * 100.0 else 0.0

            // Idle drain always uses today
            val (dayStart, dayEnd) = todayRange()
            val idleDrain = idleDrainDao.getTodayDrainKwh(dayStart, dayEnd)
            val idleDrainHours = idleDrainDao.getTodayDrainHours(dayStart, dayEnd)
            val batteryCapacity = settingsRepository.getBatteryCapacity()
            val idleDrainPercent = if (batteryCapacity > 0) idleDrain / batteryCapacity * 100.0 else 0.0
            val idleDrainRate = if (idleDrainHours > 0) idleDrain / idleDrainHours else 0.0

            val weekStart = dayStart - 6 * 24 * 60 * 60 * 1000L
            val idleDrainWeek = idleDrainDao.getTodayDrainKwh(weekStart, dayEnd)
            val idleDrainHoursWeek = idleDrainDao.getTodayDrainHours(weekStart, dayEnd)

            _uiState.update {
                it.copy(
                    totalKm = summary.totalKm,
                    totalKwh = summary.totalKwh,
                    avgConsumption = avg,
                    totalCost = summary.totalCost,
                    tripCount = summary.tripCount,
                    totalKmToday = if (period == DashboardPeriod.TODAY) summary.totalKm else it.totalKmToday,
                    totalKwhToday = if (period == DashboardPeriod.TODAY) summary.totalKwh else it.totalKwhToday,
                    idleDrainKwhToday = idleDrain,
                    idleDrainPercent = idleDrainPercent,
                    idleDrainRate = idleDrainRate,
                    idleDrainHours = idleDrainHours,
                    idleDrainKwhWeek = idleDrainWeek,
                    idleDrainHoursWeek = idleDrainHoursWeek
                )
            }
        }
    }

    private fun periodRange(period: DashboardPeriod): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        return when (period) {
            DashboardPeriod.TODAY -> todayRange()
            DashboardPeriod.WEEK -> {
                cal.add(Calendar.DAY_OF_YEAR, -7)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            DashboardPeriod.MONTH -> {
                cal.add(Calendar.DAY_OF_YEAR, -30)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            DashboardPeriod.YEAR -> {
                cal.add(Calendar.YEAR, -1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            DashboardPeriod.ALL -> 0L to now
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

    // One-time cleanup: BatCapacity method produced inflated values in v1.0.0–1.0.4
    private fun cleanupBadIdleDrainData() {
        viewModelScope.launch {
            if (settingsRepository.isIdleDrainCleanupDone()) return@launch
            val count = idleDrainDao.getCount()
            if (count > 0) {
                idleDrainDao.deleteAll()
                android.util.Log.i("DashboardVM", "Cleared $count bad idle drain records (BatCapacity bug)")
            }
            settingsRepository.setIdleDrainCleanupDone()
        }
    }

    private fun loadInsight() {
        viewModelScope.launch {
            val apiKey = settingsRepository.getString(SettingsRepository.KEY_OPENROUTER_API_KEY, "")
            _uiState.update { it.copy(hasApiKey = apiKey.isNotBlank()) }

            val cached = insightsManager.getCachedInsight()
            if (cached != null) {
                _uiState.update { current -> current.copy(
                    insightTitle = cached.title,
                    insightSummary = cached.summary,
                    insightDynamics = cached.dynamics,
                    insightInsights = cached.insights,
                    insightTone = cached.tone,
                    effectiveInsightTone = com.bydmate.app.data.automation.InsightToneLogic.worst(
                        cached.tone,
                        com.bydmate.app.data.automation.InsightToneLogic.voltage12vTone(current.voltage12v),
                        com.bydmate.app.data.automation.InsightToneLogic.cellDeltaTone(current.cellVoltageMax, current.cellVoltageMin)
                    ),
                    insightDate = insightsManager.getCachedDate()
                ) }
            }
        }
    }

    fun refreshInsight() {
        viewModelScope.launch {
            _uiState.update { it.copy(insightLoading = true, insightError = null) }
            val insight = insightsManager.refresh()
            if (insight != null) {
                _uiState.update { current -> current.copy(
                    insightTitle = insight.title,
                    insightSummary = insight.summary,
                    insightDynamics = insight.dynamics,
                    insightInsights = insight.insights,
                    insightTone = insight.tone,
                    effectiveInsightTone = com.bydmate.app.data.automation.InsightToneLogic.worst(
                        insight.tone,
                        com.bydmate.app.data.automation.InsightToneLogic.voltage12vTone(current.voltage12v),
                        com.bydmate.app.data.automation.InsightToneLogic.cellDeltaTone(current.cellVoltageMax, current.cellVoltageMin)
                    ),
                    insightDate = insightsManager.getCachedDate(),
                    insightLoading = false
                ) }
            } else {
                _uiState.update { it.copy(
                    insightLoading = false,
                    insightError = "Не удалось обновить"
                ) }
            }
        }
    }

    /** Refresh today's summary, can be called on pull-to-refresh or screen resume. */
    fun refresh() {
        loadPeriodSummary()
        loadInsight()
        viewModelScope.launch { loadAutoserviceFlag() }
    }

    private suspend fun loadAutoserviceFlag() {
        val enabled = settingsRepository.isAutoserviceEnabled()
        val flag: Boolean? = if (!enabled) null else runCatching {
            batteryStateRepository.refresh().autoserviceAvailable
        }.getOrDefault(false)
        _uiState.update { it.copy(adbConnected = flag) }
    }

    fun toggleBatteryHealthExpanded() {
        _uiState.update { it.copy(
            batteryHealthExpanded = !it.batteryHealthExpanded,
            insightExpanded = false,
            idleDrainExpanded = false
        ) }
    }

    fun toggleInsightExpanded() {
        _uiState.update { it.copy(
            insightExpanded = !it.insightExpanded,
            idleDrainExpanded = false,
            batteryHealthExpanded = false
        ) }
    }

    fun toggleIdleDrainExpanded() {
        _uiState.update { it.copy(
            idleDrainExpanded = !it.idleDrainExpanded,
            insightExpanded = false,
            batteryHealthExpanded = false
        ) }
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
            (delta != null && delta > 0.05) || (temp != null && (temp < 5 || temp > 45)) -> "warning"
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
