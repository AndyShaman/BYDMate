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
) {
    /**
     * Prefs may hold a session that *should* have been idle-closed but wasn't
     * (process killed inside the grace window, or DiLink rebooted before idle-close
     * could fire). If the last-active tick was longer ago than the idle-close
     * threshold, treat the session as dead — otherwise the widget anchor is
     * ancient and trip time renders as "11 ч" on the next ignition-on.
     */
    fun isStale(now: Long, idleCloseMs: Long): Boolean {
        return now - lastActiveTs >= idleCloseMs
    }
}

class SessionPersistence(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Bookkeeping for the save() write-gate below -- identity/ts of the last
    // write that actually reached disk.
    private var lastWrittenSessionStartedAt: Long? = null
    private var lastWrittenTs: Long = 0L

    fun load(): PersistedSession? {
        if (!prefs.contains(KEY_STARTED_AT)) return null
        val ts = prefs.getLong(KEY_STARTED_AT, 0L)
        val last = prefs.getLong(KEY_LAST_ACTIVE_TS, 0L)
        if (ts <= 0L) return null
        return PersistedSession(sessionStartedAt = ts, lastActiveTs = last)
    }

    /**
     * Write-gated to [WRITE_GRANULARITY_MS]: lastActiveTs feeds the
     * SESSION_IDLE_CLOSE_MS=10s staleness check in [PersistedSession.isStale], so
     * the gate stays well under that window. A new session (sessionStartedAt
     * differs from the last write) always writes immediately regardless of the
     * granularity -- otherwise a crash inside the delay window could resume the
     * previous, already-ended session's stale anchor.
     */
    fun save(sessionStartedAt: Long, lastActiveTs: Long) {
        if (sessionStartedAt == lastWrittenSessionStartedAt &&
            lastActiveTs - lastWrittenTs < WRITE_GRANULARITY_MS
        ) return
        lastWrittenSessionStartedAt = sessionStartedAt
        lastWrittenTs = lastActiveTs
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
        // Max delay before save() forces a write even without a session change.
        private const val WRITE_GRANULARITY_MS = 5_000L
        // Legacy v2.4.x keys — only cleared, never read.
        private const val LEGACY_KEY_MILEAGE_START = "mileage_start_km"
        private const val LEGACY_KEY_ELEC_START = "elec_start_kwh"
        private const val LEGACY_KEY_MILEAGE_START_BITS = "mileage_start_km_bits"
        private const val LEGACY_KEY_ELEC_START_BITS = "elec_start_kwh_bits"
    }
}
