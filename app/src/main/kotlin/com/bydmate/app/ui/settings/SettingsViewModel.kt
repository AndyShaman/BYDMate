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
import android.util.Log
import android.os.SystemClock
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.agent.LlmConnectionResolver
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.backup.BackupManager
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.LlmHttpException
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.remote.OpenRouterModel
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.service.UpdateChecker
import com.bydmate.app.R
import org.json.JSONArray
import org.json.JSONObject
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
import com.bydmate.app.data.vehicle.SeatChannel
import com.bydmate.app.data.vehicle.SeatChannelStore
import com.bydmate.app.service.BootReceiver
import com.bydmate.app.ui.widget.WidgetController
import com.bydmate.app.cluster.DEFAULT_VOICE_KEYCODE
import com.bydmate.app.voice.AgentPersona
import com.bydmate.app.voice.TtsGender
import com.bydmate.app.voice.VoiceController
import com.bydmate.app.voice.VoiceLang
import com.bydmate.app.voice.VoiceModelManager
import com.bydmate.app.voice.RuStressMarker
import com.bydmate.app.voice.TtsEngine
import com.bydmate.app.voice.TtsModelManager
import com.bydmate.app.voice.TtsVoiceCatalog
import com.bydmate.app.voice.GigaAmModelManager
import com.bydmate.app.voice.ContinuousAsr
import com.bydmate.app.voice.online.TtsRouter
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
    /** Exa (api.exa.ai) BYOK for the web_search tool. Empty = openrouter:web_search fallback. */
    val exaApiKey: String = "",
    val openRouterModelName: String = "",
    val showModelPicker: Boolean = false,
    val availableModels: List<OpenRouterModel> = emptyList(),
    val modelsLoading: Boolean = false,
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
    /** Status of the last config backup/restore operation. Red if starts with error prefix. */
    val configStatus: String? = null,
    val mapTileSource: String = SettingsRepository.DEFAULT_MAP_TILE_SOURCE,
    // Voice settings
    val voiceEnabled: Boolean = false,
    /** "RU", "EN", or "" (follow app language) */
    val voiceLang: String = "",
    val voiceKeycode: Int = 0,
    val voiceModelReadyRu: Boolean = false,
    val voiceModelReadyEn: Boolean = false,
    val voiceDownloadProgress: Int = -1,   // -1 = idle, 0..100 = downloading
    val voiceDownloadLang: VoiceLang? = null,   // which lang is currently downloading
    val voiceDownloadErrorLang: VoiceLang? = null,  // which lang's last download failed; null = no error
    // TTS settings (offline synthesis of agent replies)
    val ttsEnabled: Boolean = false,
    val ttsVoice: String = TtsModelManager.DEFAULT_VOICE_ID,
    /** Voice ids whose model is on disk. Voices sharing a modelDirId (artem/alena) are
     *  always both present or both absent, since they share the download. */
    val ttsReadyVoices: Set<String> = emptySet(),
    /** voiceId -> 0..100 while downloading; absent = idle. Independent per voice. */
    val ttsDownloadProgress: Map<String, Int> = emptyMap(),
    val ttsDownloadFailed: Set<String> = emptySet(),
    val ttsRate: Float = 1.0f,
    val ttsLiveliness: Int = 33,
    // Wave N: online TTS source ("offline" or a backend id: "gemini"/"minimax")
    val ttsSource: String = TtsRouter.OFFLINE,
    val minimaxProvider: String = "official",
    /** Never expose the raw MiniMax key in state -- only whether one is saved. */
    val minimaxKeySet: Boolean = false,
    // GigaAM v2 ASR settings (free-form Russian speech recognition, offline)
    val gigaAmModelReady: Boolean = false,
    val gigaAmDownloadProgress: Int = -1,   // -1 = idle, 0..100 = downloading
    val gigaAmDownloadFailed: Boolean = false,
    /** When true, the native BYD voice assistant is disabled (pm disable-user). */
    val disableNativeAssistant: Boolean = false,
    // Voice agent (Phase 1, hidden)
    val agentEnabled: Boolean = false,
    val modelTestResult: String? = null,
    val modelTestRunning: Boolean = false,
    // Agent identity: display/wake name + persona (spoken-reply style) + gender ("m"/"f")
    val agentName: String = "",
    val agentPersona: String = AgentPersona.NAVIGATOR.id,
    val agentGender: String = "m",
    // Wave J: multi-provider LLM connections (OpenRouter / z.ai / custom)
    val zaiApiKey: String = "",
    val customName: String = "",
    val customBaseUrl: String = "",
    val customApiKey: String = "",
    val customModel: String = "",
    val primaryConn: String = "openrouter",
    val fallbackConn: String = "",
    val connTestRunning: String? = null,
    val connTestResults: Map<String, String> = emptyMap(),
    // Wave O T11: custom connection model list
    val customModelList: List<String> = emptyList(),
    val customModelsError: String? = null,
    val showCustomModelPicker: Boolean = false,
    val customModelsLoading: Boolean = false,

) {
    val openRouterConfigured: Boolean get() = openRouterApiKey.isNotBlank() && openRouterModel.isNotBlank()
    val zaiConfigured: Boolean get() = zaiApiKey.isNotBlank()
    val customConfigured: Boolean get() =
        customBaseUrl.isNotBlank() && customApiKey.isNotBlank() && customModel.isNotBlank()
    val agentConnConfigured: Boolean get() = when (primaryConn.ifBlank { "openrouter" }) {
        "zai" -> zaiConfigured
        "custom" -> customConfigured
        else -> openRouterConfigured
    }
}

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
    private val chargingStateStore: com.bydmate.app.data.charging.ChargingStateStore,
    private val catchUpJournal: com.bydmate.app.data.charging.CatchUpJournal,
    private val voiceModelManager: VoiceModelManager,
    private val ttsModelManager: TtsModelManager,
    private val ruStressMarker: RuStressMarker,
    private val gigaAmModelManager: GigaAmModelManager,
    private val continuousAsr: ContinuousAsr,
    private val ttsEngine: TtsEngine,
    private val voiceController: VoiceController,
    private val seatChannelStore: SeatChannelStore,
    private val helperClient: com.bydmate.app.data.vehicle.HelperClient,
    private val helperBootstrap: com.bydmate.app.data.vehicle.HelperBootstrap,
    private val agentOrchestrator: AgentOrchestrator,
    private val llmConnectionResolver: LlmConnectionResolver,
    private val openRouterClient: OpenRouterClient,
    private val placeRepository: PlaceRepository,
    private val energyDataDeadDetector: com.bydmate.app.data.local.EnergyDataDeadDetector,
) : ViewModel() {

    private val _appLanguage = MutableStateFlow(localePreferences.getLanguage() ?: "ru")
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _agentName = MutableStateFlow(
        appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .getString("agent_name", "") ?: ""
    )
    val agentName: StateFlow<String> = _agentName.asStateFlow()

    private val _agentPersona = MutableStateFlow(
        appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .getString("agent_persona", AgentPersona.NAVIGATOR.id) ?: AgentPersona.NAVIGATOR.id
    )
    val agentPersona: StateFlow<String> = _agentPersona.asStateFlow()

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

    /** Forget the remembered seat write-channel; next seat command re-probes primary→fallback. */
    fun resetSeatChannel() = seatChannelStore.setWinner(SeatChannel.UNKNOWN)

    /** Forget the dead-energydata verdict; the next drives re-detect the trip source (#63). */
    fun resetTripSourceDetection() = energyDataDeadDetector.reset()

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
            val exaApiKey = settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "")

            // Smart Home settings
            val aliceEndpoint = settingsRepository.getString(SettingsRepository.KEY_ALICE_ENDPOINT, "")
            val aliceApiKey = settingsRepository.getString(SettingsRepository.KEY_ALICE_API_KEY, "")
            val aliceEnabled = settingsRepository.getString(SettingsRepository.KEY_ALICE_ENABLED, "false") == "true"

            val abrpEnabled = settingsRepository.getString(SettingsRepository.KEY_ABRP_ENABLED, "false") == "true"
            val abrpApiKey = settingsRepository.getString(SettingsRepository.KEY_ABRP_API_KEY, "")
            val abrpUserToken = settingsRepository.getString(SettingsRepository.KEY_ABRP_USER_TOKEN, "")
            val abrpCarModel = settingsRepository.getString(SettingsRepository.KEY_ABRP_CAR_MODEL, "")
            val mapTileSource = settingsRepository.getMapTileSource()
            val disableNativeAssistant =
                settingsRepository.getString(SettingsRepository.KEY_DISABLE_NATIVE_ASSISTANT, "false") == "true"

            // Voice settings
            val voiceEnabled = settingsRepository.isVoiceEnabled()
            val voiceLang = settingsRepository.getVoiceLang()
            val voiceKeycode = settingsRepository.getVoiceKeycode().let {
                if (it == 0) DEFAULT_VOICE_KEYCODE else it
            }
            val voiceReadyRu = voiceModelManager.isReady(VoiceLang.RU)
            val voiceReadyEn = voiceModelManager.isReady(VoiceLang.EN)

            val ttsEnabled = settingsRepository.isTtsEnabled()
            // Resolve through the catalog so a legacy id (retired "denis"/"dmitri") shows its
            // migrated voice selected in the UI, same as playback already resolves it.
            val ttsVoice = TtsVoiceCatalog.byId(
                appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
                    .getString("tts_voice", TtsModelManager.DEFAULT_VOICE_ID) ?: TtsModelManager.DEFAULT_VOICE_ID,
            ).id
            val ttsReadyVoices = TtsVoiceCatalog.ALL.filter { ttsModelManager.isReady(it) }
                .map { it.id }.toSet()
            val ttsRate = appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getFloat("tts_rate", 1.0f)
            val ttsLiveliness = appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getInt("tts_liveliness", 33)
            val voicePrefsForTts = appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
            val rawTtsSource = voicePrefsForTts.getString("tts_source", TtsRouter.OFFLINE) ?: TtsRouter.OFFLINE
            // Wave O T3: "openai" backend was removed (model not available on OpenRouter) --
            // rewrite persisted "openai" to "offline" so no backend lookup ever silently fails.
            val ttsSource = if (rawTtsSource == "openai") {
                Log.i("SettingsViewModel", "tts_source was 'openai' (backend removed) -- migrating to offline")
                voicePrefsForTts.edit().putString("tts_source", TtsRouter.OFFLINE).apply()
                TtsRouter.OFFLINE
            } else rawTtsSource
            val minimaxProvider = settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_PROVIDER, "official")
            val minimaxKeySet = settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_KEY, "").isNotBlank()

            val gigaAmReady = gigaAmModelManager.isReady()

            val agentEnabled = settingsRepository.isAgentEnabled()
            val agentName = appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getString("agent_name", "") ?: ""
            val agentPersona = appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getString("agent_persona", null) ?: AgentPersona.NAVIGATOR.id
            val agentGender = appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getString("agent_gender", "m") ?: "m"

            // Wave J: multi-provider LLM connections
            val zaiApiKey = settingsRepository.getString(SettingsRepository.KEY_ZAI_API_KEY, "")
            val customName = settingsRepository.getString(SettingsRepository.KEY_CUSTOM_NAME, "")
            val customBaseUrl = settingsRepository.getString(SettingsRepository.KEY_CUSTOM_BASE_URL, "")
            val customApiKey = settingsRepository.getString(SettingsRepository.KEY_CUSTOM_API_KEY, "")
            val customModel = settingsRepository.getString(SettingsRepository.KEY_CUSTOM_MODEL, "")
            val primaryConn = settingsRepository.getString(SettingsRepository.KEY_AGENT_PRIMARY_CONN, "openrouter")
            val fallbackConn = settingsRepository.getString(SettingsRepository.KEY_AGENT_FALLBACK_CONN, "")

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
                    exaApiKey = exaApiKey,
                    openRouterModelName = modelId.substringAfterLast("/").substringBefore(":"),
                    aliceEndpoint = aliceEndpoint,
                    aliceApiKey = aliceApiKey,
                    aliceEnabled = aliceEnabled,
                    abrpTelemetryEnabled = abrpEnabled,
                    abrpApiKey = abrpApiKey,
                    abrpUserToken = abrpUserToken,
                    abrpCarModel = abrpCarModel,
                    mapTileSource = mapTileSource,
                    disableNativeAssistant = disableNativeAssistant,
                    voiceEnabled = voiceEnabled,
                    voiceLang = voiceLang,
                    voiceKeycode = voiceKeycode,
                    voiceModelReadyRu = voiceReadyRu,
                    voiceModelReadyEn = voiceReadyEn,
                    ttsEnabled = ttsEnabled,
                    ttsVoice = ttsVoice,
                    ttsReadyVoices = ttsReadyVoices,
                    ttsRate = ttsRate,
                    ttsLiveliness = ttsLiveliness,
                    ttsSource = ttsSource,
                    minimaxProvider = minimaxProvider,
                    minimaxKeySet = minimaxKeySet,
                    gigaAmModelReady = gigaAmReady,
                    agentEnabled = agentEnabled,
                    agentName = agentName,
                    agentPersona = agentPersona,
                    agentGender = agentGender,
                    zaiApiKey = zaiApiKey,
                    customName = customName,
                    customBaseUrl = customBaseUrl,
                    customApiKey = customApiKey,
                    customModel = customModel,
                    primaryConn = primaryConn,
                    fallbackConn = fallbackConn,
                )
            }
        }
    }

    /**
     * Toggle the native BYD voice assistant. Persists the choice and applies it immediately
     * through the helper daemon: true -> pm disable-user, false -> pm enable. Reversible.
     */
    fun setDisableNativeAssistant(disabled: Boolean) {
        _uiState.update { it.copy(disableNativeAssistant = disabled) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_DISABLE_NATIVE_ASSISTANT, disabled.toString())
            helperClient.setAppHidden("com.byd.autovoice", disabled)
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
            // When a key is entered but no model has been chosen yet, fill in the default.
            // Decision is made against the CURRENT state inside the CAS loop so a concurrent
            // selectModel() call cannot be overwritten by a stale snapshot.
            if (value.isNotBlank()) {
                var filled = false
                _uiState.update {
                    if (it.openRouterModel.isBlank()) {
                        filled = true
                        it.copy(
                            openRouterModel = DEFAULT_OPENROUTER_MODEL,
                            openRouterModelName = DEFAULT_OPENROUTER_MODEL.substringAfterLast("/").substringBefore(":"),
                        )
                    } else {
                        filled = false // reset on CAS retry
                        it
                    }
                }
                if (filled) {
                    settingsRepository.setString(SettingsRepository.KEY_OPENROUTER_MODEL, DEFAULT_OPENROUTER_MODEL)
                }
            }
        }
    }

    fun saveExaApiKey(value: String) {
        _uiState.update { it.copy(exaApiKey = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_EXA_API_KEY, value)
        }
    }

    // Wave J: multi-provider LLM connections (OpenRouter / z.ai / custom)

    fun saveZaiApiKey(value: String) = saveConnField(value, SettingsRepository.KEY_ZAI_API_KEY) { s, v -> s.copy(zaiApiKey = v) }
    fun saveCustomName(value: String) = saveConnField(value, SettingsRepository.KEY_CUSTOM_NAME) { s, v -> s.copy(customName = v) }
    fun saveCustomBaseUrl(value: String) = saveConnField(value, SettingsRepository.KEY_CUSTOM_BASE_URL) { s, v -> s.copy(customBaseUrl = v, customModelList = emptyList(), customModelsError = null) }
    fun saveCustomApiKey(value: String) = saveConnField(value, SettingsRepository.KEY_CUSTOM_API_KEY) { s, v -> s.copy(customApiKey = v) }
    fun saveCustomModel(value: String) = saveConnField(value, SettingsRepository.KEY_CUSTOM_MODEL) { s, v -> s.copy(customModel = v) }

    private fun saveConnField(
        value: String,
        key: String,
        update: (SettingsUiState, String) -> SettingsUiState,
    ) {
        _uiState.update { update(it, value) }
        viewModelScope.launch { settingsRepository.setString(key, value.trim()) }
    }

    fun applyCustomPreset(name: String, baseUrl: String, model: String) {
        saveCustomName(name)
        saveCustomBaseUrl(baseUrl)
        saveCustomModel(model)
    }

    fun selectPrimaryConn(id: String) {
        _uiState.update { it.copy(primaryConn = id) }
        viewModelScope.launch { settingsRepository.setString(SettingsRepository.KEY_AGENT_PRIMARY_CONN, id) }
    }

    fun selectFallbackConn(id: String) {
        _uiState.update { it.copy(fallbackConn = id) }
        viewModelScope.launch { settingsRepository.setString(SettingsRepository.KEY_AGENT_FALLBACK_CONN, id) }
    }

    /**
     * Round-trips a canned prompt through the given connection to verify it actually works
     * (not just that the fields are filled in). One in-flight guard per screen: a tap while
     * another check is running is ignored.
     */
    fun testConnection(connId: String) {
        if (_uiState.value.connTestRunning != null) return
        _uiState.update { it.copy(connTestRunning = connId) }
        viewModelScope.launch {
            val conn = runCatching { llmConnectionResolver.get(connId) }.getOrNull()
            val text = if (conn == null) {
                appContext.getString(R.string.settings_conn_not_configured)
            } else {
                val start = SystemClock.elapsedRealtime()
                val messages = JSONArray().put(
                    JSONObject().put("role", "user").put("content", "Ответь одним словом: готов")
                )
                val result = openRouterClient.chatRaw(conn.baseUrl, conn.apiKey, conn.model, messages, null)
                val elapsedSec = (SystemClock.elapsedRealtime() - start) / 1000.0
                result.fold(
                    onSuccess = { appContext.getString(R.string.settings_conn_check_ok, elapsedSec) },
                    onFailure = { networkErrorMessage(it) },
                )
            }
            _uiState.update {
                it.copy(connTestRunning = null, connTestResults = it.connTestResults + (connId to text))
            }
        }
    }

    /** Maps network exceptions to Russian user-readable messages. Used by testConnection and loadCustomModels.
     * Walks the cause chain (up to 5 hops) so wrapped exceptions (e.g. IOException("…", UnknownHostException))
     * are matched correctly. */
    private fun networkErrorMessage(t: Throwable): String {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 5) {
            when (cur) {
                is java.net.UnknownHostException -> return appContext.getString(R.string.settings_error_dns)
                is java.net.SocketTimeoutException -> return appContext.getString(R.string.settings_error_timeout)
                is LlmHttpException -> return appContext.getString(R.string.settings_error_with_message, "HTTP ${cur.code}")
            }
            cur = cur.cause
            depth++
        }
        return appContext.getString(R.string.settings_error_with_message, t.message ?: "?")
    }

    /** Fetches model list from the custom connection's base URL and shows the picker dialog. */
    fun loadCustomModels() {
        val baseUrl = _uiState.value.customBaseUrl
        val apiKey = _uiState.value.customApiKey
        if (baseUrl.isBlank() || apiKey.isBlank()) return
        _uiState.update { it.copy(customModelsLoading = true, customModelsError = null, customModelList = emptyList(), showCustomModelPicker = true) }
        viewModelScope.launch {
            val result = openRouterClient.fetchModelsFromUrl(baseUrl, apiKey)
            result.fold(
                onSuccess = { models ->
                    _uiState.update { it.copy(customModelList = models, customModelsLoading = false) }
                },
                onFailure = { t ->
                    _uiState.update {
                        it.copy(customModelsError = networkErrorMessage(t), customModelsLoading = false)
                    }
                },
            )
        }
    }

    fun hideCustomModelPickerDialog() {
        _uiState.update { it.copy(showCustomModelPicker = false) }
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
        _uiState.update { it.copy(showModelPicker = true) }
        loadModels(apiKey)
    }

    fun hideModelPicker() {
        _uiState.update { it.copy(showModelPicker = false) }
    }

    /**
     * Fetches the OpenRouter model list into [SettingsUiState.availableModels], shared by both the
     * AI-insights model picker (IntegrationsSection) and the agent model picker (Voice-agent section) so the
     * list is fetched once regardless of which picker triggers it. Uses the same OpenRouter API key
     * from Integrations for both.
     */
    private fun loadModels(apiKey: String = _uiState.value.openRouterApiKey) {
        if (apiKey.isBlank()) return
        _uiState.update { it.copy(modelsLoading = true) }
        viewModelScope.launch {
            val models = insightsManager.getModels(apiKey)
            _uiState.update { it.copy(availableModels = models, modelsLoading = false) }
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

    // -------------------------------------------------------------------------
    // Voice settings actions
    // -------------------------------------------------------------------------

    /**
     * Writes [enabled] to Room AND to SharedPreferences("voice") so the
     * AccessibilityService (SteeringWheelKeyService) picks it up synchronously.
     * Room (SettingsRepository) is the primary store; the prefs file is a mirror
     * required because AccessibilityServices cannot query Room on a background thread.
     */
    fun setVoiceEnabled(enabled: Boolean) {
        _uiState.update { it.copy(voiceEnabled = enabled) }
        viewModelScope.launch {
            settingsRepository.setVoiceEnabled(enabled)
            // Mirror into "voice" SharedPreferences for SteeringWheelKeyService
            appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .edit().putBoolean(SettingsRepository.KEY_VOICE_ENABLED, enabled).apply()
            // Best-effort re-bind of the a11y key service (PTT is dead without it), same
            // pattern as ClusterProjectionManager.enableStarControl: bootstrap the daemon
            // FIRST — HelperClient only resolves an existing binder, so without ensureRunning()
            // the call silently no-ops when the daemon is not up. Only needed on enable.
            if (enabled) {
                if (helperBootstrap.ensureRunning()) {
                    helperClient.enableAccessibilityService()
                } else {
                    Log.e(TAG, "helper daemon not running; cannot self-enable a11y for voice PTT")
                }
                // Pre-warm the recognizer so the first PTT after enabling voice doesn't pay the
                // cold model-load cost (Task 5). No-op if the model isn't downloaded yet.
                viewModelScope.launch(Dispatchers.IO) { runCatching { continuousAsr.warmUp() } }
            }
        }
    }

    fun setVoiceLanguage(lang: String) {
        _uiState.update { it.copy(voiceLang = lang) }
        viewModelScope.launch {
            settingsRepository.setVoiceLang(lang)
            // Mirror into SharedPreferences("voice") so VoiceGate.preferredLang()
            // and SteeringWheelKeyService can read it without querying Room.
            appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .edit().putString("voice_lang", lang).apply()
        }
    }

    private var voiceDownloadJob: Job? = null

    fun downloadVoiceModel(lang: VoiceLang) {
        if (_uiState.value.voiceDownloadProgress >= 0) return   // already downloading
        voiceDownloadJob = viewModelScope.launch {
            _uiState.update { it.copy(voiceDownloadProgress = 0, voiceDownloadLang = lang, voiceDownloadErrorLang = null) }
            val result = voiceModelManager.download(lang) { progress ->
                _uiState.update { it.copy(voiceDownloadProgress = progress) }
            }
            val ready = voiceModelManager.isReady(lang)
            _uiState.update {
                it.copy(
                    voiceDownloadProgress = -1,
                    voiceDownloadLang = null,
                    voiceDownloadErrorLang = if (result.isFailure) lang else null,
                    voiceModelReadyRu = if (lang == VoiceLang.RU) ready else it.voiceModelReadyRu,
                    voiceModelReadyEn = if (lang == VoiceLang.EN) ready else it.voiceModelReadyEn,
                )
            }
        }
    }

    fun cancelVoiceDownload() {
        voiceDownloadJob?.cancel()
        voiceDownloadJob = null
        _uiState.update { it.copy(voiceDownloadProgress = -1, voiceDownloadLang = null) }
    }

    fun deleteVoiceModel(lang: VoiceLang) {
        voiceModelManager.delete(lang)
        _uiState.update {
            it.copy(
                voiceModelReadyRu = if (lang == VoiceLang.RU) false else it.voiceModelReadyRu,
                voiceModelReadyEn = if (lang == VoiceLang.EN) false else it.voiceModelReadyEn,
            )
        }
    }

    /**
     * Persists the keycode learned from [LearnButtonDialog] into Room and into
     * SharedPreferences("voice") so SteeringWheelKeyService reads the new value immediately.
     */
    fun saveVoiceKeycode(keycode: Int) {
        _uiState.update { it.copy(voiceKeycode = keycode) }
        viewModelScope.launch {
            settingsRepository.setVoiceKeycode(keycode)
            // Mirror for SteeringWheelKeyService
            appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .edit().putInt(SettingsRepository.KEY_VOICE_KEYCODE, keycode).apply()
        }
    }

    // --- TTS (offline synthesis of agent replies) ---

    /**
     * Toggle offline TTS for agent replies. Persists via SettingsRepository (Room)
     * and mirrors into SharedPreferences("voice") under the same key, same pattern
     * as setVoiceEnabled, so VoiceGate.ttsEnabled() can read it without querying Room.
     */
    fun setTtsEnabled(enabled: Boolean) {
        _uiState.update { it.copy(ttsEnabled = enabled) }
        viewModelScope.launch {
            settingsRepository.setTtsEnabled(enabled)
            appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .edit().putBoolean(SettingsRepository.KEY_TTS_ENABLED, enabled).apply()
        }
    }

    /**
     * Switches the voice used for offline TTS. Persists into SharedPreferences("voice")
     * under "tts_voice", same access pattern as setTtsEnabled/KEY_TTS_ENABLED, so
     * SherpaTtsEngine's selectedVoice() can read it without querying Room. Download state
     * is tracked per voice (see downloadTtsVoice/deleteTtsVoice), so switching voices no
     * longer needs to cancel or reset anything here.
     */
    fun setTtsVoice(voiceId: String) {
        _uiState.update { it.copy(ttsVoice = voiceId) }
        appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .edit().putString("tts_voice", voiceId).apply()
        ttsEngine.reload()
    }

    private val ttsDownloadJobs = mutableMapOf<String, Job>()

    /** Downloads the model for [voiceId]. Voices sharing a modelDirId (artem/alena) become
     *  ready together, since they share the same on-disk download. */
    fun downloadTtsVoice(voiceId: String) {
        if (_uiState.value.ttsDownloadProgress.containsKey(voiceId)) return   // already downloading
        val voice = TtsVoiceCatalog.byId(voiceId)
        val siblingIds = TtsVoiceCatalog.ALL.filter { it.modelDirId == voice.modelDirId }.map { it.id }.toSet()
        ttsDownloadJobs[voiceId] = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    ttsDownloadProgress = it.ttsDownloadProgress + (voiceId to 0),
                    ttsDownloadFailed = it.ttsDownloadFailed - voiceId,
                )
            }
            val result = ttsModelManager.download(voice) { pct ->
                // A late delivery after deleteTtsVoice() removed this voice's progress
                // entry (idle) must not resurrect an in-progress state.
                _uiState.update {
                    if (!it.ttsDownloadProgress.containsKey(voiceId)) it
                    else it.copy(ttsDownloadProgress = it.ttsDownloadProgress + (voiceId to pct))
                }
            }
            if (result.isSuccess && ttsModelManager.ensureStressDict(voice)) {
                ruStressMarker.preload()
            }
            val ready = ttsModelManager.isReady(voice)
            _uiState.update {
                it.copy(
                    ttsDownloadProgress = it.ttsDownloadProgress - voiceId,
                    ttsReadyVoices = if (ready) it.ttsReadyVoices + siblingIds else it.ttsReadyVoices - siblingIds,
                    ttsDownloadFailed = if (result.isFailure) it.ttsDownloadFailed + voiceId else it.ttsDownloadFailed - voiceId,
                )
            }
            ttsDownloadJobs.remove(voiceId)
        }
    }

    /** Deletes the on-disk model for [voiceId]. Clears readiness for every voice sharing
     *  its modelDirId (artem/alena), since the delete removes their shared download. */
    fun deleteTtsVoice(voiceId: String) {
        ttsDownloadJobs.remove(voiceId)?.cancel()
        val voice = TtsVoiceCatalog.byId(voiceId)
        val siblingIds = TtsVoiceCatalog.ALL.filter { it.modelDirId == voice.modelDirId }.map { it.id }.toSet()
        _uiState.update {
            it.copy(
                ttsReadyVoices = it.ttsReadyVoices - siblingIds,
                ttsDownloadProgress = it.ttsDownloadProgress - voiceId,
                ttsDownloadFailed = it.ttsDownloadFailed - voiceId,
            )
        }
        // Suspend delete: serialized against download's commit section inside
        // the manager, so a cancelled download can't recreate the dir after us.
        viewModelScope.launch { ttsModelManager.delete(voice.modelDirId) }
    }

    /** Speed slider (0.7-1.4). Reloads the engine so the new rate takes effect immediately. */
    fun setTtsRate(rate: Float) {
        _uiState.update { it.copy(ttsRate = rate) }
        appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .edit().putFloat("tts_rate", rate).apply()
        ttsEngine.reload()
    }

    /** Intonation liveliness slider (0-100%). Baked into the engine config at creation,
     *  so it requires a reload to take effect. */
    fun setTtsLiveliness(value: Int) {
        _uiState.update { it.copy(ttsLiveliness = value) }
        appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .edit().putInt("tts_liveliness", value).apply()
        ttsEngine.reload()
    }

    /** Reloads the engine against the current voice/rate/liveliness and speaks a sample
     *  line through the LOCAL offline engine, so the preview always demonstrates the piper
     *  voice regardless of which online source is currently selected. */
    fun previewVoice() {
        ttsEngine.reload()
        ttsEngine.speakOffline(PREVIEW_VOICE_TEXT)
    }

    // --- Wave N: online TTS source (Gemini via OpenRouter, MiniMax) ---

    /**
     * Switches which voice renders agent replies: "offline" (the local voice list) or an
     * online backend id ("gemini"/"minimax"). Persists into SharedPreferences("voice")
     * under "tts_source", the same access pattern as setTtsVoice/setTtsRate -- TtsRouter
     * (VoiceModule.provideTtsEngine) reads it directly with no Room round-trip and no reload,
     * since it re-checks the source on every speak() call. Selecting "minimax" while its key
     * is unset is rejected here too, mirroring the disabled row in the UI.
     */
    fun setTtsSource(source: String) {
        if (source == MINIMAX_SOURCE && !_uiState.value.minimaxKeySet) return
        _uiState.update { it.copy(ttsSource = source) }
        appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .edit().putString("tts_source", source).apply()
    }

    /** Persists the MiniMax transport ("official"/"fal"/"replicate") read by MiniMaxTtsBackend. */
    fun setMinimaxProvider(provider: String) {
        _uiState.update { it.copy(minimaxProvider = provider) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_MINIMAX_TTS_PROVIDER, provider)
        }
    }

    /**
     * Persists the MiniMax API key. The raw value never enters [SettingsUiState] -- only
     * [SettingsUiState.minimaxKeySet] does, so the Settings screen can never echo it back.
     * Clearing the key while it's the active tts_source falls back to offline via
     * [setTtsSource], since a selected-but-disabled MiniMax row would otherwise be stuck.
     */
    fun setMinimaxKey(key: String) {
        val trimmed = key.trim()
        _uiState.update { it.copy(minimaxKeySet = trimmed.isNotBlank()) }
        if (trimmed.isBlank() && _uiState.value.ttsSource == MINIMAX_SOURCE) {
            setTtsSource(TtsRouter.OFFLINE)
        }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_MINIMAX_TTS_KEY, trimmed)
        }
    }

    // --- GigaAM v2 ASR (free-form Russian speech recognition, offline) ---

    private var gigaAmDownloadJob: Job? = null

    fun downloadGigaAmModel() {
        if (_uiState.value.gigaAmDownloadProgress >= 0) return   // already downloading
        gigaAmDownloadJob = viewModelScope.launch {
            _uiState.update { it.copy(gigaAmDownloadProgress = 0, gigaAmDownloadFailed = false) }
            val result = gigaAmModelManager.download { pct ->
                // A late delivery after deleteGigaAmModel() reset progress to -1 (idle) must
                // not resurrect an in-progress state.
                _uiState.update {
                    if (it.gigaAmDownloadProgress < 0) it else it.copy(gigaAmDownloadProgress = pct)
                }
            }
            _uiState.update {
                it.copy(
                    gigaAmDownloadProgress = -1,
                    gigaAmModelReady = gigaAmModelManager.isReady(),
                    gigaAmDownloadFailed = result.isFailure,
                )
            }
            // Pre-warm the recognizer right after a successful download so the first PTT
            // doesn't pay the cold model-load cost (Task 5).
            if (result.isSuccess) {
                viewModelScope.launch(Dispatchers.IO) { runCatching { continuousAsr.warmUp() } }
            }
            gigaAmDownloadJob = null
        }
    }

    fun deleteGigaAmModel() {
        gigaAmDownloadJob?.cancel()
        gigaAmDownloadJob = null
        _uiState.update { it.copy(gigaAmModelReady = false, gigaAmDownloadProgress = -1, gigaAmDownloadFailed = false) }
        // Suspend delete: serialized against download's commit section inside
        // the manager, so a cancelled download can't recreate the files after us.
        viewModelScope.launch { gigaAmModelManager.delete() }
    }

    // --- Voice agent (hidden) ---

    fun setAgentEnabled(enabled: Boolean) {
        _uiState.update { it.copy(agentEnabled = enabled) }
        viewModelScope.launch { settingsRepository.setAgentEnabled(enabled) }
    }

    /**
     * Persists the agent's wake/display name into SharedPreferences("voice") under
     * "agent_name", same access pattern as setTtsEnabled/setTtsVoice, so
     * VoiceModule.provideAgentIdentity() can read it without querying Room.
     */
    fun setAgentName(name: String) {
        _agentName.value = name
        _uiState.update { it.copy(agentName = name) }
        appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .edit().putString("agent_name", name).apply()
    }

    /**
     * Switches the agent's persona (spoken-reply style). Persists into
     * SharedPreferences("voice") under "agent_persona", same access pattern as
     * setAgentName, so VoiceModule.provideAgentIdentity() can read it without querying Room.
     */
    fun setAgentPersona(id: String) {
        _agentPersona.value = id
        _uiState.update { it.copy(agentPersona = id) }
        appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .edit().putString("agent_persona", id).apply()
    }

    /**
     * Switches the agent's gender ("m"/"f"). Persists into SharedPreferences("voice")
     * under "agent_gender", same access pattern as setAgentPersona. If the currently
     * selected TTS voice doesn't match the new gender, switches it to its counterpart
     * (all catalog voices are local; online voices are added by a later task).
     */
    fun setAgentGender(gender: String) {
        _uiState.update { it.copy(agentGender = gender) }
        appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .edit().putString("agent_gender", gender).apply()
        val wantGender = if (gender == "f") TtsGender.FEMALE else TtsGender.MALE
        val currentVoice = TtsVoiceCatalog.byId(_uiState.value.ttsVoice)
        if (currentVoice.gender != wantGender) {
            setTtsVoice(TtsVoiceCatalog.counterpart(currentVoice).id)
        }
    }

    /**
     * Runs a canned prompt through the REAL agent pipeline (AgentOrchestrator, tools
     * included) so the timing matches actual voice usage, and reports wall time + answer.
     * One in-flight guard: a tap while already running is ignored.
     */
    fun testAgentModel() {
        if (_uiState.value.modelTestRunning) return
        _uiState.update { it.copy(modelTestRunning = true) }
        val state = _uiState.value
        val modelLabel = state.openRouterModel.ifBlank { "?" }
        viewModelScope.launch {
            val start = SystemClock.elapsedRealtime()
            val result = agentOrchestrator.ask(AGENT_TEST_PROMPT)
            val elapsedSec = (SystemClock.elapsedRealtime() - start) / 1000.0
            val text = when (result) {
                is AgentResult.Answer -> appContext.getString(
                    R.string.agent_test_model_result, modelLabel, elapsedSec, result.text.take(80)
                )
                AgentResult.Disabled -> appContext.getString(R.string.agent_test_model_disabled)
                is AgentResult.Error -> appContext.getString(R.string.settings_error_with_message, result.message)
            }
            _uiState.update { it.copy(modelTestRunning = false, modelTestResult = text) }
        }
    }

    private var logProcess: Process? = null
    private var logFile: File? = null
    private var logAutoStopJob: Job? = null

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val LOG_MAX_DURATION_MS = 2 * 60 * 60 * 1000L // 2 hours auto-stop
        /** Slug verified in the live OpenRouter catalog (2026-07-08). */
        internal const val DEFAULT_OPENROUTER_MODEL = "google/gemini-3.1-flash-lite"
        private const val LOG_MAX_SIZE_BYTES = 50 * 1024 * 1024L // 50 MB max
        private const val PREVIEW_VOICE_TEXT =
            "Маршрут построен. Через двести метров поверните направо."
        private const val AGENT_TEST_PROMPT =
            "Проверка связи. Вызови инструмент get_vehicle_state и ответь одним коротким " +
                "предложением: какой заряд батареи."
        private const val MINIMAX_SOURCE = "minimax"
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

            appendLine("--- charging catch-up ---")
            try {
                val anchor = chargingStateStore.load()
                val anchorTs = if (anchor.ts > 0L)
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(anchor.ts))
                else "(unset)"
                appendLine(
                    "anchor: soc=${anchor.socPercent} mileageKm=${anchor.mileageKm} " +
                        "ts=$anchorTs pending=${chargingStateStore.loadChargePending()}"
                )
                val journal = catchUpJournal.read()
                appendLine("journal:")
                if (journal.isBlank()) {
                    appendLine("  (empty)")
                } else {
                    journal.lines().forEach { appendLine("  $it") }
                }
            } catch (e: Exception) {
                appendLine("(failed to gather charging catch-up state: ${e.message})")
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
                    appendLine("liveness: ${energyDataDeadDetector.debugState()}")
                }
            } catch (e: Exception) {
                appendLine("(failed to gather vehicle data sources: ${e.message})")
            }

            appendLine("--- audio ---")
            try {
                val am = appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val musicVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val musicMax = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                appendLine("music_stream: vol=$musicVol/$musicMax active=${am.isMusicActive}")
                // BYD firmwares expose a dedicated voice stream 17 (BTTS); its absence on a
                // platform means agent TTS falls back into the (ducked) media stream.
                val bttsMax = runCatching { am.getStreamMaxVolume(17) }.getOrNull()
                val bttsVol = runCatching { am.getStreamVolume(17) }.getOrNull()
                appendLine("byd_btts_stream17: " + if (bttsMax != null) "present vol=$bttsVol/$bttsMax" else "absent")
                val preDuck = appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
                    .getInt("pre_duck_volume", -1)
                appendLine("pre_duck_volume: " + if (preDuck >= 0) "$preDuck" else "(none)")
            } catch (e: Exception) { appendLine("(failed to gather audio state: ${e.message})") }

            appendLine("--- native assistant packages ---")
            try {
                val pref = settingsRepository.getString(SettingsRepository.KEY_DISABLE_NATIVE_ASSISTANT, "")
                appendLine("disable_native_assistant pref: \"$pref\"")
                // Same package family the helper daemon disables via TX_SET_APP_HIDDEN.
                val pm = appContext.packageManager
                for (pkg in listOf("com.byd.autovoice", "com.byd.autovoice.engine", "com.byd.autovoice.tts")) {
                    val state = runCatching { enabledSettingName(pm.getApplicationEnabledSetting(pkg)) }
                        .getOrElse { "not installed" }
                    appendLine("$pkg: $state")
                }
            } catch (e: Exception) { appendLine("(failed to gather assistant package state: ${e.message})") }

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
                    "SteeringWheelKeySvc:*",
                    // v3.6: voice/audio diagnostics (issue #78 + Song volume reports)
                    "AudioCapture:*", "SherpaTtsEngine:*", "VoiceController:*"
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

/** Human-readable name for [android.content.pm.PackageManager.getApplicationEnabledSetting] values. */
internal fun enabledSettingName(state: Int): String = when (state) {
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "DEFAULT (enabled)"
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "ENABLED"
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "DISABLED"
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "DISABLED_USER"
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> "DISABLED_UNTIL_USED"
    else -> "UNKNOWN($state)"
}
