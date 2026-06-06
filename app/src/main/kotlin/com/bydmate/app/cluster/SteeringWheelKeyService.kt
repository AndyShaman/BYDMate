package com.bydmate.app.cluster

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Steering-wheel key filter for cluster projection. When the settings master switch
 * ([ClusterProjectionManager.KEY_MIRROR_ENABLED]) is ON, a short press of the configured trigger
 * button (keycode in [ClusterProjectionManager.KEY_TRIGGER_KEYCODE], default
 * [DEFAULT_TRIGGER_KEYCODE] = the right star) toggles Yandex Navi between the cluster and the centre
 * screen and is consumed; every other key (switch OFF, a non-trigger button, the right-star
 * long-press that opens the native action menu) passes through untouched.
 *
 * Inert unless enabled in secure settings (self-enabled via the daemon when the switch turns on —
 * no Accessibility UI on DiLink) AND the settings switch is on, so it does nothing for users who
 * never opt in.
 */
class SteeringWheelKeyService : AccessibilityService() {

    private var cachedEntryPoint: ClusterEntryPoint? = null
    private val prefs: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS  // 32
        serviceInfo = info
        isConnected = true
        Log.d(TAG, "connected; filtering steering-wheel keys")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (learnMode) {
            return when (learnDecision(event.keyCode, isDown)) {
                LearnAction.CAPTURE -> {
                    capturedKey.value = CaptureResult(event.keyCode, assignable = true)
                    learnMode = false  // got it; dialog moves to the confirm step
                    true
                }
                LearnAction.REJECT -> {
                    capturedKey.value = CaptureResult(event.keyCode, assignable = false)
                    true  // stay in learn mode; dialog shows "can't assign", waits for another key
                }
                LearnAction.CONSUME -> true
            }
        }
        val enabled = prefs.getBoolean(ClusterProjectionManager.KEY_MIRROR_ENABLED, false)
        val trigger = prefs.getInt(ClusterProjectionManager.KEY_TRIGGER_KEYCODE, DEFAULT_TRIGGER_KEYCODE)
        return when (starDecision(event.keyCode, isDown, enabled, trigger)) {
            StarDecision.CONSUME_AND_TOGGLE -> {
                val ep = entryPoint()
                ClusterProjectionManager.toggle(applicationContext, ep.helperClient(), ep.helperBootstrap())
                true
            }
            StarDecision.CONSUME -> true
            StarDecision.PASS_THROUGH -> false
        }
    }

    private fun entryPoint(): ClusterEntryPoint =
        cachedEntryPoint ?: EntryPointAccessors
            .fromApplication(applicationContext, ClusterEntryPoint::class.java)
            .also { cachedEntryPoint = it }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* unused */ }
    override fun onInterrupt() { /* no-op */ }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        Log.d(TAG, "unbound; star key filter inactive")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isConnected = false
        super.onDestroy()
    }

    companion object {
        const val TAG = "SteeringWheelKeySvc"

        /**
         * True liveness signal. Set when the framework actually binds + connects this service (the
         * only moment the key filter is live), cleared on unbind/destroy. The Secure-settings
         * enabled-list is NOT reliable across boot/wake, so the re-assert watchdog in TrackingService
         * gates on THIS first. Mirrors OpenBYD's SteeringWheelAccessibilityService.isConnected.
         */
        @Volatile
        var isConnected: Boolean = false
            private set

        /**
         * Learn mode: when true, the next steering-wheel key is captured into [capturedKey] instead
         * of toggling projection, and every key is consumed so its native action can't fire. Set by
         * the settings learn dialog; cleared by the service on CAPTURE or by the dialog on dismiss.
         */
        @Volatile
        var learnMode: Boolean = false

        /** Result of a learn-mode key press; the settings dialog (same process) collects this. */
        data class CaptureResult(val keyCode: Int, val assignable: Boolean)

        /** Last captured key while learning; null = nothing captured yet. */
        val capturedKey = MutableStateFlow<CaptureResult?>(null)
    }
}
