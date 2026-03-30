package com.bydmate.app

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import com.bydmate.app.service.TrackingService

/**
 * Invisible Activity used by BootReceiver on Android 12+ (SDK 30+).
 *
 * Replicates DiPlus StartMainServiceActivity approach exactly:
 * - Translucent theme (not Theme.NoDisplay — more reliable on DiLink)
 * - 1×1 pixel window at top-left corner
 * - Starts TrackingService, then finishes immediately
 *
 * BootReceiver → SilentStartActivity → TrackingService.start() → finish()
 */
class SilentStartActivity : Activity() {

    companion object {
        private const val TAG = "SilentStartActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1×1 pixel invisible window (exactly like DiPlus)
        window.apply {
            setGravity(Gravity.START or Gravity.TOP)
            attributes = attributes.also {
                it.x = 0
                it.y = 0
                it.width = 1
                it.height = 1
            }
        }

        val onBoot = intent?.getBooleanExtra("onBoot", false) ?: false
        Log.i(TAG, "Starting TrackingService (onBoot=$onBoot)")

        TrackingService.start(this)
        finish()
    }
}
