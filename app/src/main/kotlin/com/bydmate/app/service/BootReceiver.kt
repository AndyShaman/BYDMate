package com.bydmate.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Auto-start on boot — uses WorkManager (like BydConnect).
 *
 * Previous approach (SilentStartActivity → startForegroundService → finish)
 * had a race condition: process killed before service creation.
 *
 * WorkManager guarantees task execution even after process death.
 * Also listens for USER_PRESENT as backup trigger.
 *
 * BootReceiver → WorkManager → ServiceStartWorker → startForegroundService
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val PREFS_NAME = "boot_log"
        const val KEY_LAST_BOOT_TS = "last_boot_ts"
        const val KEY_LAST_BOOT_METHOD = "last_boot_method"
        const val KEY_LAST_BOOT_ACTION = "last_boot_action"
        const val KEY_CHAIN_LOG = "chain_log"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_USER_PRESENT
        )
        if (intent.action !in validActions) return

        Log.i(TAG, "Boot/user event: ${intent.action}")
        appendChainLog(context, "BootReceiver: ${intent.action}")

        // Skip USER_PRESENT if service is already running
        if (intent.action == Intent.ACTION_USER_PRESENT && TrackingService.isRunning.value) {
            Log.d(TAG, "Service already running, ignoring USER_PRESENT")
            appendChainLog(context, "USER_PRESENT skipped (service running)")
            return
        }

        logBootEvent(context, intent.action ?: "unknown")

        // Use WorkManager — guaranteed execution (like BydConnect)
        try {
            val request = OneTimeWorkRequestBuilder<ServiceStartWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ServiceStartWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
            updateBootMethod(context, "WorkManager")
            appendChainLog(context, "WorkManager enqueued OK")
            Log.i(TAG, "WorkManager enqueued ServiceStartWorker")
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager enqueue failed: ${e.message}", e)
            appendChainLog(context, "WorkManager FAILED: ${e.message}")
            updateBootMethod(context, "FAILED: ${e.message}")

            // Last resort fallback: direct start
            try {
                TrackingService.start(context)
                updateBootMethod(context, "DirectFallback")
                appendChainLog(context, "DirectFallback OK")
                Log.i(TAG, "Direct startForegroundService fallback OK")
            } catch (e2: Exception) {
                updateBootMethod(context, "FAILED: ${e2.message}")
                appendChainLog(context, "DirectFallback FAILED: ${e2.message}")
                Log.e(TAG, "All start methods failed: ${e2.message}", e2)
            }
        }
    }

    private fun logBootEvent(context: Context, action: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_BOOT_TS, System.currentTimeMillis())
                .putString(KEY_LAST_BOOT_ACTION, action)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log boot event: ${e.message}")
        }
    }

    private fun updateBootMethod(context: Context, method: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_BOOT_METHOD, method)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update boot method: ${e.message}")
        }
    }

    /** Append timestamped entry to chain log (persists in SharedPreferences, survives logcat rollover) */
    fun appendChainLog(context: Context, entry: String) {
        try {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val ts = sdf.format(Date())
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_CHAIN_LOG, "") ?: ""
            // Keep last 20 entries max
            val lines = existing.lines().takeLast(19)
            val updated = (lines + "$ts $entry").joinToString("\n")
            prefs.edit().putString(KEY_CHAIN_LOG, updated).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to append chain log: ${e.message}")
        }
    }
}
