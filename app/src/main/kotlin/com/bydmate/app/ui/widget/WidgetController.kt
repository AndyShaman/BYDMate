package com.bydmate.app.ui.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.service.TrackingService
import com.bydmate.app.ui.overlay.OverlayLifecycleOwner
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import com.bydmate.app.R
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
    // Tap on the left third of the widget launches the user-configured app
    // (default Yandex Navigator) when zoning is enabled in WidgetPreferences;
    // the right two thirds always open BYDMate. Threshold uses the live view
    // width, so it stays proportional across the 70-200% widget size range.
    private const val LEFT_TAP_FRACTION = 1f / 3f
    private const val DOUBLE_TAP_MS = 250L
    private const val BUTTON_DP = WidgetButtonLayout.BUTTON_DP
    private const val GAP_DP = WidgetButtonLayout.GAP_DP

    @Volatile private var appForegrounded: Boolean = false
    @Volatile private var previewMode: Boolean = false

    private var wm: WindowManager? = null
    private var widgetView: ComposeView? = null
    private var widgetLifecycle: OverlayLifecycleOwner? = null
    private var widgetParams: WindowManager.LayoutParams? = null

    // Current widget bounds in px after scale; touch-listener reads these
    // to clamp drag positions correctly when the user resized the widget.
    @Volatile private var currentWidgetWpx: Int = 0
    @Volatile private var currentWidgetHpx: Int = 0

    private var trashView: ComposeView? = null
    private var trashLifecycle: OverlayLifecycleOwner? = null

    private var dataScope: CoroutineScope? = null
    private var dataJob: Job? = null
    private lateinit var prefsAlphaFlow: kotlinx.coroutines.flow.Flow<Float>
    private lateinit var prefsScaleFlow: kotlinx.coroutines.flow.Flow<Float>

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
    private var scaleState = mutableStateOf(1.0f)
    private var trashActive = mutableStateOf(false)

    // Expandable-buttons state. expandedState drives the button layer's slide
    // animation; collapsedX/Y remember the window origin so an edge-clamped
    // expansion can be restored exactly on collapse.
    private var expandedState = mutableStateOf(false)
    @Volatile private var collapsedX: Int = 0
    @Volatile private var collapsedY: Int = 0
    private var buttonLayerView: ComposeView? = null
    private var buttonLifecycle: OverlayLifecycleOwner? = null
    private var rootContainer: android.widget.FrameLayout? = null

    @Synchronized
    fun attach(context: Context) {
        if (appForegrounded && !previewMode) return  // race-guard, but preview wins
        if (widgetView != null) return               // already attached

        val appCtx = context.applicationContext
        val prefs = WidgetPreferences(appCtx)
        // long-press hid widget until user opens MainActivity (preview wins)
        if (prefs.isHiddenUntilAppLaunch() && !previewMode) return

        // AppCompatDelegate.setApplicationLocales does not always refresh the
        // applicationContext's Configuration, so the overlay's ComposeView ends
        // up resolving stringResource against a stale locale. Wrap appCtx with
        // an explicit locale context so the widget always matches the user's
        // chosen language.
        val viewCtx = localizedContext(appCtx)

        val windowManager = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = windowManager

        prefsAlphaFlow = prefs.alphaFlow()
        prefsScaleFlow = prefs.scaleFlow()
        val metrics = viewCtx.resources.displayMetrics

        val initialScale = prefs.getScale()
        scaleState.value = initialScale
        val widgetWpx = (dp(viewCtx, WIDGET_WIDTH_DP) * initialScale).toInt()
        val widgetHpx = (dp(viewCtx, WIDGET_HEIGHT_DP) * initialScale).toInt()
        currentWidgetWpx = widgetWpx
        currentWidgetHpx = widgetHpx
        val (startX, startY) = resolveStartPosition(prefs, metrics, widgetWpx, widgetHpx)

        val params = WindowManager.LayoutParams(
            widgetWpx,
            widgetHpx,
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

        collapsedX = startX
        collapsedY = startY
        expandedState.value = false

        val panelCompose = ComposeView(viewCtx).apply {
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
                    scaleFactor = scaleState.value,
                )
            }
            setOnTouchListener(WidgetTouchListener(viewCtx, prefs, metrics))
        }
        widgetView = panelCompose

        val buttonLifecycleOwner = OverlayLifecycleOwner().also { it.onCreate() }
        buttonLifecycle = buttonLifecycleOwner
        val buttonCompose = ComposeView(viewCtx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(buttonLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(buttonLifecycleOwner)
            setContent {
                WidgetButtonPanel(
                    expanded = expandedState.value,
                    scaleFactor = scaleState.value,
                    onButtonClick = { n -> onWidgetButtonClick(viewCtx, n) },
                )
            }
        }
        buttonLayerView = buttonCompose

        // Transparent container: button layer fills the window; the panel pins to
        // the top-right so the left column + bottom row pockets appear on expand.
        val root = android.widget.FrameLayout(viewCtx)
        root.addView(
            buttonCompose,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            panelCompose,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ),
        )
        rootContainer = root

        try {
            windowManager.addView(root, params)
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
        if (foreground && !previewMode) detach()
    }

    /**
     * Settings UI calls this while user drags the alpha or size slider so the
     * widget pops over Settings, letting the user see the change live. Set to
     * false on screen leave (DisposableEffect.onDispose) to restore normal
     * foreground-detach behavior.
     */
    @Synchronized
    fun setPreviewMode(context: Context, active: Boolean) {
        previewMode = active
        val appCtx = context.applicationContext
        val prefs = WidgetPreferences(appCtx)
        if (active) {
            // Skip preview-attach silently if overlay permission was revoked —
            // attach() would throw on addView. User toggles widget back on
            // through the main switch which handles the permission flow.
            if (prefs.isEnabled() &&
                widgetView == null &&
                android.provider.Settings.canDrawOverlays(appCtx)
            ) attach(appCtx)
        } else if (appForegrounded) {
            detach()
        }
    }

    @Synchronized
    fun detach() {
        dataJob?.cancel()
        dataJob = null
        dataScope?.cancel()
        dataScope = null

        hideTrashZone()

        widgetView?.let { v -> v.setOnTouchListener(null) }
        rootContainer?.let { r ->
            try {
                wm?.removeView(r)
            } catch (e: Exception) {
                Log.w(TAG, "removeView root: ${e.message}")
            }
        }
        widgetLifecycle?.onDestroy()
        buttonLifecycle?.onDestroy()
        widgetView = null
        buttonLayerView = null
        rootContainer = null
        widgetLifecycle = null
        buttonLifecycle = null
        widgetParams = null
        expandedState.value = false
        wm = null
    }

    private fun startDataSubscription() {
        val scope = CoroutineScope(Dispatchers.Main)
        dataScope = scope
        // Stock combine(...) is typed only up to 5 flows — bundle consumption +
        // alpha + scale + cameraActive into one UiBundle so we stay under the limit.
        val uiFlow = combine(
            ConsumptionAggregator.state,
            prefsAlphaFlow,
            prefsScaleFlow,
            TrackingService.cameraActive,
        ) { c, a, s, cam -> UiBundle(c, a, s, cam) }
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
                    consumption = bundled.consumption,
                    alpha = bundled.alpha,
                    scale = bundled.scale,
                    cameraActive = bundled.cameraActive,
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

                if (scaleState.value != snap.scale) {
                    scaleState.value = snap.scale
                    applyScaleChange(snap.scale)
                }

                // Hide widget while the BYD camera surface is up (rear, front
                // auto-pop, 360°, parking app — all map to com.byd.avc).
                val hideForCamera = snap.cameraActive
                // Hide the entire root (panel + button layer) so the 6 buttons
                // don't stay drawn over the camera view when the panel is expanded.
                // Re-show is symmetric: same view, VISIBLE.
                rootContainer?.visibility = if (hideForCamera) View.GONE else View.VISIBLE
                if (hideForCamera) hideTrashZone()
            }
        }
    }

    @Synchronized
    private fun applyScaleChange(scale: Float) {
        val view = widgetView ?: return
        val params = widgetParams ?: return
        val windowManager = wm ?: return
        val ctx = view.context ?: return
        val metrics = ctx.resources.displayMetrics
        val widgetWpx = (dp(ctx, WIDGET_WIDTH_DP) * scale).toInt()
        val widgetHpx = (dp(ctx, WIDGET_HEIGHT_DP) * scale).toInt()
        currentWidgetWpx = widgetWpx
        currentWidgetHpx = widgetHpx
        // Force-fit the explicit pixel size so WindowManager respects the new
        // bounds (WRAP_CONTENT alone doesn't re-measure on density override).
        params.width = widgetWpx
        params.height = widgetHpx
        // Re-clamp current position so an enlarged widget doesn't end up off-screen.
        val (clampedX, clampedY) = DragGestureLogic.clampToScreen(
            x = params.x,
            y = params.y,
            widgetWidth = widgetWpx,
            widgetHeight = widgetHpx,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
        )
        params.x = clampedX
        params.y = clampedY
        try {
            windowManager.updateViewLayout(rootContainer ?: view, params)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout scale: ${e.message}")
        }
        // If buttons are out while the user resizes, recompute the expanded box so
        // the pockets track the new panel size.
        if (expandedState.value) {
            collapsePanel()
            expandPanel()
        }
    }

    private data class WidgetSnapshot(
        val data: com.bydmate.app.data.remote.DiParsData?,
        val range: Double?,
        val sessionStartedAt: Long?,
        val tripDistanceKm: Double?,
        val consumption: ConsumptionState,
        val alpha: Float,
        val scale: Float,
        val cameraActive: Boolean,
    )

    private data class UiBundle(
        val consumption: ConsumptionState,
        val alpha: Float,
        val scale: Float,
        val cameraActive: Boolean,
    )

    // --- Button panel expand/collapse ---

    @Synchronized
    private fun expandPanel() {
        if (expandedState.value) return
        val root = rootContainer ?: return
        val params = widgetParams ?: return
        val windowManager = wm ?: return
        val ctx = root.context ?: return
        val metrics = ctx.resources.displayMetrics
        // Remember the collapsed origin so collapse restores it exactly (an
        // edge-clamped expansion may have shifted the panel).
        collapsedX = params.x
        collapsedY = params.y
        val box = WidgetButtonLayout.expandedWindow(
            collapsedX = params.x,
            collapsedY = params.y,
            panelWpx = currentWidgetWpx,
            panelHpx = currentWidgetHpx,
            buttonPx = dp(ctx, (BUTTON_DP * scaleState.value).toInt()),
            gapPx = dp(ctx, (GAP_DP * scaleState.value).toInt()),
            screenW = metrics.widthPixels,
            screenH = metrics.heightPixels,
        )
        params.x = box.x
        params.y = box.y
        params.width = box.width
        params.height = box.height
        try {
            windowManager.updateViewLayout(root, params)
        } catch (e: Exception) {
            Log.w(TAG, "expand updateViewLayout: ${e.message}")
        }
        expandedState.value = true
    }

    @Synchronized
    private fun collapsePanel() {
        if (!expandedState.value) return
        val root = rootContainer ?: return
        val params = widgetParams ?: return
        val windowManager = wm ?: return
        expandedState.value = false
        // Shrink back to the panel-only window at the saved collapsed origin.
        params.x = collapsedX
        params.y = collapsedY
        params.width = currentWidgetWpx
        params.height = currentWidgetHpx
        try {
            windowManager.updateViewLayout(root, params)
        } catch (e: Exception) {
            Log.w(TAG, "collapse updateViewLayout: ${e.message}")
        }
    }

    private fun togglePanel() {
        if (expandedState.value) collapsePanel() else expandPanel()
    }

    /**
     * Button N pressed: run its rules through the engine and auto-collapse. When
     * the engine reports 0 matches (no rule for button N, or the service isn't
     * running) show a fail-soft toast. Result callback may arrive off the main
     * thread, so the toast is posted to the main looper.
     */
    private fun onWidgetButtonClick(context: Context, number: Int) {
        TrackingService.fireAutomationButton(number) { matched ->
            if (matched == 0) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.widget_button_no_rules, number),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } catch (_: Exception) {}
                }
            }
        }
        collapsePanel()
    }

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

    /**
     * Called from SettingsViewModel after the user switches app language.
     * Detaches the overlay so the next attach (driven by ActivityLifecycle
     * callbacks when the user leaves Settings) recreates ComposeView against
     * the freshly-localized Configuration context.
     */
    @Synchronized
    fun relocale() {
        if (widgetView != null) detach()
    }

    private fun localizedContext(appCtx: Context): Context {
        val lang = LocalePreferences(appCtx).getLanguage() ?: return appCtx
        val config = Configuration(appCtx.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(lang))
        }
        return appCtx.createConfigurationContext(config)
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
    ) : android.view.View.OnTouchListener {

        // Read from controller state on each gesture so resize-mid-session
        // (e.g. user moved the size slider in Settings) doesn't leave us
        // clamping with stale dimensions.
        private val widgetWpx: Int get() = currentWidgetWpx
        private val widgetHpx: Int get() = currentWidgetHpx

        private var downX = 0f
        private var downY = 0f
        private var initialParamX = 0
        private var initialParamY = 0
        private var dragging = false
        private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null
        private var singleTapRunnable: Runnable? = null
        private var lastTapUpMs: Long = 0L

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
                                context.getString(R.string.widget_toast_hidden),
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
                        singleTapRunnable?.let { longPressHandler.removeCallbacks(it) }
                        singleTapRunnable = null
                        if (expandedState.value) {
                            collapsePanel()
                            // Re-anchor drag baseline to the collapsed window origin +
                            // current finger so delta is zero at the instant of collapse.
                            // Without this, initialParamX holds the expanded-window origin
                            // (collapsedX - pocket) and every subsequent move positions the
                            // now-collapsed panel one full pocket (~62dp) too far left.
                            initialParamX = params.x
                            initialParamY = params.y
                            downX = event.rawX
                            downY = event.rawY
                        }
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
                            windowManager.updateViewLayout(rootContainer ?: v, params)
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
                        val width = v.width
                        val buttonsEnabled = prefs.isButtonsEnabled()
                        val leftTap = WidgetGestureLogic.isLeftZone(
                            eventX = event.x, viewWidth = width, fraction = LEFT_TAP_FRACTION,
                        ) && prefs.isLeftTapZoningEnabled()

                        if (leftTap) {
                            // Left zone always launches the configured app immediately,
                            // independent of the buttons feature (unchanged behavior).
                            launchLeftApp()
                        } else if (!buttonsEnabled) {
                            // Feature off: a right/center tap opens BYDMate, as before.
                            openBydMate()
                        } else {
                            val now = android.os.SystemClock.uptimeMillis()
                            val pending = singleTapRunnable
                            if (pending != null &&
                                WidgetGestureLogic.isWithinDoubleTapWindow(lastTapUpMs, now, DOUBLE_TAP_MS)
                            ) {
                                // Double tap: cancel the pending toggle, open BYDMate.
                                longPressHandler.removeCallbacks(pending)
                                singleTapRunnable = null
                                lastTapUpMs = 0L
                                openBydMate()
                            } else {
                                // First tap: arm a single-tap timer that toggles the panel.
                                lastTapUpMs = now
                                val r = Runnable {
                                    singleTapRunnable = null
                                    togglePanel()
                                }
                                singleTapRunnable = r
                                longPressHandler.postDelayed(r, DOUBLE_TAP_MS)
                            }
                        }
                    } else {
                        // End drag
                        if (trashActive.value) {
                            prefs.setEnabled(false)
                            detach()
                            return true
                        } else {
                            collapsedX = params.x
                            collapsedY = params.y
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

        private fun openBydMate() {
            try {
                val intent = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "openBydMate failed: ${e.message}")
            }
        }

        private fun launchLeftApp() {
            try {
                val intent = context.packageManager
                    .getLaunchIntentForPackage(prefs.getLeftTapAppPackage())
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "launchLeftApp failed: ${e.message}")
            }
        }
    }
}
