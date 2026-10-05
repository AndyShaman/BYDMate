package com.bydmate.app.data.automation

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.bydmate.app.R
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.util.appLocalizedContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot migration of saved rules off the removed «Питание» (PowerState) condition.
 *
 * The app starts after the car is powered and a condition already true at the first check does
 * not fire, so ON never fired; OFF cannot fire (the app dies with the car); DRIVE fired only by
 * race. ON becomes «Запуск BYDMate», DRIVE becomes «Передача = D», and a rule that could fire
 * only with the car off is removed. Same shape as [TrunkRuleMigration], with its own done-flag;
 * the flag travels with the rules in a backup, so an older archive restored is converted again.
 */
@Singleton
class PowerStateRuleMigration @Inject constructor(
    private val ruleDao: RuleDao,
    private val settings: SettingsRepository,
    private val db: AppDatabase,
    @ApplicationContext private val context: Context,
) {

    /** Returns the number of rules rewritten or removed (0 when already migrated or nothing matched). */
    @Suppress("TooGenericExceptionCaught") // any failure leaves the flag unset for a retry
    suspend fun runOnce(): Int {
        if (settings.isPowerStateRuleMigrationDone()) return 0
        val labels = labels(context)
        return try {
            var removed = 0
            val migrated = db.withTransaction {
                var count = 0
                for (rule in ruleDao.getAllList()) {
                    val triggers = TriggerDef.listFromJson(rule.triggers)
                    // An unparseable list comes back empty: writing it back would destroy it.
                    if (triggers.none { it.isPowerState() }) continue
                    val converted = convert(rule.triggerLogic, triggers, labels)
                    if (converted == null) {
                        ruleDao.delete(rule)
                        removed++
                    } else {
                        ruleDao.update(rule.copy(triggers = TriggerDef.listToJson(converted)))
                    }
                    count++
                }
                count
            }
            settings.setPowerStateRuleMigrationDone()
            Log.i(TAG, "PowerState rule migration: $migrated rule(s) changed, $removed of them removed")
            migrated
        } catch (e: Exception) {
            // Flag stays unset: the next start retries.
            Log.w(TAG, "PowerState rule migration failed: ${e.message}")
            0
        }
    }

    /** Display names of the two replacement triggers, in the app language. */
    data class Labels(val serviceStart: String, val gearDrive: String)

    companion object {
        private const val TAG = "PowerStateRuleMigration"
        const val PARAM = "PowerState"
        private const val ON = "1"
        private const val DRIVE = "2"
        private const val GEAR = "Gear"
        private const val GEAR_D = "4"

        private fun TriggerDef.isPowerState(): Boolean = kind == "param" && param == PARAM

        private fun TriggerDef.isPowerStateEquals(code: String): Boolean =
            isPowerState() && operator == "==" && value.trim() == code

        private fun TriggerDef.isGearD(): Boolean =
            kind == "param" && param == GEAR && operator == "==" && value.trim() == GEAR_D

        /** The labels a trigger made in the editor gets: «Запуск BYDMate», «Передача = D». */
        fun labels(context: Context): Labels {
            val lc = context.appLocalizedContext()
            return Labels(
                serviceStart = lc.getString(R.string.auto_trig_service_start),
                gearDrive = "${lc.getString(R.string.auto_param_gear)} = ${lc.getString(R.string.auto_enum_code_d)}",
            )
        }

        /**
         * [triggers] of a rule with [logic] and no PowerState left, or null when the rule could
         * never fire and is to be removed. ON becomes the BYDMate start and DRIVE gear D, each
         * dropped when the rule already has it (ON is dropped from a one-shot rule too: an event
         * cannot join a one-shot moment, and the app running already means the car is on). Any
         * other PowerState condition removes an AND rule and drops out of an OR rule. Without a
         * PowerState trigger the same list comes back.
         */
        fun convert(logic: String, triggers: List<TriggerDef>, labels: Labels): List<TriggerDef>? {
            if (triggers.none { it.isPowerState() }) return triggers
            val result = mutableListOf<TriggerDef>()
            for (t in triggers) {
                // Checked against the whole rule: a duplicate may also come later in the list.
                val kept = triggers + result
                val next = when {
                    !t.isPowerState() -> t
                    t.isPowerStateEquals(ON) -> TriggerDef(
                        param = "ServiceStart", chineseName = "服务启动", operator = "==", value = "true",
                        displayName = labels.serviceStart, kind = "service_start",
                    ).takeUnless { kept.any { it.kind == "service_start" || it.kind == OneShotTrigger.KIND } }
                    t.isPowerStateEquals(DRIVE) -> TriggerDef(
                        param = GEAR, chineseName = "档位", operator = "==", value = GEAR_D,
                        displayName = labels.gearDrive,
                    ).takeUnless { kept.any { it.isGearD() } }
                    logic == "OR" -> null
                    else -> return null
                }
                if (next != null) result += next
            }
            return result.ifEmpty { null }
        }
    }
}
