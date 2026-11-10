package com.bydmate.app.ui.tech

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.autoservice.Pm25Reader
import com.bydmate.app.data.carstatus.CarStatusReader
import com.bydmate.app.data.dtc.DtcBlockNames
import com.bydmate.app.data.dtc.DtcErasePlan
import com.bydmate.app.data.dtc.DtcLabels
import com.bydmate.app.data.dtc.DtcParser
import com.bydmate.app.data.dtc.DtcSavedScan
import com.bydmate.app.data.dtc.DtcScanRepository
import com.bydmate.app.data.nativestack.VehicleKindMonitor
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.AvgSocProvider
import com.bydmate.app.domain.battery.BatteryCalibration
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
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

@HiltViewModel
@Suppress("LongParameterList", "TooManyFunctions") // Hilt constructor; one loader per card
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
        observeDtcScan()
        viewModelScope.launch {
            vehicleKindMonitor.kind.collect { kind ->
                _uiState.update { it.copy(vehicleKind = kind, cardOrder = TechCardOrder.forKind(it.cardOrder, kind)) }
            }
        }
    }

    @Volatile private var pm25Seen = false

    /**
     * PM2.5 only while the screen is on display, never in the global poll: the screen runs this
     * from a LaunchedEffect, so leaving the tab cancels it (the view model itself outlives the
     * tab). A car without the sensor gets one read and no more; with it, once a minute, and a
     * failed read after that clears the values but keeps polling, also when the screen comes
     * back (the sensor is remembered for the view model's life).
     */
    suspend fun pollPm25() {
        while (true) {
            val pm = runCatching { pm25Reader.read() }.getOrNull()
            if (pm == null && !pm25Seen) return
            pm25Seen = true
            _uiState.update {
                it.copy(pm25In = pm?.inside, pm25Out = pm?.outside, pm25LevelIn = pm?.levelIn, pm25LevelOut = pm?.levelOut)
            }
            delay(PM25_REFRESH_MS)
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
                            gear = data?.gear,
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

    /** The scan's own state, the last finished scan and whether the service runs (the button needs it). */
    private fun observeDtcScan() {
        viewModelScope.launch {
            dtcScanRepository.state.collect { scan -> _uiState.update { it.copy(dtcScan = scan) } }
        }
        viewModelScope.launch {
            dtcScanRepository.eraseUpdates().collect { (erase, at) ->
                _uiState.update { it.copy(dtcErase = erase, dtcErasedAt = at) }
            }
        }
        viewModelScope.launch {
            dtcScanRepository.last.collect { last ->
                dtcSaved = last
                val summary = last?.let { DtcParser.summarize(it.results) }
                val blocks = last?.results.orEmpty().associate { it.addr to DtcBlockNames.identify(it.addr, it.idUds) }
                _uiState.update {
                    it.copy(
                        dtcSummary = summary, dtcCheckedAt = last?.at, dtcDescriptions = emptyMap(), dtcBlocks = blocks,
                        dtcEraseLocked = last?.eraseLocked.orEmpty(),
                    )
                }
                if (summary == null) return@collect
                // The tables load once, off the main thread; the codes show without text until then.
                val descriptions = runCatching { dtcScanRepository.describe(summary) }.getOrDefault(emptyMap())
                _uiState.update { if (it.dtcSummary === summary) it.copy(dtcDescriptions = descriptions) else it }
            }
        }
        viewModelScope.launch {
            TrackingService.isRunning.collect { running -> _uiState.update { it.copy(serviceRunning = running) } }
        }
    }

    /** A tap on «Сканировать» or «Сканировать снова»; the repository re-checks P and ignores a second tap. */
    fun scanDtc() = dtcScanRepository.start()

    /** The saved scan the erase targets come from. */
    @Volatile private var dtcSaved: DtcSavedScan? = null

    /**
     * A confirmed «Стереть все» ([addr] null) or «стереть ›» on a block (all addresses of its
     * group). A missing or stale scan sends an empty list, which the repository refuses with
     * «нажмите Сканировать».
     */
    fun eraseDtc(addr: Int?) {
        val now = System.currentTimeMillis()
        val addrs = if (addr == null) {
            DtcErasePlan.eligible(dtcSaved, now)
        } else {
            DtcErasePlan.eligibleBlock(dtcSaved, addr, now)
        }
        dtcScanRepository.startErase(addrs ?: IntArray(0))
    }

    /** Opens the saved scan; never starts a new one. */
    fun openDtcDialog() {
        _uiState.update { it.copy(dtcDialogOpen = true, dtcSendResult = null) }
        viewModelScope.launch {
            val ready = runCatching { settingsRepository.getTgBackupConfig().configured }.getOrDefault(false)
            _uiState.update { it.copy(dtcTelegramReady = ready) }
        }
    }

    fun closeDtcDialog() {
        _uiState.update { it.copy(dtcDialogOpen = false) }
    }

    /** One send per tap, nothing queued; a tap while one is on its way does nothing. */
    fun sendDtc(labels: DtcLabels) {
        if (_uiState.value.dtcSending) return
        _uiState.update { it.copy(dtcSending = true, dtcSendResult = null) }
        viewModelScope.launch {
            val result = dtcScanRepository.sendLastScan(labels)
            _uiState.update { it.copy(dtcSending = false, dtcSendResult = result) }
        }
    }

    private suspend fun loadAvgSoc() {
        val avg = runCatching { avgSocProvider.compute() }.getOrNull() ?: return
        _uiState.update {
            it.copy(avgSocSinceCharge = avg.sinceCharge, avgSocAllTime = avg.allTime)
        }
    }

    private companion object {
        const val PM25_REFRESH_MS = 60_000L
        const val TAG = "TechPanel"
    }
}
