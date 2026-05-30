package com.bydmate.app.cluster

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Phase 0 learn-mode key filter. Records the last steering-wheel key event so the
 * diagnostic screen can show its keycode and long-press flags. Does NOT consume events
 * unless [SteeringWheelKeyState.consumeEvents] is explicitly toggled on (to test the
 * native long-press menu). Mapping/binding lands in Phase 4.
 */
class SteeringWheelKeyService : AccessibilityService() {

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
        // Phase 0: observe only. consumeEvents=true is a diagnostic probe for the native menu.
        return SteeringWheelKeyState.consumeEvents
    }

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
