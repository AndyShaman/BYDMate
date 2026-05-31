package com.bydmate.app.cluster

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.EntryPointAccessors

/**
 * Steering-wheel key filter. Still records the last key event for the diagnostic screen
 * (learn mode). Phase 2: a short press of the mapped button ([RIGHT_STAR_KEYCODE]) cycles
 * the cluster projection and is consumed; its long-press/repeat is passed through so the
 * native action-selection menu stays intact. All other keys keep the Phase 0 behavior.
 */
class SteeringWheelKeyService : AccessibilityService() {

    private var cachedEntryPoint: ClusterEntryPoint? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        SteeringWheelKeyState.isConnected = true
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS // 32
        serviceInfo = info
        Log.d(TAG, "connected; filtering key events (learn mode)")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        SteeringWheelKeyState.record(
            KeyCapture(
                keyCode = event.keyCode,
                action = event.action,
                isLongPress = event.isLongPress,
                repeatCount = event.repeatCount,
            )
        )
        Log.d(TAG, "key keycode=${event.keyCode} action=${event.action} long=${event.isLongPress} repeat=${event.repeatCount}")

        // Phase 2: right star short press cycles the cluster projection (our action → consume).
        if (isClusterCycleTrigger(event.keyCode, event.action, event.isLongPress, event.repeatCount)) {
            val ep = entryPoint()
            ClusterProjectionManager.cycleMode(applicationContext, ep.helperClient(), ep.helperBootstrap())
            return true
        }
        // Long-press / repeat of the mapped button → native action-selection menu MUST stay (pass through).
        if (isMappedButton(event.keyCode)) return false

        // All other keys: Phase 0 diagnostic behavior (observe unless the probe toggle is on).
        return SteeringWheelKeyState.consumeEvents
    }

    private fun entryPoint(): ClusterEntryPoint =
        cachedEntryPoint ?: EntryPointAccessors
            .fromApplication(applicationContext, ClusterEntryPoint::class.java)
            .also { cachedEntryPoint = it }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* unused in Phase 0 */ }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        SteeringWheelKeyState.isConnected = false
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SteeringWheelKeySvc"
    }
}
