package com.bydmate.app.data.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.media.MediaSessionListenerService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class DispatchResult(val success: Boolean, val reason: String? = null)

@Singleton
class ActionDispatcher @Inject constructor(
    private val vehicleApi: VehicleApi,
    private val helper: HelperClient,
    @ApplicationContext private val context: Context,
    private val voiceActions: dagger.Lazy<com.bydmate.app.voice.VoiceAutomationActions>,
    private val clusterVoiceControl: ClusterVoiceControl,
    private val audioCapture: com.bydmate.app.voice.AudioCapture,
) {
    companion object {
        private const val TAG = "ActionDispatcher"
        private const val CHANNEL_SILENT_ID = "bydmate_automation_silent"
        private const val CHANNEL_SOUND_ID = "bydmate_automation_sound"
        private const val USER_NOTIF_BASE_ID = 10000
        private const val MINIMIZE_DELAY_MS = 3000L
        private const val AUTO_DIAL_DELAY_MS = 300L
        private const val BT_CALL_PACKAGE = "com.byd.bluetoothcall"
        private const val BT_CALL_ACTION_DIAL_HANGUP = "com.byd.btcall.action.DIAL_HANGUP"
        private const val BT_CALL_KEYCODE_DIAL = 313
        private const val YANDEX_MUSIC_PACKAGE = "ru.yandex.music"
        private val YOUTUBE_PACKAGES = listOf("anddea.youtube", "com.google.android.youtube")
        private const val NAVI_PACKAGE = "ru.yandex.yandexnavi"
        // Hard cap on user-set delay action; protects against typos like "60000000".
        private const val MAX_DELAY_MS = 30_000L
        private val BLOCKED_PATTERNS = listOf("发送CAN", "执行SHELL", "下电")

        /**
         * True if [command] would OPEN a side window (车窗/主驾/副驾/后左/后右) --
         * gated above 120 km/h. Sunroof (天窗) and sunshade (遮阳帘) are NOT
         * included: sunroof has its own lower threshold, sunshade is interior and
         * ungated. Pure predicate -- unit-testable without Android deps.
         *
         * "打开N" sets the aperture to N%; N==0 is a CLOSE (safe at speed) and must
         * not be treated as an open. A bare "打开" with no percentage is treated
         * conservatively as an open. 全开/半开/通风 are always opens.
         *
         * Seat commands (座椅, e.g. 主驾座椅通风N档) share the 主驾/副驾 subject and the
         * 通风 keyword but are NOT apertures -- they must never be window-gated.
         */
        internal fun isWindowOpenCommand(command: String): Boolean {
            if (command.contains("座椅")) return false
            val subjects = listOf("车窗", "主驾", "副驾", "后左", "后右")
            if (subjects.none { command.contains(it) }) return false
            if (command.contains("关")) return false
            val opensViaPosition = POSITION_OPEN.find(command)
                ?.let { it.groupValues[1].toInt() > 0 }
                ?: command.contains("打开")        // bare "打开" (no %) -- treat as open
            val opensViaWord = listOf("全开", "半开", "通风").any { command.contains(it) }
            return opensViaPosition || opensViaWord
        }

        /**
         * True if [command] would OPEN the sunroof (天窗) -- gated above 80 km/h.
         * Close and stop commands are not gated. Pure predicate -- unit-testable
         * without Android deps.
         */
        internal fun isSunroofOpenCommand(command: String): Boolean {
            if (!command.contains("天窗")) return false
            if (command.contains("关")) return false
            val opensViaPosition = POSITION_OPEN.find(command)
                ?.let { it.groupValues[1].toInt() > 0 }
                ?: command.contains("打开")        // bare "打开" (no %) -- treat as open
            val opensViaWord = listOf("全开", "半开", "通风").any { command.contains(it) }
            return opensViaPosition || opensViaWord
        }

        /**
         * True if [command] would OPEN the sunshade (遮阳帘). NEVER speed-gated
         * (interior shade) -- used only by the voice early-fire guard, which must
         * hold ALL aperture opens for the final because a bare-noun partial can
         * still be qualified. Pure predicate -- unit-testable without Android deps.
         */
        internal fun isSunshadeOpenCommand(command: String): Boolean {
            if (!command.contains("遮阳帘")) return false
            if (command.contains("关")) return false
            val opensViaPosition = POSITION_OPEN.find(command)
                ?.let { it.groupValues[1].toInt() > 0 }
                ?: command.contains("打开")        // bare "打开" (no %) -- treat as open
            val opensViaWord = listOf("全开", "半开", "通风").any { command.contains(it) }
            return opensViaPosition || opensViaWord
        }

        /**
         * Returns a block reason string if [command] is an aperture-open that is
         * forbidden at [speed], or null if the command is allowed. Sunshade (遮阳帘)
         * is interior and always returns null. Pure function -- unit-testable.
         *
         *  - Sunroof (天窗): blocked when speed > 80 or speed is null.
         *  - Windows (车窗/主驾/副驾/后左/后右): blocked when speed > 120 or speed is null.
         */
        internal fun speedGateBlockReason(command: String, speed: Int?): String? {
            if (isSunroofOpenCommand(command)) {
                val s = speed ?: return "Скорость неизвестна"
                if (s > 80) return "Открытие люка заблокировано на скорости ${s} км/ч (>80)"
            }
            if (isWindowOpenCommand(command)) {
                val s = speed ?: return "Скорость неизвестна"
                if (s > 120) return "Открытие окон заблокировано на скорости ${s} км/ч (>120)"
            }
            return null
        }

        /**
         * True if [command] unlocks the doors. "车门解锁" is the single canonical
         * unlock string across all catalogs (agent, voice, automation, Alice).
         */
        internal fun isDoorUnlockCommand(command: String): Boolean =
            command.contains("车门解锁")

        /**
         * Returns a block reason if [command] unlocks the doors while moving
         * faster than 30 km/h, or when speed is unknown (fail-closed, same
         * policy as the frunk gate). Locking is never gated. Pure function.
         */
        internal fun unlockGateBlockReason(command: String, speed: Int?): String? {
            if (!isDoorUnlockCommand(command)) return null
            val s = speed ?: return "Скорость неизвестна, двери не отпираю"
            if (s > 30) return "Отпирание дверей заблокировано на скорости ${s} км/ч (>30)"
            return null
        }

        /**
         * True if [command] would OPEN the front trunk — a powered external panel
         * gated to standstill (speed 0). Close is not gated. Pure predicate, kept in
         * the companion so it is unit-testable without Android deps.
         */
        internal fun isFrontTrunkOpenCommand(command: String): Boolean =
            command.contains("前备箱") && command.contains("打开") && !command.contains("关")

        /**
         * True if [command] would OPEN the rear trunk / tailgate — "开后备箱".
         * Distinct from the front trunk (前备箱, isFrontTrunkOpenCommand). The
         * open string itself carries 开 (open), so a plain contains-check never
         * matches the close command 关后备箱. Pure predicate.
         */
        internal fun isRearTrunkOpenCommand(command: String): Boolean =
            command.contains("开后备箱")

        /**
         * П7 origin-based defense: true if this agent-initiated [action] is in
         * the dangerous tier and must be confirmed on-screen before it fires.
         * Dangerous = door unlock, rear-trunk open, disabling sentry, or placing
         * a call. NOT windows/climate/sunroof/door-lock/front-trunk (low harm or
         * already speed-gated). Pure function — unit-testable without Android.
         */
        internal fun isDangerousAction(action: ActionDef): Boolean = when (action.kind) {
            "param" -> isDoorUnlockCommand(action.command) || isRearTrunkOpenCommand(action.command)
            "sentry" -> action.payload == "0"
            "call" -> true
            else -> false
        }

        private val POSITION_OPEN = Regex("打开(\\d+)")

        /**
         * Full safety gate for a raw vehicle command: blocked patterns, frunk
         * parked-only, door unlock above 30 km/h, window/sunroof speed limits.
         * Frunk and unlock fail closed on missing telemetry; window/sunroof
         * checks are skipped when [data] is null (existing semantics -- callers
         * that need fail-closed window behavior check the snapshot themselves).
         * Pure function -- unit-testable and reusable by manual dispatch paths.
         */
        internal fun safetyBlockReason(command: String, data: DiParsData?): String? {
            if (BLOCKED_PATTERNS.any { command.contains(it) }) return "Запрещённая команда"
            // Frunk is a powered external panel — fail SAFE. Checked BEFORE the data==null
            // guard so missing telemetry (or unknown speed) blocks the open rather than
            // allowing it. Unlike windows, this aperture must never open above standstill.
            if (isFrontTrunkOpenCommand(command)) {
                val speed = data?.speed ?: return "Скорость неизвестна, передний багажник не открыть"
                if (speed > 0) return "Передний багажник открывается только на стоянке (скорость $speed км/ч)"
            }
            // Door unlock is a safety gate like the frunk: checked BEFORE the
            // data==null guard so unknown speed blocks the unlock.
            unlockGateBlockReason(command, data?.speed)?.let { return it }
            if (data == null) return null
            return speedGateBlockReason(command, data.speed)
        }

        /** Clamp a requested media volume level to the device's valid [0, max] range. Pure — unit-testable. */
        internal fun clampVolume(level: Int, max: Int): Int = level.coerceIn(0, max.coerceAtLeast(0))

        /** Parse a media_volume payload into a concrete operation. Pure, unit-testable.
         *  Plain int -> set+clamp (back-compat with automation actions); "+k"/"-k" ->
         *  step from current+clamp; "mute"/"unmute" -> AudioManager mute. */
        internal fun resolveVolumeOp(payload: String, current: Int, max: Int): VolumeOp {
            if (payload == "mute") return VolumeOp.Mute
            if (payload == "unmute") return VolumeOp.Unmute
            val signed = payload.startsWith("+") || payload.startsWith("-")
            val n = payload.toIntOrNull() ?: return VolumeOp.Invalid
            val target = if (signed) current + n else n
            return VolumeOp.SetTo(clampVolume(target, max))
        }
    }

    sealed interface VolumeOp {
        data class SetTo(val level: Int) : VolumeOp
        data object Mute : VolumeOp
        data object Unmute : VolumeOp
        data object Invalid : VolumeOp
    }

    private val notifCounter = AtomicInteger(USER_NOTIF_BASE_ID)

    // Test seam: real impl asks MediaSessionManager for active sessions via our listener component.
    internal var activeMediaControllers: () -> List<MediaController> = {
        runCatching {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.getActiveSessions(ComponentName(context, MediaSessionListenerService::class.java))
        }.getOrDefault(emptyList())
    }

    init {
        createUserChannels()
    }

    suspend fun dispatch(action: ActionDef, data: DiParsData?): DispatchResult = try {
        when (action.kind) {
            "param" -> dispatchParam(action, data)
            "notification", "notification_silent", "notification_sound" -> showNotification(action)
            "app_launch" -> launchApp(action)
            "call" -> dial(action)
            "navigate" -> navigate(action)
            "url" -> openUrl(action)
            "yandex_music" -> launchYandexMusic(action)
            "youtube" -> launchYoutube(action)
            "go_home" -> goHome()
            "delay" -> dispatchDelay(action)
            "media_volume" -> setMediaVolume(action)
            "sentry" -> dispatchSentry(action)
            "cluster_projection" -> dispatchClusterProjection(action)
            "speak" -> dispatchSpeak(action)
            "agent_query" -> dispatchAgentQuery(action)
            else -> DispatchResult(false, "Unknown action kind: ${action.kind}")
        }
    } catch (e: Exception) {
        // CancellationException must propagate so the voice routing job can be
        // cancelled by the orb hard-stop without swallowing the signal as a failure.
        if (e is CancellationException) throw e
        Log.e(TAG, "dispatch failed for kind=${action.kind}: ${e.message}")
        DispatchResult(false, e.message ?: "Unknown error")
    }

    // --- sentry mode (Settings.Global via helper daemon) ---

    private suspend fun dispatchSentry(action: ActionDef): DispatchResult {
        val value = when (action.payload) {
            "1" -> 1
            "0" -> 0
            else -> return DispatchResult(false, "Некорректное состояние охранного режима")
        }
        val ok = helper.putGlobalSetting("sentrymode_enabled_switch", value)
        return if (ok) DispatchResult(true)
        else DispatchResult(false, "Не удалось переключить охранный режим")
    }

    // --- cluster projection (steering-wheel star key path, via ClusterVoiceControl) ---

    /** ClusterVoiceControl.apply() is fire-and-forget (async setMode under the manager's mutex,
     *  like the star key) and never throws, so there is no synchronous success/failure to report
     *  here beyond payload validation -- this stays fail-soft the same way dispatchSentry does. */
    private fun dispatchClusterProjection(action: ActionDef): DispatchResult {
        val on = when (action.payload) {
            "1" -> true
            "0" -> false
            else -> return DispatchResult(false, "Некорректное состояние проекции на приборку")
        }
        clusterVoiceControl.apply(on)
        return DispatchResult(true)
    }

    /** "speak": say the payload text verbatim via the voice coordinator (orb + duck + TTS). */
    private suspend fun dispatchSpeak(action: ActionDef): DispatchResult {
        val text = parsePayload(action.payload)?.optString("text")?.trim().orEmpty()
        if (text.isEmpty()) return DispatchResult(false, "не задан текст")
        return voiceActions.get().speak(text)
    }

    /** "agent_query": run the payload prompt through an isolated agent turn, speak the answer. */
    private suspend fun dispatchAgentQuery(action: ActionDef): DispatchResult {
        val prompt = parsePayload(action.payload)?.optString("prompt")?.trim().orEmpty()
        if (prompt.isEmpty()) return DispatchResult(false, "не задан запрос")
        return voiceActions.get().agentQuery(prompt)
    }

    private suspend fun dispatchDelay(action: ActionDef): DispatchResult {
        val ms = action.payload?.toLongOrNull()
            ?: return DispatchResult(false, "Длительность паузы не задана")
        if (ms < 0 || ms > MAX_DELAY_MS) {
            return DispatchResult(false, "Длительность паузы вне диапазона (0..${MAX_DELAY_MS} мс)")
        }
        kotlinx.coroutines.delay(ms)
        return DispatchResult(true)
    }

    // --- media volume (standard AudioManager, no autoservice) ---

    private fun setMediaVolume(action: ActionDef): DispatchResult {
        val payload = action.payload ?: return DispatchResult(false, "Уровень громкости не задан")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return DispatchResult(false, "AudioManager недоступен")
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // During a voice-session duck the stream sits at the near-zero duck level: "+N"/"-N"
        // must step from the volume the user actually perceives (the pending restore value),
        // and the result must survive the session's teardown restore (volume revert bug).
        val current = audioCapture.pendingRestoreVolume()
            ?: am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return when (val op = resolveVolumeOp(payload, current, max)) {
            is VolumeOp.SetTo -> {
                // Set + restore-target registration must be one atomic step against the
                // session teardown - AudioCapture.applyExplicitVolume holds the duck lock.
                audioCapture.applyExplicitVolume(op.level)
                DispatchResult(true)
            }
            VolumeOp.Mute -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                DispatchResult(true)
            }
            VolumeOp.Unmute -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                DispatchResult(true)
            }
            VolumeOp.Invalid -> DispatchResult(false, "Некорректный уровень громкости: $payload")
        }
    }

    // --- param (native autoservice via VehicleApi) ---

    private suspend fun dispatchParam(action: ActionDef, data: DiParsData?): DispatchResult {
        val blockReason = getBlockReason(action.command, data)
        if (blockReason != null) {
            Log.w(TAG, "Blocked '${action.command}': $blockReason")
            return DispatchResult(false, blockReason)
        }
        val result = vehicleApi.dispatch(action.command)
        val success = result.isSuccess
        val reason = if (!success) {
            result.exceptionOrNull()?.message ?: "dispatch failed"
        } else null
        return DispatchResult(success, reason)
    }

    // Delegate to the companion pure function so all callers (dispatch + manual test button)
    // share identical gate logic.
    private fun getBlockReason(command: String, data: DiParsData?): String? =
        safetyBlockReason(command, data)

    // --- notifications (user-visible) ---

    private suspend fun showNotification(action: ActionDef): DispatchResult {
        val payload = parsePayload(action.payload)
        val title = payload?.optString("title")?.takeIf(String::isNotBlank) ?: action.displayName
        val text = payload?.optString("text") ?: ""

        if (com.bydmate.app.ui.overlay.OverlayNotificationManager.canShow(context)) {
            val shown = com.bydmate.app.ui.overlay.OverlayNotificationManager.show(context, title, text)
            if (shown) return DispatchResult(true)
        }

        // Fallback: status-bar notification on the silent channel — the audible chime is a
        // per-rule setting played once at rule fire by AutomationEngine, not per notification.
        val notif = NotificationCompat.Builder(context, CHANNEL_SILENT_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val id = notifCounter.incrementAndGet()
        nm().notify(id, notif)
        return DispatchResult(true)
    }

    private fun createUserChannels() {
        val silent = NotificationChannel(
            CHANNEL_SILENT_ID,
            "Automation Silent",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
            description = "Silent automation notifications"
        }
        val sound = NotificationChannel(
            CHANNEL_SOUND_ID,
            "Automation Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Audible automation notifications"
        }
        val manager = nm()
        manager.createNotificationChannel(silent)
        manager.createNotificationChannel(sound)
    }

    // --- external activities ---

    private suspend fun launchApp(action: ActionDef): DispatchResult {
        val payload = parsePayload(action.payload)
        val pkg = payload?.optString("packageName")?.takeIf(String::isNotBlank)
            ?: return DispatchResult(false, "packageName не задан")
        if (context.packageManager.getLaunchIntentForPackage(pkg) == null) {
            return DispatchResult(false, "Приложение не установлено: $pkg")
        }
        // Authoritative launch via the shell-uid daemon (am start): a startActivity from
        // this @ApplicationContext can lose the foreground race when BYDMate is on top
        // (e.g. a voice session started from the Settings screen), so the launched app
        // would snap back behind us. Fall back to startActivity only when the daemon is
        // unreachable.
        if (helper.launchApp(pkg)) {
            maybeMinimize(payload)
            return DispatchResult(true)
        }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result = tryStartActivity(intent, "app_launch:$pkg")
        if (result.success) maybeMinimize(payload)
        return result
    }

    private suspend fun dial(action: ActionDef): DispatchResult {
        val payload = parsePayload(action.payload)
        val phone = payload?.optString("phone")?.takeIf(String::isNotBlank)
            ?: return DispatchResult(false, "phone не задан")
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result = tryStartActivity(intent, "dial:$phone")
        if (result.success && payload.optBoolean("autoDial", false)) {
            kotlinx.coroutines.delay(AUTO_DIAL_DELAY_MS)
            val press = Intent(BT_CALL_ACTION_DIAL_HANGUP).apply {
                setPackage(BT_CALL_PACKAGE)
                putExtra("keycode", BT_CALL_KEYCODE_DIAL)
            }
            try {
                context.sendBroadcast(press)
            } catch (e: Exception) {
                Log.w(TAG, "autoDial broadcast failed: ${e.message}")
            }
        }
        return result
    }

    private fun navigate(action: ActionDef): DispatchResult {
        val payload = parsePayload(action.payload) ?: return DispatchResult(false, "payload не задан")
        // Navigator's own saved Home/Work: exported shortcut actions on its MapActivity
        // resolve the address internally, so no coordinates are needed. Undocumented
        // (launcher-shortcut contract); tryStartActivity degrades to a clear error if
        // a Navigator update drops them.
        val shortcut = payload.optString("shortcut").takeIf(String::isNotBlank)
        if (shortcut != null) {
            val intentAction = when (shortcut) {
                "home" -> "ru.yandex.yandexmaps.action.ROUTE_TO_HOME_SHORTCUT"
                "work" -> "ru.yandex.yandexmaps.action.ROUTE_TO_WORK_SHORTCUT"
                else -> return DispatchResult(false, "неизвестный shortcut: $shortcut")
            }
            val intent = Intent(intentAction)
                .setPackage(NAVI_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return tryStartActivity(intent, "navigate_shortcut:$shortcut")
        }
        // Free-text destination: open Navigator's map search (route needs coordinates,
        // which the agent does not have for arbitrary addresses).
        val query = payload.optString("query").takeIf(String::isNotBlank)
        if (query != null) {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("yandexnavi://map_search?text=${Uri.encode(query)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return tryStartActivity(intent, "navigate_search:$query")
        }
        val lat = payload.optDouble("lat", Double.NaN)
        val lon = payload.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return DispatchResult(false, "lat/lon не заданы")
        // Show-only mode: drop a pin instead of building a route ("где находится X").
        if (payload.optBoolean("show", false)) {
            val desc = payload.optString("label").takeIf(String::isNotBlank)
            val showUri = buildString {
                append("yandexnavi://show_point_on_map?lat=$lat&lon=$lon&zoom=14")
                if (desc != null) append("&desc=${Uri.encode(desc)}")
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(showUri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return tryStartActivity(intent, "navigate_show:$lat,$lon")
        }
        val uri = "yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lon"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStartActivity(intent, "navigate:$lat,$lon")
    }

    private suspend fun openUrl(action: ActionDef): DispatchResult {
        val payload = parsePayload(action.payload)
        val url = payload?.optString("url")?.takeIf(String::isNotBlank)
            ?: return DispatchResult(false, "url не задан")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result = tryStartActivity(intent, "url:$url")
        if (result.success) maybeMinimize(payload)
        return result
    }

    private suspend fun launchYandexMusic(action: ActionDef): DispatchResult {
        val payload = parsePayload(action.payload)
        val mode = payload?.optString("mode")?.takeIf(String::isNotBlank) ?: "mybeat"
        if (mode == "play") {
            val playPayload = payload ?: return DispatchResult(false, "query не задан")
            val query = playPayload.optString("query").takeIf(String::isNotBlank)
                ?: return DispatchResult(false, "query не задан")
            // Real playback path: playFromSearch on Yandex Music's live MediaSession actually
            // starts the top hit; the MEDIA_PLAY_FROM_SEARCH intent below only opens the search
            // screen (field defect APK 337). Needs notification-listener access (self-granted).
            val controller = runCatching { activeMediaControllers() }
                .getOrDefault(emptyList())
                .firstOrNull { it.packageName == YANDEX_MUSIC_PACKAGE }
            if (controller != null) {
                val ok = runCatching {
                    controller.transportControls.playFromSearch(query, Bundle())
                }.isSuccess
                if (ok) { maybeMinimize(playPayload); return DispatchResult(true) }
            }
            // No live session / no listener access: fall through to the search intent so the
            // command still does something visible.
            return launchYandexMusic(action.copy(
                payload = playPayload.put("mode", "search").toString()))
        }
        if (mode == "search") {
            val query = payload?.optString("query")?.takeIf(String::isNotBlank)
                ?: return DispatchResult(false, "query не задан")
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage(YANDEX_MUSIC_PACKAGE)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra(SearchManager.QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val result = tryStartActivity(intent, "yandex_music_search:$query")
            if (result.success) maybeMinimize(payload)
            return result
        }
        val deeplink = when (mode) {
            "mybeat" -> "yandexmusic://radio/user/onyourwave?play=true"
            else -> return DispatchResult(false, "Неизвестный режим Я.Музыки: $mode")
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deeplink))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result = tryStartActivity(intent, "yandex_music:$mode")
        if (result.success) maybeMinimize(payload)
        return result
    }

    // --- youtube (search / play-from-search) ---

    private suspend fun launchYoutube(action: ActionDef): DispatchResult {
        val payload = parsePayload(action.payload)
        val query = payload?.optString("query")?.takeIf(String::isNotBlank)
            ?: return DispatchResult(false, "query не задан")
        val pkg = YOUTUBE_PACKAGES.firstOrNull { isPackageInstalled(it) }
            ?: return DispatchResult(false, "Приложение YouTube не установлено")
        val mode = payload.optString("mode").takeIf(String::isNotBlank) ?: "play"
        val intent = when (mode) {
            // Assistant-style voice search: stock YouTube auto-plays the top hit for this intent.
            // Whether the anddea (ReVanced) build keeps that behavior is validated on-car; the
            // search screen it opens otherwise is an acceptable degradation.
            "play" -> Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage(pkg)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra(SearchManager.QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            "search" -> Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            else -> return DispatchResult(false, "Неизвестный режим YouTube: $mode")
        }
        val result = tryStartActivity(intent, "youtube_$mode:$query")
        if (result.success) maybeMinimize(payload)
        return result
    }

    private fun isPackageInstalled(pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    private fun goHome(): DispatchResult {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStartActivity(home, "go_home")
    }

    private suspend fun maybeMinimize(payload: JSONObject?) {
        if (payload?.optBoolean("minimize", false) != true) return
        kotlinx.coroutines.delay(MINIMIZE_DELAY_MS)
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(home)
        } catch (e: Exception) {
            Log.w(TAG, "home failed: ${e.message}")
        }
    }

    private fun tryStartActivity(intent: Intent, label: String): DispatchResult = try {
        context.startActivity(intent)
        DispatchResult(true)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "$label: ${e.message}")
        DispatchResult(false, "Нет приложения для обработки: ${e.message}")
    } catch (e: SecurityException) {
        Log.w(TAG, "$label (security): ${e.message}")
        DispatchResult(false, "Нет разрешения: ${e.message}")
    }

    // --- helpers ---

    private fun parsePayload(payload: String?): JSONObject? {
        if (payload.isNullOrBlank()) return null
        return try { JSONObject(payload) } catch (e: Exception) { null }
    }

    private fun nm(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
