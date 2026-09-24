package com.bydmate.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import android.util.Log
import android.os.SystemClock
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.agent.LlmConnectionResolver
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.backup.AutoBackupPeriod
import com.bydmate.app.data.backup.AutoBackupRunner
import com.bydmate.app.data.backup.AutoBackupScheduler
import com.bydmate.app.data.backup.BackupPart
import com.bydmate.app.data.backup.BackupManager
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.backup.TelegramChat
import com.bydmate.app.data.backup.TelegramError
import com.bydmate.app.data.backup.TelegramSinkException
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.diagnostics.LogRecorder
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.push.fidRecorderEnabled
import com.bydmate.app.helper.push.FID_REC_NO_ERROR
import com.bydmate.app.helper.push.fidRecorderStatusLines
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.LlmHttpException
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.remote.OpenRouterModel
import com.bydmate.app.data.local.dao.TariffPeriodDao
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity
import com.bydmate.app.domain.cost.CostCalculator
import com.bydmate.app.domain.cost.MeasuredLosses
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.service.TrackingService
import com.bydmate.app.service.UpdateChecker
import com.bydmate.app.util.applyAppLanguage
import com.bydmate.app.util.CrashLog
import com.bydmate.app.util.AppStrings
import com.bydmate.app.util.appLocalizedContext
import com.bydmate.app.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.bydmate.app.data.vehicle.DumpFidsResult
import com.bydmate.app.data.vehicle.SeatChannel
import com.bydmate.app.data.vehicle.SeatChannelStore
import com.bydmate.app.service.BootReceiver
import com.bydmate.app.cluster.DEFAULT_VOICE_KEYCODE
import com.bydmate.app.voice.AgentPersona
import com.bydmate.app.voice.TtsGender
import com.bydmate.app.voice.VoiceController
import com.bydmate.app.voice.VoiceJournal
import com.bydmate.app.voice.RuStressMarker
import com.bydmate.app.voice.TtsEngine
import com.bydmate.app.voice.TtsModelManager
import com.bydmate.app.voice.TtsVoiceCatalog
import com.bydmate.app.voice.GigaAmModelManager
import com.bydmate.app.voice.ContinuousAsr
import com.bydmate.app.voice.online.TtsRouter
import java.io.File
import java.io.FileWriter
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * UI state for the Settings screen.
 * Contains current setting values and export operation status.
 */
/** Numbers behind a refused model download, in MB, ready for the error string. */
/** A finished manual save (#238): the file in Download and the Telegram line; null = no bot connected. */
data class SavedBackup(val file: File, val telegramStatus: String?)

/** A backup picked for restore and the parts its manifest lists (#238). */
data class RestoreChoice(val file: File, val parts: Set<BackupPart>)

/** The bot backups go to and the chat it sends them into. */
data class TgBackupBinding(val botName: String, val chatName: String)

data class SpaceShortfall(val requiredMb: Long, val availableMb: Long) {
    companion object {
        private const val BYTES_PER_MB = 1024L * 1024L

        /** Maps the manager's storage failure onto UI numbers; any other failure (network,
         *  corrupt archive) is not a shortfall and yields null, so the generic error shows. */
        fun from(error: Throwable?): SpaceShortfall? =
            (error as? GigaAmModelManager.InsufficientStorageException)?.let {
                SpaceShortfall(it.requiredBytes / BYTES_PER_MB, it.availableBytes / BYTES_PER_MB)
            }
    }
}

data class SettingsUiState(
    val batteryCapacity: String = SettingsRepository.DEFAULT_BATTERY_CAPACITY,
    val homeTariff: String = SettingsRepository.DEFAULT_HOME_TARIFF,
    val dcTariff: String = SettingsRepository.DEFAULT_DC_TARIFF,
    val units: String = SettingsRepository.DEFAULT_UNITS,
    val currency: String = SettingsRepository.DEFAULT_CURRENCY,
    val currencySymbol: String = "BYN",
    val importStatus: String? = null,
    val appVersion: String = "0.0.0",
    val updateStatus: String? = null,
    val updateDialogState: UpdateState = UpdateState.Idle,
    val showUpdateDialog: Boolean = false,
    val diagnosticLog: String? = null,
    val logSaveStatus: String? = null,
    val isRecordingLogs: Boolean = false,
    /** The fid recorder row is a diagnostic tool: shown on -test and local debug builds only. */
    val fidRecorderVisible: Boolean = fidRecorderEnabled(BuildConfig.VERSION_NAME, BuildConfig.DEBUG),
    val fidRecorderRunning: Boolean = false,
    /** Path of the file the current or last run wrote, with its size; null before the first run. */
    val fidRecorderFile: String? = null,
    /** Why the last start registered nothing (no file, daemon unreachable); null when it ran. */
    val fidRecorderError: String? = null,
    val tripCostTariff: String = "home",
    val consumptionGood: String = SettingsRepository.DEFAULT_CONSUMPTION_GOOD,
    val consumptionBad: String = SettingsRepository.DEFAULT_CONSUMPTION_BAD,
    val rangeCalcMethod: String = SettingsRepository.DEFAULT_RANGE_CALC_METHOD,
    val manualRangeTable: List<SettingsRepository.ManualRangePoint> = SettingsRepository.defaultManualRangeTable(),
    val showManualRangeTableDialog: Boolean = false,
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
    /** Newest period first — the order the «Тарифы и периоды» dialog lists them in. */
    val tariffPeriods: List<TariffPeriodEntity> = emptyList(),
    val showTariffPeriodsDialog: Boolean = false,
    val tariffRecalcStatus: String? = null,
    /** Why the last period could not be saved; shown in the dialog, cleared on the next try. */
    val tariffPeriodError: String? = null,
    /** Losses derived from the meter readings the driver typed in; null until one exists. */
    val measuredLosses: MeasuredLosses? = null,
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
    val webhookEnabled: Boolean = false,
    val webhookUrl: String = "",
    val webhookSecret: String = "",
    val webhookSendLocation: Boolean = false,
    val webhookSaveStatus: String? = null,
    /** Status of the last config backup/restore operation. Red if starts with error prefix. */
    val configStatus: String? = null,
    /** Backups offered by the restore picker, newest first. Null = picker closed. */
    val restoreCandidates: List<File>? = null,
    /** Non-null = the «Что восстановить» dialog is open. */
    val restoreChoice: RestoreChoice? = null,
    /** A restore runs: save and restore stay disabled until it ends (the app restarts on success). */
    val restoreInProgress: Boolean = false,
    /** Result of the last manual save; non-null = the «Поделиться» dialog is open. */
    val savedBackup: SavedBackup? = null,
    /** Parts the manual save offers checked: the last choice (#238). */
    val manualBackupParts: Set<BackupPart> = BackupPart.DEFAULT,
    // Automatic backup (#237)
    val autoBackupPeriod: AutoBackupPeriod = AutoBackupPeriod.OFF,
    val autoBackupParts: Set<BackupPart> = BackupPart.DEFAULT,
    /** 0 = never ran. */
    val autoBackupLastTs: Long = 0L,
    val autoBackupLastResult: String = "",
    val tgBackupToken: String = "",
    /** Progress or error of the last «Проверить»; null = nothing to say. */
    val tgBackupStatus: String? = null,
    val tgBackupChecking: Boolean = false,
    /**
     * One-time code the user sends to the bot to bind their chat; lives until a successful binding.
     * Non-null = the stepper is on «Код».
     */
    val tgBackupCode: String? = null,
    /** Bot found by the last «Проверить»; the code step names it. */
    val tgBackupBotName: String = "",
    /** Non-null = a bot is connected, the stepper is on «Готово». */
    val tgBackupBinding: TgBackupBinding? = null,
    /** Status of the last fid-catalog dump. Null = idle. Red if starts with error prefix. */
    val fidDumpStatus: String? = null,
    val mapTileSource: String = SettingsRepository.DEFAULT_MAP_TILE_SOURCE,
    // Voice settings
    val voiceEnabled: Boolean = false,
    /** "RU", "EN", or "" (follow app language) */
    val voiceLang: String = "",
    val voiceKeycode: Int = 0,
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
    // GigaAM v3 ASR settings (free-form Russian speech recognition, offline)
    val gigaAmModelReady: Boolean = false,
    val gigaAmDownloadProgress: Int = -1,   // -1 = idle, 0..100 = in progress
    /** Which stage [gigaAmDownloadProgress] is currently in; null while idle. The unpack
     *  stage pulls nothing over the network and takes minutes on a DiLink 3, so the UI
     *  labels it separately instead of leaving a frozen "Downloading" bar. */
    val gigaAmDownloadPhase: GigaAmModelManager.Phase? = null,
    val gigaAmDownloadFailed: Boolean = false,
    /** Non-null when the failure was the storage precheck rather than the network. */
    val gigaAmSpaceShortfall: SpaceShortfall? = null,
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
    /** #190: which map app the navigate action opens — "yandex" (default) or "dgis". */
    val routeNavigator: String = com.bydmate.app.data.automation.RouteNavigatorUris.YANDEX,
    /** Long-term facts the agent remembered about the driver (DriverMemory). */
    val agentMemoryFacts: List<String> = emptyList(),
    // Wave J: multi-provider LLM connections (OpenRouter / z.ai / custom)
    val zaiApiKey: String = "",
    val customName: String = "",
    val customBaseUrl: String = "",
    val customApiKey: String = "",
    val customModel: String = "",
    /** Raw JSON merged into every request to the custom connection (#167); blank = nothing extra. */
    val customExtraJson: String = "",
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
class SettingsViewModel @Inject @Suppress("LongParameterList") constructor( // Hilt-injected dependencies
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val chargeRepository: ChargeRepository,
    private val updateChecker: UpdateChecker,
    private val historyImporter: HistoryImporter,
    private val energyDataReader: EnergyDataReader,
    private val idleDrainDao: IdleDrainDao,
    private val insightsManager: InsightsManager,
    private val adbOnDeviceClient: AdbOnDeviceClient,
    private val localePreferences: LocalePreferences,
    private val backupManager: BackupManager,
    private val chargingStateStore: com.bydmate.app.data.charging.ChargingStateStore,
    private val catchUpJournal: com.bydmate.app.data.charging.CatchUpJournal,
    private val ttsModelManager: TtsModelManager,
    private val ruStressMarker: RuStressMarker,
    private val gigaAmModelManager: GigaAmModelManager,
    private val continuousAsr: ContinuousAsr,
    private val ttsEngine: TtsEngine,
    private val voiceController: VoiceController,
    private val seatChannelStore: SeatChannelStore,
    private val windowChannelStore: com.bydmate.app.data.vehicle.WindowChannelStore,
    private val helperClient: com.bydmate.app.data.vehicle.HelperClient,
    private val helperBootstrap: com.bydmate.app.data.vehicle.HelperBootstrap,
    private val agentOrchestrator: AgentOrchestrator,
    private val llmConnectionResolver: LlmConnectionResolver,
    private val openRouterClient: OpenRouterClient,
    private val placeRepository: PlaceRepository,
    private val energyDataDeadDetector: com.bydmate.app.data.local.EnergyDataDeadDetector,
    private val hudController: com.bydmate.app.hud.HudController,
    private val logRecorder: LogRecorder,
    private val fidPushChannel: com.bydmate.app.data.push.FidPushChannel,
    private val splitPreferences: com.bydmate.app.split.SplitPreferences,
    private val splitSessionManager: com.bydmate.app.split.SplitSessionManager,
    private val splitJournal: com.bydmate.app.split.SplitJournal,
    private val driverMemory: com.bydmate.app.agent.DriverMemory,
    private val dayMemory: com.bydmate.app.agent.DayMemory,
    private val adbRestoreManager: com.bydmate.app.data.autoservice.AdbRestoreManager,
    private val fidCatalogManager: com.bydmate.app.data.nativestack.FidCatalogManager,
    private val writeAllowlist: com.bydmate.app.data.vehicle.WriteAllowlist,
    private val ruleDao: com.bydmate.app.data.local.dao.RuleDao,
    private val automationEngine: com.bydmate.app.data.automation.AutomationEngine,
    private val voiceJournal: VoiceJournal,
    private val tariffPeriodDao: TariffPeriodDao,
    private val costCalculator: CostCalculator,
    private val blindSpotController: com.bydmate.app.camera.BlindSpotController,
    private val adbVerdictMonitor: com.bydmate.app.data.autoservice.AdbVerdictMonitor,
    private val telegramBackupSink: TelegramBackupSink,
    private val autoBackupScheduler: AutoBackupScheduler,
    private val appStrings: AppStrings,
) : ViewModel() {

    /** ADB control-channel verdict for the line under the ADB-restore toggle. */
    val adbVerdict: StateFlow<com.bydmate.app.data.autoservice.AdbVerdict?> = adbVerdictMonitor.verdict
    val adbChecking: StateFlow<Boolean> = adbVerdictMonitor.checking

    fun recheckAdb() = adbVerdictMonitor.recheck("settings")

    fun enableAdbRestore() = adbVerdictMonitor.enableRestoreAndRecheck()

    private val _appLanguage = MutableStateFlow(localePreferences.getLanguage() ?: "ru")
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _fontScale = MutableStateFlow(localePreferences.getFontScale())
    val fontScale: StateFlow<Float> = _fontScale.asStateFlow()

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

    /** MainActivity listens to the same prefs file and re-provides the density on change. */
    fun setFontScale(scale: Float) {
        localePreferences.setFontScale(scale)
        _fontScale.value = scale
    }

    fun setAppLanguage(lang: String) {
        applyAppLanguage(appContext, localePreferences, lang)
        _appLanguage.value = lang
        // Auto-select CNY when switching to Chinese
        if (lang == "zh") {
            saveCurrency("CNY")
        }
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
        observeAutoBackup()
        loadTariffPeriods()
        observeLogRecorder()
        refreshFidRecorder()
    }

    /**
     * Mirrors the daemon's recorder state into the switch. The recording lives in the daemon, not
     * in this ViewModel, so a screen reopened mid-run finds the switch on.
     */
    fun refreshFidRecorder() {
        if (!_uiState.value.fidRecorderVisible) return
        viewModelScope.launch {
            val status = helperClient.recStatus()
            _uiState.update {
                it.copy(
                    fidRecorderRunning = status?.running == true,
                    fidRecorderFile = status?.filePath?.takeIf { path -> path.isNotEmpty() }?.let { path ->
                        "$path (${status.fileBytes / 1024} КБ, ${status.totalEvents})"
                    },
                )
            }
        }
    }

    /** Switch handler: on = record every device, off = stop. The state is re-read from the daemon. */
    fun setFidRecorder(on: Boolean) {
        viewModelScope.launch {
            // Optimistic flip so the switch does not sit still while ~45 devices are registered.
            _uiState.update { it.copy(fidRecorderRunning = on, fidRecorderError = null) }
            if (on) {
                val started = helperClient.recStart(IntArray(0))
                val error = when {
                    started == null -> "демон не ответил"
                    started.error != FID_REC_NO_ERROR -> started.error
                    started.registered == 0 -> "ни один датчик не подписался"
                    else -> null
                }
                _uiState.update { it.copy(fidRecorderError = error) }
            } else {
                helperClient.recStop()
            }
            refreshFidRecorder()
        }
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
            val rangeCalcMethod = settingsRepository.getRangeCalcMethod()
            val manualRangeTable = settingsRepository.getManualRangeTable()

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

            val webhookEnabled = settingsRepository.getString(SettingsRepository.KEY_WEBHOOK_ENABLED, "false") == "true"
            val webhookUrl = settingsRepository.getString(SettingsRepository.KEY_WEBHOOK_URL, "")
            val webhookSecret = settingsRepository.getString(SettingsRepository.KEY_WEBHOOK_SECRET, "")
            val webhookSendLocation = settingsRepository.getString(SettingsRepository.KEY_WEBHOOK_SEND_LOCATION, "false") == "true"
            val mapTileSource = settingsRepository.getMapTileSource()
            val disableNativeAssistant =
                settingsRepository.getString(SettingsRepository.KEY_DISABLE_NATIVE_ASSISTANT, "false") == "true"

            // Voice settings
            val voiceEnabled = settingsRepository.isVoiceEnabled()
            val voiceLang = settingsRepository.getVoiceLang()
            val voiceKeycode = settingsRepository.getVoiceKeycode().let {
                if (it == 0) DEFAULT_VOICE_KEYCODE else it
            }

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
            val routeNavigator = com.bydmate.app.data.automation.RouteNavigatorUris.normalize(
                appContext.getSharedPreferences(
                    com.bydmate.app.data.automation.RouteNavigatorUris.PREFS_NAME, Context.MODE_PRIVATE
                ).getString(com.bydmate.app.data.automation.RouteNavigatorUris.KEY_ROUTE_NAVIGATOR, null))

            // Wave J: multi-provider LLM connections
            val zaiApiKey = settingsRepository.getString(SettingsRepository.KEY_ZAI_API_KEY, "")
            val customName = settingsRepository.getString(SettingsRepository.KEY_CUSTOM_NAME, "")
            val customBaseUrl = settingsRepository.getString(SettingsRepository.KEY_CUSTOM_BASE_URL, "")
            val customApiKey = settingsRepository.getString(SettingsRepository.KEY_CUSTOM_API_KEY, "")
            val customModel = settingsRepository.getString(SettingsRepository.KEY_CUSTOM_MODEL, "")
            val customExtraJson = settingsRepository.getString(SettingsRepository.KEY_CUSTOM_EXTRA_JSON, "")
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
                    rangeCalcMethod = rangeCalcMethod,
                    manualRangeTable = manualRangeTable,
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
                    webhookEnabled = webhookEnabled,
                    webhookUrl = webhookUrl,
                    webhookSecret = webhookSecret,
                    webhookSendLocation = webhookSendLocation,
                    mapTileSource = mapTileSource,
                    disableNativeAssistant = disableNativeAssistant,
                    voiceEnabled = voiceEnabled,
                    voiceLang = voiceLang,
                    voiceKeycode = voiceKeycode,
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
                    routeNavigator = routeNavigator,
                    agentMemoryFacts = driverMemory.facts(),
                    zaiApiKey = zaiApiKey,
                    customName = customName,
                    customBaseUrl = customBaseUrl,
                    customApiKey = customApiKey,
                    customModel = customModel,
                    customExtraJson = customExtraJson,
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

    /** Switch between the learned (auto) and user-edited (manual) range calculators. */
    fun saveRangeCalcMethod(value: String) {
        _uiState.update { it.copy(rangeCalcMethod = value) }
        viewModelScope.launch { settingsRepository.setRangeCalcMethod(value) }
    }

    fun showManualRangeTableDialog() {
        _uiState.update { it.copy(showManualRangeTableDialog = true) }
    }

    fun hideManualRangeTableDialog() {
        _uiState.update { it.copy(showManualRangeTableDialog = false) }
    }

    fun saveManualRangeTable(table: List<SettingsRepository.ManualRangePoint>) {
        _uiState.update { it.copy(manualRangeTable = table, showManualRangeTableDialog = false) }
        viewModelScope.launch { settingsRepository.setManualRangeTable(table) }
    }

    fun resetManualRangeTable() {
        val defaults = SettingsRepository.defaultManualRangeTable()
        _uiState.update { it.copy(manualRangeTable = defaults, showManualRangeTableDialog = false) }
        viewModelScope.launch { settingsRepository.resetManualRangeTable() }
    }

    /** Reloads the periods list and the measured-losses hint behind the «Тарифы» section. */
    private fun loadTariffPeriods() {
        viewModelScope.launch {
            val periods = costCalculator.schedule().periods
            _uiState.update {
                it.copy(
                    tariffPeriods = periods.sortedByDescending { p -> p.startTs },
                    measuredLosses = costCalculator.measuredLosses(),
                )
            }
        }
    }

    fun showTariffPeriods() {
        loadTariffPeriods()
        _uiState.update { it.copy(showTariffPeriodsDialog = true) }
    }

    fun hideTariffPeriods() {
        _uiState.update {
            it.copy(showTariffPeriodsDialog = false, tariffRecalcStatus = null, tariffPeriodError = null)
        }
    }

    /**
     * Saves one period (new or edited) and re-prices everything from the earliest date it can
     * affect: a period added today with a September start makes September recount by itself.
     */
    fun saveTariffPeriod(period: TariffPeriodEntity) {
        viewModelScope.launch {
            // `start_ts` is unique: writing a date another period already holds would throw.
            val occupant = tariffPeriodDao.getByStartTs(period.startTs)
            if (occupant != null && occupant.id != period.id) {
                Log.w(CostCalculator.TAG, "period save rejected start=${period.startTs} taken by id=${occupant.id}")
                _uiState.update {
                    it.copy(tariffPeriodError = appStrings.get(R.string.settings_tariff_period_date_taken))
                }
                return@launch
            }
            val before = tariffPeriodDao.getAllAsc()
            val previousStart = before.find { it.id == period.id }?.startTs
            val wasEarliest = before.firstOrNull()?.id == period.id
            if (period.id == 0L) tariffPeriodDao.insert(period) else tariffPeriodDao.update(period)
            Log.i(CostCalculator.TAG, "period saved start=${period.startTs} home=${period.homeRate} " +
                "dc=${period.dcRate} loss=${period.acLossPct}/${period.dcLossPct} rule=${period.tripRule}")
            // The earliest period also covers everything before its own date, so touching it
            // has to re-price the whole history, not just the span from its start_ts.
            val becameEarliest = tariffPeriodDao.getAllAsc().firstOrNull()?.startTs == period.startTs
            val from = if (wasEarliest || becameEarliest) 0L
            else minOf(period.startTs, previousStart ?: period.startTs)
            _uiState.update { it.copy(tariffPeriodError = null) }
            applyTariffChange(from)
        }
    }

    /** Deletes a period; the one before it takes over its span, so that span is re-priced. */
    fun deleteTariffPeriod(period: TariffPeriodEntity) {
        viewModelScope.launch {
            if (tariffPeriodDao.count() <= 1) return@launch
            val wasEarliest = tariffPeriodDao.getAllAsc().firstOrNull()?.id == period.id
            tariffPeriodDao.delete(period)
            Log.i(CostCalculator.TAG, "period deleted start=${period.startTs} wasEarliest=$wasEarliest")
            // Dropping the earliest period hands its span to the next one, which then covers
            // everything before itself as well.
            applyTariffChange(if (wasEarliest) 0L else period.startTs)
        }
    }

    /** Mirror the period in force into the flat settings keys, then recalculate from [fromTs]. */
    private suspend fun applyTariffChange(fromTs: Long) {
        val schedule = costCalculator.schedule()
        schedule.periodAt(System.currentTimeMillis())?.let { current ->
            settingsRepository.mirrorCurrentTariffPeriod(current.homeRate, current.dcRate, current.tripRule)
        }
        val result = costCalculator.recalculate(fromTs, Long.MAX_VALUE)
        _uiState.update {
            it.copy(
                tariffPeriods = schedule.periods.sortedByDescending { p -> p.startTs },
                measuredLosses = costCalculator.measuredLosses(),
                tariffRecalcStatus = appStrings.get(
                    R.string.settings_tariff_recalc_done, result.charges, result.trips
                ),
            )
        }
        delay(4000)
        _uiState.update { it.copy(tariffRecalcStatus = null) }
    }

    /** Save distance units preference (km or miles). */
    fun saveUnits(value: String) {
        _uiState.update { it.copy(units = value) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_UNITS, value)
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
    fun saveCustomExtraJson(value: String) = saveConnField(value, SettingsRepository.KEY_CUSTOM_EXTRA_JSON) { s, v -> s.copy(customExtraJson = v) }

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
                appStrings.get(R.string.settings_conn_not_configured)
            } else {
                val start = SystemClock.elapsedRealtime()
                val messages = JSONArray().put(
                    JSONObject().put("role", "user").put("content", "Ответь одним словом: готов")
                )
                val result = openRouterClient.chatRaw(conn.baseUrl, conn.apiKey, conn.model, messages, null)
                val elapsedSec = (SystemClock.elapsedRealtime() - start) / 1000.0
                result.fold(
                    onSuccess = { appStrings.get(R.string.settings_conn_check_ok, elapsedSec) },
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
                is java.net.UnknownHostException -> return appStrings.get(R.string.settings_error_dns)
                is java.net.SocketTimeoutException -> return appStrings.get(R.string.settings_error_timeout)
                is LlmHttpException -> return appStrings.get(R.string.settings_error_with_message, "HTTP ${cur.code}")
            }
            cur = cur.cause
            depth++
        }
        return appStrings.get(R.string.settings_error_with_message, t.message ?: "?")
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
            _uiState.update { it.copy(aliceEnabled = enabled, aliceSaveStatus = appStrings.get(R.string.settings_saved)) }
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
                    abrpSaveStatus = appStrings.get(R.string.settings_saved),
                )
            }
            delay(2000)
            _uiState.update { it.copy(abrpSaveStatus = null) }
        }
    }

    fun toggleWebhook(enabled: Boolean) {
        // Same reasoning as ABRP: without a URL there is nowhere to send, and
        // an "on" toggle with no target only confuses the user.
        val effective = enabled && _uiState.value.webhookUrl.isNotBlank()
        _uiState.update { it.copy(webhookEnabled = effective) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_WEBHOOK_ENABLED, effective.toString())
        }
    }

    fun toggleWebhookSendLocation(enabled: Boolean) {
        _uiState.update { it.copy(webhookSendLocation = enabled) }
        viewModelScope.launch {
            settingsRepository.setString(SettingsRepository.KEY_WEBHOOK_SEND_LOCATION, enabled.toString())
        }
    }

    fun updateWebhookUrl(value: String) {
        _uiState.update { it.copy(webhookUrl = value) }
    }

    fun updateWebhookSecret(value: String) {
        _uiState.update { it.copy(webhookSecret = value) }
    }

    fun saveWebhookSettings() {
        val state = _uiState.value
        val url = state.webhookUrl.trim()
        viewModelScope.launch {
            // Reject garbage early: the send path silently drops an unparsable
            // URL, so without this the user would see a working toggle and no data.
            val valid = url.isEmpty() || url.toHttpUrlOrNull()
                ?.let { com.bydmate.app.data.remote.WebhookTelemetryClient.isAllowedWebhookUrl(it) } == true
            if (!valid) {
                _uiState.update {
                    it.copy(webhookSaveStatus = appStrings.get(R.string.settings_webhook_invalid_url))
                }
                delay(2000)
                _uiState.update { it.copy(webhookSaveStatus = null) }
                return@launch
            }
            settingsRepository.setString(SettingsRepository.KEY_WEBHOOK_URL, url)
            settingsRepository.setString(SettingsRepository.KEY_WEBHOOK_SECRET, state.webhookSecret.trim())
            val enabled = state.webhookEnabled && url.isNotEmpty()
            settingsRepository.setString(SettingsRepository.KEY_WEBHOOK_ENABLED, enabled.toString())
            _uiState.update {
                it.copy(
                    webhookUrl = url,
                    webhookEnabled = enabled,
                    webhookSaveStatus = appStrings.get(R.string.settings_webhook_saved),
                )
            }
            delay(2000)
            _uiState.update { it.copy(webhookSaveStatus = null) }
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

    // --- GigaAM v3 ASR (free-form Russian speech recognition, offline) ---

    private var gigaAmDownloadJob: Job? = null

    fun downloadGigaAmModel() {
        if (_uiState.value.gigaAmDownloadProgress >= 0) return   // already downloading
        gigaAmDownloadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    gigaAmDownloadProgress = 0,
                    gigaAmDownloadPhase = GigaAmModelManager.Phase.DOWNLOAD,
                    gigaAmDownloadFailed = false,
                    gigaAmSpaceShortfall = null,
                )
            }
            val result = gigaAmModelManager.download { phase, pct ->
                // A late delivery after deleteGigaAmModel() reset progress to -1 (idle) must
                // not resurrect an in-progress state.
                _uiState.update {
                    if (it.gigaAmDownloadProgress < 0) it
                    else it.copy(gigaAmDownloadProgress = pct, gigaAmDownloadPhase = phase)
                }
            }
            _uiState.update {
                it.copy(
                    gigaAmDownloadProgress = -1,
                    gigaAmDownloadPhase = null,
                    gigaAmModelReady = gigaAmModelManager.isReady(),
                    gigaAmDownloadFailed = result.isFailure,
                    gigaAmSpaceShortfall = SpaceShortfall.from(result.exceptionOrNull()),
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
        _uiState.update {
            it.copy(
                gigaAmModelReady = false,
                gigaAmDownloadProgress = -1,
                gigaAmDownloadPhase = null,
                gigaAmDownloadFailed = false,
                gigaAmSpaceShortfall = null,
            )
        }
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
     * Picks the map app the navigate action opens (#190, #200): "yandex" (default), "dgis" or "maps".
     * Persisted in the same SharedPreferences("voice") file as the other agent settings, which
     * is where [com.bydmate.app.data.automation.ActionDispatcher] reads it on every route.
     */
    fun setRouteNavigator(value: String) {
        val normalized = com.bydmate.app.data.automation.RouteNavigatorUris.normalize(value)
        _uiState.update { it.copy(routeNavigator = normalized) }
        appContext.getSharedPreferences(
            com.bydmate.app.data.automation.RouteNavigatorUris.PREFS_NAME, Context.MODE_PRIVATE
        ).edit()
            .putString(com.bydmate.app.data.automation.RouteNavigatorUris.KEY_ROUTE_NAVIGATOR, normalized)
            .apply()
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
     * Re-reads the driver facts the agent keeps in DriverMemory. The agent can remember or
     * forget things while Settings is closed, so the card asks for a fresh list on entry
     * instead of trusting what was loaded with the rest of the state.
     */
    fun refreshAgentMemory() {
        _uiState.update { it.copy(agentMemoryFacts = driverMemory.facts()) }
    }

    /** Drops everything the agent remembers: the long-term facts and today's exchanges alike.
     *  No confirmation: the driver can tell them to the agent again. */
    fun forgetAgentMemory() {
        driverMemory.forgetAll()
        dayMemory.forgetAll()
        _uiState.update { it.copy(agentMemoryFacts = emptyList()) }
    }

    /** Drops one remembered fact: a single wrong fact should not cost the driver the whole list. */
    fun forgetAgentFact(fact: String) {
        driverMemory.forget(fact)
        _uiState.update { it.copy(agentMemoryFacts = driverMemory.facts()) }
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
                is AgentResult.Answer -> appStrings.get(
                    R.string.agent_test_model_result, modelLabel, elapsedSec, result.text.take(80)
                )
                AgentResult.Disabled -> appStrings.get(R.string.agent_test_model_disabled)
                is AgentResult.Error -> appStrings.get(R.string.settings_error_with_message, result.message)
            }
            _uiState.update { it.copy(modelTestRunning = false, modelTestResult = text) }
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        /** Slug verified in the live OpenRouter catalog (2026-09-14); the fastest Flash of
         *  the current line, which is what the voice path is tuned for. */
        internal const val DEFAULT_OPENROUTER_MODEL = "google/gemini-3.8-flash"
        /** Voice sessions printed in the dump's agent section (newest first). */
        private const val AGENT_DUMP_ENTRIES = 20
        private const val AGENT_DUMP_ANSWER_CHARS = 200
        private const val PREVIEW_VOICE_TEXT =
            "Маршрут построен. Через двести метров поверните направо."
        private const val AGENT_TEST_PROMPT =
            "Проверка связи. Вызови инструмент get_vehicle_state и ответь одним коротким " +
                "предложением: какой заряд батареи."
        private const val MINIMAX_SOURCE = "minimax"
        /** Telegram bind code: six digits. */
        private const val BIND_CODE_MIN = 100_000
        private const val BIND_CODE_SPAN = 900_000
        /** Shared budget for the daemon-backed dump sections (liveness + seat and steering heat reads).
         *  The dump must not hang on a wedged daemon. */
        private const val HELPER_DIAG_BUDGET_MS = 3_000L
    }

    /** What the two daemon-backed dump sections need; nulls mean "not obtained in budget". */
    private data class HelperDiagnostics(
        val alive: Boolean?,
        val seats: List<Pair<Int, Int>>?,
        val steeringHeat: List<Pair<Int, Int>>? = null,
    )

    /**
     * Collects daemon liveness and the seat fid snapshot under ONE shared budget.
     *
     * A binder transact is a blocking call: wrapping it in withTimeoutOrNull here would not
     * return until the call finished, because cancellation only takes effect at a suspension
     * point. So the reads run in a coroutine that is NOT a child of the dump, and only the
     * WAIT is bounded — a wedged daemon leaves an IO thread parked instead of stalling the
     * dump the user is trying to send us.
     */
    private suspend fun gatherHelperDiagnostics(): HelperDiagnostics {
        val probe = viewModelScope.async(Dispatchers.IO) {
            HelperDiagnostics(
                alive = runCatching { helperClient.isAlive() }.getOrNull(),
                // One binder round-trip for all ten seat reads.
                seats = runCatching { helperClient.readBatch(SeatsDiagnostics.batchItems()) }.getOrNull(),
                steeringHeat = runCatching {
                    helperClient.readBatch(SteeringHeatDiagnostics.batchItems())
                }.getOrNull(),
            )
        }
        return withTimeoutOrNull(HELPER_DIAG_BUDGET_MS) { probe.await() }
            ?: HelperDiagnostics(null, null)
    }

    /** One-line trigger summary for the dump: param, operator and value only. */
    private fun describeTriggers(json: String): String {
        val triggers = com.bydmate.app.data.local.entity.TriggerDef.listFromJson(json)
        if (triggers.isEmpty()) return if (json.isBlank() || json == "[]") "(none)" else "(unparseable)"
        // kind + placeId: place_enter / place_exit share value="enter", only the kind
        // tells them apart, and the id tells which geofence (name is user data, omitted).
        return triggers.joinToString(" ") {
            val place = it.placeId?.let { id -> " placeId=$id" } ?: ""
            "[${it.kind}$place param=${it.param} op=${it.operator} value=${it.value}]"
        }
    }

    /**
     * One-line action summary for the dump. The command string is printed only for
     * kind="param" (a fixed vehicle command); every other kind carries user data in
     * its payload/command (phone number, address, notification text), so only the
     * kind is printed.
     */
    private fun describeActions(json: String): String {
        val actions = com.bydmate.app.data.local.entity.ActionDef.listFromJson(json)
        if (actions.isEmpty()) return if (json.isBlank() || json == "[]") "(none)" else "(unparseable)"
        return actions.joinToString(" ") {
            when (it.kind) {
                "param" -> "[param ${it.command}]"
                // Toggle payload is a fixed target id, not user data — and the whole
                // point of a toggle report is which target failed to flip.
                "toggle" -> "[toggle ${it.payload}]"
                else -> "[${it.kind}]"
            }
        }
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
                appendLine("font_scale: ${localePreferences.getFontScale()}")
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
                appendLine("route_navigator=" + com.bydmate.app.data.automation.RouteNavigatorUris.normalize(
                    appContext.getSharedPreferences(
                        com.bydmate.app.data.automation.RouteNavigatorUris.PREFS_NAME, Context.MODE_PRIVATE
                    ).getString(com.bydmate.app.data.automation.RouteNavigatorUris.KEY_ROUTE_NAVIGATOR, null)))
                val secureSettingsGranted = appContext.checkSelfPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                appendLine(
                    "adb_restore=${adbRestoreManager.isEnabled()}/${adbRestoreManager.state.value} " +
                        "trigger=${adbRestoreManager.lastTrigger} retries=${adbRestoreManager.retryCount} " +
                        "write_secure_settings=$secureSettingsGranted"
                )
                appendLine("adb_verdict: ${adbVerdictMonitor.verdict.value ?: "(none)"}")
                appendLine("daemon_ever_alive: ${helperBootstrap.daemonEverAlive()}")
                appendLine(com.bydmate.app.data.backup.PostRestoreCheck.dumpLine(appContext))
            } catch (e: Exception) {
                appendLine("(failed to gather settings: ${e.message})")
            }

            appendLine("--- tariff periods ---")
            try {
                val schedule = costCalculator.schedule()
                val current = schedule.periodAt(System.currentTimeMillis())
                appendLine("periods=${schedule.periods.size} current=" + (current?.let {
                    "start=${it.startTs} home=${it.homeRate} dc=${it.dcRate} " +
                        "loss=${it.acLossPct}/${it.dcLossPct} rule=${it.tripRule}"
                } ?: "(none)"))
            } catch (e: Exception) {
                appendLine("(failed to gather tariff periods: ${e.message})")
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

            // Live poll snapshot (#64: DiLink 3.0 park/gear triggers cannot be diagnosed
            // without the raw gear value; nothing else in the dump or the log carries it).
            appendLine("--- live snapshot ---")
            val live = TrackingService.lastData.value
            if (live == null) {
                appendLine("(no poll yet)")
            } else {
                val ageS = (System.currentTimeMillis() - TrackingService.lastDataAtMs) / 1000
                // trunk/frontTrunk: the "toggle" action decides open-vs-close from these,
                // and the front-trunk value semantics are still an assumption (FidMap).
                appendLine(
                    "age_s=$ageS gear=${live.gear} speed=${live.speed} powerState=${live.powerState} " +
                        "soc=${live.soc} trunk=${live.trunk} frontTrunk=${live.frontTrunk} " +
                        // #210: the Главная slot shows both, so a "no outside temperature"
                        // report has to be checkable against what the car actually reported.
                        "insideTemp=${live.insideTemp} exteriorTemp=${live.exteriorTemp} " +
                        "remainKwh=${live.batteryRemainKwh}"
                )
                // Charging plug: the gun state the detector uses plus the two backup signals,
                // so a running car with the plug in can be compared against a parked one.
                appendLine(
                    "charge gun=${live.chargeGunState} bms=${live.bmsState} " +
                        "chargerConnect=${live.chargerConnectState} " +
                        "connectIndicator=${live.chargeConnectIndicator}"
                )
            }

            // ICE-side addresses nothing in the app reads yet (#184). A DM-i owner sends this
            // dump with the engine running and the raw words say which of them are live there.
            appendLine("--- hybrid probe ---")
            try {
                HybridProbeDiagnostics
                    .format(helperClient.readBatch(HybridProbeDiagnostics.batchItems()))
                    .forEach { appendLine(it) }
            } catch (e: Exception) { appendLine("error: ${e.message}") }

            appendLine(automationEngine.serviceStartDumpLine())
            // Automation rules (#177): issue reports about a rule that "does nothing"
            // are undiagnosable without the rule itself. Action payloads stay out —
            // they hold phone numbers, addresses and notification text.
            appendLine("--- rules ---")
            try {
                val rules = ruleDao.getAllList()
                if (rules.isEmpty()) {
                    appendLine("(no rules)")
                } else {
                    rules.forEach { rule ->
                        val last = rule.lastTriggeredAt?.let {
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it))
                        } ?: "(never)"
                        appendLine(
                            "rule id=${rule.id} \"${rule.name}\" enabled=${rule.enabled} " +
                                "logic=${rule.triggerLogic} park=${rule.requirePark} " +
                                "once=${rule.fireOncePerTrip} confirm=${rule.confirmBeforeExecute} " +
                                "cooldown=${rule.cooldownSeconds}s fired=${rule.triggerCount} last=$last"
                        )
                        appendLine("  triggers: " + describeTriggers(rule.triggers))
                        appendLine("  actions: " + describeActions(rule.actions))
                    }
                }
            } catch (e: Exception) {
                appendLine("(failed to gather rules: ${e.message})")
            }

            // Voice agent: which connection/model answered and what the last turns did.
            // The journal is a RAM ring buffer, so this is the only place a user report
            // about a wrong or fabricated answer becomes checkable.
            appendLine("--- agent ---")
            try {
                val conn = llmConnectionResolver.primary()
                appendLine("connection: ${conn?.id ?: "(not configured)"} model=${conn?.model ?: "-"}")
                val entries = voiceJournal.entries.value.take(AGENT_DUMP_ENTRIES)
                if (entries.isEmpty()) {
                    appendLine("(no voice sessions this run)")
                } else {
                    entries.forEach { e ->
                        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.timestampMs))
                        appendLine("$stamp ${e.route} ${e.outcome} \"${e.transcript}\"" +
                            (e.reason?.let { " reason=$it" } ?: ""))
                        if (e.tools.isNotEmpty()) {
                            appendLine("  tools: " + e.tools.joinToString(", ") {
                                "${it.name}:${if (it.ok) "ok" else "err"}"
                            })
                        }
                        e.answer?.let { appendLine("  answer: " + com.bydmate.app.agent.AgentTrace.clip(it, AGENT_DUMP_ANSWER_CHARS)) }
                    }
                }
            } catch (e: Exception) {
                appendLine("(failed to gather agent journal: ${e.message})")
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

            appendLine("--- displays ---")
            try {
                // Cluster projection needs an Android-managed cluster display; on several
                // platforms (Song family, DiLink 3/4) resolveClusterDisplay finds nothing.
                // The full list shows whether a cluster surface exists under another name.
                val dm = appContext.getSystemService(Context.DISPLAY_SERVICE)
                    as android.hardware.display.DisplayManager
                dm.displays.forEach { d ->
                    val p = android.graphics.Point()
                    @Suppress("DEPRECATION") d.getRealSize(p)
                    appendLine("id=${d.displayId} name=\"${d.name}\" ${p.x}x${p.y} state=${d.state}")
                }
            } catch (e: Exception) { appendLine("(failed to gather displays: ${e.message})") }

            appendLine("--- hud ---")
            try {
                // Mirrors the Settings HUD row: enabled pref, probe verdict, live status.
                // UNSUPPORTED = the SOME/IP gateway package is absent on this firmware.
                val hudPrefs = appContext.getSharedPreferences(
                    com.bydmate.app.hud.HudController.PREFS_NAME, Context.MODE_PRIVATE)
                appendLine("enabled: ${hudPrefs.getBoolean(com.bydmate.app.hud.HudController.KEY_ENABLED, false)}")
                appendLine("supported_pref: ${hudPrefs.getBoolean(com.bydmate.app.hud.HudController.KEY_SUPPORTED, true)}")
                appendLine("status: ${hudController.status.value}")
                val gatewayPresent =
                    com.bydmate.app.hud.HudSomeIpBridge.isServicePresent(appContext.packageManager)
                appendLine("someip_gateway: " + if (gatewayPresent) "present" else "absent")
                appendLine("speed_sign: ${hudPrefs.getBoolean(com.bydmate.app.hud.HudController.KEY_SPEED_SIGN, true)}")
                // Frame/RC counters from HudPushLoop via HudController.diag().
                val diag = hudController.diag()
                appendLine("frames_sent=${diag?.framesSent ?: 0} last_frame_ts=${diag?.lastFrameTs ?: 0}")
                appendLine("last_fire_rc=${diag?.lastRc ?: "n/a"} nonzero_rc_count=${diag?.nonZeroRcCount ?: 0}")
                appendLine("amap_capable=${diag?.amapCapable ?: false} amap_frames=${diag?.amapFramesSent ?: 0} amap_stops=${diag?.amapStopsSent ?: 0}")
                appendLine("hub_snapshot=${com.bydmate.app.navdata.NavGuidanceHub.snapshot()}")
                // What each channel actually carried at every maneuver change (#94): the
                // SOME/IP arrow field next to the Amap icon, on one timeline.
                val maneuvers = com.bydmate.app.hud.HudManeuverJournal(hudPrefs).lines()
                appendLine("maneuver history:")
                if (maneuvers.isEmpty()) appendLine("  (none)")
                else maneuvers.forEach { appendLine("  $it") }
                appendLine("notif_listener_enabled=${
                    androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(appContext)
                        .contains(appContext.packageName)
                }")
                // Self-heal runs for every daemon-backed grant: all-false reasserts point at the
                // daemon path, true reasserts with granted=false at the system reverting us.
                val healHistory = com.bydmate.app.service.GrantSelfHeal.history()
                if (healHistory.isEmpty()) {
                    appendLine("grant_heal_history: (none)")
                } else {
                    appendLine("grant_heal_history:")
                    val healSdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    healHistory.forEach { e ->
                        appendLine("${healSdf.format(Date(e.ts))} ${e.name} reason=${e.reason} " +
                            "tries=${e.tries} reasserts=${e.reasserts.joinToString(",", "[", "]")} " +
                            "granted=${e.granted}")
                    }
                }
                // Parallel HUD apps: presence affects channel routing decisions.
                val hudPm = appContext.packageManager
                listOf(
                    "com.unkwn2.yandexhud",      // donor HUD app
                    "com.sr.openbyd",            // OpenBYD
                    "com.byd.amapservice",       // Amap broadcast receiver (factory)
                    "com.example.amapservice",   // Amap broadcast receiver (alt)
                ).forEach { pkg ->
                    val state = runCatching { hudPm.getApplicationEnabledSetting(pkg) }
                        .getOrElse { "absent" }
                    appendLine("pkg $pkg: $state")
                }
            } catch (e: Exception) { appendLine("(failed to gather hud state: ${e.message})") }

            appendLine("--- blind spot ---")
            try {
                blindSpotController.dumpLines().forEach { appendLine(it) }
            } catch (e: Exception) { appendLine("(failed to gather blind spot state: ${e.message})") }

            appendLine("--- cluster ---")
            try {
                val cpm = com.bydmate.app.cluster.ClusterProjectionManager
                val clusterPrefs = appContext.getSharedPreferences(cpm.PREFS_NAME, Context.MODE_PRIVATE)
                val diag = cpm.diag()
                appendLine("mode: ${diag.mode} attempt_in_progress=${diag.attemptInProgress} " +
                    "last_failure=${diag.lastFailure ?: "(none)"}")
                appendLine("transport: ${if (cpm.isDirectProjectionEnabled(appContext)) "direct" else "vd"} " +
                    "auto_container=${clusterPrefs.getBoolean(cpm.KEY_AUTO_CONTAINER, true)} " +
                    "freeform_reboot_pending=${clusterPrefs.getBoolean(cpm.KEY_FREEFORM_REBOOT_PENDING, false)}")
                appendLine("projected_pkg: ${diag.projectedPackage ?: "(none)"} " +
                    "target=${clusterPrefs.getString(cpm.KEY_TARGET_PACKAGE, "(default)")}")
                // #121: the density override carries the scale in direct mode, and apps latched by
                // the death watch as dying on a non-native density are sent at the panel's own
                // density instead — their scale slider is inert.
                val densityUnsafe = cpm.densityUnsafePackages(appContext)
                appendLine("density: " + when (diag.directDensityDpi) {
                    -1 -> "(not set this session)"
                    0 -> "native"
                    else -> "${diag.directDensityDpi} dpi"
                } + " unsafe=" + if (densityUnsafe.isEmpty()) "(none)" else densityUnsafe.joinToString())
                appendLine("vd: id=${diag.vdDisplayId} overlay_attached=${diag.overlayAttached} " +
                    "direct_display=${diag.directDisplayId} " +
                    "direct_marker=${clusterPrefs.getInt(cpm.KEY_DIRECT_DISPLAY_ID, -1)}")
                // The window the user calibrated, resolved against the cluster panel as the
                // projection itself resolves it — a grey/misplaced cluster (#134) is often just
                // bounds that fall outside the visible zone.
                val dm = appContext.getSystemService(Context.DISPLAY_SERVICE)
                    as android.hardware.display.DisplayManager
                val preferFullDisplay = cpm.isPreferFullDisplay(appContext)
                val projectionDisplays = dm.displays.filter {
                    it.name.contains("XDJAScreenProjection", ignoreCase = true)
                }
                val pickedName = com.bydmate.app.cluster.pickProjectionDisplayName(
                    projectionDisplays.map { it.name }, preferFullDisplay)
                val clusterDisplay = projectionDisplays.firstOrNull { it.name == pickedName }
                if (clusterDisplay == null) {
                    appendLine("display: (no XDJAScreenProjection surface on this car)")
                    appendLine("bounds: n/a")
                } else {
                    val size = android.graphics.Point()
                    @Suppress("DEPRECATION") clusterDisplay.getRealSize(size)
                    val metrics = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION") clusterDisplay.getMetrics(metrics)
                    appendLine("display: id=${clusterDisplay.displayId} \"${clusterDisplay.name}\" " +
                        "${size.x}x${size.y} dpi=${metrics.densityDpi}")
                    appendLine("display_pref: " + if (preferFullDisplay) "full" else "mini")
                    val geo = com.bydmate.app.cluster.geometryFor(
                        com.bydmate.app.cluster.ClusterMode.FULLSCREEN, size.x, size.y,
                        clusterPrefs.getInt(cpm.KEY_WIDTH_PCT, com.bydmate.app.cluster.MAX_PROJECTION_PCT),
                        clusterPrefs.getInt(cpm.KEY_HEIGHT_PCT, com.bydmate.app.cluster.MAX_PROJECTION_PCT),
                        clusterPrefs.getInt(cpm.KEY_OFFSET_X_PCT, com.bydmate.app.cluster.CENTER_OFFSET_PCT),
                        clusterPrefs.getInt(cpm.KEY_OFFSET_Y_PCT, com.bydmate.app.cluster.CENTER_OFFSET_PCT),
                    )
                    appendLine("bounds: " + if (geo == null) "n/a" else
                        "[${geo.xOffset},${geo.yOffset},${geo.xOffset + geo.width},${geo.yOffset + geo.height}] " +
                            "scale=${clusterPrefs.getInt(cpm.KEY_SCALE_PCT, com.bydmate.app.cluster.DEFAULT_SCALE_PCT)}%")
                }
                // #194: the inventory the daemon reads under shell uid. On firmwares that hide
                // displays from the app uid (DiLink 4.0) the cluster surface appears ONLY here,
                // and "target" says which of the two lookups the projection would use.
                val daemonDisplays = runCatching { helperClient.listDisplays() }.getOrNull()
                appendLine("daemon displays: " + when {
                    daemonDisplays == null -> "(unavailable)"
                    daemonDisplays.isEmpty() -> "(none)"
                    else -> daemonDisplays.joinToString {
                        "${it.id}:\"${it.name}\" ${it.width}x${it.height} " +
                            "[${it.flags.joinToString(",")}]"
                    }
                })
                // Density question (#194, direct mode): `dumpsys display` prints the display
                // DEVICE density, so it cannot say whether WindowManager took a `wm density`
                // override or whether the projected app received it. These two blocks can.
                // Skipped entirely when the daemon did not answer the inventory call above.
                val wmDiagPkg = diag.projectedPackage
                    ?: clusterPrefs.getString(cpm.KEY_TARGET_PACKAGE, com.bydmate.app.cluster.NAVI_PACKAGE)
                    ?: com.bydmate.app.cluster.NAVI_PACKAGE
                val wmDiag = if (daemonDisplays == null) null
                    else runCatching { helperClient.clusterWmDiag(wmDiagPkg) }.getOrNull()
                if (wmDiag == null) {
                    appendLine("wm displays: (daemon unavailable)")
                } else {
                    if (wmDiag.displays.isEmpty()) appendLine("wm displays: (none)")
                    else {
                        appendLine("wm displays:")
                        wmDiag.displays.forEach { appendLine("  $it") }
                    }
                    if (wmDiag.taskConfig.isEmpty()) appendLine("nav task config: (none)")
                    else {
                        appendLine("nav task config:")
                        wmDiag.taskConfig.forEach { appendLine("  $it") }
                    }
                }
                val daemonPick = daemonDisplays?.let {
                    com.bydmate.app.cluster.pickClusterFromDaemon(it, preferFullDisplay)
                }
                appendLine("target: " + when {
                    clusterDisplay != null -> "app"
                    daemonPick != null -> "daemon (id=${daemonPick.id})"
                    else -> "none"
                })
                val clusterJournal = cpm.journalLines(appContext)
                appendLine("journal:")
                if (clusterJournal.isEmpty()) appendLine("  (empty)")
                else clusterJournal.forEach { appendLine("  $it") }
            } catch (e: Exception) { appendLine("(failed to gather cluster state: ${e.message})") }

            appendLine("--- trip counters ---")
            try {
                // Live-count integrity: km/kWh only tick when liveWholeSession=true and
                // both session baselines were captured (restore without them suppresses
                // the live contribution until the next fresh session).
                appendLine("live_whole_session: ${com.bydmate.app.service.TrackingService.liveWholeSession.value}")
                appendLine("session_started_at: ${com.bydmate.app.service.TrackingService.sessionStartedAt.value ?: "null"}")
                val sessPrefs = appContext.getSharedPreferences("bydmate_widget_session", Context.MODE_PRIVATE)
                // Literal key names mirror SessionPersistence (private consts there).
                appendLine("baseline_mileage_km: " + (if (sessPrefs.contains("mileage_start_km_v2_bits"))
                    Double.fromBits(sessPrefs.getLong("mileage_start_km_v2_bits", 0L)).toString() else "absent"))
                appendLine("baseline_elec_kwh: " + (if (sessPrefs.contains("elec_start_kwh_v2_bits"))
                    Double.fromBits(sessPrefs.getLong("elec_start_kwh_v2_bits", 0L)).toString() else "absent"))
                for (n in 1..2) {
                    appendLine("trip$n: reset_ts=${settingsRepository.getString("trip${n}_reset_ts", "0")} " +
                        "corr_km=${settingsRepository.getString("trip${n}_corr_km", "0")} " +
                        "corr_kwh=${settingsRepository.getString("trip${n}_corr_kwh", "0")} " +
                        "corr_ms=${settingsRepository.getString("trip${n}_corr_ms", "0")} " +
                        "excl=${settingsRepository.getString("trip${n}_corr_excl", "0")}")
                }
            } catch (e: Exception) { appendLine("(failed to gather trip counter state: ${e.message})") }

            appendLine("--- steering key ---")
            try {
                // Same two liveness signals TrackingService.starServiceRunning() checks:
                // our service's own connected flag and the framework's bound-a11y set.
                // The raw Secure setting is listed too - on some firmwares it desyncs
                // from the actually-bound set across ignition cycles (DiLink 4 reports).
                appendLine("a11y_connected: ${com.bydmate.app.cluster.SteeringWheelKeyService.isConnected}")
                val am = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as android.view.accessibility.AccessibilityManager
                val ours = android.content.ComponentName.unflattenFromString(
                    com.bydmate.app.helper.HelperBinderProtocol.ACCESSIBILITY_SERVICE_COMPONENT)
                val bound = ours != null && am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { android.content.ComponentName.unflattenFromString(it.id ?: "") == ours }
                appendLine("a11y_framework_bound: $bound")
                val secure = android.provider.Settings.Secure.getString(
                    appContext.contentResolver, "enabled_accessibility_services")
                appendLine("a11y_secure_setting: ${secure ?: "(null)"}")
                val autoAllowLast = appContext.getSharedPreferences(
                    com.bydmate.app.data.autoservice.AdbRestorePreferencesImpl.PREFS_NAME, Context.MODE_PRIVATE,
                ).getString(com.bydmate.app.data.autoservice.WifiDebuggingDialogAutoAllow.KEY_LAST_OUTCOME, null)
                appendLine("a11y_auto_allow_last: ${autoAllowLast ?: "(none)"}")
                val voicePrefs = appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
                appendLine(
                    "voice_ptt: enabled=${voicePrefs.getBoolean("voice_enabled", false)} " +
                        "keycode=${voicePrefs.getInt("voice_keycode", DEFAULT_VOICE_KEYCODE)}"
                )
                val clusterPrefs = appContext.getSharedPreferences(
                    com.bydmate.app.cluster.ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
                appendLine("mirror_enabled: ${clusterPrefs.getBoolean(
                    com.bydmate.app.cluster.ClusterProjectionManager.KEY_MIRROR_ENABLED, false)}")
            } catch (e: Exception) { appendLine("(failed to gather steering key state: ${e.message})") }

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

            // Both daemon sections come from one detached probe (see gatherHelperDiagnostics).
            val helperDiag = gatherHelperDiagnostics()

            appendLine("--- helper daemon ---")
            try {
                appendLine("alive: ${helperDiag.alive?.toString() ?: "(unknown — probe timed out)"}")
                // How the daemon is reachable: a registered service name, or the Binder it
                // broadcast to us on firmwares that refuse addService (#64/#148).
                val registered = com.bydmate.app.data.vehicle.helperServiceBinder() != null
                appendLine("transport: " + if (registered) "servicemanager"
                    else com.bydmate.app.helper.HelperBinderHolder.transport)
                appendLine("broadcast_last_reject: " +
                    (com.bydmate.app.helper.HelperBinderHolder.lastReject ?: "(none)"))
                // Whether a recreated process can still authenticate the daemon's re-announce,
                // and whether this car delivers the daemon by broadcast at all (#64/#148).
                val helperPrefs = appContext.getSharedPreferences(
                    com.bydmate.app.helper.HelperBinderHolder.PREFS_NAME, Context.MODE_PRIVATE)
                appendLine("token_persisted: " + if (helperPrefs.contains(
                        com.bydmate.app.helper.HelperBinderHolder.KEY_SPAWN_TOKEN)) "yes" else "no")
                appendLine("last_transport: " + (helperPrefs.getString(
                    com.bydmate.app.helper.HelperBinderHolder.KEY_LAST_TRANSPORT, null) ?: "absent"))
                val failure = helperBootstrap.lastSpawnFailure()
                if (failure == null) {
                    appendLine("last_spawn_failure: (none)")
                } else {
                    appendLine("last_spawn_failure: ${
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(failure.ts))
                    } reason=${failure.reason}")
                    failure.detail.lines().forEach { appendLine("  $it") }
                }
            } catch (e: Exception) { appendLine("(failed to gather helper daemon state: ${e.message})") }

            appendLine("--- split ---")
            try {
                appendLine("feature_enabled: ${splitPreferences.isFeatureEnabled()}")
                // The freeform flag is read once at boot, so its value next to BOOT_COUNT and the
                // verdict state is what tells a firmware that ignores the flag apart from one that
                // simply has not been rebooted yet (#147).
                val freeformFlag = android.provider.Settings.Global.getInt(
                    appContext.contentResolver, "enable_freeform_support", -1)
                appendLine("enable_freeform_support: " + if (freeformFlag < 0) "unset" else "$freeformFlag")
                appendLine("boot_count: ${android.provider.Settings.Global.getInt(
                    appContext.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)}")
                val verdictPrefs = appContext.getSharedPreferences(
                    com.bydmate.app.cluster.ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
                val unsupported = verdictPrefs.getBoolean(
                    com.bydmate.app.split.SplitFreeformVerdict.KEY_UNSUPPORTED, false)
                val seenBoot = verdictPrefs.getInt(
                    com.bydmate.app.split.SplitFreeformVerdict.KEY_SEEN_BOOT, 0)
                val retryBoot = verdictPrefs.getInt(
                    com.bydmate.app.split.SplitFreeformVerdict.KEY_RETRY_BOOT, 0)
                val splitHint = verdictPrefs.getBoolean(
                    com.bydmate.app.cluster.ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING, false)
                val clusterHint = verdictPrefs.getBoolean(
                    com.bydmate.app.cluster.ClusterProjectionManager.KEY_FREEFORM_REBOOT_PENDING, false)
                appendLine(
                    "freeform_verdict: known_good=${com.bydmate.app.split.PaneTypePolicy().knownGood} " +
                        "unsupported=$unsupported seen_boot=$seenBoot retry_boot=$retryBoot " +
                        "split_hint=$splitHint cluster_hint=$clusterHint"
                )
                val lastPair = splitPreferences.getLastPair()
                appendLine("last_pair: " + if (lastPair == null) "(none)" else
                    "narrow=${lastPair.narrowPkg} wide=${lastPair.widePkg} side=${lastPair.narrowSide}")
                when (val session = splitSessionManager.state.value) {
                    is com.bydmate.app.split.SplitSessionState.Idle -> appendLine("session: idle")
                    is com.bydmate.app.split.SplitSessionState.Active -> {
                        appendLine(
                            "session: active narrow=${session.pair.narrowPkg}#${session.narrowTaskId} " +
                                "wide=${session.pair.widePkg}#${session.wideTaskId} " +
                                "side=${session.pair.narrowSide} native=${session.nativePanes}"
                        )
                        val departed = splitSessionManager.departedPanePkgs()
                        appendLine("departed_panes: " + if (departed.isEmpty()) "(none)" else departed.joinToString(","))
                    }
                }
                val splitLines = splitJournal.read()
                appendLine("journal:")
                if (splitLines.isEmpty()) {
                    appendLine("  (empty)")
                } else {
                    splitLines.forEach { appendLine("  $it") }
                }
            } catch (e: Exception) { appendLine("(failed to gather split state: ${e.message})") }

            appendLine("--- windows ---")
            // Which write channel this firmware ended up on (#79): percent fids or CTRL.
            appendLine("window channel: ${windowChannelStore.winner()}")

            appendLine("--- seats ---")
            SeatsDiagnostics.format(helperDiag.seats).forEach { appendLine(it) }

            appendLine("--- seat command journal ---")
            SeatsDiagnostics.journalLines(appContext).forEach { appendLine(it) }

            appendLine("--- steering heat ---")
            SteeringHeatDiagnostics.format(helperDiag.steeringHeat).forEach { appendLine(it) }

            appendLine("--- fid resolve ---")
            try {
                com.bydmate.app.data.nativestack.FidResolveDiagnostics.format(
                    com.bydmate.app.data.nativestack.FidAddresses.table,
                    fidCatalogManager.catalog,
                    writeAllowlist.allEntries().map {
                        com.bydmate.app.data.nativestack.WriteFidRow(it.actionName, it.dev, it.writeFid)
                    },
                    fidCatalogManager.resolveStatus,
                ).forEach { appendLine(it) }
            } catch (e: Exception) { appendLine("error: ${e.message}") }

            appendLine("--- auto backup ---")
            try {
                val lastTs = settingsRepository.getAutoBackupLastTs()
                val chatId = settingsRepository.getTgBackupChatId()
                val tgConfigured = settingsRepository.getTgBackupToken().isNotEmpty() && chatId != null
                appendLine("period: ${settingsRepository.getAutoBackupPeriod().key}")
                appendLine(
                    "parts: auto=${BackupPart.toCsv(settingsRepository.getAutoBackupParts())} " +
                        "manual=${BackupPart.toCsv(settingsRepository.getManualBackupParts())}"
                )
                appendLine("last_ts: " + if (lastTs > 0L)
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(lastTs)) else "(never)")
                appendLine("last_result: ${settingsRepository.getAutoBackupLastResult().ifEmpty { "(none)" }}")
                appendLine("pending: ${File(settingsRepository.getAutoBackupPendingUpload()).name.ifEmpty { "(none)" }}")
                // Never the token; chat id only by its last 4 digits.
                appendLine(
                    "telegram: configured=${if (tgConfigured) "yes" else "no"} " +
                        "bot=${settingsRepository.getTgBackupBotName().ifEmpty { null }?.let { "@$it" } ?: "-"} " +
                        "chat=${chatId?.let { "…" + it.toString().takeLast(4) } ?: "(none)"}"
                )
            } catch (e: Exception) { appendLine("error: ${e.message}") }

            appendLine("--- fid push ---")
            try {
                fidPushChannel.diagnosticsSnapshot().forEach { appendLine(it) }
            } catch (e: Exception) { appendLine("error: ${e.message}") }

            appendLine("--- fid recorder ---")
            try {
                val recorder = helperClient.recStatus()
                when {
                    recorder == null -> appendLine("status unavailable (daemon unreachable)")
                    !recorder.running && recorder.devices.isEmpty() -> appendLine("not running")
                    else -> fidRecorderStatusLines(recorder).forEach { appendLine(it) }
                }
            } catch (e: Exception) { appendLine("error: ${e.message}") }

            appendLine("--- last crash ---")
            try {
                val crashes = CrashLog.read(appContext)
                if (crashes.isEmpty()) {
                    appendLine("(none)")
                } else {
                    crashes.forEachIndexed { index, entry ->
                        if (index > 0) appendLine()
                        appendLine(entry)
                    }
                }
            } catch (e: Exception) { appendLine("(failed to gather crash log: ${e.message})") }

            appendLine("===============================")
            appendLine()
        }

        // File-write failures must surface so the user sees a meaningful
        // error instead of a "запись начата" status next to an empty file.
        FileWriter(file, false).use { it.write(header) }
    }

    fun startLogRecording() {
        viewModelScope.launch {
            // Status for a successful start (and for a stop, including the 2h auto-stop)
            // comes from the recorder state; only failures are reported here.
            when (val result = logRecorder.start { file -> writeDiagnosticHeader(file) }) {
                is LogRecorder.StartResult.Started, LogRecorder.StartResult.AlreadyRecording -> Unit
                LogRecorder.StartResult.NoStorage -> _uiState.update {
                    it.copy(logSaveStatus = appStrings.get(R.string.settings_log_error_no_fs_access))
                }
                is LogRecorder.StartResult.Failed -> _uiState.update {
                    it.copy(logSaveStatus = appStrings.get(R.string.settings_error_with_message, result.message))
                }
            }
        }
    }

    fun stopLogRecording() {
        viewModelScope.launch { logRecorder.stop() }
    }

    /**
     * Mirrors the recorder state into the UI: a ViewModel created after the app
     * window was closed picks up a recording that is still running, and a stop
     * (manual or the 2h auto-stop) reports the saved file from any instance.
     */
    private fun observeLogRecorder() {
        viewModelScope.launch {
            var first = true
            logRecorder.state.collect { state ->
                _uiState.update {
                    val status = when {
                        state.isRecording -> appStrings.get(
                            R.string.settings_log_recording_started, state.filePath ?: "?"
                        )
                        // A fresh ViewModel must not surface the result of a recording
                        // the user stopped long ago.
                        first -> it.logSaveStatus
                        else -> state.lastStopped?.let { stopped ->
                            appStrings.get(R.string.settings_log_saved, stopped.path, stopped.sizeKb)
                        } ?: it.logSaveStatus
                    }
                    it.copy(isRecordingLogs = state.isRecording, logSaveStatus = status)
                }
                first = false
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
            _uiState.update { it.copy(updateDialogState = UpdateState.Checking, updateStatus = appStrings.get(R.string.settings_update_check_in_progress)) }
            try {
                val update = updateChecker.checkForUpdate(appContext, forceCheck = true)
                if (update != null) {
                    _uiState.update {
                        it.copy(
                            updateDialogState = UpdateState.Available(
                                version = update.version,
                                notes = update.releaseNotes ?: ""
                            ),
                            updateStatus = appStrings.get(R.string.settings_update_available_short, update.version)
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            updateDialogState = UpdateState.UpToDate,
                            updateStatus = appStrings.get(R.string.settings_update_up_to_date)
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        updateDialogState = UpdateState.Error(e.message ?: "Unknown error"),
                        updateStatus = appStrings.get(R.string.settings_error_with_message, e.message ?: "?")
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Config backup / restore
    // -------------------------------------------------------------------------

    /**
     * «Сохранить конфигурацию» (#238): exports [parts] to Download and sends a copy to the bot when
     * it is connected. The choice is remembered for the next save. Errors go to configStatus.
     */
    fun saveConfiguration(parts: Set<BackupPart>) {
        if (parts.isEmpty()) return
        _uiState.update { it.copy(manualBackupParts = parts) }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(configStatus = appStrings.get(R.string.settings_export_in_progress)) }
            try {
                settingsRepository.setManualBackupParts(parts)
                val result = AutoBackupRunner(backupManager, telegramBackupSink, settingsRepository).saveManual(parts)
                val telegram = when {
                    result.sent -> appStrings.get(R.string.settings_config_save_sent)
                    result.sendError != null -> appStrings.get(
                        R.string.settings_config_save_send_failed,
                        telegramErrorText(appStrings.context, result.sendError),
                    )
                    else -> null
                }
                // The «Поделиться» dialog shows the saved file instead of the status line.
                _uiState.update { it.copy(configStatus = null, savedBackup = SavedBackup(result.file, telegram)) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(configStatus = appStrings.get(R.string.settings_error_with_message, e.message ?: "?"))
                }
            }
        }
    }

    /** Export zips found in the public Download folder, newest first. */
    /** Lists the backups off the main thread (a scan of the whole Download folder), then opens the picker. */
    // Scan behind the picker: repeated taps don't start a second one, and a closed picker
    // must not be reopened by a late result.
    private var restoreScanJob: Job? = null

    // Test seam: tests swap it for a test dispatcher so the restore scan runs on virtual time.
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    fun openRestorePicker() {
        if (restoreScanJob?.isActive == true) return
        restoreScanJob = viewModelScope.launch(ioDispatcher) {
            val files = backupManager.listBackups()
            ensureActive()
            _uiState.update { it.copy(restoreCandidates = files) }
        }
    }

    fun closeRestorePicker() {
        restoreScanJob?.cancel()
        _uiState.update { it.copy(restoreCandidates = null) }
    }

    /** A file tapped in the picker: reads the parts its manifest lists, then opens «Что восстановить». */
    fun pickRestoreFile(file: File) {
        closeRestorePicker()
        viewModelScope.launch(ioDispatcher) {
            try {
                val parts = backupManager.archiveParts(file)
                _uiState.update { it.copy(restoreChoice = RestoreChoice(file, parts)) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(configStatus = appStrings.get(R.string.settings_error_with_message, e.message ?: "?"))
                }
            }
        }
    }

    fun dismissRestoreChoice() {
        _uiState.update { it.copy(restoreChoice = null) }
    }

    /**
     * Restore [parts] of the app state from a user-picked backup zip.
     * On success the process is immediately restarted so Room re-opens the replaced DB.
     * On failure configStatus is set to the error message.
     */
    fun restoreConfig(file: File, parts: Set<BackupPart>) {
        // One restore at a time: a second one would run over the first one's temp files.
        if (_uiState.value.restoreInProgress) return
        restoreScanJob?.cancel()
        _uiState.update { it.copy(restoreChoice = null, restoreInProgress = true) }
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(configStatus = appStrings.get(R.string.settings_export_in_progress)) }
            try {
                backupManager.restore(file, parts)
                restartApp()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(configStatus = appStrings.get(R.string.settings_error_with_message, e.message ?: "?"))
                }
            } finally {
                _uiState.update { it.copy(restoreInProgress = false) }
            }
        }
    }

    /** Dismiss the config backup/restore status message. */
    fun clearConfigStatus() {
        _uiState.update { it.copy(configStatus = null) }
    }

    /** «Поделиться» in the dialog after a manual export (#237). */
    fun shareExportedBackup() {
        val file = _uiState.value.savedBackup?.file ?: return
        _uiState.update { it.copy(savedBackup = null) }
        try {
            shareFile(file, "application/zip")
        } catch (e: Exception) {
            _uiState.update {
                it.copy(configStatus = appStrings.get(R.string.settings_error_with_message, e.message ?: "?"))
            }
        }
    }

    fun dismissExportedBackup() {
        _uiState.update { it.copy(savedBackup = null) }
    }

    /** Standard ACTION_SEND share sheet for a file in Download (or the app's external files). */
    private fun shareFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(
            appContext, "${appContext.packageName}.fileprovider", file,
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(chooser)
    }

    // -------------------------------------------------------------------------
    // Automatic backup (#237)
    // -------------------------------------------------------------------------

    /** Loads the bot once, then mirrors period and last run live so the status follows the worker. */
    private fun observeAutoBackup() {
        viewModelScope.launch {
            val config = settingsRepository.getTgBackupConfig()
            val binding = if (config.configured) TgBackupBinding(config.botName, config.chatName) else null
            val manualParts = settingsRepository.getManualBackupParts()
            _uiState.update {
                it.copy(tgBackupToken = config.token, tgBackupBinding = binding, manualBackupParts = manualParts)
            }
            combine(
                settingsRepository.observeAutoBackupPeriod(),
                settingsRepository.observeAutoBackupLastTs(),
                settingsRepository.observeAutoBackupLastResult(),
                settingsRepository.observeAutoBackupParts(),
            ) { period, lastTs, lastResult, parts ->
                { s: SettingsUiState ->
                    s.copy(
                        autoBackupPeriod = period,
                        autoBackupLastTs = lastTs,
                        autoBackupLastResult = lastResult,
                        autoBackupParts = parts,
                    )
                }
            }.collect { apply -> _uiState.update(apply) }
        }
    }

    fun setAutoBackupPeriod(period: AutoBackupPeriod) {
        _uiState.update { it.copy(autoBackupPeriod = period) }
        if (period == AutoBackupPeriod.OFF) autoBackupScheduler.cancelScheduled(appContext)
        viewModelScope.launch { settingsRepository.setAutoBackupPeriod(period) }
    }

    /** A part chip under the period chips; the last checked part cannot be unchecked. */
    fun toggleAutoBackupPart(part: BackupPart) {
        val parts = _uiState.value.autoBackupParts.let { if (part in it) it - part else it + part }
        if (parts.isEmpty()) return
        _uiState.update { it.copy(autoBackupParts = parts) }
        viewModelScope.launch { settingsRepository.setAutoBackupParts(parts) }
    }

    /**
     * Token field edits stay in the state; the token is stored once «Проверить» accepts it.
     * Trimmed: a token pasted from a chat often carries a trailing space or newline.
     */
    fun updateTgBackupToken(value: String) {
        _uiState.update { it.copy(tgBackupToken = value.trim()) }
    }

    private var tgCheckJob: Job? = null

    /** «Проверить»: token, then bot name, then the chat bound by a code, then a greeting message. */
    fun checkTelegramBackup() {
        val token = _uiState.value.tgBackupToken.trim()
        if (token.isEmpty() || _uiState.value.tgBackupChecking) return
        _uiState.update {
            it.copy(tgBackupChecking = true, tgBackupStatus = appStrings.get(R.string.settings_tg_backup_checking))
        }
        tgCheckJob = viewModelScope.launch {
            val status = connectTelegramBot(token)
            _uiState.update {
                // The field was edited while the check ran: the result belongs to another token.
                if (it.tgBackupToken != token) it.copy(tgBackupChecking = false, tgBackupStatus = null)
                else it.copy(tgBackupChecking = false, tgBackupStatus = status)
            }
        }
    }

    /**
     * Binds the chat that sent the one-time code and stores the binding only after the greeting
     * went through; any error changes nothing. A bot already bound with this token keeps its chat.
     * Returns the status line to show; null = the stepper itself tells what happened.
     */
    private suspend fun connectTelegramBot(token: String): String? {
        val botName = telegramBackupSink.getMe(token).getOrElse { return tgBackupError(it) }
        val stored = settingsRepository.getTgBackupConfig()
        val chat = if (stored.token == token && stored.chatId != null) {
            TelegramChat(stored.chatId, stored.chatName)
        } else {
            val code = _uiState.value.tgBackupCode
            if (code == null) {
                _uiState.update { it.copy(tgBackupCode = newBindCode(), tgBackupBotName = botName) }
                return null
            }
            telegramBackupSink.findPrivateChat(token, code).getOrElse { return tgBackupError(it) }
                ?: return appStrings.get(R.string.settings_tg_backup_code_not_received)
        }
        telegramBackupSink.sendMessage(token, chat.id, appStrings.get(R.string.settings_tg_backup_greeting))
            .getOrElse { return tgBackupError(it) }
        // The field now holds another token: store nothing, the caller drops this status.
        if (_uiState.value.tgBackupToken != token) return null
        settingsRepository.saveTgBackup(token, botName, chat.id, chat.name)
        _uiState.update { it.copy(tgBackupCode = null, tgBackupBinding = TgBackupBinding(botName, chat.name)) }
        return null
    }

    /** «Назад» on the code step: drops the code, the token stays in the field for another try. */
    fun cancelTelegramCode() {
        tgCheckJob?.cancel()
        _uiState.update { it.copy(tgBackupCode = null, tgBackupStatus = null, tgBackupChecking = false) }
    }

    private fun newBindCode(): String = (BIND_CODE_MIN + SecureRandom().nextInt(BIND_CODE_SPAN)).toString()

    private fun tgBackupError(error: Throwable): String = appStrings.get(
        R.string.settings_error_with_message,
        (error as? TelegramSinkException)?.let { telegramErrorText(appStrings.context, it.key) } ?: error.message ?: "?",
    )

    /**
     * «Отключить»: stops a running check and a waiting scheduled run, then forgets token, chat and
     * bot name; backups stay local.
     */
    fun disconnectTelegramBackup() {
        val check = tgCheckJob
        autoBackupScheduler.cancelScheduled(appContext)
        _uiState.update {
            it.copy(
                tgBackupToken = "",
                tgBackupStatus = null,
                tgBackupChecking = false,
                tgBackupCode = null,
                tgBackupBotName = "",
                tgBackupBinding = null,
            )
        }
        viewModelScope.launch {
            // Joined first so a check that was about to save cannot write after the clear.
            check?.cancelAndJoin()
            settingsRepository.clearTgBackup()
        }
    }

    /**
     * Reflects all static int/long constants out of the BYD SDK fid classes via the helper
     * daemon, writes the result to the public Download/fid-dump-<timestamp>.txt, prepends a
     * 3-line header, then fires the standard ACTION_SEND share sheet.
     *
     * The folder is public (W6-F4) because the previous private filesDir target was reachable
     * neither by a file manager nor by `adb pull`, so users could not hand the dump over.
     *
     * Privacy: NO automatic upload, NO background collection. The dump contains only SDK
     * constant names/values; no VIN, no location, no personal data. The file leaves the
     * device only through the user-driven share sheet.
     */
    fun dumpFids() {
        // Compute the locale-aware context on the calling thread (Main) before switching to IO.
        // This avoids thread-specific Robolectric issues and is cheap (createConfigurationContext).
        val lc = appContext.appLocalizedContext()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(fidDumpStatus = lc.getString(R.string.settings_fid_dump_in_progress)) }
            // C-3: ensure fresh daemon so old-format TX_DUMP_FIDS replies are never encountered.
            helperBootstrap.ensureRunning()
            val dumpResult = helperClient.dumpFids()
            val dump: String = when (dumpResult) {
                is DumpFidsResult.BinderAbsent -> {
                    _uiState.update {
                        it.copy(fidDumpStatus = lc.getString(
                            R.string.settings_error_with_message,
                            lc.getString(R.string.settings_fid_dump_error_unavailable),
                        ))
                    }
                    return@launch
                }
                is DumpFidsResult.ReadError -> {
                    _uiState.update {
                        it.copy(fidDumpStatus = lc.getString(
                            R.string.settings_error_with_message,
                            lc.getString(R.string.settings_fid_dump_error_read, dumpResult.detail),
                        ))
                    }
                    return@launch
                }
                is DumpFidsResult.Success -> dumpResult.dump
            }
            if (dump.isBlank()) {
                _uiState.update { it.copy(fidDumpStatus = lc.getString(R.string.settings_fid_dump_empty)) }
                return@launch
            }
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val fileName = "fid-dump-$timestamp.txt"
                // Same candidate chain and same target folder as startLogRecording(): straight
                // into the public Download, no subfolder — CSV export, config backup and the APK
                // update all land there too.
                val dir = listOfNotNull(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    File("/storage/emulated/0/Download"),
                    appContext.getExternalFilesDir(null),
                ).firstOrNull { (it.isDirectory || it.mkdirs()) && it.canWrite() }
                if (dir == null) {
                    _uiState.update { it.copy(fidDumpStatus = lc.getString(R.string.settings_log_error_no_fs_access)) }
                    return@launch
                }
                // Keep only the most recent dump so a share still reading the previous URI
                // can finish; the new file makes it the second, giving a two-file rolling window.
                dir.listFiles { _, name -> name.startsWith("fid-dump-") && name.endsWith(".txt") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(1)
                    ?.forEach { it.delete() }
                val file = File(dir, fileName)
                file.bufferedWriter().use { out ->
                    val pi = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                    val vName = pi.versionName ?: "?"
                    val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        pi.longVersionCode.toString()
                    else
                        @Suppress("DEPRECATION") pi.versionCode.toString()
                    val model = Build.MODEL
                    val buildId = Build.DISPLAY
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                    out.appendLine("BYDMate $vName ($vCode), $date")
                    out.appendLine("model: $model  build: $buildId")
                    out.appendLine("---")
                    out.append(dump)
                }
                shareFile(file, "text/plain")
                // Show the path the user actually sees in a file manager (storage root stripped),
                // e.g. "Download/fid-dump-20260729-120000.txt".
                val storageRoot = Environment.getExternalStorageDirectory()?.absolutePath
                val visiblePath = file.absolutePath.let { path ->
                    if (storageRoot != null && path.startsWith(storageRoot))
                        path.removePrefix(storageRoot).trimStart('/')
                    else
                        path
                }
                _uiState.update { it.copy(fidDumpStatus = lc.getString(R.string.settings_fid_dump_saved, visiblePath)) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(fidDumpStatus = lc.getString(R.string.settings_error_with_message, e.message ?: "?"))
                }
            }
        }
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
                        it.copy(updateDialogState = UpdateState.Downloading(update.version, appStrings.get(R.string.update_downloading_start)))
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
/** Localized text for a [TelegramSinkException.key]; an unknown key is shown as is. */
internal fun telegramErrorText(context: Context, key: String): String {
    if (key.startsWith("HTTP:")) return context.getString(R.string.settings_tg_error_http, key.removePrefix("HTTP:"))
    val res = when (TelegramError.entries.firstOrNull { it.name == key }) {
        TelegramError.BAD_TOKEN -> R.string.settings_tg_error_bad_token
        TelegramError.NO_NETWORK -> R.string.settings_tg_error_no_network
        TelegramError.NO_CHAT -> R.string.settings_tg_error_no_chat
        TelegramError.TOO_LARGE -> R.string.settings_tg_error_too_large
        TelegramError.WEBHOOK -> R.string.settings_tg_error_webhook
        TelegramError.BAD_RESPONSE -> R.string.settings_tg_error_bad_response
        TelegramError.HTTP, null -> return key
    }
    return context.getString(res)
}

/** Localized text for the stored auto backup result key (see [AutoBackupRunner] RESULT_*). */
internal fun autoBackupResultText(context: Context, key: String): String = when {
    key == AutoBackupRunner.RESULT_SENT -> context.getString(R.string.settings_auto_backup_result_sent)
    key == AutoBackupRunner.RESULT_LOCAL_ONLY -> context.getString(R.string.settings_auto_backup_result_local_only)
    key == AutoBackupRunner.RESULT_EXPORT_ERROR -> context.getString(R.string.settings_auto_backup_result_export_error)
    key.startsWith(AutoBackupRunner.RESULT_SEND_ERROR_PREFIX) -> context.getString(
        R.string.settings_auto_backup_result_send_error,
        telegramErrorText(context, key.removePrefix(AutoBackupRunner.RESULT_SEND_ERROR_PREFIX)),
    )
    else -> key
}

internal fun enabledSettingName(state: Int): String = when (state) {
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "DEFAULT (enabled)"
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "ENABLED"
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "DISABLED"
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "DISABLED_USER"
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> "DISABLED_UNTIL_USED"
    else -> "UNKNOWN($state)"
}
