package com.bydmate.app.data.local

import android.content.SharedPreferences

/**
 * Per-device verdict and detection state for a dead leftover energydata DB (issue #63).
 *
 * Some head units (e.g. Yuan Plus / DiLink 3) carry an EC_database.db file from an old
 * firmware that the car never appends to. EnergyDataReader.isAvailable() sees a readable
 * DB, TripRecorder stays passive, and no trips get recorded at all. This store persists
 * the verdict "energydata is dead on THIS device" plus the counters used to reach it.
 *
 * Mirrors the SeatChannelStore pattern: interface + prefs-backed impl with a schema-version
 * guard so an app update that changes detection semantics discards stale state, plus a
 * Settings reset button.
 */
interface EnergyDataLivenessStore {
    fun isDead(): Boolean
    fun setDead()
    fun streak(): Int
    fun setStreak(value: Int)
    fun pendingDriving(): Boolean
    fun setPendingDriving(value: Boolean)
    fun fingerprint(): Pair<Long, Long>?
    fun setFingerprint(mtime: Long, size: Long)
    fun lastIncrementTs(): Long
    fun setLastIncrementTs(value: Long)
    /** Forget everything; detection restarts from scratch. Settings button + auto-heal. */
    fun reset()
}

class EnergyDataLivenessStorePrefs(private val prefs: SharedPreferences) : EnergyDataLivenessStore {

    private fun versionOk() = prefs.getInt(KEY_VERSION, -1) == SCHEMA_VERSION

    /** Editor pre-stamped with the current schema version. If the stored version is
     *  stale, all data keys are cleared first so a fresh write under the new stamp
     *  cannot resurrect values written by an older schema. */
    private fun stampedEdit(): SharedPreferences.Editor {
        val editor = prefs.edit()
        if (!versionOk()) {
            editor.remove(KEY_DEAD).remove(KEY_STREAK).remove(KEY_PENDING)
                .remove(KEY_FP_MTIME).remove(KEY_FP_SIZE).remove(KEY_LAST_INCREMENT_TS)
        }
        return editor.putInt(KEY_VERSION, SCHEMA_VERSION)
    }

    override fun isDead(): Boolean = versionOk() && prefs.getBoolean(KEY_DEAD, false)

    override fun setDead() { stampedEdit().putBoolean(KEY_DEAD, true).apply() }

    override fun streak(): Int = if (versionOk()) prefs.getInt(KEY_STREAK, 0) else 0

    override fun setStreak(value: Int) { stampedEdit().putInt(KEY_STREAK, value).apply() }

    override fun pendingDriving(): Boolean = versionOk() && prefs.getBoolean(KEY_PENDING, false)

    override fun setPendingDriving(value: Boolean) { stampedEdit().putBoolean(KEY_PENDING, value).apply() }

    override fun fingerprint(): Pair<Long, Long>? {
        if (!versionOk()) return null
        if (!prefs.contains(KEY_FP_MTIME) || !prefs.contains(KEY_FP_SIZE)) return null
        return prefs.getLong(KEY_FP_MTIME, 0L) to prefs.getLong(KEY_FP_SIZE, 0L)
    }

    override fun setFingerprint(mtime: Long, size: Long) { stampedEdit().putLong(KEY_FP_MTIME, mtime).putLong(KEY_FP_SIZE, size).apply() }

    override fun lastIncrementTs(): Long = if (versionOk()) prefs.getLong(KEY_LAST_INCREMENT_TS, 0L) else 0L

    override fun setLastIncrementTs(value: Long) { stampedEdit().putLong(KEY_LAST_INCREMENT_TS, value).apply() }

    override fun reset() {
        prefs.edit()
            .remove(KEY_DEAD)
            .remove(KEY_STREAK)
            .remove(KEY_PENDING)
            .remove(KEY_FP_MTIME)
            .remove(KEY_FP_SIZE)
            .remove(KEY_LAST_INCREMENT_TS)
            .remove(KEY_VERSION)
            .apply()
    }

    companion object {
        // Bump when detection semantics change, to auto-discard stored verdicts.
        const val SCHEMA_VERSION = 1
        private const val KEY_VERSION = "energydata_liveness_schema_version"
        private const val KEY_DEAD = "energydata_dead"
        private const val KEY_STREAK = "energydata_dead_streak"
        private const val KEY_PENDING = "energydata_pending_driving"
        private const val KEY_FP_MTIME = "energydata_fp_mtime"
        private const val KEY_FP_SIZE = "energydata_fp_size"
        private const val KEY_LAST_INCREMENT_TS = "energydata_last_increment_ts"
    }
}
