package com.bydmate.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.bydmate.app.SilentStartActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Auto-start on boot — replicates DiPlus BootReceiver strategy exactly.
 *
 * DiPlus on Android 11+ (SDK 30+) starts an invisible Activity first,
 * which then starts the foreground service. This avoids background
 * foreground-service restrictions on Android 12+.
 *
 * BootReceiver → SilentStartActivity → TrackingService.start() → finish()
 *
 * Fallback: if Activity start fails, try startForegroundService() directly.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val PREFS_NAME = "boot_log"
        const val KEY_LAST_BOOT_TS = "last_boot_ts"
        const val KEY_LAST_BOOT_METHOD = "last_boot_method"
        const val KEY_LAST_BOOT_ACTION = "last_boot_action"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.LOCKED_BOOT_COMPLETED"
        )
        if (intent.action !in validActions) return

        Log.i(TAG, "Boot event: ${intent.action}")

        // Log boot event to SharedPreferences (visible in Settings without ADB)
        logBootEvent(context, intent.action ?: "unknown")

        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+ — use Activity intermediary (like DiPlus StartMainServiceActivity)
            try {
                val activityIntent = Intent(context, SilentStartActivity::class.java).apply {
                    putExtra("onBoot", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(activityIntent)
                updateBootMethod(context, "Activity")
                Log.i(TAG, "startActivity(SilentStartActivity) OK")
                return
            } catch (e: Exception) {
                Log.w(TAG, "startActivity failed, falling back to startForegroundService: ${e.message}")
            }
        }

        // Android <11 or Activity fallback — start service directly
        try {
            val serviceIntent = Intent(context, TrackingService::class.java).apply {
                putExtra("onBoot", true)
            }
            context.startForegroundService(serviceIntent)
            updateBootMethod(context, "ForegroundService")
            Log.i(TAG, "startForegroundService(TrackingService) OK")
        } catch (e: Exception) {
            updateBootMethod(context, "FAILED: ${e.message}")
            Log.e(TAG, "startForegroundService failed: ${e.message}", e)
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
}
