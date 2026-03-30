package com.bydmate.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-start on boot.
 *
 * Uses startForegroundService() directly — same approach as TripInfo
 * (which works on this DiLink device). BOOT_COMPLETED provides
 * a temporary allowlist that permits foreground service starts.
 *
 * No Activity intermediary needed — START_ACTIVITIES_FROM_BACKGROUND
 * is a signature permission that sideloaded apps cannot get.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.LOCKED_BOOT_COMPLETED"
        )
        if (intent.action !in validActions) return

        Log.i(TAG, "Boot event: ${intent.action}")

        try {
            val serviceIntent = Intent(context, TrackingService::class.java).apply {
                putExtra("onBoot", true)
            }
            context.startForegroundService(serviceIntent)
            Log.i(TAG, "startForegroundService(TrackingService) OK")
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService failed: ${e.message}", e)
        }
    }
}
