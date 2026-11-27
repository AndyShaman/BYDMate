package com.bydmate.app.service

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea

/**
 * WorkManager worker that starts TrackingService.
 *
 * Used by BootReceiver instead of direct startForegroundService().
 * WorkManager guarantees execution even after process death —
 * same approach as BydConnect (ServiceStartWorker).
 */
class ServiceStartWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ServiceStartWorker"
        const val WORK_NAME = "ServiceStart"

        /**
         * A restart enqueued by onTaskRemoved / onDestroy, and a cold boot, are dropped when the
         * screen is off: the car is parked, and the service would hold its wake lock all night.
         * A skipped cold boot waits for the screen in [ColdBootDeferral]. Every other source
         * starts as before.
         */
        fun shouldStart(source: String?, interactive: Boolean, coldBoot: Boolean = false): Boolean =
            interactive || !(coldBoot || source == AutostartTrace.SOURCE_TASK_REMOVED ||
                source == AutostartTrace.SOURCE_SERVICE_DESTROYED)
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting TrackingService via WorkManager")
        ChainLog.append(applicationContext, "Worker doWork started")
        val source = inputData.getString(AutostartTrace.KEY_WORKER_SOURCE)
        val trigger = AutostartTrace.workerTrigger(source)
        val coldBoot = inputData.getBoolean(AutostartTrace.KEY_COLD_BOOT, false)
        val interactive = (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (!shouldStart(source, interactive, coldBoot)) {
            Log.i(TAG, "Skipped: screen off, source=$source cold_boot=$coldBoot")
            Trace.event(TraceArea.APP, "start-worker skipped: screen off", "trigger" to trigger, "cold_boot" to coldBoot)
            if (coldBoot) ColdBootDeferral.arm(applicationContext, "cold_boot")
            return Result.success()
        }
        return try {
            val intent = Intent(applicationContext, TrackingService::class.java).apply {
                putExtra(AutostartTrace.EXTRA_TRIGGER, trigger)
                putExtra(AutostartTrace.KEY_COLD_BOOT, coldBoot)
            }
            ContextCompat.startForegroundService(applicationContext, intent)
            ChainLog.append(applicationContext, "startForegroundService OK")
            Log.i(TAG, "startForegroundService OK")
            Trace.event(TraceArea.APP, "start-worker", "trigger" to trigger, "attempt" to runAttemptCount,
                "result" to "ok", "cold_boot" to coldBoot)
            Result.success()
        } catch (e: Exception) {
            ChainLog.append(applicationContext, "startForegroundService FAILED: ${e.message}")
            Log.e(TAG, "Failed to start TrackingService: ${e.message}", e)
            Trace.event(TraceArea.APP, "start-worker", "trigger" to trigger, "attempt" to runAttemptCount,
                "result" to "retry", "error" to e.javaClass.simpleName)
            Result.retry()
        }
    }
}
