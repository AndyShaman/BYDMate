package com.bydmate.app

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.bydmate.app.service.TrackingService

/**
 * Invisible Activity used by BootReceiver on Android 12+ (SDK 31+).
 *
 * Android 12 restricts starting foreground services from BroadcastReceivers.
 * DiPlus (com.van.diplus) solves this with StartMainServiceActivity — we do the same.
 * BootReceiver launches this Activity → it starts TrackingService → finishes immediately.
 *
 * Theme: @android:style/Theme.NoDisplay (no window, no UI).
 */
class SilentStartActivity : Activity() {

    companion object {
        private const val TAG = "SilentStartActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Starting TrackingService from boot context")
        TrackingService.start(this)
        finish()
    }
}
