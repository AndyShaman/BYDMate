package com.bydmate.app.util

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent crash log for uncaught exceptions — survives logcat rollover
 * (DiLink rotates logcat aggressively), so a crash from a previous session
 * is still visible in the diagnostic dump. record() is called from
 * Thread.UncaughtExceptionHandler right before the process dies, so it
 * commits synchronously — apply()'s async write would be lost.
 */
object CrashLog {
    private const val TAG = "CrashLog"
    private const val PREFS_NAME = "crash_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 5

    // Each entry embeds its own newlines (full stack trace), so entries
    // can't be joined with "\n" like ChainLog's single-line ring — use a
    // control character that never appears in exception text instead.
    private const val ENTRY_DELIMITER = ""

    fun record(context: Context, throwable: Throwable) {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val ts = sdf.format(Date())
            val entry = "$ts\n${throwable.stackTraceToString()}"
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_ENTRIES, "") ?: ""
            val entries = if (existing.isEmpty()) emptyList() else existing.split(ENTRY_DELIMITER)
            val updated = (entries + entry).takeLast(MAX_ENTRIES)
            prefs.edit().putString(KEY_ENTRIES, updated.joinToString(ENTRY_DELIMITER)).commit()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record crash: ${e.message}")
        }
    }

    fun read(context: Context): List<String> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_ENTRIES, "") ?: ""
            if (existing.isEmpty()) emptyList() else existing.split(ENTRY_DELIMITER).reversed()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read crash log: ${e.message}")
            emptyList()
        }
    }
}
