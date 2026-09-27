package com.bydmate.app.data.automation

import android.util.Log
import androidx.room.withTransaction
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot migration of saved Trunk rules to the encoding the car reports.
 *
 * The catalog offered "0" as closed, but the tailgate fid answers 2 closed, 1 open, 3 moving
 * (2 seen 18 times in user logs, 0 never), so a «Багажник = Закрыт» rule could never match.
 * Trunk triggers holding exactly "0" become "2"; every other trigger, param and value is written
 * back unchanged. Same shape as [DriveModeRuleMigration], with its own done-flag.
 */
@Singleton
class TrunkRuleMigration @Inject constructor(
    private val ruleDao: RuleDao,
    private val settings: SettingsRepository,
    private val db: AppDatabase,
) {

    /** Returns the number of rules rewritten (0 when already migrated or nothing matched). */
    @Suppress("TooGenericExceptionCaught") // any failure leaves the flag unset for a retry
    suspend fun runOnce(): Int {
        if (settings.isTrunkRuleMigrationDone()) return 0
        return try {
            val migrated = db.withTransaction {
                var count = 0
                for (rule in ruleDao.getAllList()) {
                    val triggers = TriggerDef.listFromJson(rule.triggers)
                    // An unparseable list comes back empty: writing it back would destroy it.
                    if (triggers.none { it.isLegacyClosedTrunk() }) continue
                    ruleDao.update(rule.copy(triggers = TriggerDef.listToJson(fix(triggers))))
                    count++
                }
                count
            }
            settings.setTrunkRuleMigrationDone()
            Log.i(TAG, "Trunk rule migration: value 0 -> $CLOSED_CODE in $migrated rule(s)")
            migrated
        } catch (e: Exception) {
            // Flag stays unset: the next start retries.
            Log.w(TAG, "Trunk rule migration failed: ${e.message}")
            0
        }
    }

    companion object {
        private const val TAG = "TrunkRuleMigration"
        private const val PARAM = "Trunk"
        private const val LEGACY_CLOSED_CODE = "0"
        private const val CLOSED_CODE = "2"

        private fun TriggerDef.isLegacyClosedTrunk(): Boolean = param == PARAM && value == LEGACY_CLOSED_CODE

        /** [triggers] with every Trunk "0" as "2". The car never reports 0, so this is always right. */
        fun fix(triggers: List<TriggerDef>): List<TriggerDef> =
            triggers.map { if (it.isLegacyClosedTrunk()) it.copy(value = CLOSED_CODE) else it }
    }
}
