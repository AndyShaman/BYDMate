package com.bydmate.app.cluster

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One captured key event from the steering-wheel accessibility filter. */
data class KeyCapture(
    val keyCode: Int,
    val action: Int,          // KeyEvent.ACTION_DOWN=0 / ACTION_UP=1
    val isLongPress: Boolean, // KeyEvent.isLongPress() (FLAG_LONG_PRESS)
    val repeatCount: Int,     // >0 also signals a held key
)

/**
 * Process-wide bridge between SteeringWheelKeyService (app process) and the
 * diagnostic UI. Phase 0: the service only OBSERVES; [consumeEvents] is an opt-in
 * toggle to test whether returning true from onKeyEvent breaks the native long-press
 * menu. No persistence, no mapping — that is Phase 4.
 */
object SteeringWheelKeyState {
    private val _capturedKeyEvent = MutableStateFlow<KeyCapture?>(null)
    val capturedKeyEvent: StateFlow<KeyCapture?> = _capturedKeyEvent.asStateFlow()

    @Volatile var consumeEvents: Boolean = false
    @Volatile var isConnected: Boolean = false

    fun record(capture: KeyCapture) {
        _capturedKeyEvent.value = capture
    }
}
