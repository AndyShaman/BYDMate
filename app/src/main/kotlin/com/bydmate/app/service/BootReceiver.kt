package com.bydmate.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea

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
        const val KEY_CHAIN_LOG = "chain_log"
        /** Explicit restart request from the helper daemon after the Android 10 a11y recovery. */
        const val ACTION_RECOVER_START = "com.bydmate.app.action.RECOVER_START"

        // DiLink's JobScheduler blocks third-party jobs while the car is off, so a start job can
        // sit enqueued until morning and must not swallow the car-wake boot or a later unlock.
        fun workPolicyFor(action: String?): ExistingWorkPolicy = when (action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_PRESENT -> ExistingWorkPolicy.REPLACE
            else -> ExistingWorkPolicy.KEEP
        }

        /** The start worker's input: who enqueued it, and whether the boot was cold ([AutostartTrace.isColdBoot]). */
        fun workInput(intent: Intent): Data = workDataOf(
            AutostartTrace.KEY_WORKER_SOURCE to AutostartTrace.actionName(intent.action),
            AutostartTrace.KEY_COLD_BOOT to AutostartTrace.isColdBoot(
                intent.action, intent.getBooleanExtra(AutostartTrace.EXTRA_FROM_QUICKBOOT, false)),
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // An APK update kills the app and sends only this: without it the service stayed down
            // until the next boot or unlock.
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_USER_PRESENT,
            ACTION_RECOVER_START,
        )
        if (intent.action !in validActions) return

        Log.i(TAG, "Boot/user event: ${intent.action}")
        ChainLog.append(context, "BootReceiver: ${intent.action}")
        val input = workInput(intent)
        val interactive = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        Trace.event(TraceArea.APP, "boot-receiver", "action" to AutostartTrace.actionName(intent.action),
            "service_running" to TrackingService.isRunning.value,
            "cold_boot" to input.getBoolean(AutostartTrace.KEY_COLD_BOOT, false), "interactive" to interactive)

        // Skip USER_PRESENT if service is already running
        if (intent.action == Intent.ACTION_USER_PRESENT && TrackingService.isRunning.value) {
            Log.d(TAG, "Service already running, ignoring USER_PRESENT")
            ChainLog.append(context, "USER_PRESENT skipped (service running)")
            return
        }

        logBootEvent(context)

        // Use WorkManager — guaranteed execution (like BydConnect)
        try {
            val request = OneTimeWorkRequestBuilder<ServiceStartWorker>()
                .setInputData(input)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ServiceStartWorker.WORK_NAME,
                workPolicyFor(intent.action),
                request
            )
            updateBootMethod(context, "WorkManager")
            ChainLog.append(context, "WorkManager enqueued OK")
            Log.i(TAG, "WorkManager enqueued ServiceStartWorker")
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager enqueue failed: ${e.message}", e)
            ChainLog.append(context, "WorkManager FAILED: ${e.message}")
            updateBootMethod(context, "FAILED: ${e.message}")

            // Last resort fallback: direct start
            try {
                TrackingService.start(context, AutostartTrace.directFallbackTrigger(intent.action))
                updateBootMethod(context, "DirectFallback")
                ChainLog.append(context, "DirectFallback OK")
                Log.i(TAG, "Direct startForegroundService fallback OK")
            } catch (e2: Exception) {
                updateBootMethod(context, "FAILED: ${e2.message}")
                ChainLog.append(context, "DirectFallback FAILED: ${e2.message}")
                Log.e(TAG, "All start methods failed: ${e2.message}", e2)
            }
        }
    }

    private fun logBootEvent(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_BOOT_TS, System.currentTimeMillis())
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
