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
import java.util.Calendar
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
import com.bydmate.app.data.telegram.withReportRuleName
import com.bydmate.app.service.TrackingService
import com.bydmate.app.ui.overlay.OverlayNotificationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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

        // How long after it fires the service_start trigger stays true, giving
        // cold-start params a few polls to warm up (#51).
        const val SERVICE_START_WINDOW_MS = 30_000L

        // Legacy service_start session keys of 3.17.x-3.18.0 (#177): no longer read or written,
        // the stale values stay in prefs and are kept out of backups.
        internal const val PREFS_NAME = "automation"
        internal const val KEY_SERVICE_START_BOOT_ID = "service_start_boot_id"
        internal const val KEY_SERVICE_START_LAST_SEEN_ELAPSED = "service_start_last_seen_elapsed"
        internal const val KEY_SERVICE_START_LAST_SEEN_UPTIME = "service_start_last_seen_uptime"
        internal const val KEY_SERVICE_START_CAR_OFF = "service_start_car_off"

        // Android 10 a11y recovery force-stops our own process after service_start fired, and
        // RECOVER_START brings a new one back (11-44 s on Atto 3 logs). The dying process stores
        // its elapsedRealtime here; the next process skips service_start once if it starts within
        // SELF_RESTART_MAX_GAP_MS of it. 120 s is about 3x the slowest restart seen; the restarted
        // process consumes the mark, so a later car start never sees it.
        internal const val KEY_SERVICE_START_SELF_RESTART_ELAPSED = "service_start_self_restart_elapsed"
        const val SELF_RESTART_MAX_GAP_MS = 120_000L
        // The mark holds elapsed and wall time; a reboot makes their gaps disagree, so a real
        // start after a reboot is never taken for our restart.
        private const val SELF_RESTART_CLOCK_TOLERANCE_MS = 5_000L

        // Steering-wheel key trigger: manual only, like button_press. Fires from
        // the a11y key filter through onSteeringKey(), never from the poll.
        const val TRIGGER_KIND_STEERING_KEY = "steering_key"
        const val TRIGGER_PARAM_STEERING_KEY = "steering_key"

        /** An enabled rule with a steering_key trigger needs the a11y key filter bound (#262). */
        fun hasEnabledSteeringKeyRule(rules: List<RuleEntity>): Boolean = rules.any { rule ->
            rule.enabled && TriggerDef.listFromJson(rule.triggers).any { it.kind == TRIGGER_KIND_STEERING_KEY }
        }

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
    // Service-start trigger (#177): a new process is a car start. At car off the firmware
    // force-stops every app and the process comes back through WorkManager, sometimes while the
    // screen is still dark. So the trigger fires on the first evaluate() of this process with the
    // screen on, then stays true for SERVICE_START_WINDOW_MS while the screen is on, and each
    // rule fires on it once per process. The window keeps "запуск BYDMate AND темп > 22" from
    // missing the trip when ExtTemp is still null on tick one (issue #51). Memory only: a new
    // process starts over.
    @Volatile private var serviceStartFiredAt: Long? = null
    @Volatile private var serviceStartWaitLogged = false
    // Whether this process is our own a11y recovery restart; read once, on the first evaluate.
    @Volatile private var selfRestartVerdict: Boolean? = null
    private val serviceStartConsumed: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    // evaluate() has two callers (the poll tick and every push event), and its per-rule gates are
    // read-then-write across several steps: cooldown, once-per-trip, service_start consumption,
    // updateLastTriggered. Two overlapping calls could pass the same rule and dispatch its actions
    // twice, so callers run it under this lock. Actions are dispatched inside the engine's own
    // scope, so the lock is held for the rule scan only.
    val evaluateMutex = Mutex()
    // Test seam: monotonic clock of the service_start window.
    internal var elapsedMs: () -> Long = { SystemClock.elapsedRealtime() }
    // Test seam: whether the head unit screen is on. Without a PowerManager the screen is taken
    // as on, so service_start fires on the first evaluate().
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
    // Test seam: the freshest poll, read before every step so a speed gate after a pause sees
    // the car as it is then, not as it was when the rule fired.
    internal var liveData: () -> DiParsData? = { TrackingService.lastData.value }
    // Test seam: the range the Dashboard shows, for the RangeKm condition.
    internal var rangeKm: () -> Double? = { TrackingService.lastRangeKm.value }
    // «Why was the rule skipped» lines: one per rule and reason per minute.
    private val skipLog = com.bydmate.app.data.autoservice.LogThrottle()
    private val journal = RuleJournal(ruleLogDao, appStrings, skipLog) { nowMs() }
    // The DriveMode condition value; trusts the target fid once it agreed with dev 1006.
    private val driveModeCondition = DriveModeCondition { Log.i(TAG, it) }
    // Rules already logged as «true at the first check» in this process.
    private val firstCheckLogged: MutableSet<Long> = ConcurrentHashMap.newKeySet()
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

    // Read from the DB, not steeringKeyCodes: that cache is filled asynchronously after start.
    suspend fun steeringKeyRuleEnabled(): Boolean = hasEnabledSteeringKeyRule(ruleDao.getEnabled())

    // One line for the diagnostics dump (#177).
    fun serviceStartDumpLine(): String =
        "service_start: interactive=${interactiveProvider()} fired=${serviceStartFiredAt != null}"

    /**
     * Whether service_start is true on this evaluate(): fires on the first one with the screen on,
     * then stays true for [SERVICE_START_WINDOW_MS] while the screen is on. Runs under
     * [evaluateMutex].
     */
    private fun serviceStartWindowOpen(): Boolean {
        // Judged on the first evaluate of the process, whatever the screen: a recovery process
        // can come up dark and see the screen only after the mark has aged.
        val bySelf = selfRestartVerdict ?: restartedBySelf().also { selfRestartVerdict = it }
        val lit = interactiveProvider()
        val firedAt = serviceStartFiredAt
        if (firedAt != null) return lit && elapsedMs() - firedAt <= SERVICE_START_WINDOW_MS
        if (!lit) {
            if (!serviceStartWaitLogged) {
                serviceStartWaitLogged = true
                Log.i(TAG, "service_start: screen off at start, waiting for screen on")
            }
            return false
        }
        if (bySelf) {
            // Fired and expired: the start already fired in the process our recovery killed.
            serviceStartFiredAt = elapsedMs() - SERVICE_START_WINDOW_MS - 1
            return false
        }
        serviceStartFiredAt = elapsedMs()
        Log.i(TAG, "service_start: fired (new process, screen on)")
        return true
    }

    /** Reads and clears the self-restart mark; true when this process is our a11y recovery restart. */
    private fun restartedBySelf(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SERVICE_START_SELF_RESTART_ELAPSED, null) ?: return false
        prefs.edit().remove(KEY_SERVICE_START_SELF_RESTART_ELAPSED).apply()
        val parts = raw.split(',').mapNotNull { it.toLongOrNull() }
        if (parts.size != 2) {
            Log.i(TAG, "service_start: self-restart mark ignored, unreadable: $raw")
            return false
        }
        val gap = elapsedMs() - parts[0]
        val wallGap = nowMs() - parts[1]
        // After a reboot elapsed restarts and wall time does not, so the two gaps disagree.
        val agree = abs(gap - wallGap) <= SELF_RESTART_CLOCK_TOLERANCE_MS
        if (gap !in 0..SELF_RESTART_MAX_GAP_MS || wallGap !in 0..SELF_RESTART_MAX_GAP_MS || !agree) {
            Log.i(TAG, "service_start: self-restart mark ignored, gap=${gap}ms wallGap=${wallGap}ms")
            return false
        }
        Log.i(TAG, "service_start: suppressed, our own a11y recovery restart ${gap}ms ago")
        return true
    }

    /**
     * Called right before the a11y recovery force-stops this process: if service_start already
     * fired here, the restarted process must not fire it again. Written synchronously, the process
     * is about to die.
     */
    fun markSelfRestart() {
        if (serviceStartFiredAt == null) return
        val now = elapsedMs()
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SERVICE_START_SELF_RESTART_ELAPSED, "$now,${nowMs()}").commit()
        Log.i(TAG, "service_start: self-restart mark written at elapsed=$now saved=$saved")
    }

    /** The recovery call returned, so this process survived: the next one is a real start. */
    fun clearSelfRestartMark() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_SERVICE_START_SELF_RESTART_ELAPSED).commit()
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

        val serviceStartWindow = serviceStartWindowOpen()

        // Prune per-rule state for rules that have been deleted (or disabled
        // and removed from the active set). Without this, `lastEvalResults`,
        // `lastFiredTripByRule` and `lastSeenNetworkAvailableAt` would grow
        // monotonically over the app lifetime as the user creates and removes
        // rules. O(rules) per tick — negligible at typical N (≤ a few dozen).
        val activeIds = rules.mapTo(HashSet()) { it.id }
        lastEvalResults.keys.retainAll(activeIds)
        lastFiredTripByRule.keys.retainAll(activeIds)
        lastSeenNetworkAvailableAt.keys.retainAll(activeIds)
        // serviceStartConsumed is not pruned: a rule disabled for a tick must not fire twice.

        for (rule in rules) {
            try {
                val triggers = TriggerDef.listFromJson(rule.triggers)
                if (triggers.isEmpty()) continue
                val oneShot = triggers.firstOrNull { it.kind == OneShotTrigger.KIND }
                if (oneShot != null && OneShotTrigger.state(oneShot.value, now) == OneShotTrigger.State.EXPIRED) {
                    ruleDao.setEnabled(rule.id, false)
                    journal.oneShotExpired(rule, oneShot.value, now)
                    continue
                }

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

                val serviceStartActive = serviceStartWindow && rule.id !in serviceStartConsumed
                val perTrigger = evaluateEachTrigger(triggers, data, location, placesById, serviceStartActive, networkEdge)
                val matched = combineByLogic(perTrigger, rule.triggerLogic)
                if (!matched) logMissingData(rule, triggers, data)

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
                    // Audit point 1: a condition already true when first seen is only remembered.
                    if (previous == null && matched && firstCheckLogged.add(rule.id)) {
                        Log.i(TAG, "rule ${rule.id} '${rule.name}': already true at first check, remembered without firing")
                    }
                    // null = first observation, hash mismatch = rule just edited —
                    // either way seed only, do not fire on a synthetic transition.
                    previous != null && previous.triggersHash == triggersHash &&
                        !previous.matched && matched
                }
                if (!shouldFire) continue

                // Cooldown (the front above is already consumed, never deferred)
                val lastFired = rule.lastTriggeredAt ?: 0L
                if (now - lastFired < rule.cooldownSeconds * 1000L) {
                    logSkip(rule, "cooldown", "${rule.cooldownSeconds}s, last ${(now - lastFired) / 1000}s ago")
                    continue
                }

                // Park-only rule
                if (rule.requirePark && data.gear != 1) {
                    logSkip(rule, "park only", "gear=${data.gear}")
                    continue
                }

                // Once-per-trip gate: skip if already fired in the current trip
                if (rule.fireOncePerTrip && tripStartedAt != null &&
                    lastFiredTripByRule[rule.id] == tripStartedAt) {
                    logSkip(rule, "once per trip", "trip=$tripStartedAt")
                    continue
                }

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
                // A one-shot rule is spent by this fire, even if its confirmation is cancelled.
                if (oneShot != null) {
                    ruleDao.setEnabled(rule.id, false)
                    Log.i(TAG, "one-shot rule ${rule.id} '${rule.name}' fired (at=${oneShot.value}), switched off")
                }

                val snapshot = buildSnapshot(triggers, data)

                if (rule.confirmBeforeExecute) {
                    confirmThenRun(rule, actions, snapshot, now)
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
                if (rule.requirePark && data?.gear != 1) {
                    journal.parkRequired(rule, JSONObject().put(kind, value).toString(), data?.gear)
                    continue
                }

                val actions = ActionDef.listFromJson(rule.actions)
                if (actions.isEmpty()) continue

                val triggers = TriggerDef.listFromJson(rule.triggers)
                val snapshot = if (data != null) buildSnapshot(triggers, data) else "{}"

                // Mark triggered before execution (same ordering as evaluate path).
                ruleDao.updateLastTriggered(rule.id, now)

                if (rule.confirmBeforeExecute) {
                    confirmThenRun(rule, actions, snapshot, now)
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
            // Same for steering-wheel keys: only the a11y filter fires them,
            // through onSteeringKey(). Voice is fired on demand via fireVoiceRule.
            "button_press", TRIGGER_KIND_STEERING_KEY, "voice" -> false
            // One-shot: from its moment for a day, screen on. The rule is switched off once it fires.
            OneShotTrigger.KIND ->
                OneShotTrigger.state(trigger.value, nowMs()) == OneShotTrigger.State.DUE && interactiveProvider()
            else -> { // "param" (default)
                val actual = getParamValue(data, trigger.param) ?: return@map false
                val expected = TriggerNumber.parse(trigger.value) ?: return@map false
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
        // A one-shot rule fires at its first check at or after the moment, not on a false->true
        // front: a rule first seen after the moment would otherwise only be remembered.
        val eventKinds = setOf("service_start", "network_available", OneShotTrigger.KIND)
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
        "SeatbeltRL" -> data.seatbeltRL?.toDouble()
        "SeatbeltRM" -> data.seatbeltRM?.toDouble()
        "SeatbeltRR" -> data.seatbeltRR?.toDouble()
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
        "DriveMode" -> driveModeCondition.value(data.driveMode, data.driveModeTarget)?.toDouble()
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
        "RangeKm" -> rangeKm()
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

        for ((i, action) in actions.withIndex()) {
            // Fresh data per step: a pause may lie between the fire and this step. A speed-gated
            // step does not trust it either: the dispatcher reads the speed itself right before.
            val stepData = liveData() ?: data
            val result = actionDispatcher.dispatch(action.withReportRuleName(rule.name), stepData)
            Log.i(
                TAG,
                "rule ${rule.id} step ${i + 1}/${actions.size} ${action.kind} speed=${stepData?.speed} " +
                    "-> ${if (result.success) "ok" else "failed: ${result.reason}"}",
            )
            results.put(RuleJournal.recordedAction(action).apply {
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
        if (rule.requirePark && data?.gear != 1) {
            journal.parkRequired(rule, JSONObject().put("voice", true).toString(), data?.gear)
            return VoiceFireResult.ParkRequired
        }

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
            confirmThenRun(rule, actions, snapshot, System.currentTimeMillis())
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
                "time_of_day", "time_range", "button_press", TRIGGER_KIND_STEERING_KEY, OneShotTrigger.KIND ->
                    json.put(t.kind, t.value)
                "service_start" -> json.put("service_start", true)
                "network_available" -> json.put("network_available", true)
                else -> json.put(t.param, getParamValue(data, t.param) ?: JSONObject.NULL)
            }
        }
        return json.toString()
    }

    /**
     * Asks before running [actions]: the overlay, or the notification when overlays are not
     * allowed. The steps run against the data at confirm time; a cancel or no answer goes to the
     * journal with its reason.
     */
    private fun confirmThenRun(rule: RuleEntity, actions: List<ActionDef>, snapshot: String, at: Long) {
        val shown = ConfirmOverlayManager.show(
            context = context,
            ruleName = rule.name,
            actionsSummary = actions.joinToString(", ") { it.displayName },
            onConfirm = { scope.launch { executeAndLog(rule, actions, snapshot, TrackingService.lastData.value) } },
            onCancel = CancelOrTimeout(
                onCancel = { scope.launch { journal.cancelled(rule, snapshot, at) } },
                onTimeout = { scope.launch { journal.timeout(rule, snapshot, at) } },
            ),
        )
        // Fallback: user hasn't granted SYSTEM_ALERT_WINDOW.
        if (!shown) showConfirmNotification(rule, actions, snapshot)
    }

    private fun logSkip(rule: RuleEntity, reason: String, detail: String) {
        if (skipLog.shouldLog("${rule.id}:$reason", nowMs())) {
            Log.i(TAG, "rule ${rule.id} '${rule.name}' skipped: $reason ($detail)")
        }
    }

    /** A param condition the car gave no value for is false, whatever it compares: say which. */
    private fun logMissingData(rule: RuleEntity, triggers: List<TriggerDef>, data: DiParsData) {
        val missing = triggers.filter { it.kind == "param" && getParamValue(data, it.param) == null }
        if (missing.isNotEmpty()) logSkip(rule, "no data", missing.joinToString(",") { it.param })
    }

    /** Journal retention, run at service start: 30 days, 2000 rows. */
    suspend fun pruneJournal() = journal.prune(nowMs())

    /** The `--- automation journal ---` dump section: the newest entries with their reasons. */
    suspend fun journalDumpLines(): List<String> = journal.dumpLines()

    /** Every one of [params] as a condition reads it now, for the dump. */
    fun paramSnapshotLine(params: List<String>): String {
        val data = liveData() ?: return "(no data yet)"
        return params.joinToString(" ") { "$it=${getParamValue(data, it) ?: "-"}" }
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
        // Step kinds only, never the display name: it can carry a link, a phone number or a
        // place name. A param step's own command is a fixed vehicle code, safe to log as-is.
        val logSummary = actions.joinToString(", ") { if (it.kind == "param") "param ${it.command}" else it.kind }
        Log.i(TAG, "Confirm requested: '${rule.name}' → $logSummary")
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val expired = pendingConfirmations.entries
            .filter { now - it.value.createdAt > CONFIRM_TIMEOUT_MS }

        for ((notifId, pending) in expired) {
            pendingConfirmations.remove(notifId)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
            scope.launch { journal.timeout(pending.rule, pending.snapshot, pending.createdAt) }
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
                    ACTION_CANCEL -> scope.launch {
                        journal.cancelled(pending.rule, pending.snapshot, pending.createdAt)
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
