package com.bydmate.app.data.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
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
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutomationEngine @Inject constructor(
    private val ruleDao: RuleDao,
    private val ruleLogDao: RuleLogDao,
    private val actionDispatcher: ActionDispatcher,
    private val placeRepository: PlaceRepository,
    private val networkAvailableMonitor: NetworkAvailableMonitor,
    @ApplicationContext private val context: Context
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
    // Service-start trigger: active during a short window after the first
    // evaluate() of the process, consumed per rule on fire. The previous
    // one-shot flag raced cold-start nulls: the very first tick often carries
    // incomplete data, so "запуск BYDMate AND темп > 22" silently missed the
    // whole trip when ExtTemp was still null on tick one (issue #51).
    @Volatile private var serviceStartWindowEnd = 0L
    private val serviceStartConsumed = ConcurrentHashMap.newKeySet<Long>()

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
        val now = System.currentTimeMillis()

        // Arm the service_start window on the very first evaluate() of the process.
        if (serviceStartWindowEnd == 0L) serviceStartWindowEnd = now + SERVICE_START_WINDOW_MS

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

                val serviceStartActive = now <= serviceStartWindowEnd && rule.id !in serviceStartConsumed
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
     * independent of the 3-second poll. The button press is an explicit user
     * command, so:
     *  - co-triggers on the matched rule are NOT evaluated (the press alone is
     *    enough to run the rule),
     *  - cooldownSeconds and fireOncePerTrip are bypassed (a repeatable manual
     *    action),
     *  - requirePark and confirmBeforeExecute are honored, and every per-action
     *    ActionDispatcher safety gate stays in force (it reads the same snapshot).
     * Safety snapshot is the latest TrackingService.lastData.value.
     *
     * Returns the number of rules matched by button number (0 ⇒ caller shows the
     * "no rules for button N" toast). A matched-but-park-gated rule still counts,
     * so the caller does not falsely report "no rules".
     */
    suspend fun onButtonPress(buttonId: Int): Int {
        val matching = ruleDao.getEnabled().filter { rule ->
            TriggerDef.listFromJson(rule.triggers).any {
                it.kind == "button_press" && it.value.toIntOrNull() == buttonId
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
                Log.e(TAG, "onButtonPress error for rule '${rule.name}': ${e.message}")
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
    ) {
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
            .addAction(android.R.drawable.ic_menu_send, context.getString(R.string.service_confirm_action_yes), confirmPI)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.service_confirm_action_no), cancelPI)
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

    private fun createConfirmChannel() {
        val channel = NotificationChannel(
            CONFIRM_CHANNEL_ID,
            "Automation Confirmations",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Confirmation dialogs for automation rules"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
