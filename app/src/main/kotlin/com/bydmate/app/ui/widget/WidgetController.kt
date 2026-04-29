package com.bydmate.app.ui.widget

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bydmate.app.MainActivity
import com.bydmate.app.service.TrackingService
import com.bydmate.app.ui.overlay.OverlayLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import com.bydmate.app.domain.calculator.ConsumptionAggregator
import com.bydmate.app.domain.calculator.ConsumptionState
import com.bydmate.app.domain.calculator.Trend
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Singleton controller that owns the floating-widget + trash-zone overlay
 * lifecycle. Called from TrackingService when the preference + permission
 * allow, and from ActivityLifecycleCallbacks to show/hide across fore/back.
 */
object WidgetController {

    private const val TAG = "WidgetController"

    // Widget dimensions in dp — matches FloatingWidgetView layout
    private const val WIDGET_WIDTH_DP = 260
    private const val WIDGET_HEIGHT_DP = 108
    private const val DRAG_THRESHOLD_DP = 8
    private const val TRASH_RADIUS_DP = 48
    private const val LONG_PRESS_MS = 1500L

    @Volatile private var appForegrounded: Boolean = false

    private var wm: WindowManager? = null
    private var widgetView: ComposeView? = null
    private var widgetLifecycle: OverlayLifecycleOwner? = null
    private var widgetParams: WindowManager.LayoutParams? = null

    private var trashView: ComposeView? = null
    private var trashLifecycle: OverlayLifecycleOwner? = null

    private var dataScope: CoroutineScope? = null
    private var dataJob: Job? = null
    private lateinit var prefsAlphaFlow: kotlinx.coroutines.flow.Flow<Float>

    // Compose state for the widget data
    private var socState = mutableStateOf<Int?>(null)
    private var rangeState = mutableStateOf<Double?>(null)
    private var consumptionState = mutableStateOf<Double?>(null)
    private var trendState = mutableStateOf(Trend.NONE)
    private var sessionStartedAtState = mutableStateOf<Long?>(null)
    private var tripDistanceKmState = mutableStateOf<Double?>(null)
    private var insideTempState = mutableStateOf<Int?>(null)
    private var batTempState = mutableStateOf<Int?>(null)
    private var voltsState = mutableStateOf<Double?>(null)
    private var alphaState = mutableStateOf(1.0f)
    private var trashActive = mutableStateOf(false)

    @Synchronized
    fun attach(context: Context) {
        if (appForegrounded) return     // race-guard: our app is on screen
        if (widgetView != null) return  // already attached

        val appCtx = context.applicationContext
        val prefs = WidgetPreferences(appCtx)
        // long-press hid widget until user opens MainActivity
        if (prefs.isHiddenUntilAppLaunch()) return

        val windowManager = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = windowManager

        prefsAlphaFlow = prefs.alphaFlow()
        val metrics = appCtx.resources.displayMetrics

        val widgetWpx = dp(appCtx, WIDGET_WIDTH_DP)
        val widgetHpx = dp(appCtx, WIDGET_HEIGHT_DP)
        val (startX, startY) = resolveStartPosition(prefs, metrics, widgetWpx, widgetHpx)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }
        widgetParams = params

        val lifecycleOwner = OverlayLifecycleOwner().also { it.onCreate() }
        widgetLifecycle = lifecycleOwner

        val compose = ComposeView(appCtx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                FloatingWidgetView(
                    soc = socState.value,
                    rangeKm = rangeState.value,
                    consumption = consumptionState.value,
                    trend = trendState.value,
                    sessionStartedAt = sessionStartedAtState.value,
                    tripDistanceKm = tripDistanceKmState.value,
                    insideTemp = insideTempState.value,
                    batTemp = batTempState.value,
                    voltage12v = voltsState.value,
                    alpha = alphaState.value,
                )
            }
            setOnTouchListener(WidgetTouchListener(appCtx, prefs, metrics, widgetWpx, widgetHpx))
        }
        widgetView = compose

        try {
            windowManager.addView(compose, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
            detach()
            return
        }

        startDataSubscription()
    }

    @Synchronized
    fun setAppForegrounded(foreground: Boolean) {
        appForegrounded = foreground
        if (foreground) detach()
    }

    @Synchronized
    fun detach() {
        dataJob?.cancel()
        dataJob = null
        dataScope?.cancel()
        dataScope = null

        hideTrashZone()

        widgetView?.let { v ->
            try {
                wm?.removeView(v)
            } catch (e: Exception) {
                Log.w(TAG, "removeView widget: ${e.message}")
            }
        }
        widgetLifecycle?.onDestroy()
        widgetView = null
        widgetLifecycle = null
        widgetParams = null
        wm = null
    }

    private fun startDataSubscription() {
        val scope = CoroutineScope(Dispatchers.Main)
        dataScope = scope
        // Stock combine(...) is typed only up to 5 flows — bundle consumption + alpha
        // into one derived pair so we stay under the limit.
        val uiFlow = ConsumptionAggregator.state.combine(prefsAlphaFlow) { c, a -> c to a }
        dataJob = scope.launch {
            combine(
                TrackingService.lastData,
                TrackingService.lastRangeKm,
                TrackingService.sessionStartedAt,
                TrackingService.tripDistanceKm,
                uiFlow,
            ) { data, range, sessionStart, tripDist, bundled ->
                WidgetSnapshot(
                    data = data,
                    range = range,
                    sessionStartedAt = sessionStart,
                    tripDistanceKm = tripDist,
                    consumption = bundled.first,
                    alpha = bundled.second,
                )
            }.collect { snap ->
                socState.value = snap.data?.soc
                rangeState.value = snap.range
                insideTempState.value = snap.data?.insideTemp
                batTempState.value = snap.data?.avgBatTemp
                voltsState.value = snap.data?.voltage12v
                sessionStartedAtState.value = snap.sessionStartedAt
                tripDistanceKmState.value = snap.tripDistanceKm
                consumptionState.value = snap.consumption.displayValue
                trendState.value = snap.consumption.trend
                alphaState.value = snap.alpha

                // Hide widget while reverse gear is engaged (issue #5).
                // DiPars gear: 1=P, 2=R, 3=N, 4=D.
                val inReverse = snap.data?.gear == 2
                widgetView?.visibility = if (inReverse) View.GONE else View.VISIBLE
                if (inReverse) hideTrashZone()
            }
        }
    }

    private data class WidgetSnapshot(
        val data: com.bydmate.app.data.remote.DiParsData?,
        val range: Double?,
        val sessionStartedAt: Long?,
        val tripDistanceKm: Double?,
        val consumption: ConsumptionState,
        val alpha: Float,
    )

    // --- Trash zone ---

    internal fun showTrashZone(context: Context) {
        if (trashView != null) return
        val windowManager = wm ?: return

        val lifecycleOwner = OverlayLifecycleOwner().also { it.onCreate() }
        trashLifecycle = lifecycleOwner

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        val compose = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                TrashZoneView(active = trashActive.value)
            }
        }
        trashView = compose

        try {
            windowManager.addView(compose, params)
        } catch (e: Exception) {
            Log.w(TAG, "addView trash: ${e.message}")
        }
    }

    internal fun hideTrashZone() {
        trashActive.value = false
        trashView?.let { v ->
            try { wm?.removeView(v) } catch (_: Exception) {}
        }
        trashLifecycle?.onDestroy()
        trashView = null
        trashLifecycle = null
    }

    internal fun setTrashActive(active: Boolean) {
        trashActive.value = active
    }

    // --- Helpers ---

    private fun resolveStartPosition(
        prefs: WidgetPreferences,
        metrics: DisplayMetrics,
        widgetWpx: Int,
        widgetHpx: Int,
    ): Pair<Int, Int> {
        val savedX = prefs.getX()
        val savedY = prefs.getY()
        return if (savedX == 0 && savedY == 0) {
            // Default: centered on screen so user notices it immediately.
            val x = (metrics.widthPixels - widgetWpx) / 2
            val y = (metrics.heightPixels - widgetHpx) / 2
            DragGestureLogic.clampToScreen(
                x = x,
                y = y,
                widgetWidth = widgetWpx,
                widgetHeight = widgetHpx,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
            )
        } else {
            DragGestureLogic.clampToScreen(
                x = savedX,
                y = savedY,
                widgetWidth = widgetWpx,
                widgetHeight = widgetHpx,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
            )
        }
    }

    private fun dp(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    private fun dpFromMetrics(metrics: DisplayMetrics, dp: Int): Int =
        (dp * metrics.density).toInt()

    // --- Touch handling ---

    private class WidgetTouchListener(
        private val context: Context,
        private val prefs: WidgetPreferences,
        private val metrics: DisplayMetrics,
        private val widgetWpx: Int,
        private val widgetHpx: Int,
    ) : android.view.View.OnTouchListener {

        private var downX = 0f
        private var downY = 0f
        private var initialParamX = 0
        private var initialParamY = 0
        private var dragging = false
        private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null

        override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
            val params = widgetParams ?: return false
            val windowManager = wm ?: return false
            val thresholdPx = dpFromMetrics(metrics, DRAG_THRESHOLD_DP)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    initialParamX = params.x
                    initialParamY = params.y
                    dragging = false
                    // Schedule long-press: if finger stays still for LONG_PRESS_MS,
                    // hide widget until user opens BYDMate. Canceled in ACTION_MOVE
                    // as soon as finger travels past drag threshold, and in ACTION_UP.
                    val runnable = Runnable {
                        if (dragging) return@Runnable
                        prefs.setHiddenUntilAppLaunch(true)
                        detach()
                        try {
                            android.widget.Toast.makeText(
                                context,
                                "Виджет скрыт. Откройте BYDMate — вернётся.",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } catch (_: Exception) {}
                    }
                    longPressRunnable = runnable
                    longPressHandler.postDelayed(runnable, LONG_PRESS_MS)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    val totalDistPx = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toInt()
                    if (!dragging && totalDistPx > thresholdPx) {
                        dragging = true
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        longPressRunnable = null
                        showTrashZone(context)
                    }
                    if (dragging) {
                        val (clampedX, clampedY) = DragGestureLogic.clampToScreen(
                            x = initialParamX + dx,
                            y = initialParamY + dy,
                            widgetWidth = widgetWpx,
                            widgetHeight = widgetHpx,
                            screenWidth = metrics.widthPixels,
                            screenHeight = metrics.heightPixels,
                        )
                        params.x = clampedX
                        params.y = clampedY
                        try {
                            windowManager.updateViewLayout(v, params)
                        } catch (_: Exception) {}

                        // Trash zone hit-test (center-to-center)
                        val widgetCx = clampedX + widgetWpx / 2
                        val widgetCy = clampedY + widgetHpx / 2
                        val trashCx = metrics.widthPixels / 2
                        val trashRadiusPx = dpFromMetrics(metrics, TRASH_RADIUS_DP)
                        val trashBottomMarginPx = dpFromMetrics(metrics, 24 + 36)  // 24dp from bottom + half of 72dp halo
                        val trashCy = metrics.heightPixels - trashBottomMarginPx
                        val inside = DragGestureLogic.isInsideTrash(
                            widgetCx = widgetCx, widgetCy = widgetCy,
                            trashCx = trashCx, trashCy = trashCy,
                            radiusPx = trashRadiusPx,
                        )
                        setTrashActive(inside)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    val wasTap = DragGestureLogic.isTap(0, 0, dx, dy, thresholdPx)

                    if (wasTap) {
                        // Tap → launch MainActivity
                        try {
                            val intent = Intent(context, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.w(TAG, "tap launch failed: ${e.message}")
                        }
                    } else {
                        // End drag
                        if (trashActive.value) {
                            // Dropped into trash → hide + disable
                            prefs.setEnabled(false)
                            detach()
                            return true
                        } else {
                            prefs.savePosition(params.x, params.y)
                        }
                        hideTrashZone()
                    }
                    dragging = false
                    return true
                }
            }
            return false
        }
    }
}
