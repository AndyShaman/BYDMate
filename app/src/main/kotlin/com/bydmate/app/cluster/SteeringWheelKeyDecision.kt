package com.bydmate.app.cluster

/**
 * Right steering-wheel star, SHORT press (KEYCODE_AUTO_R_CUSTOM_KEY). Validated on Leopard 3
 * (docs/superpowers/plans/2026-05-29-cluster-projection-phase0.md): short=351, long=352 are
 * DIFFERENT keycodes the MCU emits, so the long-press is never matched here and reaches the
 * native hold-menu (where the user picks what the short press does natively).
 */
const val RIGHT_STAR_KEYCODE = 351

/** What the a11y filter should do with a key event. */
enum class StarDecision { CONSUME_AND_TOGGLE, CONSUME, PASS_THROUGH }

/**
 * Pure gate for SteeringWheelKeyService.onKeyEvent. Pure Int/Boolean so it is trivially unit-tested.
 * - switch off, or any key other than the right-star short press → PASS_THROUGH (native intact).
 * - right-star short, enabled: CONSUME_AND_TOGGLE on the DOWN edge (flip projection once),
 *   CONSUME on the UP edge (swallow it so the native short action never fires).
 */
fun starDecision(keyCode: Int, isDown: Boolean, enabled: Boolean): StarDecision {
    if (!enabled || keyCode != RIGHT_STAR_KEYCODE) return StarDecision.PASS_THROUGH
    return if (isDown) StarDecision.CONSUME_AND_TOGGLE else StarDecision.CONSUME
}
