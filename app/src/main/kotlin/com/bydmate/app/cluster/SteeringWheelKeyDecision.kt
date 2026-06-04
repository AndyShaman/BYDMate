package com.bydmate.app.cluster

/**
 * Right steering-wheel star, SHORT press (KEYCODE_AUTO_R_CUSTOM_KEY). Validated on Leopard 3:
 * short=351, long=352 are DIFFERENT keycodes the MCU emits, so a long-press never matches the
 * trigger and reaches the native hold-menu. This is the DEFAULT trigger; the user may reassign it.
 */
const val RIGHT_STAR_KEYCODE = 351

/** Default trigger keycode for a fresh install / Leopard 3 (the right star). */
const val DEFAULT_TRIGGER_KEYCODE = RIGHT_STAR_KEYCODE

/**
 * Keycodes that must NOT be assignable as the trigger — assigning one would steal an important or
 * safety-critical function while the feature is on. Pure literals (no android.view.KeyEvent import)
 * so this module stays unit-testable without Robolectric. Numbers are the standard Android keycodes.
 */
val NON_ASSIGNABLE_KEYCODES: Set<Int> = setOf(
    24, // KEYCODE_VOLUME_UP
    25, // KEYCODE_VOLUME_DOWN
    26, // KEYCODE_POWER
    4,  // KEYCODE_BACK
    3,  // KEYCODE_HOME
    82, // KEYCODE_MENU
    5,  // KEYCODE_CALL
    6,  // KEYCODE_ENDCALL
    310, // 360-view button (parking cameras) — code from OpenBYD
    309, // cluster carousel (native widget switch) on Leopard 3
)

/** True if [keyCode] may be assigned as the projection trigger (not in [NON_ASSIGNABLE_KEYCODES]). */
fun isAssignable(keyCode: Int): Boolean = keyCode !in NON_ASSIGNABLE_KEYCODES

/** What the a11y filter should do with a key event in normal (non-learning) operation. */
enum class StarDecision { CONSUME_AND_TOGGLE, CONSUME, PASS_THROUGH }

/**
 * Pure gate for SteeringWheelKeyService.onKeyEvent in normal operation. Pure Int/Boolean so it is
 * trivially unit-tested.
 * - switch off, or any key other than [triggerKeyCode] → PASS_THROUGH (native intact).
 * - trigger key, enabled: CONSUME_AND_TOGGLE on the DOWN edge (flip projection once),
 *   CONSUME on the UP edge (swallow it so the native short action never fires).
 */
fun starDecision(keyCode: Int, isDown: Boolean, enabled: Boolean, triggerKeyCode: Int): StarDecision {
    if (!enabled || keyCode != triggerKeyCode) return StarDecision.PASS_THROUGH
    return if (isDown) StarDecision.CONSUME_AND_TOGGLE else StarDecision.CONSUME
}

/**
 * What the a11y filter should do while LEARNING a new trigger. The service consumes the event in
 * ALL three cases (so the captured button's native action never fires mid-learn); the difference is
 * the side effect:
 * - CAPTURE: an assignable key was pressed — publish it and leave learn mode.
 * - REJECT:  a blocked key (system / 360-view / carousel) was pressed — surface "can't assign",
 *            stay in learn mode for another try.
 * - CONSUME: a non-down edge (UP/MULTIPLE) — swallow silently, no side effect.
 */
enum class LearnAction { CAPTURE, REJECT, CONSUME }

/**
 * Pure learn-mode gate. Called by the service only while learn mode is active.
 * Only the DOWN edge decides CAPTURE vs REJECT (via [isAssignable]); other edges are CONSUME.
 */
fun learnDecision(keyCode: Int, isDown: Boolean): LearnAction {
    if (!isDown) return LearnAction.CONSUME
    return if (isAssignable(keyCode)) LearnAction.CAPTURE else LearnAction.REJECT
}
