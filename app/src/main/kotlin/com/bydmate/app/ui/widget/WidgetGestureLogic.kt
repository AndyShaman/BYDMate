package com.bydmate.app.ui.widget

/**
 * Pure tap classification for the floating widget. Kept Android-free so it is
 * unit-tested like DragGestureLogic.
 */
object WidgetGestureLogic {

    /** True when the touch falls in the left [fraction] of a measured view. */
    fun isLeftZone(eventX: Float, viewWidth: Int, fraction: Float): Boolean =
        viewWidth > 0 && eventX < viewWidth * fraction

    /**
     * True when a tap whose ACTION_UP is at [nowMs] follows a previous tap-up at
     * [prevUpMs] within [windowMs] (inclusive). prevUpMs <= 0 means no prior tap.
     */
    fun isWithinDoubleTapWindow(prevUpMs: Long, nowMs: Long, windowMs: Long): Boolean =
        prevUpMs > 0L && (nowMs - prevUpMs) <= windowMs
}
