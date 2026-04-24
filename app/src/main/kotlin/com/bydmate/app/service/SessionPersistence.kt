package com.bydmate.app.service

import android.content.Context

/**
 * Holds the current widget-session anchor across process restarts. After v2.5.0
 * the aggregator no longer needs mileage/totalElec baselines (those moved into
 * OdometerConsumptionBuffer which is itself Room-persistent), so this is just
 * the ignition-on timestamp + last-active heartbeat.
 *
 * Cleared on ignition-off (powerState 0 + 30 sec idle) so the next session
 * gets a fresh sessionStartedAt.
 */
data class PersistedSession(
    val sessionStartedAt: Long,
    val lastActiveTs: Long,
)

class SessionPersistence(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PersistedSession? {
        if (!prefs.contains(KEY_STARTED_AT)) return null
        val ts = prefs.getLong(KEY_STARTED_AT, 0L)
        val last = prefs.getLong(KEY_LAST_ACTIVE_TS, 0L)
        if (ts <= 0L) return null
        return PersistedSession(sessionStartedAt = ts, lastActiveTs = last)
    }

    fun save(sessionStartedAt: Long, lastActiveTs: Long) {
        prefs.edit()
            .putLong(KEY_STARTED_AT, sessionStartedAt)
            .putLong(KEY_LAST_ACTIVE_TS, lastActiveTs)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_STARTED_AT)
            .remove(KEY_LAST_ACTIVE_TS)
            // Also wipe legacy keys from v2.4.x in case of in-place upgrade.
            .remove(LEGACY_KEY_MILEAGE_START)
            .remove(LEGACY_KEY_ELEC_START)
            .remove(LEGACY_KEY_MILEAGE_START_BITS)
            .remove(LEGACY_KEY_ELEC_START_BITS)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "bydmate_widget_session"
        private const val KEY_STARTED_AT = "session_started_at"
        private const val KEY_LAST_ACTIVE_TS = "last_active_ts"
        // Legacy v2.4.x keys — only cleared, never read.
        private const val LEGACY_KEY_MILEAGE_START = "mileage_start_km"
        private const val LEGACY_KEY_ELEC_START = "elec_start_kwh"
        private const val LEGACY_KEY_MILEAGE_START_BITS = "mileage_start_km_bits"
        private const val LEGACY_KEY_ELEC_START_BITS = "elec_start_kwh_bits"
    }
}
