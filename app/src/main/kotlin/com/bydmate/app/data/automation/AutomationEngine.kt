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
import kotlin.math.abs
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingConfirmations = ConcurrentHashMap<Int, PendingAction>()
    // Edge triggering: only fire when condition transitions from false→true
    private val lastEvalResults = ConcurrentHashMap<Long, Boolean>()

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

    // Called every 3s from TrackingService poll loop
    suspend fun evaluate(data: DiParsData) {
        cleanupExpired()

        val location = TrackingService.lastLocation.value
        val placesById = placeRepository.getAllSnapshot().associateBy { it.id }

        val rules = ruleDao.getEnabled()
        val now = System.currentTimeMillis()

        for (rule in rules) {
            try {
                // Cooldown
                val lastFired = rule.lastTriggeredAt ?: 0L
                if (now - lastFired < rule.cooldownSeconds * 1000L) continue

                // Park-only rule
                if (rule.requirePark && data.gear != 1) continue

                val triggers = TriggerDef.listFromJson(rule.triggers)
                if (triggers.isEmpty()) continue

                val matched = evaluateTriggers(triggers, data, rule.triggerLogic, location, placesById)
                val previous = lastEvalResults.put(rule.id, matched)
                if (previous == null) continue          // first observation — seed only, do not fire
                if (!matched || previous) continue       // edge trigger: fire only on false→true

                val actions = ActionDef.listFromJson(rule.actions)
                if (actions.isEmpty()) continue

                // Mark triggered immediately to prevent re-fire
                ruleDao.updateLastTriggered(rule.id, now)

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

    // --- Trigger evaluation ---

    private fun evaluateTriggers(
        triggers: List<TriggerDef>,
        data: DiParsData,
        logic: String,
        location: Location?,
        places: Map<Long, PlaceEntity>
    ): Boolean {
        val results = triggers.map { trigger ->
            when (trigger.kind) {
                "place_enter" -> evaluatePlace(trigger, location, places, enterKind = true)
                "place_exit" -> evaluatePlace(trigger, location, places, enterKind = false)
                "time_of_day" -> evaluateTimeOfDay(trigger, location)
                else -> { // "param" (default)
                    val actual = getParamValue(data, trigger.param) ?: return@map false
                    val expected = trigger.value.toDoubleOrNull() ?: return@map false
                    compare(actual, trigger.operator, expected)
                }
            }
        }
        return when (logic) {
            "OR" -> results.any { it }
            else -> results.all { it }
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

    fun shutdown() {
        scope.cancel()
        pendingConfirmations.clear()
        lastEvalResults.clear()
    }

    private fun buildSnapshot(triggers: List<TriggerDef>, data: DiParsData): String {
        val json = JSONObject()
        triggers.forEach { t ->
            when (t.kind) {
                "place_enter" -> json.put("place_enter", t.placeName ?: "?")
                "place_exit" -> json.put("place_exit", t.placeName ?: "?")
                "time_of_day" -> json.put("time_of_day", t.value)
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
            .addAction(android.R.drawable.ic_menu_send, "Выполнить", confirmPI)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отмена", cancelPI)
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
