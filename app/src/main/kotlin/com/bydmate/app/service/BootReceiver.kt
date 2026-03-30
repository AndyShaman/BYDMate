package com.bydmate.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.bydmate.app.SilentStartActivity

/**
 * Auto-start on boot — same approach as DiPlus (com.van.diplus).
 *
 * Android 12+ (SDK 31+) restricts startForegroundService() from BroadcastReceiver.
 * DiPlus solves this by launching an invisible Activity first, which then starts the service.
 * We do the same: BootReceiver → SilentStartActivity → TrackingService.
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

        Log.i(TAG, "Boot event: ${intent.action}, SDK=${Build.VERSION.SDK_INT}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // SDK 31+ (Android 12+): start via invisible Activity (like DiPlus)
            val activityIntent = Intent(context, SilentStartActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(activityIntent)
        } else {
            // SDK < 31: direct foreground service start is allowed
            TrackingService.start(context)
        }
    }
}
