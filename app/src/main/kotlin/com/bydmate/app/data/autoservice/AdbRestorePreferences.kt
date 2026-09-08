package com.bydmate.app.data.autoservice

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent state of the ADB restore feature: the user's toggle plus the record of the
 * last `adb_wifi_enabled` write. The write record is persisted (not kept in memory) because
 * the service is restarted far more often than the 10 minute cooldown it guards — without
 * it every restart would pop another system dialog on a network the user has not approved.
 */
interface AdbRestorePreferences {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)

    /** Network id (BSSID/SSID) of the last enable-write, or null if there was none. */
    fun lastWriteNetwork(): String?

    /** Timestamp of the last enable-write, 0 if there was none. */
    fun lastWriteAtMs(): Long

    fun recordWrite(network: String?, atMs: Long)
}

/** SharedPreferences-backed production implementation, file "adb_restore". */
class AdbRestorePreferencesImpl(context: Context) : AdbRestorePreferences {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    override fun lastWriteNetwork(): String? = prefs.getString(KEY_LAST_WRITE_NETWORK, null)

    override fun lastWriteAtMs(): Long = prefs.getLong(KEY_LAST_WRITE_AT, 0L)

    override fun recordWrite(network: String?, atMs: Long) {
        prefs.edit()
            .putString(KEY_LAST_WRITE_NETWORK, network)
            .putLong(KEY_LAST_WRITE_AT, atMs)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "adb_restore"
        const val KEY_ENABLED = "adb_restore_enabled"
        const val KEY_LAST_WRITE_NETWORK = "last_write_network"
        const val KEY_LAST_WRITE_AT = "last_write_at"
    }
}
