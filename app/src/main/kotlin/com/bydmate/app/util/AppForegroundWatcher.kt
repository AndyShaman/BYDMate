package com.bydmate.app.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Polls UsageStatsManager every 500 ms for the latest MOVE_TO_FOREGROUND event
 * and exposes the current foreground package as a StateFlow.
 *
 * Requires android.permission.PACKAGE_USAGE_STATS — non-runtime permission
 * granted manually by the user via Settings → Special access → Usage access.
 */
object AppForegroundWatcher {

    private const val TAG = "AppForegroundWatcher"
    private const val POLL_INTERVAL_MS = 500L
    private const val LOOKBACK_MS = 10_000L  // query 10 sec window

    private val _currentForegroundPackage = MutableStateFlow<String?>(null)
    val currentForegroundPackage: StateFlow<String?> = _currentForegroundPackage

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return
        if (!hasPermission(context)) {
            Log.w(TAG, "PACKAGE_USAGE_STATS not granted, watcher will not start")
            return
        }
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        job = scope.launch { pollLoop(usm) }
        Log.i(TAG, "Started")
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _currentForegroundPackage.value = null
    }

    private suspend fun pollLoop(usm: UsageStatsManager) {
        while (true) {
            try {
                val now = System.currentTimeMillis()
                val events = usm.queryEvents(now - LOOKBACK_MS, now)
                var latestPackage: String? = _currentForegroundPackage.value
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        latestPackage = event.packageName
                    }
                }
                if (latestPackage != _currentForegroundPackage.value) {
                    _currentForegroundPackage.value = latestPackage
                }
            } catch (e: Exception) {
                Log.w(TAG, "pollLoop: ${e.message}")
            }
            delay(POLL_INTERVAL_MS)
        }
    }
}
