package com.bydmate.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea

/**
 * A start skipped on a cold boot with the screen off, or a cold-boot service stopped when the
 * screen went off ([ColdBootGuard]), waits here for the screen. A car wake normally sends a fresh
 * BOOT_COMPLETED that starts the service anyway; this covers a firmware that lights the screen
 * without one. The receiver lives in the app process: if the firmware kills the process, the
 * next car wake starts it with its own broadcast.
 */
internal object ColdBootDeferral {
    private const val TAG = "ColdBootDeferral"
    private var receiver: BroadcastReceiver? = null

    @Synchronized
    fun arm(context: Context, reason: String, start: (Context) -> Unit = ::enqueueStart) {
        if (receiver != null) return
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (!release(app, this)) return
                Log.i(TAG, "screen on after a skipped cold-boot start: ${intent?.action}")
                Trace.event(TraceArea.APP, "start-deferred fired", "on" to AutostartTrace.actionName(intent?.action))
                runCatching { start(app) }.onFailure { Log.w(TAG, "start failed: ${it.message}") }
            }
        }
        runCatching {
            app.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_USER_PRESENT) })
        }.onSuccess {
            receiver = r
            Log.i(TAG, "waiting for the screen: $reason")
            Trace.event(TraceArea.APP, "start-deferred", "reason" to reason)
        }.onFailure { Log.w(TAG, "register failed: ${it.message}") }
    }

    @Synchronized
    fun disarm(context: Context) {
        receiver?.let { release(context.applicationContext, it) }
    }

    /** True only for the receiver currently armed, so one wake starts the service once. */
    @Synchronized
    private fun release(app: Context, r: BroadcastReceiver): Boolean {
        if (receiver !== r) return false
        receiver = null
        runCatching { app.unregisterReceiver(r) }
        return true
    }

    private fun enqueueStart(context: Context) {
        val request = OneTimeWorkRequestBuilder<ServiceStartWorker>()
            .setInputData(workDataOf(AutostartTrace.KEY_WORKER_SOURCE to AutostartTrace.SOURCE_SCREEN_ON))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ServiceStartWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
