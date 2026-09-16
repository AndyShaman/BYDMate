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
import com.bydmate.app.R
import com.bydmate.app.cluster.ClusterMode
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.media.MediaSessionListenerService
import com.bydmate.app.service.TrackingService
import com.bydmate.app.split.SplitPair
import com.bydmate.app.split.SplitSessionManager
import com.bydmate.app.split.SplitSessionState
import com.bydmate.app.split.SplitSide
import com.bydmate.app.split.SplitStartResult
import com.bydmate.app.util.appLocalizedContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    private val splitSessionManager: SplitSessionManager,
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
        private const val MAX_DELAY_MS = 60_000L
        private val BLOCKED_PATTERNS = listOf("发送CAN", "执行SHELL", "下电")

        /**
         * Did the driver ask to START moving («поехали туда»), not just to build the route?
         * Show-only ("где находится X") drops a pin and has nothing to start, so it ignores
         * the flag. Pure -- unit-testable without Android.
         */
        internal fun autoGoRequested(payload: JSONObject): Boolean =
            payload.optBoolean("go", false) && !payload.optBoolean("show", false)

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
         * Returns a block reason if [command] is an aperture-open that is
         * forbidden at [speed], or null if the command is allowed. Sunshade (遮阳帘)
         * is interior and always returns null. Pure function -- unit-testable.
         *
         *  - Sunroof (天窗): blocked when speed > 80 or speed is null.
         *  - Windows (车窗/主驾/副驾/后左/后右): blocked when speed > 120 or speed is null.
         */
        internal fun speedGateBlockReason(command: String, speed: Int?): BlockReason? {
            if (isSunroofOpenCommand(command)) {
                val s = speed ?: return BlockReason.SpeedUnknown
                if (s > 80) return BlockReason.SunroofSpeed(s)
            }
            if (isWindowOpenCommand(command)) {
                val s = speed ?: return BlockReason.SpeedUnknown
                if (s > 120) return BlockReason.WindowsSpeed(s)
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
        internal fun unlockGateBlockReason(command: String, speed: Int?): BlockReason? {
            if (!isDoorUnlockCommand(command)) return null
            val s = speed ?: return BlockReason.UnlockSpeedUnknown
            if (s > 30) return BlockReason.UnlockSpeed(s)
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
        /** Projection failed because the cluster daemon is restarting: retriable, not broken. */
        internal const val DAEMON_RESTART_REASON = "служебный процесс перезапускается"

        internal fun isDangerousAction(action: ActionDef): Boolean = when (action.kind) {
            "param" -> isDoorUnlockCommand(action.command) || isRearTrunkOpenCommand(action.command)
            "sentry" -> action.payload == "0"
            "call" -> true
            // A toggle resolves to its command only at dispatch time, against the live
            // state. Locks and the rear trunk can resolve to an unlock / a trunk open,
            // so they are treated as dangerous whichever way they would flip.
            "toggle" -> action.payload == TOGGLE_LOCKS || action.payload == TOGGLE_TRUNK
            else -> false
        }

        // --- toggle targets ("toggle" action payload) ---

        internal const val TOGGLE_TRUNK = "trunk"
        internal const val TOGGLE_FRONT_TRUNK = "front_trunk"
        internal const val TOGGLE_SUNROOF = "sunroof"
        internal const val TOGGLE_LOCKS = "locks"
        internal const val TOGGLE_CLUSTER = "cluster"
        internal const val TOGGLE_HAZARD = "hazard"
        internal const val TOGGLE_CLIMATE = "climate"
        internal const val TOGGLE_SEAT_HEAT_DRIVER = "seat_heat_driver"
        internal const val TOGGLE_SEAT_HEAT_PASSENGER = "seat_heat_passenger"
        internal const val TOGGLE_SEAT_VENT_DRIVER = "seat_vent_driver"
        internal const val TOGGLE_SEAT_VENT_PASSENGER = "seat_vent_passenger"

        /** Targets a "toggle" action can flip, in picker order. */
        internal val TOGGLE_TARGETS = listOf(
            TOGGLE_TRUNK, TOGGLE_FRONT_TRUNK, TOGGLE_SUNROOF, TOGGLE_LOCKS, TOGGLE_CLUSTER,
            TOGGLE_HAZARD, TOGGLE_CLIMATE,
            TOGGLE_SEAT_HEAT_DRIVER, TOGGLE_SEAT_HEAT_PASSENGER,
            TOGGLE_SEAT_VENT_DRIVER, TOGGLE_SEAT_VENT_PASSENGER,
        )

        /** Localized name of a toggle target; null when the id is not a known target. */
        internal fun toggleTargetNameRes(target: String): Int? = when (target) {
            TOGGLE_TRUNK -> R.string.toggle_target_trunk
            TOGGLE_FRONT_TRUNK -> R.string.toggle_target_front_trunk
            TOGGLE_SUNROOF -> R.string.toggle_target_sunroof
            TOGGLE_LOCKS -> R.string.toggle_target_locks
            TOGGLE_CLUSTER -> R.string.toggle_target_cluster
            TOGGLE_HAZARD -> R.string.toggle_target_hazard
            TOGGLE_CLIMATE -> R.string.toggle_target_climate
            TOGGLE_SEAT_HEAT_DRIVER -> R.string.toggle_target_seat_heat_driver
            TOGGLE_SEAT_HEAT_PASSENGER -> R.string.toggle_target_seat_heat_passenger
            TOGGLE_SEAT_VENT_DRIVER -> R.string.toggle_target_seat_vent_driver
            TOGGLE_SEAT_VENT_PASSENGER -> R.string.toggle_target_seat_vent_passenger
            else -> null
        }

        /**
         * Outcome of resolving a "toggle" against the live state: either the concrete
         * command that flips it, or why it cannot be flipped right now.
         */
        internal sealed interface ToggleResolution {
            data class Command(val command: String) : ToggleResolution
            data object StateUnknown : ToggleResolution
            data object TrunkMoving : ToggleResolution
            data object UnknownTarget : ToggleResolution
        }

        /**
         * Pick the command that flips [target] from its current [state]. Pure — every
         * state semantic lives here so it is unit-testable without Android:
         *   trunk       2=closed, 1=open; anything else = tailgate in motion
         *   frontTrunk  2=closed, 1=open, 3=moving (measured on-car 2026-09-15)
         *   sunroof     aperture percent, 0=closed
         *   lockFL      1=unlocked, 2=locked
         *   turnSignal  6=hazard on (the mask holds while it blinks), anything else = off
         *   acStatus    0=off, 1=on
         *   seat steps  0=off, 1..5=level
         * The cluster target has no snapshot field and is resolved by the caller.
         * [lastSeatLevel] is the step a seat is turned back on with; it only matters for the
         * seat targets, where the snapshot says «off» but not «off from which step».
         */
        internal fun resolveToggleCommand(
            target: String,
            state: Int?,
            lastSeatLevel: Int = SeatLevelMemory.DEFAULT_LEVEL,
        ): ToggleResolution {
            if (target !in TOGGLE_TARGETS || target == TOGGLE_CLUSTER) {
                return ToggleResolution.UnknownTarget
            }
            val value = state ?: return ToggleResolution.StateUnknown
            SeatLevelMemory.SEAT_COMMAND_PREFIX[target]?.let { prefix ->
                return resolveSeatToggle(value, prefix, lastSeatLevel)
            }
            return when (target) {
                TOGGLE_TRUNK -> resolveHatchToggle(value, "开后备箱", "关后备箱")
                TOGGLE_FRONT_TRUNK -> resolveHatchToggle(value, "前备箱打开", "前备箱关闭")
                TOGGLE_SUNROOF -> ToggleResolution.Command(
                    if (value == 0) "天窗打开100" else "天窗打开0"
                )
                TOGGLE_HAZARD -> ToggleResolution.Command(
                    if (value == TURN_SIGNAL_HAZARD) "双闪关闭" else "双闪打开"
                )
                TOGGLE_CLIMATE -> resolveClimateToggle(value)
                else -> resolveLocksToggle(value)   // TOGGLE_LOCKS -- the only target left
            }
        }

        /** Hazard blinkers: the turn-signal mask reads 6 while they run. */
        private const val TURN_SIGNAL_HAZARD = 6

        /** Climate: 1=on, 0=off; a firmware answering anything else is not reporting a state. */
        private fun resolveClimateToggle(value: Int): ToggleResolution = when (value) {
            1 -> ToggleResolution.Command("关闭空调")
            0 -> ToggleResolution.Command("自动空调")
            else -> ToggleResolution.StateUnknown
        }

        /**
         * A seat running at any step goes off; a seat that is off comes back at [lastLevel] —
         * the step the driver last asked for through the app, or the middle one.
         */
        private fun resolveSeatToggle(value: Int, prefix: String, lastLevel: Int): ToggleResolution =
            when {
                value > 0 -> ToggleResolution.Command("${prefix}关闭")
                value == 0 -> ToggleResolution.Command("$prefix${lastLevel}档")
                else -> ToggleResolution.StateUnknown
            }

        /**
         * Hatch position (front and rear share the enum): 2=closed, 1=open; any other
         * step (3 = travelling on the frunk) means it is still moving. Measured on the
         * frunk on-car 2026-09-15 (2→3→1 opening, 1→3→2 closing); the rear tailgate is
         * manual on that car, so only its closed value (2) was seen — same family assumed.
         */
        private fun resolveHatchToggle(value: Int, open: String, close: String): ToggleResolution =
            when (value) {
                2 -> ToggleResolution.Command(open)
                1 -> ToggleResolution.Command(close)
                else -> ToggleResolution.TrunkMoving
            }

        /** Driver door lock: 1=unlocked, 2=locked; anything else is not a lock state. */
        private fun resolveLocksToggle(value: Int): ToggleResolution = when (value) {
            1 -> ToggleResolution.Command("车门上锁")
            2 -> ToggleResolution.Command("车门解锁")
            else -> ToggleResolution.StateUnknown
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
        internal fun safetyBlockReason(command: String, data: DiParsData?): BlockReason? {
            if (BLOCKED_PATTERNS.any { command.contains(it) }) return BlockReason.Forbidden
            // Frunk is a powered external panel — fail SAFE. Checked BEFORE the data==null
            // guard so missing telemetry (or unknown speed) blocks the open rather than
            // allowing it. Unlike windows, this aperture must never open above standstill.
            if (isFrontTrunkOpenCommand(command)) {
                val speed = data?.speed ?: return BlockReason.FrunkSpeedUnknown
                if (speed > 0) return BlockReason.FrunkMoving(speed)
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

    /**
     * Why a command was refused by a safety gate. The gate functions stay pure
     * (no Context) and return this; [toText] renders it in the app's language —
     * the gates run off an Activity, so the raw application context would follow
     * the head unit's system locale instead (#162).
     */
    sealed class BlockReason {
        data object SpeedUnknown : BlockReason()
        data class SunroofSpeed(val speed: Int) : BlockReason()
        data class WindowsSpeed(val speed: Int) : BlockReason()
        data object UnlockSpeedUnknown : BlockReason()
        data class UnlockSpeed(val speed: Int) : BlockReason()
        data object Forbidden : BlockReason()
        data object FrunkSpeedUnknown : BlockReason()
        data class FrunkMoving(val speed: Int) : BlockReason()

        fun toText(context: Context): String {
            val lc = context.appLocalizedContext()
            return when (this) {
                is SpeedUnknown -> lc.getString(R.string.gate_speed_unknown)
                is SunroofSpeed -> lc.getString(R.string.gate_sunroof_speed, speed)
                is WindowsSpeed -> lc.getString(R.string.gate_windows_speed, speed)
                is UnlockSpeedUnknown -> lc.getString(R.string.gate_unlock_speed_unknown)
                is UnlockSpeed -> lc.getString(R.string.gate_unlock_speed, speed)
                is Forbidden -> lc.getString(R.string.gate_forbidden_command)
                is FrunkSpeedUnknown -> lc.getString(R.string.gate_frunk_speed_unknown)
                is FrunkMoving -> lc.getString(R.string.gate_frunk_moving, speed)
            }
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
    /** Test seam -- the freshest poll a "toggle" resolves its state against. */
    internal var liveSnapshot: () -> DiParsData? = { TrackingService.lastData.value }

    /** Last seat step asked for per seat, so a «toggle» can bring the seat back to it. */
    internal var seatLevelMemory = SeatLevelMemory(context)

    /** Test seam -- how long to wait for the cluster projection to actually come up. */
    internal var clusterPollIntervalMs = 500L
    internal var clusterPollAttempts = 10

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
            "hotspot" -> dispatchHotspot(action)
            "cluster_projection" -> dispatchClusterProjection(action)
            "toggle" -> dispatchToggle(action, data)
            "speak" -> dispatchSpeak(action)
            "agent_query" -> dispatchAgentQuery(action)
            "split_screen" -> dispatchSplitScreen(action)
            "split_screen_close" -> dispatchSplitScreenClose()
            "split_screen_toggle" -> dispatchSplitScreenToggle()
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

    // --- hotspot (Wi-Fi tethering via helper daemon, shell uid holds TETHER_PRIVILEGED) ---

    private suspend fun dispatchHotspot(action: ActionDef): DispatchResult {
        val enable = when (action.payload) {
            "1" -> true
            "0" -> false
            else -> return DispatchResult(false, "Некорректное состояние точки доступа Wi-Fi")
        }
        val ok = helper.setHotspot(enable)
        return if (ok) DispatchResult(true)
        else DispatchResult(false, "Не удалось переключить точку доступа Wi-Fi")
    }

    // --- cluster projection (steering-wheel star key path, via ClusterVoiceControl) ---

    /**
     * ClusterVoiceControl.apply() is fire-and-forget (async setMode under the manager's mutex,
     * like the star key), so the only honest verdict comes from reading the mode back. We wait
     * for it up to [clusterPollAttempts] x [clusterPollIntervalMs] and report a failure when the
     * projection never reached the requested state.
     *
     * This applies to automation-origin dispatches too, by design: a rule whose "projection on"
     * step silently did nothing must show up as a failed step, exactly like a rejected vehicle
     * write. The gate semantics above (speed limits, CAN/SHELL blocking) are untouched -- this
     * only changes what a dispatched-but-ineffective projection reports.
     */
    private suspend fun dispatchClusterProjection(action: ActionDef): DispatchResult {
        val on = when (action.payload) {
            "1" -> true
            "0" -> false
            else -> return DispatchResult(false, "Некорректное состояние проекции на приборку")
        }
        val want = if (on) ClusterMode.FULLSCREEN else ClusterMode.OFF
        clusterVoiceControl.apply(on)
        repeat(clusterPollAttempts) {
            if (clusterVoiceControl.projectionMode() == want) return DispatchResult(true)
            delay(clusterPollIntervalMs)
        }
        if (clusterVoiceControl.projectionMode() == want) return DispatchResult(true)
        val reason = if (clusterVoiceControl.lastFailure() == "daemon") {
            DAEMON_RESTART_REASON
        } else if (on) {
            "проекция на приборку не включилась"
        } else {
            "проекция с приборки не убралась"
        }
        Log.w(TAG, "cluster projection did not reach $want: $reason")
        return DispatchResult(false, reason)
    }

    // --- toggle (flip a panel / the locks / the projection from its current state) ---

    /**
     * "toggle": read the target's current state, then dispatch the opposite command through
     * [dispatch] itself — so the resolved command passes the very same speed gates and
     * confirmation rules as a hand-picked action. A state the car does not report is never
     * guessed: the step fails with a reason the user can read.
     *
     * The state comes from the CURRENT poll, not from [data]: a rule hands every step the one
     * snapshot taken before the rule started, so "toggle → delay → toggle" would resolve the
     * second step against the state from before the first one and flip the panel the same way
     * twice. [data] stays as the fallback for callers that dispatch without a running poll.
     */
    private suspend fun dispatchToggle(action: ActionDef, data: DiParsData?): DispatchResult {
        val lc = context.appLocalizedContext()
        val target = action.payload.orEmpty()
        val targetName = toggleTargetNameRes(target)?.let { lc.getString(it) }
            ?: return DispatchResult(false, lc.getString(R.string.toggle_unknown_target, target))

        val polled = liveSnapshot()
        val live = polled ?: data
        val src = if (polled != null) "live" else "step"

        if (target == TOGGLE_CLUSTER) return dispatchClusterToggle(action, live, src)

        val state = toggleState(target, live)
        val lastLevel = seatLevelMemory.lastLevel(target)
        return when (val resolution = resolveToggleCommand(target, state, lastLevel)) {
            is ToggleResolution.Command -> {
                Log.i(TAG, "toggle $target: src=$src state=$state -> ${resolution.command}")
                dispatch(action.copy(command = resolution.command, kind = "param", payload = null), live)
            }
            ToggleResolution.TrunkMoving -> {
                Log.w(TAG, "toggle $target: src=$src state=$state -> refused (moving)")
                DispatchResult(false, lc.getString(R.string.toggle_trunk_moving))
            }
            ToggleResolution.StateUnknown, ToggleResolution.UnknownTarget -> {
                Log.w(TAG, "toggle $target: src=$src state=$state -> refused (unknown state)")
                DispatchResult(false, lc.getString(R.string.toggle_state_unknown, targetName))
            }
        }
    }

    /** Snapshot field each toggle target reads its current state from. */
    private fun toggleState(target: String, live: DiParsData?): Int? = when (target) {
        TOGGLE_TRUNK -> live?.trunk
        TOGGLE_FRONT_TRUNK -> live?.frontTrunk
        TOGGLE_SUNROOF -> live?.sunroof
        TOGGLE_LOCKS -> live?.lockFL
        TOGGLE_HAZARD -> live?.turnSignal
        TOGGLE_CLIMATE -> live?.acStatus
        TOGGLE_SEAT_HEAT_DRIVER -> live?.seatHeatDriver
        TOGGLE_SEAT_HEAT_PASSENGER -> live?.seatHeatPassenger
        TOGGLE_SEAT_VENT_DRIVER -> live?.seatVentDriver
        TOGGLE_SEAT_VENT_PASSENGER -> live?.seatVentPassenger
        else -> null
    }

    /**
     * Cluster target: the projection state is its own live reading (the manager's current mode),
     * so the snapshot only rides along for the gates of the resolved action.
     */
    private suspend fun dispatchClusterToggle(
        action: ActionDef,
        live: DiParsData?,
        src: String,
    ): DispatchResult {
        val mode = clusterVoiceControl.projectionMode()
        val payload = if (mode == ClusterMode.FULLSCREEN) "0" else "1"
        Log.i(TAG, "toggle $TOGGLE_CLUSTER: src=$src state=$mode -> cluster_projection $payload")
        return dispatch(
            action.copy(command = "cluster_projection", kind = "cluster_projection", payload = payload),
            live,
        )
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

    /**
     * "split_screen": launch two apps in freeform split layout via SplitSessionManager.
     * Payload: {"narrow":"<pkg>","wide":"<pkg>","side":"left"|"right"}.
     * No speed/safety gate — split is not a dangerous action.
     */
    private suspend fun dispatchSplitScreen(action: ActionDef): DispatchResult {
        val json = parsePayload(action.payload)
            ?: return DispatchResult(false, "payload не задан")
        val narrow = json.optString("narrow").takeIf(String::isNotBlank)
            ?: return DispatchResult(false, "narrow не задан")
        val wide = json.optString("wide").takeIf(String::isNotBlank)
            ?: return DispatchResult(false, "wide не задан")
        val side = when (json.optString("side")) {
            "left" -> SplitSide.LEFT
            "right" -> SplitSide.RIGHT
            else -> return DispatchResult(false, "неверная сторона")
        }
        return when (splitSessionManager.start(SplitPair(narrow, wide, side))) {
            SplitStartResult.OK -> DispatchResult(true)
            SplitStartResult.FREEFORM_UNAVAILABLE ->
                DispatchResult(false, freeformUnavailableHint())
            SplitStartResult.LAUNCH_FAILED ->
                DispatchResult(false, context.getString(R.string.split_launch_failed))
            SplitStartResult.DISABLED ->
                DispatchResult(false, context.getString(R.string.split_feature_disabled))
        }
    }

    /**
     * "split_screen_close": end the split session. No payload, no speed gate.
     * Idempotent — an absent session already IS the requested end state, and
     * exit() is a no-op there, so this reports success either way.
     */
    private suspend fun dispatchSplitScreenClose(): DispatchResult {
        splitSessionManager.exit()
        return DispatchResult(true)
    }

    /**
     * "split_screen_toggle": session running → exit, otherwise restore the last pair.
     * No payload, no speed gate. The first-pair picker is deliberately NOT opened from
     * automation (a rule fires without anyone waiting to answer a dialog), so a pair
     * that was never saved is a plain failure.
     */
    private suspend fun dispatchSplitScreenToggle(): DispatchResult {
        if (splitSessionManager.state.value is SplitSessionState.Active) {
            splitSessionManager.exit()
            return DispatchResult(true)
        }
        return when (splitSessionManager.startLastPair()) {
            null -> DispatchResult(false, "пара для разделения экрана не сохранена")
            SplitStartResult.OK -> DispatchResult(true)
            SplitStartResult.FREEFORM_UNAVAILABLE ->
                DispatchResult(false, freeformUnavailableHint())
            SplitStartResult.LAUNCH_FAILED ->
                DispatchResult(false, context.getString(R.string.split_launch_failed))
            SplitStartResult.DISABLED ->
                DispatchResult(false, context.getString(R.string.split_feature_disabled))
        }
    }

    /**
     * Hint for FREEFORM_UNAVAILABLE: on firmwares proven to ignore the freeform flag (#139)
     * a reboot never helps, so promising one would be a lie.
     */
    private fun freeformUnavailableHint(): String = context.getString(
        if (splitSessionManager.freeformUnsupported()) R.string.split_freeform_unsupported_hint
        else R.string.split_freeform_reboot_hint
    )

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
            return DispatchResult(false, blockReason.toText(context))
        }
        val result = vehicleApi.dispatch(action.command)
        val success = result.isSuccess
        // A seat step the driver asked for is the one a later «toggle» brings back.
        if (success) seatLevelMemory.remember(action.command)
        val reason = if (!success) {
            result.exceptionOrNull()?.message ?: "dispatch failed"
        } else null
        return DispatchResult(success, reason)
    }

    // Delegate to the companion pure function so all callers (dispatch + manual test button)
    // share identical gate logic.
    private fun getBlockReason(command: String, data: DiParsData?): BlockReason? =
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

    /**
     * Route dispatch. Two things happen around the intent itself (wave 2026-09-16):
     *
     * - with a split session standing, the firmware would answer the launch with startFullWindow
     *   and the Navigator would lose the route — so the split is ended, the route is built, and
     *   the pair is restarted. See [NavigateSplitFlow].
     * - `go` = the driver said «поехали», not «построй маршрут»: then «Поехали» on the route
     *   preview is pressed for him.
     */
    private suspend fun navigate(action: ActionDef): DispatchResult {
        val payload = parsePayload(action.payload) ?: return DispatchResult(false, "payload не задан")
        val shortcut = payload.optString("shortcut").takeIf(String::isNotBlank)
        // Only a Yandex ROUTE ends on a «Поехали» screen: show-only drops a pin, search opens a
        // result list, and 2GIS has no such node at all.
        val routeMode = !payload.optBoolean("show", false) &&
            payload.optString("query").isBlank()
        val autoGoSupported = routeMode &&
            (shortcut != null || resolveNavigator().first == RouteNavigatorUris.YANDEX)
        val go = autoGoRequested(payload)
        val flow = NavigateSplitFlow(object : NavigateSplitFlow.Env {
            override fun activeSplitPair(): SplitPair? =
                (splitSessionManager.state.value as? SplitSessionState.Active)?.pair

            override suspend fun exitSplit(): Boolean {
                splitSessionManager.exit()
                return splitSessionManager.state.value !is SplitSessionState.Active
            }

            override suspend fun restoreSplit(pair: SplitPair): String? =
                when (splitSessionManager.start(pair)) {
                    SplitStartResult.OK -> null
                    SplitStartResult.FREEFORM_UNAVAILABLE -> freeformUnavailableHint()
                    SplitStartResult.LAUNCH_FAILED -> context.getString(R.string.split_launch_failed)
                    SplitStartResult.DISABLED -> context.getString(R.string.split_feature_disabled)
                }

            override suspend fun sendIntent(): DispatchResult = sendNavigateIntent(payload, shortcut)

            override fun a11yConnected(): Boolean =
                com.bydmate.app.cluster.SteeringWheelKeyService.isConnected

            override fun goButtonVisible(): Boolean =
                withNavigatorRoot { com.bydmate.app.media.NaviGoButton.visible(it) }

            override fun clickGo(): Boolean =
                withNavigatorRoot { com.bydmate.app.media.NaviGoButton.click(it) }

            override suspend fun wait(ms: Long) = delay(ms)

            // The split session's own transitions are journalled by SplitSessionManager; this
            // is the navigate side of the story and lives in the log with the other dispatches.
            override fun log(line: String) {
                Log.i(TAG, line)
            }
        })
        return flow.run(go = go, autoGoSupported = autoGoSupported)
    }

    /**
     * Runs [block] on the Navigator's a11y window — the same read the HUD uses, including the
     * minimized and cluster-projected cases — and recycles the node afterwards.
     */
    private fun withNavigatorRoot(
        block: (android.view.accessibility.AccessibilityNodeInfo?) -> Boolean,
    ): Boolean {
        val root = com.bydmate.app.cluster.SteeringWheelKeyService.instance?.findNavigatorRoot()
            ?: return false
        return try {
            block(root)
        } finally {
            @Suppress("DEPRECATION") runCatching { root.recycle() }
        }
    }

    /** Which map app routes go to, plus the reason text when 2GIS was replaced by Yandex (#190). */
    private fun resolveNavigator(): Pair<String, String?> {
        val chosen = RouteNavigatorUris.normalize(
            context.getSharedPreferences(RouteNavigatorUris.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(RouteNavigatorUris.KEY_ROUTE_NAVIGATOR, null))
        val fellBack = chosen == RouteNavigatorUris.DGIS &&
            !isPackageInstalled(RouteNavigatorUris.DGIS_PACKAGE)
        if (fellBack) Log.i(TAG, "navigate: 2gis not installed, falling back to yandex")
        return if (fellBack) {
            RouteNavigatorUris.YANDEX to "2ГИС не установлен, открыт Яндекс Навигатор"
        } else {
            chosen to null
        }
    }

    private fun sendNavigateIntent(payload: JSONObject, shortcut: String?): DispatchResult {
        // Navigator's own saved Home/Work: exported shortcut actions on its MapActivity
        // resolve the address internally, so no coordinates are needed. Undocumented
        // (launcher-shortcut contract); tryStartActivity degrades to a clear error if
        // a Navigator update drops them.
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
        // #190: which map app the user picked for routes and map search. 2GIS that is not
        // installed falls back to Yandex for this action, and says so in the result reason.
        val (navigator, fallbackReason) = resolveNavigator()
        // Free-text destination: open the map search (route needs coordinates,
        // which the agent does not have for arbitrary addresses).
        val query = payload.optString("query").takeIf(String::isNotBlank)
        if (query != null) {
            return startNavigate(
                navigator, RouteNavigatorUris.MODE_SEARCH,
                RouteNavigatorUris.search(navigator, query),
                "navigate_search:$query", fallbackReason,
            )
        }
        val lat = payload.optDouble("lat", Double.NaN)
        val lon = payload.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return DispatchResult(false, "lat/lon не заданы")
        // Show-only mode: drop a pin instead of building a route ("где находится X").
        if (payload.optBoolean("show", false)) {
            val desc = payload.optString("label").takeIf(String::isNotBlank)
            return startNavigate(
                navigator, RouteNavigatorUris.MODE_SHOW,
                RouteNavigatorUris.showPoint(navigator, lat, lon, desc),
                "navigate_show:$lat,$lon", fallbackReason,
            )
        }
        return startNavigate(
            navigator, RouteNavigatorUris.MODE_ROUTE,
            RouteNavigatorUris.route(navigator, lat, lon),
            "navigate:$lat,$lon", fallbackReason,
        )
    }

    /**
     * Fires one navigation deep link and journals which app it went to (#190).
     *
     * The package is pinned for 2GIS only: the Yandex links have always resolved by scheme, and
     * pinning them now would break any head unit whose navigator ships under another package.
     */
    private fun startNavigate(
        navigator: String, mode: String, uri: String, label: String, fallbackReason: String?,
    ): DispatchResult {
        Log.i(TAG, "navigate: app=$navigator mode=$mode uri=$uri")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (navigator == RouteNavigatorUris.DGIS) {
            intent.setPackage(RouteNavigatorUris.DGIS_PACKAGE)
        }
        val result = tryStartActivity(intent, label)
        Log.i(TAG, "navigate: intent sent label=$label ok=${result.success}")
        return if (result.success && fallbackReason != null) result.copy(reason = fallbackReason)
        else result
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
