package com.bydmate.app.camera

import android.content.Context
import android.graphics.Rect
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Shape of a main-screen camera window: the width slider plus the upright flag (#207). */
data class PipShape(val widthPct: Int, val rotated: Boolean)

/**
 * Blind-spot feature settings. The settings card writes the same SharedPreferences file
 * directly (the projection cards next to it work that way too), so every getter re-reads —
 * a change lands on the next fast-loop tick without any notification plumbing.
 */
@Singleton
class BlindSpotPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)
    val thresholdKmh: Int get() = prefs.getInt(KEY_THRESHOLD_KMH, DEFAULT_THRESHOLD_KMH)
    val pipWidthPct: Int get() = prefs.getInt(KEY_PIP_WIDTH_PCT, DEFAULT_PIP_WIDTH_PCT)

    /** Top-left corner in real display pixels; [UNSET_PX] until the user drags the window. */
    val pipXPx: Int get() = prefs.getInt(KEY_PIP_X_PX, UNSET_PX)
    val pipYPx: Int get() = prefs.getInt(KEY_PIP_Y_PX, UNSET_PX)

    /** Same for the left window (#183); [UNSET_PX] means "mirror the right one". */
    val leftPipXPx: Int get() = prefs.getInt(KEY_LEFT_PIP_X_PX, UNSET_PX)
    val leftPipYPx: Int get() = prefs.getInt(KEY_LEFT_PIP_Y_PX, UNSET_PX)
    val bsdGlow: Boolean get() = prefs.getBoolean(KEY_BSD_GLOW, true)

    /** Opt-in (#183): keep the left camera on the main screen even when a cluster panel exists. */
    val bothOnMain: Boolean get() = prefs.getBoolean(KEY_BOTH_ON_MAIN, false)

    /** Opt-in (#207): portrait main-screen windows with the picture turned 90°, like a mirror. */
    val pipRotate90: Boolean get() = prefs.getBoolean(KEY_PIP_ROTATE_90, false)

    val pipShape: PipShape get() = PipShape(pipWidthPct, pipRotate90)

    companion object {
        const val PREFS_NAME = "blind_spot"
        const val KEY_ENABLED = "enabled"
        const val KEY_THRESHOLD_KMH = "threshold_kmh"
        const val KEY_PIP_WIDTH_PCT = "pip_width_pct"
        const val KEY_PIP_X_PX = "pip_x_px"
        const val KEY_PIP_Y_PX = "pip_y_px"
        const val KEY_LEFT_PIP_X_PX = "left_pip_x_px"
        const val KEY_LEFT_PIP_Y_PX = "left_pip_y_px"
        const val KEY_BSD_GLOW = "bsd_glow"
        const val KEY_BOTH_ON_MAIN = "both_on_main"
        const val KEY_PIP_ROTATE_90 = "pip_rotate_90"

        /** Position is stored in absolute pixels, so "never placed" needs its own value. */
        const val UNSET_PX = -1

        const val DEFAULT_THRESHOLD_KMH = 20
        const val DEFAULT_PIP_WIDTH_PCT = 36

        /** 0 = show from standstill: the fast loop and the warm camera then stay armed whenever the
         *  car is on and out of reverse, which is what a driver who picks 0 is asking for. */
        const val MIN_THRESHOLD_KMH = 0
        const val MAX_THRESHOLD_KMH = 60
        const val MIN_PIP_WIDTH_PCT = 20
        const val MAX_PIP_WIDTH_PCT = 60

        /**
         * 16:9 window sized to [widthPct] % of the display width, upright (#207) or not.
         * An upright window is as tall as a flat one is wide, so a wide slider would run it off
         * the top and bottom of the screen: it is then scaled down whole, keeping 16:9, to
         * exactly the display height.
         */
        fun pipSize(displayW: Int, displayH: Int, shape: PipShape): Size {
            val width = (displayW * shape.widthPct / 100f).roundToInt()
            val height = width * 9 / 16
            val size = if (shape.rotated) Size(height, width) else Size(width, height)
            if (size.height <= displayH || displayH <= 0) return size
            return Size((size.width * displayH.toFloat() / size.height).roundToInt(), displayH)
        }

        /** Right edge, vertically centered: the PiP sits away from the navigation card. */
        fun defaultPipRect(displayW: Int, displayH: Int, shape: PipShape): Rect {
            val size = pipSize(displayW, displayH, shape)
            val left = (displayW - size.width).coerceAtLeast(0)
            val top = ((displayH - size.height) / 2).coerceAtLeast(0)
            return Rect(left, top, left + size.width, top + size.height)
        }

        /** The saved corner clamped to the screen, or the default slot while nothing was placed. */
        fun placedPipRect(displayW: Int, displayH: Int, shape: PipShape, x: Int, y: Int): Rect {
            if (x == UNSET_PX || y == UNSET_PX) return defaultPipRect(displayW, displayH, shape)
            val size = pipSize(displayW, displayH, shape)
            val left = x.coerceIn(0, (displayW - size.width).coerceAtLeast(0))
            val top = y.coerceIn(0, (displayH - size.height).coerceAtLeast(0))
            return Rect(left, top, left + size.width, top + size.height)
        }

        /** Reflection of a main-screen rect across the vertical axis of the screen. */
        fun mirrorRect(rect: Rect, displayW: Int): Rect {
            val left = (displayW - rect.right).coerceAtLeast(0)
            return Rect(left, rect.top, left + rect.width(), rect.bottom)
        }

        /**
         * Left window (#183): its own corner once the driver placed it, the mirror of [rightRect]
         * until then — so a driver who only tunes the right camera gets a symmetric pair.
         * The controller and the drag overlay both come here, so the two always agree.
         */
        fun leftPipRect(
            displayW: Int,
            displayH: Int,
            shape: PipShape,
            leftX: Int,
            leftY: Int,
            rightRect: Rect,
        ): Rect =
            if (leftX == UNSET_PX || leftY == UNSET_PX) mirrorRect(rightRect, displayW)
            else placedPipRect(displayW, displayH, shape, leftX, leftY)
    }
}
