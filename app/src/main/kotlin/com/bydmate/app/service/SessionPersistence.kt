package com.bydmate.app.service

import android.content.Context
import com.bydmate.app.domain.calculator.SessionBaseline

/**
 * Thin SharedPreferences wrapper holding the current widget session anchor
 * (ignition-on timestamp + mileage/elec baselines). Survives process kill so that
 * the ConsumptionAggregator can resume cumulative mode from the real ignition-on
 * point, not from whenever the process was respawned.
 *
 * Cleared on ignition-off (powerState 0) so the next session starts fresh.
 */
data class PersistedSession(
    val baseline: SessionBaseline,
    val lastActiveTs: Long,
)

class SessionPersistence(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PersistedSession? {
        if (!prefs.contains(KEY_STARTED_AT)) return null
        val ts = prefs.getLong(KEY_STARTED_AT, 0L)
        val miles = loadDouble(KEY_MILEAGE_START_BITS, KEY_MILEAGE_START)
        val elec = loadDouble(KEY_ELEC_START_BITS, KEY_ELEC_START)
        val lastActiveTs = prefs.getLong(KEY_LAST_ACTIVE_TS, 0L)
        if (ts <= 0L || miles == null || elec == null) return null
        return PersistedSession(
            baseline = SessionBaseline(
                sessionStartedAt = ts,
                mileageStart = miles,
                totalElecStart = elec,
            ),
            lastActiveTs = lastActiveTs,
        )
    }

    fun save(baseline: SessionBaseline, lastActiveTs: Long) {
        prefs.edit()
            .putLong(KEY_STARTED_AT, baseline.sessionStartedAt)
            .putLong(KEY_MILEAGE_START_BITS, baseline.mileageStart.toRawBits())
            .putLong(KEY_ELEC_START_BITS, baseline.totalElecStart.toRawBits())
            .putLong(KEY_LAST_ACTIVE_TS, lastActiveTs)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_STARTED_AT)
            .remove(KEY_MILEAGE_START)
            .remove(KEY_ELEC_START)
            .remove(KEY_MILEAGE_START_BITS)
            .remove(KEY_ELEC_START_BITS)
            .remove(KEY_LAST_ACTIVE_TS)
            .apply()
    }

    private fun loadDouble(bitsKey: String, floatKey: String): Double? {
        if (prefs.contains(bitsKey)) {
            return Double.fromBits(prefs.getLong(bitsKey, 0L))
        }
        if (!prefs.contains(floatKey)) return null
        val value = prefs.getFloat(floatKey, Float.NaN)
        return if (value.isNaN()) null else value.toDouble()
    }

    companion object {
        private const val PREFS_NAME = "bydmate_widget_session"
        private const val KEY_STARTED_AT = "session_started_at"
        private const val KEY_MILEAGE_START = "mileage_start_km"
        private const val KEY_ELEC_START = "elec_start_kwh"
        private const val KEY_MILEAGE_START_BITS = "mileage_start_km_bits"
        private const val KEY_ELEC_START_BITS = "elec_start_kwh_bits"
        private const val KEY_LAST_ACTIVE_TS = "last_active_ts"
    }
}
