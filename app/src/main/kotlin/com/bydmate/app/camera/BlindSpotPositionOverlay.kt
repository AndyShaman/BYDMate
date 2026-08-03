package com.bydmate.app.camera

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.bydmate.app.R
import kotlin.math.roundToInt

/**
 * Drag-to-place stand-in for the blind-spot PiP: a window of exactly the size the camera window
 * will have, which the driver moves with a finger. ACTION_UP writes the corner into the feature's
 * own SharedPreferences, and [BlindSpotController] picks it up on its next tick.
 *
 * The window catches touches (no FLAG_NOT_TOUCHABLE — same as the preview windows, where stock
 * AOSP would clamp a touch-transparent overlay to alpha 0.8). Geometry comes from the real
 * display metrics, not from the app's own: in split-screen the app's metrics follow its window and
 * the saved position would land somewhere else on the next show.
 */
class BlindSpotPositionOverlay {
    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hide() }
    private val retryHide = Runnable { hide() }

    private var context: Context? = null
    private var wm: WindowManager? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** Told when the window actually went away, including the idle auto-hide the caller cannot
     *  see otherwise. Called on Main. */
    var onHidden: (() -> Unit)? = null

    /** Finger offset inside the window, captured on ACTION_DOWN. */
    private var grabX = 0f
    private var grabY = 0f

    private var removeAttempts = 0

    val isShown: Boolean get() = view != null

    /** True when the window is up; false when WindowManager refused it. */
    fun show(context: Context): Boolean {
        if (view != null) return true
        val metrics = realMetrics(context)
        val rect = placement(context, metrics)
        val layoutParams = WindowManager.LayoutParams(
            rect.width(), rect.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left
            y = rect.top
        }
        val frame = buildView(context, metrics)
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            windowManager.addView(frame, layoutParams)
        } catch (e: Throwable) {
            Log.w(TAG, "position overlay failed: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        this.context = context
        wm = windowManager
        view = frame
        params = layoutParams
        removeAttempts = 0
        handler.postDelayed(autoHide, AUTO_HIDE_MS)
        return true
    }

    fun hide() {
        handler.removeCallbacks(autoHide)
        handler.removeCallbacks(retryHide)
        val frame = view ?: return
        frame.setOnTouchListener(null)
        val error = runCatching { wm?.removeView(frame) }.exceptionOrNull()
        if (error != null && error !is IllegalArgumentException) {
            Log.w(TAG, "position overlay remove failed: ${error.javaClass.simpleName}: ${error.message}")
            removeAttempts++
            if (removeAttempts < MAX_REMOVE_ATTEMPTS) {
                // The references stay: dropping them would leave an opaque touchable window on
                // screen with nothing left able to reach it.
                handler.postDelayed(retryHide, REMOVE_RETRY_MS)
                return
            }
            Log.w(TAG, "position overlay stuck after $removeAttempts removes; dropping references")
        }
        removeAttempts = 0
        view = null
        params = null
        wm = null
        context = null
        onHidden?.invoke()
    }

    /** Re-applies the size after the width slider moved, keeping the window on screen. */
    fun refreshSize() {
        val ctx = context ?: return
        val frame = view ?: return
        val p = params ?: return
        val metrics = realMetrics(ctx)
        val size = BlindSpotPreferences.pipSize(metrics.widthPixels, prefs(ctx).widthPct())
        p.width = size.width
        p.height = size.height
        p.x = p.x.coerceIn(0, (metrics.widthPixels - size.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (metrics.heightPixels - size.height).coerceAtLeast(0))
        runCatching { wm?.updateViewLayout(frame, p) }.exceptionOrNull()?.let {
            Log.w(TAG, "position overlay resize failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun buildView(context: Context, metrics: DisplayMetrics): View {
        val hint = TextView(context).apply {
            text = context.getString(R.string.settings_blindspot_position_drag)
            setTextColor(Color.WHITE)
            textSize = HINT_TEXT_SP
        }
        val border = View(context).apply {
            background = GradientDrawable().apply {
                setStroke(
                    (BORDER_DP * context.resources.displayMetrics.density).roundToInt(),
                    BORDER_COLOR,
                )
                setColor(Color.TRANSPARENT)
            }
        }
        return FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(hint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
            addView(border, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setOnTouchListener { _, event -> onTouch(event, metrics) }
        }
    }

    private fun onTouch(event: MotionEvent, metrics: DisplayMetrics): Boolean {
        val frame = view ?: return false
        val p = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                grabX = event.rawX - p.x
                grabY = event.rawY - p.y
                // The driver is placing the window; the idle timer starts over.
                handler.removeCallbacks(autoHide)
            }
            MotionEvent.ACTION_MOVE -> {
                p.x = (event.rawX - grabX).roundToInt()
                    .coerceIn(0, (metrics.widthPixels - p.width).coerceAtLeast(0))
                p.y = (event.rawY - grabY).roundToInt()
                    .coerceIn(0, (metrics.heightPixels - p.height).coerceAtLeast(0))
                runCatching { wm?.updateViewLayout(frame, p) }.exceptionOrNull()?.let {
                    Log.w(TAG, "position overlay move failed: ${it.javaClass.simpleName}: ${it.message}")
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                context?.let {
                    prefs(it).edit()
                        .putInt(BlindSpotPreferences.KEY_PIP_X_PX, p.x)
                        .putInt(BlindSpotPreferences.KEY_PIP_Y_PX, p.y)
                        .apply()
                }
                handler.postDelayed(autoHide, AUTO_HIDE_MS)
            }
            else -> return false
        }
        return true
    }

    /** Saved corner clamped to the screen, or the default slot while nothing was placed yet. */
    private fun placement(context: Context, metrics: DisplayMetrics): Rect {
        val prefs = prefs(context)
        val widthPct = prefs.widthPct()
        val x = prefs.getInt(BlindSpotPreferences.KEY_PIP_X_PX, BlindSpotPreferences.UNSET_PX)
        val y = prefs.getInt(BlindSpotPreferences.KEY_PIP_Y_PX, BlindSpotPreferences.UNSET_PX)
        if (x == BlindSpotPreferences.UNSET_PX || y == BlindSpotPreferences.UNSET_PX) {
            return BlindSpotPreferences.defaultPipRect(
                metrics.widthPixels, metrics.heightPixels, widthPct)
        }
        val size = BlindSpotPreferences.pipSize(metrics.widthPixels, widthPct)
        val left = x.coerceIn(0, (metrics.widthPixels - size.width).coerceAtLeast(0))
        val top = y.coerceIn(0, (metrics.heightPixels - size.height).coerceAtLeast(0))
        return Rect(left, top, left + size.width, top + size.height)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(BlindSpotPreferences.PREFS_NAME, Context.MODE_PRIVATE)

    private fun SharedPreferences.widthPct(): Int = getInt(
        BlindSpotPreferences.KEY_PIP_WIDTH_PCT, BlindSpotPreferences.DEFAULT_PIP_WIDTH_PCT)

    private fun realMetrics(context: Context): DisplayMetrics {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY) ?: return context.resources.displayMetrics
        @Suppress("DEPRECATION")
        return DisplayMetrics().also { display.getRealMetrics(it) }
    }

    private companion object {
        const val TAG = "BlindSpot"

        /** Left up by mistake, the window would sit over the navigation; it takes itself away. */
        const val AUTO_HIDE_MS = 60_000L

        const val REMOVE_RETRY_MS = 1_000L
        const val MAX_REMOVE_ATTEMPTS = 5

        const val BORDER_DP = 12f
        const val BORDER_COLOR = 0xFFFF7A00.toInt()
        const val HINT_TEXT_SP = 16f
    }
}
