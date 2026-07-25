package com.bydmate.app.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.remote.DynamicMetric
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.data.trips.TripCounterMath
import com.bydmate.app.data.trips.TripCounterUi
import com.bydmate.app.data.trips.TripResetState
import com.bydmate.app.domain.battery.AvgSocCalculator
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.ConsumptionAggregator
import com.bydmate.app.domain.calculator.ConsumptionState
import com.bydmate.app.domain.calculator.Trend
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
    val totalFuelLiters: Double = 0.0,
    val avgConsumption: Double = 0.0,
    val avgFuelConsumption: Double = 0.0,
    val totalCost: Double = 0.0,
    val tripCount: Int = 0,
    // Legacy aliases for backward compat
    val totalKmToday: Double = 0.0,
    val totalKwhToday: Double = 0.0,
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
    val insightTitle: String? = null,
    val insightSummary: String? = null,
    val insightDynamics: List<DynamicMetric> = emptyList(),
    val insightInsights: List<String> = emptyList(),
    val insightTone: String = "good",
    val effectiveInsightTone: String = "good",
    val insightDate: String? = null,
    val insightPeriodDays: Int = 7,
    val insightExpanded: Boolean = false,
    val batteryHealthExpanded: Boolean = false,
    val avgSocSinceCharge: Int? = null,
    val avgSocAllTime: Int? = null,
    val estimatedRangeKm: Double? = null,
    val vehicleDataConnected: Boolean = true,
    val adbConnected: Boolean? = null,
    val currentSoh: Float? = null,
    val currentLifetimeKm: Float? = null,
    val currentLifetimeKwh: Float? = null,
    val autoserviceEnabled: Boolean = false,
    val vehicleProfileId: String = SettingsRepository.DEFAULT_VEHICLE_PROFILE,
    // Widget-style stats around SOC ring (mirror FloatingWidgetView bindings).
    val insideTemp: Int? = null,
    val tripDistanceKm: Double? = null,
    val sessionStartedAt: Long? = null,
    val consumption: Double? = null,
    val consumptionTrend: Trend = Trend.NONE,
    val isCharging: Boolean = false,
    // Resettable trip counters (TRIP 1 / TRIP 2 buttons).
    val trip1: TripCounterUi? = null,
    val trip2: TripCounterUi? = null,
    val trip1Expanded: Boolean = false,
    val trip2Expanded: Boolean = false,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val tripRepository: TripRepository,
    private val settingsRepository: SettingsRepository,
    private val idleDrainDao: IdleDrainDao,
    private val chargeDao: ChargeDao,
    private val insightsManager: InsightsManager,
    private val batteryStateRepository: BatteryStateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    // Reset anchors for the two resettable trip counters.
    private val trip1Reset = MutableStateFlow<TripResetState?>(null)
    private val trip2Reset = MutableStateFlow<TripResetState?>(null)

    init {
        cleanupBadIdleDrainData()
        observeLiveData()
        observeLastTrip()
        observeRecentTrips()
        loadCurrency()
        loadInsight()
        loadPeriodSummary()
        viewModelScope.launch { loadAutoserviceFlag() }
        observeVehicleProfile()
        observeTripCounters()
    }

    fun setPeriod(period: DashboardPeriod) {
        _uiState.update { it.copy(period = period) }
        loadPeriodSummary()
    }

    /** Recompute both the dynamics table and the generated text for a 7- or
     *  30-day window (dialog toggle). InsightsManager caches per period/day,
     *  so re-toggling within the same day is served from cache. On failure
     *  (unguarded Room/derived-text errors) leave state untouched: chip stays
     *  on the old period, no label/data mismatch. */
    fun setInsightPeriod(days: Int) {
        viewModelScope.launch {
            val insight = runCatching {
                insightsManager.getDisplayInsight(days) ?: insightsManager.refreshIfNeeded(days)
            }.getOrNull() ?: return@launch
            _uiState.update { current -> current.copy(
                insightPeriodDays = days,
                insightTitle = insight.title,
                insightSummary = insight.summary,
                insightDynamics = insight.dynamics,
                insightInsights = insight.insights,
                insightTone = insight.tone,
                effectiveInsightTone = com.bydmate.app.data.automation.InsightToneLogic.worst(
                    insight.tone,
                    com.bydmate.app.data.automation.InsightToneLogic.voltage12vTone(current.voltage12v),
                    com.bydmate.app.data.automation.InsightToneLogic.cellDeltaTone(current.cellVoltageMax, current.cellVoltageMin, current.soc)
                ),
                insightDate = insightsManager.getCachedDate(days)
            ) }
        }
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
        val sessionStartedAt: Long?,
        val tripDistanceKm: Double?,
        val consumption: ConsumptionState,
    )

    private fun observeLiveData() {
        viewModelScope.launch {
            // combine() is typed only up to 5 flows — bundle data+connected and
            // session+tripKm to stay under the limit (mirrors WidgetController).
            val dataConnFlow = TrackingService.lastData.combine(TrackingService.vehicleDataConnected) { d, c -> d to c }
            val tripFlow = TrackingService.sessionStartedAt.combine(TrackingService.tripDistanceKm) { s, t -> s to t }
            combine(
                dataConnFlow,
                TrackingService.isRunning,
                TrackingService.lastRangeKm,
                tripFlow,
                ConsumptionAggregator.state,
            ) { dataConn, running, rangeKm, trip, consumption ->
                LiveSnapshot(
                    data = dataConn.first,
                    connected = dataConn.second,
                    running = running,
                    rangeKm = rangeKm,
                    sessionStartedAt = trip.first,
                    tripDistanceKm = trip.second,
                    consumption = consumption,
                )
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
                                data?.minCellVoltage ?: current.cellVoltageMin,
                                data?.soc ?: current.soc
                            )
                        ),
                        estimatedRangeKm = rangeKm ?: current.estimatedRangeKm,
                        vehicleDataConnected = connected,
                        insideTemp = data?.insideTemp ?: current.insideTemp,
                        tripDistanceKm = snapshot.tripDistanceKm,
                        sessionStartedAt = snapshot.sessionStartedAt,
                        consumption = snapshot.consumption.displayValue,
                        consumptionTrend = snapshot.consumption.trend,
                        // Bolt = "energy is flowing into the battery right now."
                        // chargeGunState semantics differ from BMS chargingStatus codes
                        // across firmwares; the only universally truthful signal is
                        // gun-connected AND negative motor power. Regen has gun=0, so
                        // it's filtered. Gun pull → gunState=0 within ≤3s → bolt off.
                        isCharging = data?.chargeGunState == 2 && (data.power ?: 0.0) < -0.3,
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
            val vehicleProfile = settingsRepository.getVehicleProfile()
            _uiState.update {
                it.copy(
                    currencySymbol = symbol,
                    vehicleProfileId = vehicleProfile.id
                )
            }
        }
    }

    private fun observeVehicleProfile() {
        viewModelScope.launch {
            settingsRepository.observeString(SettingsRepository.KEY_VEHICLE_PROFILE).collect { id ->
                _uiState.update {
                    it.copy(vehicleProfileId = id ?: SettingsRepository.DEFAULT_VEHICLE_PROFILE)
                }
            }
        }
    }

    private fun loadPeriodSummary() {
        viewModelScope.launch {
            val period = _uiState.value.period
            val (from, to) = periodRange(period)
            val summary = tripRepository.getPeriodSummary(from, to)
            val avg = if (summary.totalKm > 0) summary.totalKwh / summary.totalKm * 100.0 else 0.0
            val avgFuel = if (summary.totalKm > 0) summary.totalFuelLiters / summary.totalKm * 100.0 else 0.0

            _uiState.update {
                it.copy(
                    totalKm = summary.totalKm,
                    totalKwh = summary.totalKwh,
                    totalFuelLiters = summary.totalFuelLiters,
                    avgConsumption = avg,
                    avgFuelConsumption = avgFuel,
                    totalCost = summary.totalCost,
                    tripCount = summary.tripCount,
                    totalKmToday = if (period == DashboardPeriod.TODAY) summary.totalKm else it.totalKmToday,
                    totalKwhToday = if (period == DashboardPeriod.TODAY) summary.totalKwh else it.totalKwhToday
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
            var cached = insightsManager.getDisplayInsight()
            if (cached == null) cached = insightsManager.refreshIfNeeded()
            if (cached != null) {
                _uiState.update { current -> current.copy(
                    insightTitle = cached.title,
                    insightSummary = cached.summary,
                    insightDynamics = cached.dynamics,
                    // Cached insight dynamics are always weekly — reset the chip so
                    // it doesn't show a stale "Месяц" selection over weekly data.
                    insightPeriodDays = 7,
                    insightInsights = cached.insights,
                    insightTone = cached.tone,
                    effectiveInsightTone = com.bydmate.app.data.automation.InsightToneLogic.worst(
                        cached.tone,
                        com.bydmate.app.data.automation.InsightToneLogic.voltage12vTone(current.voltage12v),
                        com.bydmate.app.data.automation.InsightToneLogic.cellDeltaTone(current.cellVoltageMax, current.cellVoltageMin, current.soc)
                    ),
                    insightDate = insightsManager.getCachedDate()
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
        val state = runCatching { batteryStateRepository.refresh() }.getOrNull()
        _uiState.update {
            if (state == null) {
                it.copy(adbConnected = false)
            } else {
                it.copy(
                    adbConnected = state.autoserviceAvailable,
                    currentSoh = state.sohPercent,
                    currentLifetimeKm = state.lifetimeKm,
                    currentLifetimeKwh = state.lifetimeKwh,
                )
            }
        }
    }

    private fun observeTripCounters() {
        viewModelScope.launch {
            trip1Reset.value = settingsRepository.getTripResetState(1)
            trip2Reset.value = settingsRepository.getTripResetState(2)
            val tariff = settingsRepository.getTripCostTariff()
            launch { collectTripCounter(1, trip1Reset, tariff) }
            launch { collectTripCounter(2, trip2Reset, tariff) }
        }
    }

    /** Room aggregate re-queries on every reset (flatMapLatest) and re-emits on any
     *  trips-table change; the live-session flows tick while driving, keeping the
     *  button values fresh mid-trip. The flatMapLatest key is the pair (reset, sessionStart):
     *  a new session invalidates the landed-row query. The DAO receives sessionStart so only
     *  rows within the current session window are subtracted from the live delta.
     *  liveWholeSession from TrackingService guards coverage: when false (baseline gap after
     *  a restart) compute() suppresses live km/kWh to avoid unknown-window inflation. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectTripCounter(n: Int, resetFlow: StateFlow<TripResetState?>, tariff: Double) {
        resetFlow.filterNotNull()
            .combine(TrackingService.sessionStartedAt) { reset, sessionStart -> reset to sessionStart }
            .flatMapLatest { (reset, sessionStart) ->
                val landedFrom = sessionStart ?: Long.MAX_VALUE
                combine(
                    tripRepository.observeCounterStats(reset.resetTs, landedFrom),
                    TrackingService.tripDistanceKm,
                    TrackingService.tripKwhConsumed,
                    TrackingService.sessionStartedAt,
                    TrackingService.liveWholeSession,
                ) { stats, liveKm, liveKwh, sessionStartInner, liveWholeSession ->
                    TripCounterMath.compute(stats, reset, liveKm, liveKwh, sessionStartInner,
                        liveWholeSession, System.currentTimeMillis(), tariff)
                }
            }.collect { ui ->
                _uiState.update { if (n == 1) it.copy(trip1 = ui) else it.copy(trip2 = ui) }
            }
    }

    /** Long-press reset: instant, no confirmation (approved design). Captures the live
     *  session's current progress as the correction so the counter restarts from ~zero
     *  immediately even mid-drive. When coverage is degraded the live partials are not a
     *  valid pre-reset measurement: store zero corrections and mark the straddling row for
     *  whole-row exclusion instead (counting restarts from the next trip). */
    fun resetTripCounter(n: Int) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val continuous = TrackingService.liveWholeSession.value &&
                TrackingService.sessionStartedAt.value != null
            val state = TripResetState(
                resetTs = now,
                corrKm = if (continuous) TrackingService.tripDistanceKm.value ?: 0.0 else 0.0,
                corrKwh = if (continuous) TrackingService.tripKwhConsumed.value ?: 0.0 else 0.0,
                corrMs = TrackingService.sessionStartedAt.value?.let { now - it } ?: 0L,
                excludeStraddling = !continuous,
            )
            settingsRepository.setTripResetState(n, state)
            if (n == 1) trip1Reset.value = state else trip2Reset.value = state
        }
    }

    fun toggleTripExpanded(n: Int) {
        _uiState.update {
            val t1 = n == 1 && !it.trip1Expanded
            val t2 = n == 2 && !it.trip2Expanded
            it.copy(trip1Expanded = t1, trip2Expanded = t2,
                insightExpanded = false, batteryHealthExpanded = false)
        }
    }

    fun toggleBatteryHealthExpanded() {
        _uiState.update { it.copy(
            batteryHealthExpanded = !it.batteryHealthExpanded,
            insightExpanded = false,
            trip1Expanded = false,
            trip2Expanded = false,
        ) }
        if (_uiState.value.batteryHealthExpanded) {
            viewModelScope.launch { computeAvgSoc() }
        }
    }

    /** #93: cheap on-open aggregation — a few hundred Room rows, no polling. */
    private suspend fun computeAvgSoc() {
        val trips = tripRepository.getAllTrips().first()
        val charges = chargeDao.getAll().first()
        val now = System.currentTimeMillis()
        val points = AvgSocCalculator.buildPoints(trips, charges)
        val allTime = points.firstOrNull()
            ?.let { AvgSocCalculator.averageSince(points, it.ts, now) }
        val lastChargeEnd = charges
            .filter { it.status == "COMPLETED" && it.endTs != null && it.socEnd != null }
            .maxByOrNull { it.endTs!! }
            ?.endTs
        val sinceCharge = lastChargeEnd
            ?.let { AvgSocCalculator.averageSince(points, it, now) }
        _uiState.update { it.copy(avgSocAllTime = allTime, avgSocSinceCharge = sinceCharge) }
    }

    fun toggleInsightExpanded() {
        _uiState.update { it.copy(
            insightExpanded = !it.insightExpanded,
            batteryHealthExpanded = false,
            trip1Expanded = false,
            trip2Expanded = false,
        ) }
    }

    private fun calculateBatteryStatus(
        data: com.bydmate.app.data.remote.DiParsData?,
        current: DashboardUiState
    ): String {
        val maxV = data?.maxCellVoltage ?: current.cellVoltageMax
        val minV = data?.minCellVoltage ?: current.cellVoltageMin
        val soc = data?.soc ?: current.soc
        val hasCellData = maxV != null && minV != null
        // Delta is diagnostic only on the LFP plateau (SOC 20-80%, BYD's check band);
        // near full charge cells naturally diverge, so outside the band it must not
        // paint the card red (#113). Out-of-band is "gated", not "missing data".
        val delta = if (hasCellData && soc != null && soc in 20..80)
            Math.round((maxV!! - minV!!) * 1000.0) / 1000.0 else null
        val temp = data?.avgBatTemp ?: current.avgBatTemp
        if (!hasCellData && temp == null) return current.batteryHealthStatus
        return when {
            (delta != null && delta >= 0.090) || (temp != null && (temp < 5 || temp > 50)) -> "critical"
            (delta != null && delta >= 0.050) || (temp != null && (temp < 5 || temp > 45)) -> "warning"
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
