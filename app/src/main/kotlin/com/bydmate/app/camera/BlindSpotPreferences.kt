package com.bydmate.app.camera

import android.content.Context
import android.graphics.Rect
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

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
    val bsdGlow: Boolean get() = prefs.getBoolean(KEY_BSD_GLOW, true)

    companion object {
        const val PREFS_NAME = "blind_spot"
        const val KEY_ENABLED = "enabled"
        const val KEY_THRESHOLD_KMH = "threshold_kmh"
        const val KEY_PIP_WIDTH_PCT = "pip_width_pct"
        const val KEY_PIP_X_PX = "pip_x_px"
        const val KEY_PIP_Y_PX = "pip_y_px"
        const val KEY_BSD_GLOW = "bsd_glow"

        /** Position is stored in absolute pixels, so "never placed" needs its own value. */
        const val UNSET_PX = -1

        const val DEFAULT_THRESHOLD_KMH = 20
        const val DEFAULT_PIP_WIDTH_PCT = 36

        const val MIN_THRESHOLD_KMH = 5
        const val MAX_THRESHOLD_KMH = 60
        const val MIN_PIP_WIDTH_PCT = 20
        const val MAX_PIP_WIDTH_PCT = 60

        /** 16:9 window sized to [widthPct] % of the display width. */
        fun pipSize(displayW: Int, widthPct: Int): Size {
            val width = (displayW * widthPct / 100f).roundToInt()
            return Size(width, width * 9 / 16)
        }

        /** Right edge, vertically centered: the PiP sits away from the navigation card. */
        fun defaultPipRect(displayW: Int, displayH: Int, widthPct: Int): Rect {
            val size = pipSize(displayW, widthPct)
            val left = (displayW - size.width).coerceAtLeast(0)
            val top = ((displayH - size.height) / 2).coerceAtLeast(0)
            return Rect(left, top, left + size.width, top + size.height)
        }
    }
}
