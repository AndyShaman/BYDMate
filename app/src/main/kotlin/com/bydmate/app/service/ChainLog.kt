package com.bydmate.app.service

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent chain log for autostart diagnostics — survives logcat rollover
 * (DiLink rotates logcat aggressively). Used by BootReceiver / ServiceStartWorker /
 * TrackingService to trace boot → WorkManager → service-start chain.
 */
object ChainLog {
    private const val TAG = "ChainLog"
    private const val MAX_ENTRIES = 20

    fun append(context: Context, entry: String) {
        try {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val ts = sdf.format(Date())
            val prefs = context.getSharedPreferences(
                BootReceiver.PREFS_NAME, Context.MODE_PRIVATE
            )
            val existing = prefs.getString(BootReceiver.KEY_CHAIN_LOG, "") ?: ""
            val lines = existing.lines().takeLast(MAX_ENTRIES - 1)
            val updated = (lines + "$ts $entry").joinToString("\n")
            prefs.edit().putString(BootReceiver.KEY_CHAIN_LOG, updated).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to append chain log: ${e.message}")
        }
    }
}
