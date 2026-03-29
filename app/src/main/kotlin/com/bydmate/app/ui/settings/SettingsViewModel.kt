package com.bydmate.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.remote.DiParsClient
import com.bydmate.app.data.remote.DiPlusDbReader
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.service.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val currencySymbol: String = "Br",
    val exportStatus: String? = null,
    val importStatus: String? = null,
    val appVersion: String = "0.0.0",
    val updateStatus: String? = null,
    val diagnosticLog: String? = null,
    val logSaveStatus: String? = null,
    val isRecordingLogs: Boolean = false,
    val tripCostTariff: String = "home",
    val consumptionGood: String = SettingsRepository.DEFAULT_CONSUMPTION_GOOD,
    val consumptionBad: String = SettingsRepository.DEFAULT_CONSUMPTION_BAD
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
    private val diParsClient: DiParsClient,
    private val idleDrainDao: IdleDrainDao,
    private val diPlusDbReader: DiPlusDbReader
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(
        appVersion = getVersion()
    ))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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
                    consumptionBad = consumptionBad
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

    /** Save home (AC) tariff setting. */
    fun saveHomeTariff(value: String) {
        _uiState.update { it.copy(homeTariff = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_HOME_TARIFF, value)
        }
    }

    /** Save DC fast-charge tariff setting. */
    fun saveDcTariff(value: String) {
        _uiState.update { it.copy(dcTariff = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_DC_TARIFF, value)
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
            _uiState.update { it.copy(exportStatus = "Экспорт...") }

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

                val tripCount = trips.size
                val chargeCount = charges.size
                _uiState.update {
                    it.copy(
                        exportStatus = "Экспортировано: $tripCount поездок, $chargeCount зарядок\n→ ${downloadsDir.absolutePath}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(exportStatus = "Ошибка: ${e.message}")
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
            val result = historyImporter.forceImport()
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

    /** Import charging sessions from DiPlus ChargingLog database. */
    fun importDiPlusCharges() {
        viewModelScope.launch {
            _uiState.update { it.copy(importStatus = "Импорт DiPlus...") }
            val result = diPlusDbReader.importChargingLog()
            if (result.isError) {
                _uiState.update { it.copy(importStatus = "Ошибка: ${result.error}") }
            } else {
                val msg = buildString {
                    append("Импортировано ${result.imported} зарядок")
                    if (result.skipped > 0) append(", пропущено ${result.skipped} дублей")
                    append(" (всего в DiPlus: ${result.totalInDb})")
                }
                _uiState.update { it.copy(importStatus = msg) }
            }
        }
    }

    /** Run full diagnostics: BYD storage, our DB, DiPlus API, permissions. */
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

            // 4. DiPlus API
            sb.appendLine("\n=== DiPlus API ===")
            try {
                val testTemplate = "SOC:{电量百分比}|Speed:{车速}|Mileage:{里程}"
                val httpUrl = okhttp3.HttpUrl.Builder()
                    .scheme("http").host("127.0.0.1").port(8988)
                    .addPathSegments("api/getDiPars")
                    .addQueryParameter("text", testTemplate)
                    .build()
                val testClient = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val resp = testClient.newCall(
                    okhttp3.Request.Builder().url(httpUrl).build()
                ).execute()
                val body = resp.body?.string() ?: "(пустое тело)"
                sb.appendLine("HTTP ${resp.code}: $body")
            } catch (e: java.net.ConnectException) {
                sb.appendLine("Соединение отклонено — DiPlus не запущен")
            } catch (e: java.net.SocketTimeoutException) {
                sb.appendLine("Таймаут соединения")
            } catch (e: Exception) {
                sb.appendLine("${e.javaClass.simpleName}: ${e.message}")
            }

            _uiState.update { it.copy(diagnosticLog = sb.toString()) }
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
                    _uiState.update { it.copy(logSaveStatus = "Ошибка: нет доступа к файловой системе") }
                    return@launch
                }

                logFile = File(saveDir, fileName)

                // Clear logcat buffer and start continuous recording
                Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()

                logProcess = Runtime.getRuntime().exec(arrayOf(
                    "logcat", "-v", "time",
                    "-s", "DiParsClient:*", "TrackingService:*", "TripTracker:*",
                    "ChargeTracker:*", "IdleDrainTracker:*", "DiPlusDbReader:*",
                    "HistoryImporter:*", "EnergyDataReader:*"
                ))

                // Background thread to pipe logcat to file with size limit
                Thread {
                    try {
                        logProcess?.inputStream?.bufferedReader()?.use { reader ->
                            logFile?.bufferedWriter()?.use { writer ->
                                var line = reader.readLine()
                                while (line != null) {
                                    // Stop if file exceeds size limit
                                    if ((logFile?.length() ?: 0) > LOG_MAX_SIZE_BYTES) {
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
                    it.copy(isRecordingLogs = true, logSaveStatus = "Запись (авто-стоп через 2ч)... → ${logFile?.absolutePath}")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(logSaveStatus = "Ошибка: ${e.message}") }
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
                        logSaveStatus = "Сохранено: ${file?.absolutePath} (${sizeKb} КБ)"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isRecordingLogs = false, logSaveStatus = "Ошибка: ${e.message}")
                }
            }
        }
    }

    /** Check for app updates on GitHub. */
    fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(updateStatus = "Проверка...") }
            try {
                val update = updateChecker.checkForUpdate(appContext, forceCheck = true)
                if (update != null) {
                    _uiState.update {
                        it.copy(updateStatus = "Доступна v${update.version}. Скачивание...")
                    }
                    updateChecker.downloadAndInstall(appContext, update) { progress ->
                        _uiState.update { it.copy(updateStatus = progress) }
                    }
                } else {
                    _uiState.update {
                        it.copy(updateStatus = "Установлена последняя версия")
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(updateStatus = "Ошибка: ${e.message}")
                }
            }
        }
    }
}
