package com.bydmate.app.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.bydmate.app.agent.LlmConnectionResolver
import com.bydmate.app.data.backup.BackupPart
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.helper.push.fidRecorderStatusLines
import com.bydmate.app.domain.cost.CostCalculator
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.service.TrackingService
import com.bydmate.app.util.CrashLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.bydmate.app.service.BootReceiver
import com.bydmate.app.cluster.DEFAULT_VOICE_KEYCODE
import com.bydmate.app.cluster.voiceCompanionsFromCsv
import com.bydmate.app.cluster.voiceCompanionsToCsv
import com.bydmate.app.voice.VoiceJournal
import com.bydmate.app.voice.VoiceJournalDump
import com.bydmate.app.voice.TtsModelManager
import com.bydmate.app.voice.online.TtsRouter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/** Writes the diagnostic dump header that opens every log recording ([SettingsViewModel.startLogRecording]). */
class DiagnosticDumpWriter @Inject @Suppress("LongParameterList") constructor( // Hilt-injected dependencies
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val historyImporter: HistoryImporter,
    private val localePreferences: LocalePreferences,
    private val chargingStateStore: com.bydmate.app.data.charging.ChargingStateStore,
    private val catchUpJournal: com.bydmate.app.data.charging.CatchUpJournal,
    private val windowChannelStore: com.bydmate.app.data.vehicle.WindowChannelStore,
    private val helperClient: com.bydmate.app.data.vehicle.HelperClient,
    private val helperBootstrap: com.bydmate.app.data.vehicle.HelperBootstrap,
    private val llmConnectionResolver: LlmConnectionResolver,
    private val energyDataDeadDetector: com.bydmate.app.data.local.EnergyDataDeadDetector,
    private val hudController: com.bydmate.app.hud.HudController,
    private val fidPushChannel: com.bydmate.app.data.push.FidPushChannel,
    private val splitPreferences: com.bydmate.app.split.SplitPreferences,
    private val splitSessionManager: com.bydmate.app.split.SplitSessionManager,
    private val splitJournal: com.bydmate.app.split.SplitJournal,
    private val adbRestoreManager: com.bydmate.app.data.autoservice.AdbRestoreManager,
    private val cloudOverWifiManager: com.bydmate.app.data.autoservice.CloudOverWifiManager,
    private val fidCatalogManager: com.bydmate.app.data.nativestack.FidCatalogManager,
    private val writeAllowlist: com.bydmate.app.data.vehicle.WriteAllowlist,
    private val ruleDao: com.bydmate.app.data.local.dao.RuleDao,
    private val automationEngine: com.bydmate.app.data.automation.AutomationEngine,
    private val voiceJournal: VoiceJournal,
    private val costCalculator: CostCalculator,
    private val blindSpotController: com.bydmate.app.camera.BlindSpotController,
    private val adbVerdictMonitor: com.bydmate.app.data.autoservice.AdbVerdictMonitor,
    private val telegramReporter: com.bydmate.app.data.telegram.TelegramReporter,
    private val clusterMusicBridge: com.bydmate.app.media.ClusterMusicBridge,
    private val vehicleWriteLogDao: com.bydmate.app.data.local.dao.VehicleWriteLogDao,
    private val vehicleKindMonitor: com.bydmate.app.data.nativestack.VehicleKindMonitor,
) {

    companion object {
        /** Voice sessions printed in the dump's agent section (newest first). */
        private const val AGENT_DUMP_ENTRIES = 20
        private const val AGENT_DUMP_ANSWER_CHARS = 200
        /** Shared budget for the daemon-backed dump sections (liveness + seat, steering heat and window reads).
         *  The dump must not hang on a wedged daemon. */
        private const val HELPER_DIAG_BUDGET_MS = 3_000L
        private const val VEHICLE_WRITES_IN_DUMP = 40

        /**
         * A binder transact is a blocking call: wrapping it in withTimeoutOrNull here would not
         * return until the call finished, because cancellation only takes effect at a suspension
         * point. So the reads run in a coroutine that is NOT a child of the dump, and only the
         * WAIT is bounded — a wedged daemon leaves an IO thread parked instead of stalling the
         * dump the user is trying to send us. Each section is kept as soon as it is read, so a
         * later section that hangs past [budgetMs] does not erase the earlier ones.
         */
        internal suspend fun collectHelperDiagnostics(
            scope: CoroutineScope,
            helper: com.bydmate.app.data.vehicle.HelperClient,
            budgetMs: Long,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): HelperDiagnostics {
            // Single writer (the probe), read after the wait: the reference gives visibility.
            val collected = AtomicReference(HelperDiagnostics(null, null))
            val probe = scope.launch(dispatcher) {
                val alive = runCatching { helper.isAlive() }.getOrNull()
                collected.set(collected.get().copy(alive = alive))
                // One binder round-trip for all ten seat reads.
                val seats = runCatching { helper.readBatch(SeatsDiagnostics.batchItems()) }.getOrNull()
                collected.set(collected.get().copy(seats = seats))
                val steeringHeat = runCatching { helper.readBatch(SteeringHeatDiagnostics.batchItems()) }.getOrNull()
                collected.set(collected.get().copy(steeringHeat = steeringHeat))
                val windows = runCatching { helper.readBatch(WindowDiagnostics.batchItems()) }.getOrNull()
                collected.set(collected.get().copy(windows = windows))
                val hud = runCatching { helper.readBatch(HudDiagnostics.batchItems()) }.getOrNull()
                collected.set(collected.get().copy(hud = hud))
                val hudSwitch = runCatching { helper.readBatch(HudDiagnostics.switchBatchItems()) }.getOrNull()
                collected.set(collected.get().copy(hudSwitch = hudSwitch))
                val batteryPreheat = runCatching { helper.readBatch(BatteryPreheatDiagnostics.batchItems()) }.getOrNull()
                collected.set(collected.get().copy(batteryPreheat = batteryPreheat))
                // Read last: the budget is shared, and the older rows must not lose their time to it.
                val spoiler = runCatching { helper.readBatch(SpoilerDiagnostics.batchItems()) }.getOrNull()
                collected.set(collected.get().copy(spoiler = spoiler))
            }
            withTimeoutOrNull(budgetMs) { probe.join() }
            return collected.get()
        }
    }

    /** What the daemon-backed dump sections need; nulls mean "not obtained in budget". */
    internal data class HelperDiagnostics(
        val alive: Boolean?,
        val seats: List<Pair<Int, Int>>?,
        val steeringHeat: List<Pair<Int, Int>>? = null,
        val windows: List<Pair<Int, Int>>? = null,
        val hud: List<Pair<Int, Int>>? = null,
        val hudSwitch: List<Pair<Int, Int>>? = null,
        val spoiler: List<Pair<Int, Int>>? = null,
        val batteryPreheat: List<Pair<Int, Int>>? = null,
    )

    /** Liveness and the seat, steering heat and window fid snapshots under ONE shared budget. */
    private suspend fun gatherHelperDiagnostics(viewModelScope: CoroutineScope): HelperDiagnostics =
        collectHelperDiagnostics(viewModelScope, helperClient, HELPER_DIAG_BUDGET_MS)

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
    suspend fun write(file: File, viewModelScope: CoroutineScope) = withContext(Dispatchers.IO) {
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
            try {
                val pm = appContext.packageManager
                appendLine(RecordingDumpFormat.appVersionsLine { pkg ->
                    try {
                        val pi = pm.getPackageInfo(pkg, 0)
                        pi.versionName to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
                        else @Suppress("DEPRECATION") pi.versionCode.toLong()
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                })
            } catch (e: Exception) {
                appendLine("(failed to gather app versions: ${e.message})")
            }

            appendLine("--- settings ---")
            try {
                val dataSource = settingsRepository.getDataSource().name
                val capacityRaw = settingsRepository.getString(SettingsRepository.KEY_BATTERY_CAPACITY, "")
                val capacityParsed = settingsRepository.getBatteryCapacity()
                val abrpEnabled = settingsRepository.getString(SettingsRepository.KEY_ABRP_ENABLED, "false") == "true"
                val abrpTokenLen = settingsRepository.getString(SettingsRepository.KEY_ABRP_USER_TOKEN, "").length
                val abrpCarModel = settingsRepository.getString(SettingsRepository.KEY_ABRP_CAR_MODEL, "")
                val abrpSendLocation = settingsRepository.getString(SettingsRepository.KEY_ABRP_SEND_LOCATION, "false") == "true"
                appendLine("data_source: $dataSource")
                appendLine("battery_capacity: raw=\"$capacityRaw\" parsed=$capacityParsed")
                appendLine("abrp_enabled: $abrpEnabled token_len=$abrpTokenLen car_model=\"$abrpCarModel\" send_location=$abrpSendLocation")
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
                appendLine(cloudOverWifiManager.dumpLine())
                appendLine("adb_verdict: ${adbVerdictMonitor.verdict.value ?: "(none)"}")
                appendLine("daemon_ever_alive: ${helperBootstrap.daemonEverAlive()}")
                appendLine(com.bydmate.app.data.backup.PostRestoreCheck.dumpLine(appContext))
                appendLine(
                    "widget_home_only=${com.bydmate.app.ui.widget.WidgetPreferences(appContext).isHomeOnly()} " +
                        "home_packages=${com.bydmate.app.ui.widget.WidgetController.queryHomePackages(appContext)}"
                )
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
                appendLine("fuel_price_per_liter=" + (current?.fuelPricePerLiter?.toString() ?: ""))
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

            // «Состояние машины» / «Диагностика»: the stock car status provider as read right now
            // (raw keys and what the card makes of them). PM2.5 is read by the Tech screen only
            // and logs its own `pm25 read:` lines under TechPanel.
            appendLine("--- car status ---")
            try {
                appendLine(com.bydmate.app.data.carstatus.CarStatusReader(appContext).dumpLine())
            } catch (e: Exception) { appendLine("error: ${e.message}") }

            // ICE-side addresses nothing in the app reads yet (#184). A DM-i owner sends this
            // dump with the engine running and the raw words say which of them are live there.
            appendLine("--- hybrid probe ---")
            try {
                HybridProbeDiagnostics
                    .format(helperClient.readBatch(HybridProbeDiagnostics.batchItems()))
                    .forEach { appendLine(it) }
            } catch (e: Exception) { appendLine("error: ${e.message}") }
            // Where the poll reads the hybrid fields on this car (#394): the compiled address, or
            // the catalog one where the compiled address answers an error.
            try {
                val hybridTable = com.bydmate.app.data.nativestack.FidAddresses.hybrid
                HybridProbeDiagnostics
                    .formatTable(hybridTable, helperClient.readBatch(HybridProbeDiagnostics.tableItems(hybridTable)))
                    .forEach { appendLine(it) }
            } catch (e: Exception) { appendLine("hybrid table: error ${e.message}") }
            try {
                appendLine(vehicleKindMonitor.dumpLine())
            } catch (e: Exception) { appendLine("vehicle_kind: error ${e.message}") }
            try {
                appendLine(com.bydmate.app.data.dtc.CarAutoType.dumpLine(helperClient))
            } catch (e: Exception) { appendLine("car_autotype: error ${e.message}") }

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
            // What the rules did and why not, and every condition as the engine reads it now.
            appendLine("--- automation journal ---")
            try {
                automationEngine.journalDumpLines().forEach { appendLine(it) }
            } catch (e: Exception) {
                appendLine("(failed to gather journal: ${e.message})")
            }
            appendLine("--- automation params ---")
            appendLine(automationEngine.paramSnapshotLine(com.bydmate.app.ui.automation.TRIGGER_PARAMS.map { it.param }))

            // Voice agent: which connection/model answered.
            appendLine("--- agent ---")
            try {
                val conn = llmConnectionResolver.primary()
                appendLine("connection: ${conn?.id ?: "(not configured)"} model=${conn?.model ?: "-"}")
            } catch (e: Exception) {
                appendLine("(failed to gather agent connection: ${e.message})")
            }
            // Voice speed wave: which TTS source speaks the replies (online latency depends on it).
            try {
                val voicePrefs = appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
                val source = voicePrefs.getString("tts_source", TtsRouter.OFFLINE) ?: TtsRouter.OFFLINE
                val minimaxProvider = settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_PROVIDER, "official")
                val voice = voicePrefs.getString("tts_voice", TtsModelManager.DEFAULT_VOICE_ID)
                val gender = voicePrefs.getString("agent_gender", "m")
                appendLine("tts: source=$source minimax_provider=$minimaxProvider offline_voice=$voice gender=$gender")
            } catch (e: Exception) {
                appendLine("(failed to gather tts source: ${e.message})")
            }
            // Voice sessions, persisted across restarts: what ASR heard, what it became (command,
            // automation, agent) and why not, with the agent's tools and answer. The only place a
            // user report about a misheard or refused command becomes checkable.
            appendLine("--- voice ---")
            try {
                VoiceJournalDump.lines(voiceJournal.entries.value, AGENT_DUMP_ENTRIES, AGENT_DUMP_ANSWER_CHARS)
                    .forEach { appendLine(it) }
            } catch (e: Exception) {
                appendLine("(failed to gather voice journal: ${e.message})")
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
                    appendLine(historyImporter.importStateLine())
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
                val voicePrefs = appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
                val preDuck = voicePrefs.getInt("pre_duck_volume", -1)
                appendLine("pre_duck_volume: " + if (preDuck >= 0) "$preDuck" else "(none)")
                // #353: the firmware voice duck state; the amp type tells which cars needed it.
                val ampType = com.bydmate.app.util.readSystemProperty("sys.ext.amp.type") ?: "(none)"
                val autoVoiceMarker = voicePrefs.getBoolean(com.bydmate.app.voice.AutoVoiceDuck.KEY_MARKER, false)
                val autoVoiceLast = voicePrefs.getString(com.bydmate.app.voice.AutoVoiceDuck.KEY_LAST, null) ?: "(none)"
                appendLine("auto_voice: ext_amp_type=$ampType marker=${if (autoVoiceMarker) "set" else "none"} last=$autoVoiceLast")
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
                // The way to the glass (1..3, «Способ вывода на стекло»).
                appendLine("mode: ${hudController.mode()}")
                // Frame/RC counters from HudPushLoop via HudController.diag().
                val diag = hudController.diag()
                // Ways 2 and 3: CAN guidance writes the car accepted and refused (clears not
                // counted), and what a process death may have left on the instrument or the gateway.
                appendLine(
                    "can_accepted=${diag?.canAccepted ?: 0} can_refused=${diag?.canRefused ?: 0} " +
                        "can_left=${hudPrefs.contains(com.bydmate.app.hud.HudWayChannels.KEY_CAN_LEFT)} " +
                        "lmcn_left=${hudPrefs.contains(com.bydmate.app.hud.HudWayChannels.KEY_LMCN_LEFT)}"
                )
                appendLine("frames_sent=${diag?.framesSent ?: 0} last_frame_ts=${diag?.lastFrameTs ?: 0}")
                appendLine("last_fire_rc=${diag?.lastRc ?: "n/a"} nonzero_rc_count=${diag?.nonZeroRcCount ?: 0}")
                // Every gateway service the product's binding holds open (the HUD check's step 4 adds
                // its own while it runs on that binding), and each topic's fireEvent rcs.
                val someIp = hudController.boundBridge
                appendLine("someip_services=${someIp?.startedServices()?.let { com.bydmate.app.hud.HudSomeIpBridge.describeServices(it) } ?: "n/a"}")
                appendLine("someip_fire_rc=${someIp?.fireCounts()?.let { com.bydmate.app.hud.HudSomeIpBridge.describeFires(it) } ?: "n/a"}")
                appendLine("amap_capable=${diag?.amapCapable ?: false} amap_frames=${diag?.amapFramesSent ?: 0} amap_stops=${diag?.amapStopsSent ?: 0}")
                // No gateway (#301): TYPE 0 frames and KILLs to the Amap adapter's cluster card.
                appendLine(hudController.amapCluster?.let {
                    "amap_cluster: frames=${it.framesSent} kills=${it.killsSent} last_frame_ts=${it.lastFrameTs}"
                } ?: "amap_cluster: n/a")
                appendLine("hub_snapshot=${RecordingDumpFormat.hubSnapshot(com.bydmate.app.navdata.NavGuidanceHub.snapshot())}")
                // The current route's counters, or the last route's (the route-summary trace line).
                appendLine("route_summary: ${com.bydmate.app.navdata.NavGuidanceHub.routeSummary()}")
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
                // Approved is not bound (#325): the framework may never connect an approved listener.
                appendLine("notif_listener_connected=${com.bydmate.app.media.MediaSessionListenerService.isConnected}")
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
                // density instead — their scale slider is inert. Each with why and when it latched.
                val densityUnsafe = cpm.densityUnsafePackages(appContext)
                appendLine("density: " + when (diag.directDensityDpi) {
                    -1 -> "(not set this session)"
                    0 -> "native"
                    else -> "${diag.directDensityDpi} dpi"
                } + " unsafe=" + if (densityUnsafe.isEmpty()) "(none)" else densityUnsafe.joinToString { pkg ->
                    "$pkg (${cpm.densityUnsafeReason(appContext, pkg) ?: "no reason recorded"})"
                })
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
                // Both surfaces with their sizes: «Полный экран приборки» only matters where
                // the car has two, and only these lines tell which firmwares do.
                appendLine("surfaces: " + if (projectionDisplays.isEmpty()) "(none)" else
                    projectionDisplays.joinToString(" ") { d ->
                        val p = android.graphics.Point()
                        @Suppress("DEPRECATION") d.getRealSize(p)
                        "${d.name}=${p.x}x${p.y}"
                    })
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

            appendLine("--- cluster music ---")
            try {
                clusterMusicBridge.dumpLines().forEach { appendLine(it) }
            } catch (e: Exception) { appendLine("(failed to gather cluster music state: ${e.message})") }

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
                val voiceCompanions = voiceCompanionsFromCsv(
                    voicePrefs.getString(SettingsRepository.KEY_VOICE_COMPANIONS, null))
                appendLine(
                    "voice_ptt: enabled=${voicePrefs.getBoolean("voice_enabled", false)} " +
                        "keycode=${voicePrefs.getInt("voice_keycode", DEFAULT_VOICE_KEYCODE)} " +
                        "companions=${voiceCompanionsToCsv(voiceCompanions).ifEmpty { "-" }}"
                )
                val clusterPrefs = appContext.getSharedPreferences(
                    com.bydmate.app.cluster.ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
                appendLine("mirror_enabled: ${clusterPrefs.getBoolean(
                    com.bydmate.app.cluster.ClusterProjectionManager.KEY_MIRROR_ENABLED, false)}")
                appendLine("split_key: enabled=${splitPreferences.isFeatureEnabled()} " +
                    "keycode=${splitPreferences.getSteeringKeyCode()}")
                appendLine(com.bydmate.app.cluster.SteeringWheelKeyService.keyCounters.line())
            } catch (e: Exception) { appendLine("(failed to gather steering key state: ${e.message})") }

            appendLine("--- autostart ---")
            try {
                val bootPrefs = appContext.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
                // The a11y recovery gate lives in the cluster prefs, where TrackingService keeps it.
                val gatePrefs = appContext.getSharedPreferences(
                    com.bydmate.app.cluster.ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
                AutostartDiagnostics.format(
                    chainLog = bootPrefs.getString(BootReceiver.KEY_CHAIN_LOG, null),
                    failStreak = gatePrefs.getInt(com.bydmate.app.service.A11yRecoveryGate.KEY_FAIL_STREAK, 0),
                    lastAttemptElapsedMs = gatePrefs.getLong(
                        com.bydmate.app.service.A11yRecoveryGate.KEY_LAST_ATTEMPT_ELAPSED_MS, 0L),
                    nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
                ).forEach { appendLine(it) }
            } catch (e: Exception) { appendLine("(failed to gather autostart state: ${e.message})") }

            appendLine("--- native assistant packages ---")
            try {
                val pref = settingsRepository.getString(SettingsRepository.KEY_DISABLE_NATIVE_ASSISTANT, "")
                appendLine("disable_native_assistant pref: \"$pref\"")
                // Same package family the helper daemon disables via TX_SET_APP_HIDDEN.
                val pm = appContext.packageManager
                for (pkg in listOf("com.byd.autovoice", "com.byd.autovoice.engine", "com.byd.autovoice.tts", "com.byd.vrassistant")) {
                    val state = runCatching { enabledSettingName(pm.getApplicationEnabledSetting(pkg)) }
                        .getOrElse { "not installed" }
                    appendLine("$pkg: $state")
                }
            } catch (e: Exception) { appendLine("(failed to gather assistant package state: ${e.message})") }

            // Both daemon sections come from one detached probe (see gatherHelperDiagnostics).
            val helperDiag = gatherHelperDiagnostics(viewModelScope)

            appendLine("--- helper daemon ---")
            try {
                appendLine("alive: ${helperDiag.alive?.toString() ?: "(unknown — probe timed out)"}")
                // The car's own «Опц. содержимое → Навигация» gate (#269), read only.
                appendLine("hud_navi_gate:")
                HudDiagnostics.format(helperDiag.hud).forEach { appendLine(it) }
                // The HUD master switch (#292) and the HUD type, read only.
                appendLine(HudDiagnostics.switchLine(helperDiag.hudSwitch))
                // The electric spoiler (#383): fitted or not, status and position, read only.
                appendLine(SpoilerDiagnostics.line(helperDiag.spoiler))
                // The battery warm-up (#311): fitted or not, and whether the car permits it now, read only.
                appendLine(BatteryPreheatDiagnostics.line(helperDiag.batteryPreheat))
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
                // last_pair = what the next start-from-saved opens: the session pair, else the menu one.
                val menuPair = splitPreferences.getMenuPair()
                val sessionPair = splitPreferences.getSessionPair()
                appendLine("menu_pair: " + if (menuPair == null) "(none)" else "${menuPair.narrowPkg}+${menuPair.widePkg}")
                appendLine("session_pair: " + if (sessionPair == null) "(none)" else
                    "${sessionPair.narrowPkg}+${sessionPair.widePkg}")
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
            WindowDiagnostics.format(helperDiag.windows).forEach { appendLine(it) }

            appendLine("--- seats ---")
            SeatsDiagnostics.format(helperDiag.seats).forEach { appendLine(it) }

            appendLine("--- seat command journal ---")
            SeatsDiagnostics.journalLines(appContext).forEach { appendLine(it) }

            appendLine("--- steering heat ---")
            SteeringHeatDiagnostics.format(helperDiag.steeringHeat).forEach { appendLine(it) }

            // Rear seat heat (#327): the gate the editor's tiles follow, read only.
            appendLine("--- rear seat heat ---")
            try {
                val catalog = fidCatalogManager.catalog
                val items = RearSeatHeatDiagnostics.batchItems(RearSeatHeatDiagnostics.addresses(catalog))
                val readings = if (items.isEmpty()) emptyList() else helperClient.readBatch(items)
                RearSeatHeatDiagnostics.format(catalog, readings).forEach { appendLine(it) }
            } catch (e: Exception) { appendLine("error: ${e.message}") }

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
                telegramReporter.diagnosticsLines().forEach { appendLine(it) }
            } catch (e: Exception) { appendLine("error: ${e.message}") }

            appendLine("--- vehicle writes ---")
            try {
                RecordingDumpFormat.vehicleWriteLines(vehicleWriteLogDao.getLatest(VEHICLE_WRITES_IN_DUMP))
                    .forEach { appendLine(it) }
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

            // The last manual block-fault scan, raw per address, and the daemon's scan state. Never scans.
            appendLine("--- dtc scan ---")
            try {
                val prefs = appContext.getSharedPreferences(
                    com.bydmate.app.data.dtc.DtcScanRepository.PREFS_NAME, Context.MODE_PRIVATE,
                )
                com.bydmate.app.data.dtc.DtcScanRepository.dumpLines(prefs, helperClient.dtcScanStatus())
                    .forEach { appendLine(it) }
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

            // What the app did before this recording: logcat was cleared when it started.
            appendLine("--- trace ---")
            try {
                val events = Trace.lines()
                if (events.isEmpty()) appendLine("(none)") else events.forEach { appendLine(it) }
            } catch (e: Exception) { appendLine("error: ${e.message}") }

            appendLine("===============================")
            appendLine()
        }

        // File-write failures must surface so the user sees a meaningful
        // error instead of a "запись начата" status next to an empty file.
        FileWriter(file, false).use { it.write(header) }
    }
}

internal fun enabledSettingName(state: Int): String = when (state) {
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "DEFAULT (enabled)"
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "ENABLED"
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "DISABLED"
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "DISABLED_USER"
    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> "DISABLED_UNTIL_USED"
    else -> "UNKNOWN($state)"
}
