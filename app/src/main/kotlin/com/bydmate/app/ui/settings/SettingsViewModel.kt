package com.bydmate.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import android.net.Uri
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.backup.BackupManager
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterModel
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.service.UpdateChecker
import com.bydmate.app.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bydmate.app.service.BootReceiver
import com.bydmate.app.ui.widget.WidgetController
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * UI state for the Settings screen.
 * Contains current setting values and export operation status.
 */
data class SettingsUiState(
    val batteryCapacity: String = SettingsRepository.DEFAULT_BATTERY_CAPACITY,
    val homeTariff: String = SettingsRepository.DEFAULT_HOME_TARIFF,
    val dcTariff: String = SettingsRepository.DEFAULT_DC_TARIFF,
    val units: String = SettingsRepository.DEFAULT_UNITS,
    val currency: String = SettingsRepository.DEFAULT_CURRENCY,
    val currencySymbol: String = "BYN",
    val exportStatus: String? = null,
    val importStatus: String? = null,
    val appVersion: String = "0.0.0",
    val updateStatus: String? = null,
    val updateDialogState: UpdateState = UpdateState.Idle,
    val showUpdateDialog: Boolean = false,
    val diagnosticLog: String? = null,
    val logSaveStatus: String? = null,
    val isRecordingLogs: Boolean = false,
    val tripCostTariff: String = "home",
    val consumptionGood: String = SettingsRepository.DEFAULT_CONSUMPTION_GOOD,
    val consumptionBad: String = SettingsRepository.DEFAULT_CONSUMPTION_BAD,
    val lastBootInfo: String? = null,
    val chainLog: String? = null,
    val openRouterApiKey: String = "",
    val openRouterModel: String = "",
    val openRouterModelName: String = "",
    val showModelPicker: Boolean = false,
    val availableModels: List<OpenRouterModel> = emptyList(),
    val modelsLoading: Boolean = false,
    val aiSaveStatus: String? = null,
    val tariffSaveStatus: String? = null,
    val recalcStatus: String? = null,
    val showRecalcConfirm: Boolean = false,
    // Hidden Smart Home settings (unlocked by tapping version 7 times)
    val devModeUnlocked: Boolean = false,
    val aliceEndpoint: String = "",
    val aliceApiKey: String = "",
    val aliceEnabled: Boolean = false,
    val aliceSaveStatus: String? = null,
    val autoCheckUpdates: Boolean = true,
    val abrpTelemetryEnabled: Boolean = false,
    val abrpApiKey: String = "",
    val abrpUserToken: String = "",
    val abrpCarModel: String = "",
    val abrpSaveStatus: String? = null,
    val mapTileSource: String = SettingsRepository.DEFAULT_MAP_TILE_SOURCE,
    /** Status of the last config backup/restore operation. Red if starts with error prefix. */
    val configStatus: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val chargeRepository: ChargeRepository,
    private val updateChecker: UpdateChecker,
    private val historyImporter: HistoryImporter,
    private val energyDataReader: EnergyDataReader,
    private val idleDrainDao: IdleDrainDao,
    private val tripPointDao: TripPointDao,
    private val insightsManager: InsightsManager,
    private val adbOnDeviceClient: AdbOnDeviceClient,
    private val localePreferences: LocalePreferences,
    private val backupManager: BackupManager,
) : ViewModel() {

    private val _appLanguage = MutableStateFlow(localePreferences.getLanguage() ?: "ru")
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    fun setAppLanguage(lang: String) {
        localePreferences.setLanguage(lang)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
        _appLanguage.value = lang
        // Auto-select CNY when switching to Chinese
        if (lang == "zh") {
            saveCurrency("CNY")
        }
        // Force overlay teardown so the next attach picks up the new locale.
        // applicationContext keeps a stale Configuration after setApplicationLocales,
        // which leaves the floating widget rendering against the old language.
        WidgetController.relocale()
    }

    private val _uiState = MutableStateFlow(SettingsUiState(
        appVersion = getVersion(),
        autoCheckUpdates = UpdateChecker.isAutoCheckEnabled(appContext)
    ))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Tracks the in-flight update download so "Close" can cancel it (issue #23).
    private var downloadJob: Job? = null

    private fun getVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    init {
        loadSettings()
    }

    /** Load all settings from the repository on init. */
    private fun loadSettings() {
        viewModelScope.launch {
            val capacity = settingsRepository.getString(
                SettingsRepository.KEY_BATTERY_CAPACITY,
                SettingsRepository.DEFAULT_BATTERY_CAPACITY
            )
            val homeTariff = settingsRepository.getString(
                SettingsRepository.KEY_HOME_TARIFF,
                SettingsRepository.DEFAULT_HOME_TARIFF
            )
            val dcTariff = settingsRepository.getString(
                SettingsRepository.KEY_DC_TARIFF,
                SettingsRepository.DEFAULT_DC_TARIFF
            )
            val units = settingsRepository.getString(
                SettingsRepository.KEY_UNITS,
                SettingsRepository.DEFAULT_UNITS
            )
            val currency = settingsRepository.getCurrency()
            val tripCostTariff = settingsRepository.getTripCostTariffKey()
            val consumptionGood = settingsRepository.getString(
                SettingsRepository.KEY_CONSUMPTION_GOOD,
                SettingsRepository.DEFAULT_CONSUMPTION_GOOD
            )
            val consumptionBad = settingsRepository.getString(
                SettingsRepository.KEY_CONSUMPTION_BAD,
                SettingsRepository.DEFAULT_CONSUMPTION_BAD
            )

            // Read boot log from SharedPreferences
            val bootInfo = readBootInfo()
            val chainLog = readChainLog()

            // AI settings
            val apiKey = settingsRepository.getString(SettingsRepository.KEY_OPENROUTER_API_KEY, "")
            val modelId = settingsRepository.getString(SettingsRepository.KEY_OPENROUTER_MODEL, "")

            // Smart Home settings
            val aliceEndpoint = settingsRepository.getString(SettingsRepository.KEY_ALICE_ENDPOINT, "")
            val aliceApiKey = settingsRepository.getString(SettingsRepository.KEY_ALICE_API_KEY, "")
            val aliceEnabled = settingsRepository.getString(SettingsRepository.KEY_ALICE_ENABLED, "false") == "true"

            val abrpEnabled = settingsRepository.getString(SettingsRepository.KEY_ABRP_ENABLED, "false") == "true"
            val abrpApiKey = settingsRepository.getString(SettingsRepository.KEY_ABRP_API_KEY, "")
            val abrpUserToken = settingsRepository.getString(SettingsRepository.KEY_ABRP_USER_TOKEN, "")
            val abrpCarModel = settingsRepository.getString(SettingsRepository.KEY_ABRP_CAR_MODEL, "")
            val mapTileSource = settingsRepository.getMapTileSource()

            _uiState.update {
                it.copy(
                    batteryCapacity = capacity,
                    homeTariff = homeTariff,
                    dcTariff = dcTariff,
                    units = units,
                    currency = currency.code,
                    currencySymbol = currency.symbol,
                    tripCostTariff = tripCostTariff,
                    consumptionGood = consumptionGood,
                    consumptionBad = consumptionBad,
                    lastBootInfo = bootInfo,
                    chainLog = chainLog,
                    openRouterApiKey = apiKey,
                    openRouterModel = modelId,
                    openRouterModelName = modelId.substringAfterLast("/").substringBefore(":"),
                    aliceEndpoint = aliceEndpoint,
                    aliceApiKey = aliceApiKey,
                    aliceEnabled = aliceEnabled,
                    abrpTelemetryEnabled = abrpEnabled,
                    abrpApiKey = abrpApiKey,
                    abrpUserToken = abrpUserToken,
                    abrpCarModel = abrpCarModel,
                    mapTileSource = mapTileSource,
                )
            }
        }
    }

    /** Save battery capacity setting. */
    fun saveBatteryCapacity(value: String) {
        _uiState.update { it.copy(batteryCapacity = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_BATTERY_CAPACITY, value)
        }
    }

    /** Update tariff in UI only (no DB save until explicit "Save" press). */
    fun updateHomeTariff(value: String) {
        _uiState.update { it.copy(homeTariff = value) }
    }

    fun updateDcTariff(value: String) {
        _uiState.update { it.copy(dcTariff = value) }
    }

    /** Save tariffs to DB and calculate costs for new trips. */
    fun saveTariffs() {
        val state = _uiState.value
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_HOME_TARIFF, state.homeTariff)
            settingsRepository.setString(SettingsRepository.KEY_DC_TARIFF, state.dcTariff)
            val tariff = settingsRepository.getTripCostTariff()
            historyImporter.calculateMissingCosts(tariff)
            _uiState.update { it.copy(tariffSaveStatus = appContext.getString(R.string.settings_saved)) }
            delay(2000)
            _uiState.update { it.copy(tariffSaveStatus = null) }
        }
    }

    /** Recalculate cost for ALL trips using current tariff. */
    fun recalculateAllCosts() {
        viewModelScope.launch {
            val tariff = settingsRepository.getTripCostTariff()
            val allTrips = tripRepository.getAllTrips().firstOrNull() ?: emptyList()
            var count = 0
            for (trip in allTrips) {
                val kwh = trip.kwhConsumed ?: continue
                tripRepository.updateTrip(trip.copy(cost = kwh * tariff))
                count++
            }
            _uiState.update { it.copy(recalcStatus = appContext.getString(R.string.settings_recalc_done, count)) }
            delay(3000)
            _uiState.update { it.copy(recalcStatus = null) }
        }
    }

    /** Save distance units preference (km or miles). */
    fun saveUnits(value: String) {
        _uiState.update { it.copy(units = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_UNITS, value)
        }
    }

    /** Save trip cost tariff preference. */
    fun saveTripCostTariff(value: String) {
        _uiState.update { it.copy(tripCostTariff = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_TRIP_COST_TARIFF, value)
        }
    }

    fun showRecalcConfirm() { _uiState.update { it.copy(showRecalcConfirm = true) } }
    fun hideRecalcConfirm() { _uiState.update { it.copy(showRecalcConfirm = false) } }
    fun confirmRecalc() {
        _uiState.update { it.copy(showRecalcConfirm = false) }
        recalculateAllCosts()
    }

    /** Save currency preference. */
    fun saveCurrency(code: String) {
        val currency = SettingsRepository.CURRENCIES.find { it.code == code }
            ?: SettingsRepository.CURRENCIES.first()
        _uiState.update { it.copy(currency = currency.code, currencySymbol = currency.symbol) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_CURRENCY, code)
        }
    }

    /**
     * Export all trips and charges to CSV files in the Downloads directory.
     * Creates two files: bydmate_trips_<timestamp>.csv and bydmate_charges_<timestamp>.csv.
     */
    fun exportCsv() {
        viewModelScope.launch {
            _uiState.update { it.copy(exportStatus = appContext.getString(R.string.settings_export_in_progress)) }

            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

                // Export trips
                val trips = tripRepository.getAllTrips().firstOrNull() ?: emptyList()
                val tripsFile = File(downloadsDir, "bydmate_trips_$timestamp.csv")
                FileWriter(tripsFile).use { writer ->
                    writer.append("id,start_ts,end_ts,distance_km,kwh_consumed,kwh_per_100km,soc_start,soc_end,temp_avg_c,avg_speed_kmh,bat_temp_avg,bat_temp_max,bat_temp_min,cost,exterior_temp\n")
                    for (trip in trips) {
                        writer.append("${trip.id},${trip.startTs},${trip.endTs ?: ""},")
                        writer.append("${trip.distanceKm ?: ""},${trip.kwhConsumed ?: ""},")
                        writer.append("${trip.kwhPer100km ?: ""},${trip.socStart ?: ""},")
                        writer.append("${trip.socEnd ?: ""},${trip.tempAvgC ?: ""},")
                        writer.append("${trip.avgSpeedKmh ?: ""},${trip.batTempAvg ?: ""},")
                        writer.append("${trip.batTempMax ?: ""},${trip.batTempMin ?: ""},")
                        writer.append("${trip.cost ?: ""},${trip.exteriorTemp ?: ""}\n")
                    }
                }

                // Export charges
                val charges = chargeRepository.getAllCharges().firstOrNull() ?: emptyList()
                val chargesFile = File(downloadsDir, "bydmate_charges_$timestamp.csv")
                FileWriter(chargesFile).use { writer ->
                    writer.append("id,start_ts,end_ts,soc_start,soc_end,kwh_charged,kwh_charged_soc,max_power_kw,type,cost,lat,lon,bat_temp_avg,bat_temp_max,bat_temp_min,avg_power_kw,status,cell_voltage_min,cell_voltage_max,voltage_12v,exterior_temp,merged_count\n")
                    for (charge in charges) {
                        writer.append("${charge.id},${charge.startTs},${charge.endTs ?: ""},")
                        writer.append("${charge.socStart ?: ""},${charge.socEnd ?: ""},")
                        writer.append("${charge.kwhCharged ?: ""},${charge.kwhChargedSoc ?: ""},")
                        writer.append("${charge.maxPowerKw ?: ""},${charge.type ?: ""},")
                        writer.append("${charge.cost ?: ""},${charge.lat ?: ""},")
                        writer.append("${charge.lon ?: ""},${charge.batTempAvg ?: ""},")
                        writer.append("${charge.batTempMax ?: ""},${charge.batTempMin ?: ""},")
                        writer.append("${charge.avgPowerKw ?: ""},${charge.status},")
                        writer.append("${charge.cellVoltageMin ?: ""},${charge.cellVoltageMax ?: ""},")
                        writer.append("${charge.voltage12v ?: ""},${charge.exteriorTemp ?: ""},")
                        writer.append("${charge.mergedCount}\n")
                    }
                }

                // Export GPS track points
                val tripPoints = tripPointDao.getAll()
                val pointsFile = File(downloadsDir, "bydmate_trip_points_$timestamp.csv")
                FileWriter(pointsFile).use { writer ->
                    writer.append("id,trip_id,timestamp,lat,lon,speed_kmh\n")
                    for (p in tripPoints) {
                        writer.append("${p.id},${p.tripId},${p.timestamp},")
                        writer.append("${p.lat},${p.lon},${p.speedKmh ?: ""}\n")
                    }
                }

                // Export idle drains (parked battery drain)
                val idleDrains = idleDrainDao.getAll()
                val drainsFile = File(downloadsDir, "bydmate_idle_drains_$timestamp.csv")
                FileWriter(drainsFile).use { writer ->
                    writer.append("id,start_ts,end_ts,soc_start,soc_end,kwh_consumed\n")
                    for (d in idleDrains) {
                        writer.append("${d.id},${d.startTs},${d.endTs ?: ""},")
                        writer.append("${d.socStart ?: ""},${d.socEnd ?: ""},${d.kwhConsumed ?: ""}\n")
                    }
                }

                val tripCount = trips.size
                val chargeCount = charges.size
                _uiState.update {
                    it.copy(
                        exportStatus = appContext.getString(R.string.settings_export_done, tripCount, chargeCount, tripPoints.size, idleDrains.size) + "\n-> ${downloadsDir.absolutePath}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(exportStatus = appContext.getString(R.string.settings_error_with_message, e.message ?: "?"))
                }
            }
        }
    }

    /** Clear the export status message. */
    fun clearExportStatus() {
        _uiState.update { it.copy(exportStatus = null) }
    }

    /** Import trip history from BYD energydata database. */
    fun importBydHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(importStatus = "Импорт...") }
            val result = historyImporter.runSync()
            if (result.isError) {
                _uiState.update {
                    it.copy(importStatus = "Ошибка: ${result.error}")
                }
            } else {
                val status = result.details
                    ?: "Импортировано ${result.count} поездок из BYD"
                _uiState.update { it.copy(importStatus = status) }
            }
        }
    }

    /** Run full diagnostics: BYD storage, our DB, permissions. */
    fun runDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(diagnosticLog = "Диагностика...") }

            val sb = StringBuilder()
            val sdf = SimpleDateFormat("dd.MM.yy HH:mm:ss", Locale.US)

            // 1. Permissions
            sb.appendLine("=== Разрешения ===")
            val perms = listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
            for (perm in perms) {
                val granted = ContextCompat.checkSelfPermission(appContext, perm) ==
                    PackageManager.PERMISSION_GRANTED
                val name = perm.substringAfterLast(".")
                sb.appendLine("$name: ${if (granted) "✓" else "✗"}")
            }

            // 2. BYD energydata
            try {
                val bydReport = energyDataReader.diagnose()
                sb.appendLine()
                sb.append(bydReport)
            } catch (e: Exception) {
                sb.appendLine("\nОШИБКА BYD: ${e.message}")
            }

            // 3. Our database
            sb.appendLine("\n=== Наша база данных ===")
            try {
                val trips = tripRepository.getAllTrips().first()
                val charges = chargeRepository.getAllCharges().first()
                val drainCount = idleDrainDao.getCount()
                val drainKwh = idleDrainDao.getTotalKwh()
                sb.appendLine("Поездок: ${trips.size}")
                sb.appendLine("Зарядок: ${charges.size}")
                sb.appendLine("Стоянок (idle drain): $drainCount (%.2f кВт·ч)".format(drainKwh))

                if (trips.isNotEmpty()) {
                    sb.appendLine("\nПоследние 5 поездок:")
                    trips.take(5).forEach { t ->
                        val startFmt = sdf.format(Date(t.startTs))
                        val endFmt = t.endTs?.let { sdf.format(Date(it)) } ?: "null"
                        sb.appendLine("#${t.id}: $startFmt – $endFmt")
                        sb.appendLine("  km=${t.distanceKm ?: "-"}, kwh=${t.kwhConsumed ?: "-"}, " +
                            "soc=${t.socStart ?: "-"}→${t.socEnd ?: "-"}, " +
                            "speed=${t.avgSpeedKmh?.let { "%.0f".format(it) } ?: "-"}")
                        sb.appendLine("  raw: start=${t.startTs}, end=${t.endTs ?: "null"}")
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("ОШИБКА: ${e.message}")
            }

            _uiState.update { it.copy(diagnosticLog = sb.toString()) }
        }
    }

    private fun readBootInfo(): String? {
        return try {
            val prefs = appContext.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            val ts = prefs.getLong(BootReceiver.KEY_LAST_BOOT_TS, 0L)
            if (ts == 0L) return null
            val method = prefs.getString(BootReceiver.KEY_LAST_BOOT_METHOD, "?") ?: "?"
            val sdf = SimpleDateFormat("dd.MM.yy HH:mm:ss", Locale.US)
            "${sdf.format(Date(ts))} ($method)"
        } catch (_: Exception) { null }
    }

    private fun readChainLog(): String? {
        return try {
            val prefs = appContext.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            val log = prefs.getString(BootReceiver.KEY_CHAIN_LOG, null)
            if (log.isNullOrBlank()) null else log
        } catch (_: Exception) { null }
    }

    fun saveOpenRouterApiKey(value: String) {
        _uiState.update { it.copy(openRouterApiKey = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_OPENROUTER_API_KEY, value)
        }
    }

    fun selectModel(model: OpenRouterModel) {
        _uiState.update { it.copy(
            openRouterModel = model.id,
            openRouterModelName = model.name,
            showModelPicker = false
        ) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_OPENROUTER_MODEL, model.id)
        }
    }

    fun showModelPicker() {
        val apiKey = _uiState.value.openRouterApiKey
        if (apiKey.isBlank()) return
        _uiState.update { it.copy(showModelPicker = true, modelsLoading = true) }
        viewModelScope.launch {
            val models = insightsManager.getModels(apiKey)
            _uiState.update { it.copy(availableModels = models, modelsLoading = false) }
        }
    }

    fun hideModelPicker() {
        _uiState.update { it.copy(showModelPicker = false) }
    }

    fun saveAiSettings() {
        val apiKey = _uiState.value.openRouterApiKey
        val model = _uiState.value.openRouterModel
        if (apiKey.isBlank() || model.isBlank()) {
            _uiState.update { it.copy(aiSaveStatus = appContext.getString(R.string.settings_ai_no_key_model)) }
            return
        }
        // "Загрузка инсайта..." is a programmatic state key used in SettingsScreen for comparison
        // AND as display text; keep in sync with R.string.settings_ai_loading_label
        _uiState.update { it.copy(aiSaveStatus = appContext.getString(R.string.settings_ai_loading_label)) }
        viewModelScope.launch {
            val insight = insightsManager.refresh()
            if (insight != null) {
                _uiState.update { it.copy(aiSaveStatus = appContext.getString(R.string.settings_ai_done)) }
            } else {
                _uiState.update { it.copy(aiSaveStatus = appContext.getString(R.string.settings_ai_fetch_error)) }
            }
        }
    }

    // --- Smart Home (hidden) ---

    private var versionTapCount = 0
    private var lastVersionTapTime = 0L

    fun onVersionTap() {
        val now = System.currentTimeMillis()
        if (now - lastVersionTapTime > 2000) versionTapCount = 0
        lastVersionTapTime = now
        versionTapCount++
        if (versionTapCount >= 7) {
            _uiState.update { it.copy(devModeUnlocked = true) }
            versionTapCount = 0
        }
    }

    fun updateAliceEndpoint(value: String) {
        _uiState.update { it.copy(aliceEndpoint = value) }
    }

    fun updateAliceApiKey(value: String) {
        _uiState.update { it.copy(aliceApiKey = value) }
    }

    fun saveAliceSettings() {
        val state = _uiState.value
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_ALICE_ENDPOINT, state.aliceEndpoint)
            settingsRepository.setString(SettingsRepository.KEY_ALICE_API_KEY, state.aliceApiKey)
            val enabled = state.aliceEndpoint.isNotBlank() && state.aliceApiKey.isNotBlank()
            settingsRepository.setString(SettingsRepository.KEY_ALICE_ENABLED, enabled.toString())
            _uiState.update { it.copy(aliceEnabled = enabled, aliceSaveStatus = appContext.getString(R.string.settings_saved)) }
            delay(2000)
            _uiState.update { it.copy(aliceSaveStatus = null) }
        }
    }

    fun toggleAlice(enabled: Boolean) {
        _uiState.update { it.copy(aliceEnabled = enabled) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_ALICE_ENABLED, enabled.toString())
        }
    }

    fun toggleAbrpTelemetry(enabled: Boolean) {
        // Switching ON without a user token is meaningless — Iternio rejects the
        // call and we'd just spam failed requests. UI also gates on this flag,
        // but enforce here so programmatic callers can't bypass it.
        val effective = enabled && _uiState.value.abrpUserToken.isNotBlank()
        _uiState.update { it.copy(abrpTelemetryEnabled = effective) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_ABRP_ENABLED, effective.toString())
        }
    }

    fun updateAbrpApiKey(value: String) {
        _uiState.update { it.copy(abrpApiKey = value) }
    }

    fun updateAbrpUserToken(value: String) {
        _uiState.update { it.copy(abrpUserToken = value) }
    }

    fun updateAbrpCarModel(value: String) {
        _uiState.update { it.copy(abrpCarModel = value) }
    }

    fun saveAbrpSettings() {
        val state = _uiState.value
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_ABRP_API_KEY, state.abrpApiKey.trim())
            settingsRepository.setString(SettingsRepository.KEY_ABRP_USER_TOKEN, state.abrpUserToken.trim())
            settingsRepository.setString(SettingsRepository.KEY_ABRP_CAR_MODEL, state.abrpCarModel.trim())
            val enabled = state.abrpTelemetryEnabled && state.abrpUserToken.isNotBlank()
            settingsRepository.setString(SettingsRepository.KEY_ABRP_ENABLED, enabled.toString())
            _uiState.update {
                it.copy(
                    abrpTelemetryEnabled = enabled,
                    abrpSaveStatus = appContext.getString(R.string.settings_saved),
                )
            }
            delay(2000)
            _uiState.update { it.copy(abrpSaveStatus = null) }
        }
    }

    fun saveMapTileSource(source: String) {
        _uiState.update { it.copy(mapTileSource = source) }
        viewModelScope.launch {
            settingsRepository.setMapTileSource(source)
        }
    }

    fun saveConsumptionGood(value: String) {
        _uiState.update { it.copy(consumptionGood = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_CONSUMPTION_GOOD, value)
        }
    }

    fun saveConsumptionBad(value: String) {
        _uiState.update { it.copy(consumptionBad = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_CONSUMPTION_BAD, value)
        }
    }

    private var logProcess: Process? = null
    private var logFile: File? = null
    private var logAutoStopJob: Job? = null

    companion object {
        private const val LOG_MAX_DURATION_MS = 2 * 60 * 60 * 1000L // 2 hours auto-stop
        private const val LOG_MAX_SIZE_BYTES = 50 * 1024 * 1024L // 50 MB max
    }

    /**
     * Writes a diagnostic header to the recording file before piping logcat.
     * Captures app / device / setting context that issue reports (e.g. #19)
     * routinely lack: which battery capacity the user typed (raw + parsed,
     * which surfaces the comma-decimal bug immediately), whether autoservice /
     * ABRP are configured, and whether the BYD energydata trip source is
     * reachable.
     */
    private suspend fun writeDiagnosticHeader(file: File) = withContext(Dispatchers.IO) {
        // Build the header. Each piece is independently caught so a single
        // failing getter doesn't drop the whole header.
        val header = buildString {
            appendLine("=== BYDMate diagnostic dump ===")
            try {
                val pkg = appContext.packageName
                val pi = appContext.packageManager.getPackageInfo(pkg, 0)
                val versionName = pi.versionName ?: "?"
                val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                    pi.longVersionCode.toString()
                else
                    @Suppress("DEPRECATION") pi.versionCode.toString()
                appendLine("timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("app: $pkg v$versionName (code=$versionCode)")
                appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                appendLine("fingerprint: ${android.os.Build.FINGERPRINT}")
                appendLine("locale: jvm=${Locale.getDefault().toLanguageTag()} app=${localePreferences.getLanguage() ?: "(unset)"}")
            } catch (e: Exception) {
                appendLine("(failed to gather app/device metadata: ${e.message})")
            }

            appendLine("--- settings ---")
            try {
                val dataSource = settingsRepository.getDataSource().name
                val capacityRaw = settingsRepository.getString(SettingsRepository.KEY_BATTERY_CAPACITY, "")
                val capacityParsed = settingsRepository.getBatteryCapacity()
                val abrpEnabled = settingsRepository.getString(SettingsRepository.KEY_ABRP_ENABLED, "false") == "true"
                val abrpTokenLen = settingsRepository.getString(SettingsRepository.KEY_ABRP_USER_TOKEN, "").length
                val abrpCarModel = settingsRepository.getString(SettingsRepository.KEY_ABRP_CAR_MODEL, "")
                appendLine("data_source: $dataSource")
                appendLine("battery_capacity: raw=\"$capacityRaw\" parsed=$capacityParsed")
                appendLine("abrp_enabled: $abrpEnabled token_len=$abrpTokenLen car_model=\"$abrpCarModel\"")
            } catch (e: Exception) {
                appendLine("(failed to gather settings: ${e.message})")
            }

            appendLine("--- vehicle data sources ---")
            try {
                val energyDb = File("/storage/emulated/0/energydata")
                appendLine("energydata dir: exists=${energyDb.exists()} isDir=${energyDb.isDirectory}")
                if (energyDb.exists() && energyDb.isDirectory) {
                    val files = energyDb.listFiles()
                    if (files == null) {
                        appendLine("  listFiles: null (permission?)")
                    } else {
                        files.forEach { appendLine("  ${it.name} (${it.length()}B, mtime=${it.lastModified()})") }
                    }
                }
            } catch (e: Exception) {
                appendLine("(failed to gather vehicle data sources: ${e.message})")
            }
            appendLine("===============================")
            appendLine()
        }

        // File-write failures must surface so the user sees a meaningful
        // error instead of a "запись начата" status next to an empty file.
        FileWriter(file, false).use { it.write(header) }
    }

    fun startLogRecording() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "bydmate_logs_$timestamp.txt"

                val saveDir = listOf(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    File("/storage/emulated/0/Download"),
                    appContext.getExternalFilesDir(null)
                ).firstOrNull { dir ->
                    dir != null && (dir.exists() || dir.mkdirs()) && dir.canWrite()
                }

                if (saveDir == null) {
                    _uiState.update { it.copy(logSaveStatus = appContext.getString(R.string.settings_log_error_no_fs_access)) }
                    return@launch
                }

                logFile = File(saveDir, fileName)

                // Diagnostic header — written directly to the file before the
                // logcat pipe so issue #19-style reports include device / setting
                // context up front instead of being buried in logcat noise.
                writeDiagnosticHeader(logFile!!)

                // Clear logcat buffer and start continuous recording
                Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()

                logProcess = Runtime.getRuntime().exec(arrayOf(
                    "logcat", "-v", "time",
                    "-s", "BootReceiver:*",
                    "TrackingService:*", "TripTracker:*",
                    "HistoryImporter:*", "EnergyDataReader:*",
                    "AutoserviceClient:*", "AdbOnDeviceClient:*",
                    "IternioTelemetryClient:*", "BatteryHealthRepository:*",
                    "ChargesViewModel:*", "ChargeRepository:*",
                    // v3.0.3: widen coverage to write/daemon/automation subsystems
                    "HelperClient:*", "HelperBootstrap:*",
                    "ActionDispatcher:*", "VehicleApiImpl:*",
                    "AutomationEngine:*", "AutoserviceDetector:*",
                    "SteeringWheelKeySvc:*"
                ))

                // Background thread to pipe logcat to file with size limit.
                // Open in append mode so the diagnostic header written by
                // writeDiagnosticHeader() is preserved instead of overwritten.
                Thread {
                    try {
                        logProcess?.inputStream?.bufferedReader()?.use { reader ->
                            val target = logFile ?: return@Thread
                            java.io.FileOutputStream(target, /* append = */ true)
                                .bufferedWriter().use { writer ->
                                var line = reader.readLine()
                                while (line != null) {
                                    // Stop if file exceeds size limit
                                    if (target.length() > LOG_MAX_SIZE_BYTES) {
                                        writer.write("--- LOG STOPPED: file size limit reached (50 MB) ---")
                                        writer.newLine()
                                        break
                                    }
                                    writer.write(line)
                                    writer.newLine()
                                    writer.flush()
                                    line = reader.readLine()
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }.start()

                // Auto-stop after 2 hours
                logAutoStopJob = viewModelScope.launch {
                    delay(LOG_MAX_DURATION_MS)
                    stopLogRecording()
                }

                _uiState.update {
                    it.copy(isRecordingLogs = true, logSaveStatus = appContext.getString(R.string.settings_log_recording_started, logFile?.absolutePath ?: "?"))
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(logSaveStatus = appContext.getString(R.string.settings_error_with_message, e.message ?: "?")) }
            }
        }
    }

    fun stopLogRecording() {
        logAutoStopJob?.cancel()
        logAutoStopJob = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                logProcess?.destroy()
                logProcess = null

                val file = logFile
                val sizeKb = (file?.length() ?: 0) / 1024

                _uiState.update {
                    it.copy(
                        isRecordingLogs = false,
                        logSaveStatus = appContext.getString(R.string.settings_log_saved, file?.absolutePath ?: "?", sizeKb)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isRecordingLogs = false, logSaveStatus = appContext.getString(R.string.settings_error_with_message, e.message ?: "?"))
                }
            }
        }
    }

    fun showUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = true, updateDialogState = UpdateState.Idle) }
    }

    fun hideUpdateDialog() {
        // Cancel any in-flight download so the progress callback stops re-emitting
        // Downloading (which reopened the dialog) and the finished download no longer
        // fires the system install prompt after the user closed it (issue #23).
        downloadJob?.cancel()
        downloadJob = null
        _uiState.update { it.copy(showUpdateDialog = false, updateDialogState = UpdateState.Idle) }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        UpdateChecker.setAutoCheckEnabled(appContext, enabled)
        _uiState.update { it.copy(autoCheckUpdates = enabled) }
    }

    /** Check for app updates on GitHub. */
    fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(updateDialogState = UpdateState.Checking, updateStatus = appContext.getString(R.string.settings_update_check_in_progress)) }
            try {
                val update = updateChecker.checkForUpdate(appContext, forceCheck = true)
                if (update != null) {
                    _uiState.update {
                        it.copy(
                            updateDialogState = UpdateState.Available(
                                version = update.version,
                                notes = update.releaseNotes ?: ""
                            ),
                            updateStatus = appContext.getString(R.string.settings_update_available_short, update.version)
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            updateDialogState = UpdateState.UpToDate,
                            updateStatus = appContext.getString(R.string.settings_update_up_to_date)
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        updateDialogState = UpdateState.Error(e.message ?: "Unknown error"),
                        updateStatus = appContext.getString(R.string.settings_error_with_message, e.message ?: "?")
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Config backup / restore
    // -------------------------------------------------------------------------

    /**
     * Export the full app state (DB + prefs) to a zip file in Downloads.
     * Updates configStatus with a success path or an error message.
     */
    fun exportConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(configStatus = appContext.getString(R.string.settings_export_in_progress)) }
            try {
                val file = backupManager.export()
                _uiState.update {
                    it.copy(configStatus = appContext.getString(R.string.settings_config_export_done, file.absolutePath))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(configStatus = appContext.getString(R.string.settings_error_with_message, e.message ?: "?"))
                }
            }
        }
    }

    /**
     * Restore the full app state from a user-picked backup zip.
     * On success the process is immediately restarted so Room re-opens the replaced DB.
     * On failure configStatus is set to the error message.
     */
    fun restoreConfig(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(configStatus = appContext.getString(R.string.settings_export_in_progress)) }
            try {
                backupManager.restore(uri)
                restartApp()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(configStatus = appContext.getString(R.string.settings_error_with_message, e.message ?: "?"))
                }
            }
        }
    }

    /** Dismiss the config backup/restore status message. */
    fun clearConfigStatus() {
        _uiState.update { it.copy(configStatus = null) }
    }

    /**
     * Relaunch the app from scratch so Room re-opens the freshly restored DB file.
     * FLAG_ACTIVITY_CLEAR_TASK terminates all existing activities before the new launch.
     */
    private fun restartApp() {
        val intent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        if (intent != null) {
            appContext.startActivity(intent)
        }
        Runtime.getRuntime().exit(0)
    }

    fun downloadUpdate() {
        downloadJob = viewModelScope.launch {
            try {
                val update = updateChecker.checkForUpdate(appContext, forceCheck = true)
                if (update != null) {
                    _uiState.update {
                        it.copy(updateDialogState = UpdateState.Downloading(update.version, appContext.getString(R.string.update_downloading_start)))
                    }
                    updateChecker.downloadAndInstall(appContext, update) { progress ->
                        // Ignore late progress after the job was cancelled (Close pressed)
                        // so a closed dialog is never resurrected.
                        if (isActive) {
                            _uiState.update {
                                it.copy(updateDialogState = UpdateState.Downloading(update.version, progress))
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // cooperative cancellation from hideUpdateDialog(); not an error
            } catch (e: Exception) {
                _uiState.update { it.copy(updateDialogState = UpdateState.Error(e.message ?: "Download failed")) }
            }
        }
    }
}
