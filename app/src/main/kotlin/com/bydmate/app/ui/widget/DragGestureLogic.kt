package com.bydmate.app.ui.widget

import kotlin.math.hypot

/**
 * Pure helpers for the floating-widget drag gesture. Kept separate from
 * WidgetController so we can unit-test without Android.
 */
object DragGestureLogic {

    /** True if total finger displacement is within [thresholdPx] (tap, not drag). */
    fun isTap(downX: Int, downY: Int, upX: Int, upY: Int, thresholdPx: Int): Boolean {
        val dist = hypot((upX - downX).toDouble(), (upY - downY).toDouble())
        return dist <= thresholdPx
    }

    /** True if widget centre is within [radiusPx] of trash centre. Boundary inclusive. */
    fun isInsideTrash(
        widgetCx: Int,
        widgetCy: Int,
        trashCx: Int,
        trashCy: Int,
        radiusPx: Int,
    ): Boolean {
        val dist = hypot((widgetCx - trashCx).toDouble(), (widgetCy - trashCy).toDouble())
        return dist <= radiusPx
    }

    /**
     * Keeps the widget fully visible on screen. Returns clamped (x, y).
     * Origin is top-left of the screen, x/y are the widget's top-left.
     */
    fun clampToScreen(
        x: Int,
        y: Int,
        widgetWidth: Int,
        widgetHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Int, Int> {
        val maxX = (screenWidth - widgetWidth).coerceAtLeast(0)
        val maxY = (screenHeight - widgetHeight).coerceAtLeast(0)
        val cx = x.coerceIn(0, maxX)
        val cy = y.coerceIn(0, maxY)
        return cx to cy
    }
}
