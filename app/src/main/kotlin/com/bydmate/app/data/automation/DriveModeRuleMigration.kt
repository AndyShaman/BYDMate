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
 * One-shot migration of saved DriveMode rules to the measured fid encoding.
 *
 * The catalog used to offer "0" as NORMAL. Live on Leopard 3 (2026-07-30) the fid turned out
 * to answer 1=ECO, 2=SPORT, 3=NORMAL, 4=off-road, and 0 only while a switch is in flight —
 * so the reader no longer reports 0 at all and every rule saved against it is dead. Rewriting
 * the stored value to 3 revives those rules and makes the editor show a real label again.
 *
 * Only DriveMode triggers holding exactly "0" are touched; every other trigger, param and
 * value is written back unchanged.
 */
@Singleton
class DriveModeRuleMigration @Inject constructor(
    private val ruleDao: RuleDao,
    private val settings: SettingsRepository,
    private val db: AppDatabase,
) {

    /** Returns the number of rules rewritten (0 when already migrated or nothing matched). */
    suspend fun runOnce(): Int {
        if (settings.isDriveModeRuleMigrationDone()) return 0
        return try {
            // Read + rewrite in one transaction: nothing else may interleave a rule write
            // between the read and the update.
            val migrated = db.withTransaction {
                var count = 0
                for (rule in ruleDao.getAllList()) {
                    val triggers = TriggerDef.listFromJson(rule.triggers)
                    if (triggers.isEmpty()) {
                        // listFromJson returns an empty list on a parse failure. Skipping is
                        // the only safe move: writing back would replace the (unreadable but
                        // possibly recoverable) original with "[]".
                        if (rule.triggers.isNotBlank() && rule.triggers != EMPTY_JSON_ARRAY) {
                            Log.w(TAG, "rule ${rule.id} has unparseable triggers, left untouched")
                        }
                        continue
                    }
                    if (triggers.none { it.isLegacyDriveMode() }) continue
                    val fixed = triggers.map {
                        if (it.isLegacyDriveMode()) it.copy(value = NORMAL_CODE) else it
                    }
                    ruleDao.update(rule.copy(triggers = TriggerDef.listToJson(fixed)))
                    count++
                }
                count
            }
            // Set even when some rules were unparseable: retrying cannot repair broken JSON.
            settings.setDriveModeRuleMigrationDone()
            if (migrated > 0) Log.i(TAG, "DriveMode rule migration: value 0 -> $NORMAL_CODE in $migrated rule(s)")
            migrated
        } catch (e: Exception) {
            // Leave the flag unset so the next start retries; a dead rule is better than
            // a rule rewritten from a half-parsed list.
            Log.w(TAG, "DriveMode rule migration failed: ${e.message}")
            0
        }
    }

    private fun TriggerDef.isLegacyDriveMode(): Boolean = param == PARAM && value == LEGACY_CODE

    private companion object {
        const val TAG = "DriveModeRuleMigration"
        const val PARAM = "DriveMode"
        const val LEGACY_CODE = "0"
        const val NORMAL_CODE = "3"
        const val EMPTY_JSON_ARRAY = "[]"
    }
}
