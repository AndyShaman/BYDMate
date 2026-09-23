package com.bydmate.app.data.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.R
import com.bydmate.app.util.AppStrings
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.service.TrackingService
import com.bydmate.app.ui.overlay.OverlayNotificationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutomationEngine @Inject @Suppress("LongParameterList") constructor( // Hilt-injected dependencies
    private val ruleDao: RuleDao,
    private val ruleLogDao: RuleLogDao,
    private val actionDispatcher: ActionDispatcher,
    private val placeRepository: PlaceRepository,
    private val networkAvailableMonitor: NetworkAvailableMonitor,
    @ApplicationContext private val context: Context,
    private val appStrings: AppStrings,
) {
    companion object {
        private const val TAG = "AutomationEngine"
        private const val CONFIRM_CHANNEL_ID = "bydmate_automation_confirm"
        private const val CONFIRM_TIMEOUT_MS = 30_000L
        private const val NOTIF_BASE_ID = 5000

        const val ACTION_CONFIRM = "com.bydmate.app.AUTOMATION_CONFIRM"
        const val ACTION_CANCEL = "com.bydmate.app.AUTOMATION_CANCEL"
        const val EXTRA_NOTIF_ID = "notif_id"

        // How long after the first evaluate() the service_start trigger stays
        // armed, giving cold-start params a few polls to warm up.
        const val SERVICE_START_WINDOW_MS = 30_000L

        // Kernel boot id of the DiLink session that already had its service_start window.
        // A process restart on the same boot must not reopen it (#177).
        internal const val PREFS_NAME = "automation"
        internal const val KEY_SERVICE_START_BOOT_ID = "service_start_boot_id"
        private const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"
        // Heartbeat of a live evaluate() loop with the screen on, on elapsedRealtime (monotonic,
        // immune to NTP/GPS clock corrections). The head unit does not reboot between drives:
        // on ACC_OFF the screen goes dark while Android keeps running, and the process may be
        // restarted with the screen still off. A screen-on tick after more than
        // SERVICE_START_WAKE_GAP_MS without a heartbeat means the car was off, so this is a
        // real car start. A process restart mid-drive leaves a heartbeat of at most
        // SERVICE_START_HEARTBEAT_MS plus the restart delay and is not.
        internal const val KEY_SERVICE_START_LAST_SEEN_ELAPSED = "service_start_last_seen_elapsed"
        // Legacy heartbeat key of 3.17.5: no longer read or written, the stale value stays in
        // prefs and is kept out of backups.
        internal const val KEY_SERVICE_START_LAST_SEEN_UPTIME = "service_start_last_seen_uptime"
        private const val SERVICE_START_WAKE_GAP_MS = 60_000L
        private const val SERVICE_START_HEARTBEAT_MS = 30_000L

        // Steering-wheel key trigger: manual only, like button_press. Fires from
        // the a11y key filter through onSteeringKey(), never from the poll.
        const val TRIGGER_KIND_STEERING_KEY = "steering_key"
        const val TRIGGER_PARAM_STEERING_KEY = "steering_key"

        /**
         * Creates the confirmation channel, or renames it: the same id updates the name and the
         * description to [strings], a context in the app language.
         */
        fun createConfirmChannel(context: Context, strings: Context) {
            val channel = NotificationChannel(
                CONFIRM_CHANNEL_ID,
                strings.getString(R.string.notif_channel_auto_confirm_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = strings.getString(R.string.notif_channel_auto_confirm_desc)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingConfirmations = ConcurrentHashMap<Int, PendingAction>()
    // Edge triggering: only fire when condition transitions from false→true.
    // triggersHash detects rule edits, so a saved edit reseeds instead of
    // comparing the new condition against state of the OLD one — which fired
    // rules right at save time (issue #51).
    private data class EvalState(val triggersHash: Int, val matched: Boolean)
    private val lastEvalResults = ConcurrentHashMap<Long, EvalState>()
    // Once-per-trip gate: ruleId -> tripStartedAt captured when rule last fired
    private val lastFiredTripByRule = ConcurrentHashMap<Long, Long>()
    // Per-rule consumption marker for the network_available trigger. Tracks the
    // most recent NetworkAvailableMonitor.lastAvailableAt that this rule has
    // already responded to, so a single VALIDATED edge fires the rule at most
    // once even if the rule's other AND-conditions delay the actual fire.
    private val lastSeenNetworkAvailableAt = ConcurrentHashMap<Long, Long>()
    // Service-start trigger: active during a short window after a new session
    // starts (see evaluate()), consumed per rule on fire. The previous
    // one-shot flag raced cold-start nulls: the very first tick often carries
    // incomplete data, so "запуск BYDMate AND темп > 22" silently missed the
    // whole trip when ExtTemp was still null on tick one (issue #51).
    // Deadline on elapsedMs(): 0 = never armed, -1 = closed for this session.
    @Volatile private var serviceStartWindowEnd = 0L
    private val serviceStartConsumed = ConcurrentHashMap.newKeySet<Long>()
    // Test seam: identifies the current DiLink boot. Empty string = unknown (boot_id not
    // readable); an unknown id on either side takes no part in the new-session decision.
    internal var bootIdProvider: () -> String = {
        runCatching { java.io.File(BOOT_ID_PATH).readText().trim() }.getOrNull().orEmpty()
    }
    // Test seam: monotonic clock of evaluate() that counts deep sleep, drives the heartbeat.
    internal var elapsedMs: () -> Long = { SystemClock.elapsedRealtime() }
    // Test seam: whether the head unit screen is on. Without a PowerManager the session
    // falls back to the heartbeat alone, as if the screen were always on.
    internal var interactiveProvider: () -> Boolean = {
        runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrElse {
            if (!powerManagerWarned) {
                powerManagerWarned = true
                Log.w(TAG, "service_start: PowerManager unavailable, assuming screen on")
            }
            true
        }
    }
    @Volatile private var powerManagerWarned = false
    // Test seam: wall clock of evaluate().
    internal var nowMs: () -> Long = { System.currentTimeMillis() }
    // Session state of this process: boot id and elapsed of the last screen-on tick, seeded
    // from the persisted heartbeat on the first evaluate() (may stay null: nothing stored).
    // sessionDecided flips on the first screen-on tick, which alone may keep the window closed
    // for a process restarted mid-drive.
    @Volatile private var seeded = false
    @Volatile private var sessionDecided = false
    @Volatile private var lastInteractive = false
    @Volatile private var sessionBootId: String? = null
    @Volatile private var savedBootId: String? = null
    @Volatile private var lastTickElapsed: Long? = null
    @Volatile private var lastHeartbeatWriteElapsed: Long? = null
    // Explicit wake edge: USER_PRESENT seen by the running service, or the start intent of a
    // service started by USER_PRESENT / BOOT_COMPLETED. The next screen-on tick opens a new
    // session whatever the gap; the gap rule stays as the fallback without USER_PRESENT.
    @Volatile private var wakeEdgePending = false
    // Elapsed time the last session opened. One car start delivers USER_PRESENT twice with the
    // process alive (the service receiver, then BootReceiver -> worker -> start intent 1-3 s
    // later), so an edge within SERVICE_START_WAKE_GAP_MS of an opened session is the same
    // start. A genuine second car start within that minute is suppressed as well: accepted.
    @Volatile private var lastSessionOpenedElapsed: Long? = null

    // Keycodes bound to a steering_key trigger of an ENABLED rule. Kept as a
    // live cache so the a11y key filter can answer "is this key mine?" on the
    // key event itself — a DB read there would run on the input path.
    private val _steeringKeyCodes = MutableStateFlow<Set<Int>>(emptySet())
    val steeringKeyCodes: StateFlow<Set<Int>> = _steeringKeyCodes

    private data class PendingAction(
        val rule: RuleEntity,
        val actions: List<ActionDef>,
        val snapshot: String,
        val createdAt: Long,
        val notifId: Int
    )

    init {
        createConfirmChannel()
        registerConfirmReceiver()
        observeSteeringKeyCodes()
    }

    // Rule edits reach the engine only through the DAO flow (the poll re-reads
    // getEnabled() every tick), so the same flow keeps the keycode cache fresh.
    private fun observeSteeringKeyCodes() {
        scope.launch {
            ruleDao.getAll().collect { rules ->
                _steeringKeyCodes.value = rules
                    .filter { it.enabled }
                    .flatMap { TriggerDef.listFromJson(it.triggers) }
                    .filter { it.kind == TRIGGER_KIND_STEERING_KEY }
                    // 0 = "not assigned yet" (a trigger just added from the menu); it must never
                    // claim a key, so the filter never sees it.
                    .mapNotNullTo(HashSet()) { it.value.toIntOrNull()?.takeIf { code -> code > 0 } }
            }
        }
    }

    // One line for the diagnostics dump (#177): the service_start session as this process
    // sees it. The heartbeat age is read from prefs, as a restarted process would see it.
    fun serviceStartDumpLine(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val end = serviceStartWindowEnd
        val elapsed = elapsedMs()
        val window = when {
            end <= 0L -> "not armed"
            elapsed <= end -> "armed until " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(nowMs() + end - elapsed))
            else -> "spent"
        }
        val heartbeat = prefs.getLong(KEY_SERVICE_START_LAST_SEEN_ELAPSED, -1L)
        val age = if (heartbeat >= 0L) "${(elapsedMs() - heartbeat) / 1000}s" else "-"
        val bootId = prefs.getString(KEY_SERVICE_START_BOOT_ID, null)?.takeIf { it.isNotEmpty() } ?: "-"
        val wakeEdge = if (wakeEdgePending) "pending" else "-"
        return "service_start: interactive=${interactiveProvider()} window=$window " +
            "consumed=${serviceStartConsumed.size} last_heartbeat_age=$age boot_id=$bootId wake_edge=$wakeEdge"
    }

    /**
     * The user unlocked the head unit after the screen came on (USER_PRESENT): the car was
     * switched on. Not called for SCREEN_ON, so a screen blink while driving does not re-fire.
     */
    fun onUserPresent() {
        Log.i(TAG, "service_start: user present")
        wakeEdgePending = true
    }

    // Called every 3s from TrackingService poll loop.
    // tripStartedAt is passed explicitly (not read from TrackingService.tripStartedAt)
    // because the latter mirrors TripTracker via an async collect, which lags by a
    // poll tick — would make the once-per-trip gate unreliable at trip boundaries.
    suspend fun evaluate(data: DiParsData, tripStartedAt: Long?) {
        cleanupExpired()

        val location = TrackingService.lastLocation.value
        val placesById = placeRepository.getAllSnapshot().associateBy { it.id }

        val rules = ruleDao.getEnabled()
        val now = nowMs()

        // A new session arms the service_start window. The session follows the head unit screen,
        // not kernel suspend: between drives the screen is dark while Android may stay awake
        // (#177). Decided on a screen-on tick only: a different kernel boot id (reboot), no
        // stored heartbeat, elapsed going backwards (reboot), or more than
        // SERVICE_START_WAKE_GAP_MS since the last screen-on tick (car was off: screen dark
        // and/or process dead). Checked on every evaluate(), so a process that lives through a
        // stop re-arms when the screen comes back. Ticks with the screen off write no heartbeat;
        // a process started with the screen off (restart after ACC_OFF) keeps the window closed
        // and decides on its first screen-on tick. A process restarted mid-drive (fresh
        // heartbeat) marks the window as already spent (-1), so service_start rules don't fire
        // again. Wall-clock time takes no part in the decision nor in the window deadline, so
        // clock corrections can't reopen, stretch or cut the window. Limitation: without a
        // readable boot id, a reboot whose new elapsed already exceeds the saved one and lies
        // within the wake gap is not recognised.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val elapsed = elapsedMs()
        val interactive = interactiveProvider()
        val firstTick = !seeded
        if (firstTick) {
            seeded = true
            sessionBootId = bootIdProvider()
            savedBootId = prefs.getString(KEY_SERVICE_START_BOOT_ID, null)
            lastTickElapsed = prefs.getLong(KEY_SERVICE_START_LAST_SEEN_ELAPSED, -1L).takeIf { it >= 0L }
        }
        if (!interactive) {
            if (firstTick) {
                Log.i(TAG, "service_start: screen off at start, waiting for wake-up")
                serviceStartWindowEnd = -1L
            } else {
                if (lastInteractive) Log.i(TAG, "service_start: screen off, heartbeat paused")
                // The car went off before a service_start rule matched: it must not fire later
                // with the screen dark.
                if (elapsed <= serviceStartWindowEnd) {
                    Log.i(TAG, "service_start: screen off, window closed")
                    serviceStartWindowEnd = -1L
                }
            }
        } else {
            val bootId = sessionBootId.orEmpty()
            val saved = savedBootId.orEmpty()
            val prevElapsed = lastTickElapsed
            val gap = prevElapsed?.let { "${(elapsed - it) / 1000}s" } ?: "-"
            var wakeEdge = wakeEdgePending
            if (wakeEdge) {
                wakeEdgePending = false
                val opened = lastSessionOpenedElapsed
                if (opened != null && elapsed >= opened && elapsed - opened <= SERVICE_START_WAKE_GAP_MS) {
                    Log.i(TAG, "service_start: wake edge within session, ignored gap=${(elapsed - opened) / 1000}s")
                    wakeEdge = false
                }
            }
            val reason = when {
                wakeEdge -> "user_present"
                bootId.isNotEmpty() && saved.isNotEmpty() && bootId != saved -> "boot"
                prevElapsed == null -> "first"
                elapsed < prevElapsed -> "elapsed_back"
                elapsed - prevElapsed > SERVICE_START_WAKE_GAP_MS -> "wake"
                else -> null
            }
            if (reason != null) {
                Log.i(TAG, "service_start: new session reason=$reason gap=$gap")
                serviceStartWindowEnd = elapsed + SERVICE_START_WINDOW_MS
                serviceStartConsumed.clear()
                lastSessionOpenedElapsed = elapsed
                // One synchronous write before any rule runs: a process killed right after the
                // fire must find this session's heartbeat, or its restart would re-arm.
                prefs.edit()
                    .putString(KEY_SERVICE_START_BOOT_ID, bootId)
                    .putLong(KEY_SERVICE_START_LAST_SEEN_ELAPSED, elapsed)
                    .commit()
                lastHeartbeatWriteElapsed = elapsed
                savedBootId = bootId
            } else if (!sessionDecided) {
                Log.i(TAG, "service_start: process restarted mid-session, not re-arming gap=$gap")
                serviceStartWindowEnd = -1L
            }
            sessionDecided = true
            lastTickElapsed = elapsed
            val lastWrite = lastHeartbeatWriteElapsed
            if (lastWrite == null || elapsed - lastWrite >= SERVICE_START_HEARTBEAT_MS) {
                lastHeartbeatWriteElapsed = elapsed
                prefs.edit().putLong(KEY_SERVICE_START_LAST_SEEN_ELAPSED, elapsed).apply()
            }
        }
        lastInteractive = interactive

        // Prune per-rule state for rules that have been deleted (or disabled
        // and removed from the active set). Without this, `lastEvalResults`,
        // `lastFiredTripByRule` and `lastSeenNetworkAvailableAt` would grow
        // monotonically over the app lifetime as the user creates and removes
        // rules. O(rules) per tick — negligible at typical N (≤ a few dozen).
        val activeIds = rules.mapTo(HashSet()) { it.id }
        lastEvalResults.keys.retainAll(activeIds)
        lastFiredTripByRule.keys.retainAll(activeIds)
        lastSeenNetworkAvailableAt.keys.retainAll(activeIds)
        serviceStartConsumed.retainAll(activeIds)

        for (rule in rules) {
            try {
                val triggers = TriggerDef.listFromJson(rule.triggers)
                if (triggers.isEmpty()) continue

                // network_available is an event trigger (like service_start) — fire when
                // VALIDATED internet edge happened that this rule has not consumed yet.
                // We CONSUME the edge eagerly (before cooldown / park-mode / matched
                // checks) so a stale edge can't fire later when the AND-condition or
                // cooldown finally permits. Edge semantics: network-validated is a
                // moment-in-time signal, not a persistent state.
                //
                // First observation of a rule (newly created, freshly enabled, or
                // first poll after a process restart) SEEDS the watermark with the
                // monitor's current value without firing. Without this guard the rule
                // would fire on any stale edge that happened before the rule was
                // active — including a probe-success captured at app startup before
                // the user toggled the rule on.
                val networkEdgeAt = networkAvailableMonitor.lastAvailableAt
                val seenAt = lastSeenNetworkAvailableAt[rule.id]
                val networkEdge: Boolean = when {
                    // Async-probe race guard. If we seed 0 here while a probe is
                    // about to publish a fresh edge, the next poll would treat
                    // that edge as "new" and fire — exactly the false-fire this
                    // logic is meant to prevent. Defer one tick.
                    seenAt == null && networkAvailableMonitor.probePending -> false
                    seenAt == null -> {
                        lastSeenNetworkAvailableAt[rule.id] = networkEdgeAt
                        false
                    }
                    else -> {
                        val isEdge = networkEdgeAt > 0L && networkEdgeAt > seenAt
                        if (isEdge) lastSeenNetworkAvailableAt[rule.id] = networkEdgeAt
                        isEdge
                    }
                }

                val serviceStartActive = interactive && elapsed <= serviceStartWindowEnd &&
                    rule.id !in serviceStartConsumed
                val perTrigger = evaluateEachTrigger(triggers, data, location, placesById, serviceStartActive, networkEdge)
                val matched = combineByLogic(perTrigger, rule.triggerLogic)

                // Event-style triggers (service_start, network_available) bypass edge
                // detection ONLY when the matched=true was driven by the event itself.
                // Without this discriminator, a rule like `network_available OR speed > 50`
                // would fire every poll where speed > 50 (after cooldown), because the
                // bypass branch would see `hasEventTrigger=true` regardless of whether
                // the event actually contributed to the match.
                //
                // Edge bookkeeping happens BEFORE the cooldown / park gates so that a
                // front occurring while the rule is gated is consumed, not frozen and
                // fired minutes later when the gate finally opens (issue #51: "сценарий
                // сработал сам по себе"). A front must happen while the rule is able
                // to act on it.
                val matchedViaEvent = matchedViaEventTrigger(triggers, perTrigger, rule.triggerLogic)
                val triggersHash = (rule.triggers + rule.triggerLogic).hashCode()
                val state = EvalState(triggersHash, matched)
                val shouldFire = if (matchedViaEvent) {
                    lastEvalResults[rule.id] = state
                    matched
                } else {
                    val previous = lastEvalResults.put(rule.id, state)
                    // null = first observation, hash mismatch = rule just edited —
                    // either way seed only, do not fire on a synthetic transition.
                    previous != null && previous.triggersHash == triggersHash &&
                        !previous.matched && matched
                }
                if (!shouldFire) continue

                // Cooldown (the front above is already consumed, never deferred)
                val lastFired = rule.lastTriggeredAt ?: 0L
                if (now - lastFired < rule.cooldownSeconds * 1000L) continue

                // Park-only rule
                if (rule.requirePark && data.gear != 1) continue

                // Once-per-trip gate: skip if already fired in the current trip
                if (rule.fireOncePerTrip && tripStartedAt != null &&
                    lastFiredTripByRule[rule.id] == tripStartedAt) continue

                val actions = ActionDef.listFromJson(rule.actions)
                if (actions.isEmpty()) continue

                // Mark triggered immediately to prevent re-fire
                ruleDao.updateLastTriggered(rule.id, now)
                if (serviceStartActive && triggers.any { it.kind == "service_start" }) {
                    serviceStartConsumed.add(rule.id)
                }
                if (rule.fireOncePerTrip && tripStartedAt != null) {
                    lastFiredTripByRule[rule.id] = tripStartedAt
                }

                val snapshot = buildSnapshot(triggers, data)

                if (rule.confirmBeforeExecute) {
                    val shown = ConfirmOverlayManager.show(
                        context = context,
                        ruleName = rule.name,
                        actionsSummary = actions.joinToString(", ") { it.displayName },
                        onConfirm = {
                            scope.launch {
                                executeAndLog(rule, actions, snapshot, TrackingService.lastData.value)
                            }
                        },
                        onCancel = {
                            scope.launch {
                                ruleLogDao.insert(
                                    RuleLogEntity(
                                        ruleId = rule.id,
                                        ruleName = rule.name,
                                        triggeredAt = now,
                                        triggersSnapshot = snapshot,
                                        actionsResult = """[{"result":"cancelled"}]""",
                                        success = false,
                                    )
                                )
                                Log.i(TAG, "Cancelled via overlay: '${rule.name}'")
                            }
                        },
                    )
                    if (!shown) {
                        // Fallback: user hasn't granted SYSTEM_ALERT_WINDOW.
                        showConfirmNotification(rule, actions, snapshot)
                    }
                } else {
                    scope.launch { executeAndLog(rule, actions, snapshot, data) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error evaluating rule '${rule.name}': ${e.message}")
            }
        }
    }

    /**
     * Direct, explicit entry point for a widget button press. Runs every ENABLED
     * rule whose button_press trigger value equals [buttonId] immediately —
     * independent of the 3-second poll. See [fireManualTrigger] for the semantics.
     *
     * Returns the number of rules matched by button number (0 ⇒ caller shows the
     * "no rules for button N" toast).
     */
    suspend fun onButtonPress(buttonId: Int): Int =
        fireManualTrigger("button_press", buttonId.toString())

    /**
     * Direct entry point for a steering-wheel key bound to a rule, called from the
     * a11y key filter. Same manual semantics as [onButtonPress].
     *
     * Returns the number of rules matched by keycode (0 ⇒ nothing was bound to it).
     */
    suspend fun onSteeringKey(keyCode: Int): Int =
        fireManualTrigger(TRIGGER_KIND_STEERING_KEY, keyCode.toString())

    /**
     * Shared body of the manual (event) trigger paths. Runs every ENABLED rule
     * carrying a [kind] trigger whose value equals [value]. The press is an
     * explicit user command, so:
     *  - co-triggers on the matched rule are NOT evaluated (the press alone is
     *    enough to run the rule),
     *  - cooldownSeconds and fireOncePerTrip are bypassed (a repeatable manual
     *    action),
     *  - requirePark and confirmBeforeExecute are honored, and every per-action
     *    ActionDispatcher safety gate stays in force (it reads the same snapshot).
     * Safety snapshot is the latest TrackingService.lastData.value.
     *
     * Returns the number of matched rules. A matched-but-park-gated rule still
     * counts, so the caller does not falsely report "no rules".
     */
    private suspend fun fireManualTrigger(kind: String, value: String): Int {
        val matching = ruleDao.getEnabled().filter { rule ->
            TriggerDef.listFromJson(rule.triggers).any {
                it.kind == kind && it.value == value
            }
        }
        if (matching.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val data = TrackingService.lastData.value
        for (rule in matching) {
            try {
                // Honor requirePark even on the manual path. Reuse the engine's
                // existing parked-check semantics: parked = gear == 1. When data
                // is null the park gate is closed (null?.gear != 1 → true).
                if (rule.requirePark && data?.gear != 1) continue

                val actions = ActionDef.listFromJson(rule.actions)
                if (actions.isEmpty()) continue

                val triggers = TriggerDef.listFromJson(rule.triggers)
                val snapshot = if (data != null) buildSnapshot(triggers, data) else "{}"

                // Mark triggered before execution (same ordering as evaluate path).
                ruleDao.updateLastTriggered(rule.id, now)

                if (rule.confirmBeforeExecute) {
                    val shown = ConfirmOverlayManager.show(
                        context = context,
                        ruleName = rule.name,
                        actionsSummary = actions.joinToString(", ") { it.displayName },
                        onConfirm = {
                            scope.launch {
                                executeAndLog(rule, actions, snapshot, TrackingService.lastData.value)
                            }
                        },
                        onCancel = {
                            scope.launch {
                                ruleLogDao.insert(
                                    RuleLogEntity(
                                        ruleId = rule.id,
                                        ruleName = rule.name,
                                        triggeredAt = now,
                                        triggersSnapshot = snapshot,
                                        actionsResult = """[{"result":"cancelled"}]""",
                                        success = false,
                                    )
                                )
                            }
                        },
                    )
                    if (!shown) showConfirmNotification(rule, actions, snapshot)
                } else {
                    // Awaited directly (not scope.launch) so the caller's
                    // coroutine observes dispatch completion deterministically.
                    executeAndLog(rule, actions, snapshot, data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "$kind error for rule '${rule.name}': ${e.message}")
            }
        }
        return matching.size
    }

    // --- Trigger evaluation ---

    private fun evaluateEachTrigger(
        triggers: List<TriggerDef>,
        data: DiParsData,
        location: Location?,
        places: Map<Long, PlaceEntity>,
        serviceStartActive: Boolean,
        networkAvailableEdge: Boolean
    ): List<Boolean> = triggers.map { trigger ->
        when (trigger.kind) {
            "place_enter" -> evaluatePlace(trigger, location, places, enterKind = true)
            "place_exit" -> evaluatePlace(trigger, location, places, enterKind = false)
            "time_of_day" -> evaluateTimeOfDay(trigger, location)
            "time_range" -> evaluateSchedule(trigger)
            "service_start" -> serviceStartActive
            "network_available" -> networkAvailableEdge
            // Button-press triggers never match during the 3-second poll. They
            // fire only through the explicit onButtonPress() entry point, so a
            // rule built around a widget button can't be triggered by polling.
            "button_press" -> false
            // Same for steering-wheel keys: only the a11y filter fires them,
            // through onSteeringKey().
            TRIGGER_KIND_STEERING_KEY -> false
            "voice" -> false   // event trigger; fired on demand via fireVoiceRule, never polled
            else -> { // "param" (default)
                val actual = getParamValue(data, trigger.param) ?: return@map false
                val expected = trigger.value.toDoubleOrNull() ?: return@map false
                compare(actual, trigger.operator, expected)
            }
        }
    }

    private fun combineByLogic(results: List<Boolean>, logic: String): Boolean = when (logic) {
        "OR" -> results.any { it }
        else -> results.all { it }
    }

    /**
     * Determines whether the rule's matched=true was actually driven by an event
     * trigger (service_start / network_available), so the bypass-edge-detection
     * branch only fires for genuine events. Without this gate, a rule like
     * `network_available OR speed > 50` would re-fire on every poll where speed
     * exceeds 50 (after cooldown), because the bypass logic would see a present
     * event-trigger regardless of whether the event itself contributed.
     */
    private fun matchedViaEventTrigger(
        triggers: List<TriggerDef>,
        perTrigger: List<Boolean>,
        logic: String
    ): Boolean {
        val eventKinds = setOf("service_start", "network_available")
        return when (logic) {
            // OR: at least one event-trigger must be true on its own.
            "OR" -> triggers.zip(perTrigger).any { (t, r) -> r && t.kind in eventKinds }
            // AND: every trigger must be true; if at least one of them is an
            // event, the whole composition fires only when that event was true,
            // so the rule is event-driven.
            else -> triggers.any { it.kind in eventKinds } && perTrigger.all { it }
        }
    }

    private fun evaluatePlace(
        trigger: TriggerDef,
        location: Location?,
        places: Map<Long, PlaceEntity>,
        enterKind: Boolean
    ): Boolean {
        if (location == null) return false
        val placeId = trigger.placeId ?: return false
        val place = places[placeId] ?: return false
        val inside = PlaceGeometry.isInside(
            location.latitude, location.longitude,
            place.lat, place.lon, place.radiusM
        )
        return if (enterKind) inside else !inside
    }

    private fun evaluateTimeOfDay(trigger: TriggerDef, location: Location?): Boolean {
        val loc = location ?: return false
        val phase = SunTimeCalculator.currentPhase(loc)
        val target = trigger.value.uppercase()
        return phase.name == target
    }

    private fun evaluateSchedule(trigger: TriggerDef): Boolean {
        val spec = ScheduleSpec.fromJson(trigger.value) ?: return false
        val cal = Calendar.getInstance()
        val nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // Calendar: SUNDAY=1..SATURDAY=7 → ISO: MONDAY=1..SUNDAY=7
        val nowDow = if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
            else cal.get(Calendar.DAY_OF_WEEK) - 1
        return isWithinSchedule(spec, nowMinute, nowDow)
    }

    private fun compare(actual: Double, op: String, expected: Double): Boolean = when (op) {
        ">" -> actual > expected
        "<" -> actual < expected
        ">=" -> actual >= expected
        "<=" -> actual <= expected
        "==" -> abs(actual - expected) < 0.01
        "!=" -> abs(actual - expected) >= 0.01
        else -> false
    }

    private fun getParamValue(data: DiParsData, param: String): Double? = when (param) {
        "Speed" -> data.speed?.toDouble()
        "SOC" -> data.soc?.toDouble()
        "ExtTemp" -> data.exteriorTemp?.toDouble()
        "InsideTemp" -> data.insideTemp?.toDouble()
        "ChargingStatus" -> data.chargingStatus?.toDouble()
        "PowerState" -> data.powerState?.toDouble()
        "Gear" -> data.gear?.toDouble()
        "ACStatus" -> data.acStatus?.toDouble()
        "ACTemp" -> data.acTemp?.toDouble()
        "FanLevel" -> data.fanLevel?.toDouble()
        "ACCirc" -> data.acCirc?.toDouble()
        "DoorFL" -> data.doorFL?.toDouble()
        "DoorFR" -> data.doorFR?.toDouble()
        "DoorRL" -> data.doorRL?.toDouble()
        "DoorRR" -> data.doorRR?.toDouble()
        "WindowFL" -> data.windowFL?.toDouble()
        "WindowFR" -> data.windowFR?.toDouble()
        "WindowRL" -> data.windowRL?.toDouble()
        "WindowRR" -> data.windowRR?.toDouble()
        "Sunroof" -> data.sunroof?.toDouble()
        "Trunk" -> data.trunk?.toDouble()
        "Hood" -> data.hood?.toDouble()
        "SeatbeltFL" -> data.seatbeltFL?.toDouble()
        "SeatbeltFR" -> data.seatbeltFR?.toDouble()
        "OccupancyFL" -> data.occupancyFL?.toDouble()
        "OccupancyFR" -> data.occupancyFR?.toDouble()
        "OccupancyRL" -> data.occupancyRL?.toDouble()
        "OccupancyRM" -> data.occupancyRM?.toDouble()
        "OccupancyRR" -> data.occupancyRR?.toDouble()
        "LightLevel" -> data.lightLevel?.toDouble()
        "KeyBattery" -> data.keyBatteryStatus?.toDouble()
        "LockFL" -> data.lockFL?.toDouble()
        "TirePressFL" -> data.tirePressFL?.toDouble()
        "TirePressFR" -> data.tirePressFR?.toDouble()
        "TirePressRL" -> data.tirePressRL?.toDouble()
        "TirePressRR" -> data.tirePressRR?.toDouble()
        "DriveMode" -> data.driveMode?.toDouble()
        "WorkMode" -> data.workMode?.toDouble()
        "AutoPark" -> data.autoPark?.toDouble()
        "Rain" -> data.rain?.toDouble()
        "LightLow" -> data.lightLow?.toDouble()
        "DRL" -> data.drl?.toDouble()
        "TurnSignal" -> data.turnSignal?.toDouble()
        "MaxBatTemp" -> data.maxBatTemp?.toDouble()
        "AvgBatTemp" -> data.avgBatTemp?.toDouble()
        "MinBatTemp" -> data.minBatTemp?.toDouble()
        "Power" -> data.power
        "Mileage" -> data.mileage
        "Voltage12V" -> data.voltage12v
        "MinCellVoltage" -> data.minCellVoltage
        "MaxCellVoltage" -> data.maxCellVoltage
        else -> null
    }

    // --- Execution ---

    private suspend fun executeAndLog(
        rule: RuleEntity,
        actions: List<ActionDef>,
        snapshot: String,
        data: DiParsData?
    ): Boolean {
        // One chime per rule firing, regardless of action kinds (per-rule "Выполнять со звуком").
        if (rule.playSound) {
            OverlayNotificationManager.playNotificationSound(context)
        }

        val results = JSONArray()
        var allSuccess = true

        for (action in actions) {
            val result = actionDispatcher.dispatch(action, data)
            results.put(JSONObject().apply {
                put("command", action.command)
                put("displayName", action.displayName)
                put("kind", action.kind)
                put("success", result.success)
                if (result.reason != null) put("reason", result.reason)
            })
            if (!result.success) allSuccess = false
        }

        ruleLogDao.insert(
            RuleLogEntity(
                ruleId = rule.id,
                ruleName = rule.name,
                triggeredAt = System.currentTimeMillis(),
                triggersSnapshot = snapshot,
                actionsResult = results.toString(),
                success = allSuccess
            )
        )
        Log.i(TAG, "Rule '${rule.name}' executed: success=$allSuccess")
        return allSuccess
    }

    /**
     * Fire a voice-triggered rule's actions on demand (outside the polling loop).
     * Honors requirePark and confirmBeforeExecute; bypasses cooldown / fireOncePerTrip
     * (anti-spam concepts for automatic triggers, irrelevant to a spoken command).
     * Param actions still pass through the per-action speed gate in ActionDispatcher.
     * The non-confirm path launches execution in [scope] and returns [VoiceFireResult.Fired]
     * as soon as the actions are handed off, not once they finish running.
     */
    suspend fun fireVoiceRule(ruleId: Long, data: DiParsData?): VoiceFireResult {
        val rule = ruleDao.getById(ruleId) ?: return VoiceFireResult.NotFound
        if (!rule.enabled) return VoiceFireResult.NotFound
        val actions = ActionDef.listFromJson(rule.actions)
        if (actions.isEmpty()) return VoiceFireResult.NotFound

        // requirePark: gear 1 = P.
        if (rule.requirePark && data?.gear != 1) return VoiceFireResult.ParkRequired

        // Fail-closed: getBlockReason() allows window/sunroof-open when the whole snapshot
        // is missing. Guard here so voice automations can't open either at unknown speed
        // (both are speed-gated predicates after the T12 split; sunshade is not held).
        if (data == null && actions.any {
                ActionDispatcher.isWindowOpenCommand(it.command) ||
                    ActionDispatcher.isSunroofOpenCommand(it.command)
            }) {
            return VoiceFireResult.SpeedUnknown
        }

        ruleDao.updateLastTriggered(rule.id, System.currentTimeMillis())
        val snapshot = JSONObject().put("voice", true).toString()

        if (rule.confirmBeforeExecute) {
            val shown = ConfirmOverlayManager.show(
                context = context,
                ruleName = rule.name,
                actionsSummary = actions.joinToString(", ") { it.displayName },
                // Re-read live vehicle data at confirm time (the overlay can sit
                // open while the car accelerates); mirrors the polling confirm path
                // so the >80km/h window gate runs against current speed, not speak-time.
                onConfirm = { scope.launch { executeAndLog(rule, actions, snapshot, TrackingService.lastData.value) } },
                onCancel = {
                    scope.launch {
                        ruleLogDao.insert(
                            RuleLogEntity(
                                ruleId = rule.id, ruleName = rule.name,
                                triggeredAt = System.currentTimeMillis(),
                                triggersSnapshot = snapshot,
                                actionsResult = """[{"result":"cancelled"}]""",
                                success = false,
                            )
                        )
                    }
                },
            )
            if (!shown) showConfirmNotification(rule, actions, snapshot)
            return VoiceFireResult.Confirming
        }

        // Run in the engine's own service-lifetime scope, not the caller's coroutine (mirrors
        // the confirm branch above): a voice-fired rule can contain a delay action, and if we
        // suspend here on the caller's routingJob, the assistant UI disappearing (button tap
        // or timeout) cancels that job mid-sequence, aborting the rule with actions half-run.
        Log.i(TAG, "voice fire: rule=${rule.id} actions=${actions.size} launched in engine scope")
        scope.launch { executeAndLog(rule, actions, snapshot, data) }
        return VoiceFireResult.Fired(true)
    }

    private fun buildSnapshot(triggers: List<TriggerDef>, data: DiParsData): String {
        val json = JSONObject()
        triggers.forEach { t ->
            when (t.kind) {
                "place_enter" -> json.put("place_enter", t.placeName ?: "?")
                "place_exit" -> json.put("place_exit", t.placeName ?: "?")
                "time_of_day" -> json.put("time_of_day", t.value)
                "time_range" -> json.put("time_range", t.value)
                "service_start" -> json.put("service_start", true)
                "network_available" -> json.put("network_available", true)
                "button_press" -> json.put("button_press", t.value)
                TRIGGER_KIND_STEERING_KEY -> json.put(TRIGGER_KIND_STEERING_KEY, t.value)
                else -> json.put(t.param, getParamValue(data, t.param) ?: JSONObject.NULL)
            }
        }
        return json.toString()
    }

    // --- Confirmation notifications ---

    private fun showConfirmNotification(
        rule: RuleEntity,
        actions: List<ActionDef>,
        snapshot: String
    ) {
        val notifId = NOTIF_BASE_ID + rule.id.toInt()

        pendingConfirmations[notifId] = PendingAction(
            rule = rule, actions = actions, snapshot = snapshot,
            createdAt = System.currentTimeMillis(), notifId = notifId
        )

        val summary = actions.joinToString(", ") { it.displayName }

        val confirmPI = PendingIntent.getBroadcast(
            context, notifId,
            Intent(ACTION_CONFIRM).putExtra(EXTRA_NOTIF_ID, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelPI = PendingIntent.getBroadcast(
            context, notifId + 10000,
            Intent(ACTION_CANCEL).putExtra(EXTRA_NOTIF_ID, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CONFIRM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(rule.name)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .addAction(android.R.drawable.ic_menu_send, appStrings.get(R.string.service_confirm_action_yes), confirmPI)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, appStrings.get(R.string.service_confirm_action_no), cancelPI)
            .setAutoCancel(true)
            .setTimeoutAfter(CONFIRM_TIMEOUT_MS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, notification)
        Log.i(TAG, "Confirm requested: '${rule.name}' → $summary")
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val expired = pendingConfirmations.entries
            .filter { now - it.value.createdAt > CONFIRM_TIMEOUT_MS }

        for ((notifId, pending) in expired) {
            pendingConfirmations.remove(notifId)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
            scope.launch {
                ruleLogDao.insert(
                    RuleLogEntity(
                        ruleId = pending.rule.id,
                        ruleName = pending.rule.name,
                        triggeredAt = pending.createdAt,
                        triggersSnapshot = pending.snapshot,
                        actionsResult = """[{"result":"timeout"}]""",
                        success = false
                    )
                )
            }
            Log.i(TAG, "Confirm timeout: '${pending.rule.name}'")
        }
    }

    private fun registerConfirmReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
                val pending = pendingConfirmations.remove(notifId) ?: return

                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notifId)

                when (intent.action) {
                    ACTION_CONFIRM -> scope.launch {
                        val currentData = TrackingService.lastData.value
                        executeAndLog(pending.rule, pending.actions, pending.snapshot, currentData)
                    }
                    ACTION_CANCEL -> {
                        scope.launch {
                            ruleLogDao.insert(
                                RuleLogEntity(
                                    ruleId = pending.rule.id,
                                    ruleName = pending.rule.name,
                                    triggeredAt = pending.createdAt,
                                    triggersSnapshot = pending.snapshot,
                                    actionsResult = """[{"result":"cancelled"}]""",
                                    success = false
                                )
                            )
                        }
                        Log.i(TAG, "Cancelled by user: '${pending.rule.name}'")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_CONFIRM)
            addAction(ACTION_CANCEL)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun createConfirmChannel() = createConfirmChannel(context, appStrings.context)
}
