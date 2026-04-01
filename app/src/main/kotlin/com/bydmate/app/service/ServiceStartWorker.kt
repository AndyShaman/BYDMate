package com.bydmate.app.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting TrackingService via WorkManager")
        appendChainLog("Worker doWork started")
        return try {
            val intent = Intent(applicationContext, TrackingService::class.java).apply {
                putExtra("onBoot", true)
            }
            ContextCompat.startForegroundService(applicationContext, intent)
            appendChainLog("startForegroundService OK")
            Log.i(TAG, "startForegroundService OK")
            Result.success()
        } catch (e: Exception) {
            appendChainLog("startForegroundService FAILED: ${e.message}")
            Log.e(TAG, "Failed to start TrackingService: ${e.message}", e)
            Result.retry()
        }
    }

    private fun appendChainLog(entry: String) {
        try {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val ts = sdf.format(Date())
            val prefs = applicationContext.getSharedPreferences(
                BootReceiver.PREFS_NAME, Context.MODE_PRIVATE
            )
            val existing = prefs.getString(BootReceiver.KEY_CHAIN_LOG, "") ?: ""
            val lines = existing.lines().takeLast(19)
            val updated = (lines + "$ts $entry").joinToString("\n")
            prefs.edit().putString(BootReceiver.KEY_CHAIN_LOG, updated).apply()
        } catch (_: Exception) {}
    }
}
