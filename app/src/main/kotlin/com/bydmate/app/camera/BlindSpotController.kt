package com.bydmate.app.camera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.bydmate.app.cluster.CENTER_OFFSET_PCT
import com.bydmate.app.cluster.ClusterGeometry
import com.bydmate.app.cluster.ClusterMode
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.cluster.MAX_PROJECTION_PCT
import com.bydmate.app.cluster.geometryFor
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turn signal → blind-spot camera.
 *
 * The main poll (1 s) only arms the pipeline: once the car is near the speed threshold a fast
 * loop takes over and reads blink/speed/gear through the daemon every 150 ms, because a turn
 * signal that shows up a second late is useless. The camera is opened warm (both previews
 * bound, both windows parked off screen) while the car is moving, so a show is one
 * updateViewLayout rather than a vendor-stack open. Hiding moves the window off screen rather
 * than dropping its alpha, so the vendor keeps filling a live surface.
 *
 * Left goes on the cluster (that is where the driver looks before a left lane change), in the
 * window geometry the navigation projection uses, right goes into a small window on the main
 * screen. Without a projection display the left view falls back to a second main-screen window,
 * mirrored across the right one. Reverse gear closes everything: the factory rear view owns the
 * screens there.
 *
 * Threading: window work runs on Main, every vendor-stack call runs on a private
 * single camera thread ([cameraDispatcher]) because open/close block for hundreds of
 * milliseconds. The two are never mixed — each hop is an explicit withContext.
 */
@Singleton
class BlindSpotController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: BlindSpotPreferences,
    private val helper: HelperClient,
) {
    private val probe = AvmCameraProbe()
    private val telemetry = BlindSpotTelemetryGate()

    /** Teardown must outlive [serviceScope]: TrackingService cancels it right after stop(),
     *  and windows left on screen would survive the service. */
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Touched from the poll collector (IO) and from onDestroy; everything below them lives on Main.
    @Volatile private var serviceScope: CoroutineScope? = null
    @Volatile private var fastLoop: Job? = null

    /** Created on first camera use, closed in [stop]; recreated if the service starts again
     *  (this singleton outlives TrackingService — WorkManager restarts it into the same process). */
    private var cameraThread: ExecutorCoroutineDispatcher? = null
    private var compositorJob: Job? = null

    private var clusterWindow: PreviewWindow? = null   // left camera, cluster display
    private var pipWindow: PreviewWindow? = null       // right camera, main screen
    /** True while [clusterWindow] is the mirrored PiP on the main screen (this car has no
     *  projection display) — the cluster compositor must stay untouched then. */
    private var clusterOnMainScreen = false
    /** Geometry the PiP window currently carries; a mismatch with the settings re-applies it. */
    private var appliedPipRect: Rect? = null
    /** Windows whose removeView threw for a reason other than "already detached": retried on
     *  every tick and in [stop] rather than dropped, or the overlay would leak. */
    private val pendingDetach = mutableListOf<PreviewWindow>()

    private var shownSide = BlindSpotSide.NONE
    private var lastRequestedSide = BlindSpotSide.NONE
    private var cameraOpen = false
    private var compositorPowered = false   // last CONFIRMED compositor state
    private var compositorTarget = false    // last requested state, in flight or applied
    private var discovered = false

    private var windowsAttachedAt = 0L
    private var cameraOpenedAt = 0L
    private var coolingSince = 0L
    private var retryAt = 0L
    private var tearingDown = false

    /** Called from TrackingService.onCreate; the scope dies with the service, and so does the loop. */
    fun start(scope: CoroutineScope) {
        serviceScope = scope
    }

    fun stop() {
        fastLoop?.cancel()
        fastLoop = null
        serviceScope = null
        ownScope.launch {
            teardownNow("service stop")
            retryPendingDetach()
            cameraThread?.close()
            cameraThread = null
            // Drop any straggler compositor job; the scope itself stays usable for a restart.
            ownScope.coroutineContext[Job]?.cancelChildren()
        }
    }

    /**
     * Arming gate on the main poll: the fast loop (and the daemon traffic it makes) only runs
     * while the feature is on, the car is out of reverse and at or near the show threshold.
     */
    fun onPollSnapshot(data: DiParsData) {
        val armed = blindSpotArmed(prefs.enabled, data.gear, data.speed, prefs.thresholdKmh)
        if (armed) startFastLoop() else stopFastLoop()
    }

    private fun startFastLoop() {
        if (fastLoop?.isActive == true) return
        val scope = serviceScope ?: return
        Log.i(TAG, "fast loop start")
        telemetry.reset(SystemClock.elapsedRealtime())
        fastLoop = scope.launch(Dispatchers.Main) {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    private fun stopFastLoop() {
        val loop = fastLoop ?: return
        fastLoop = null
        loop.cancel()
        Log.i(TAG, "fast loop stop")
        ownScope.launch { teardownNow("disarmed") }
    }

    /** Cancels the loop we are currently running in; the teardown must already be done. */
    private fun cancelFastLoop() {
        val loop = fastLoop ?: return
        fastLoop = null
        loop.cancel()
    }

    private suspend fun tick() {
        retryPendingDetach()

        // The switch can go off mid-drive, and the preference is all the settings card writes.
        // Checked before anything else so the camera never outlives the switch by a tick.
        if (!prefs.enabled) {
            awaitTeardown("feature switched off")
            cancelFastLoop()
            return
        }

        val glow = prefs.bsdGlow
        val pairs = helper.readBatch(if (glow) BATCH_WITH_BSD else BATCH_CORE)
        val now = SystemClock.elapsedRealtime()
        val sample = pairs?.let {
            BlindSpotSample(it.intAt(INDEX_BLINK), it.floatAt(INDEX_SPEED), it.intAt(INDEX_GEAR))
        }
        val state = telemetry.onSample(sample, now)
        if (state.mustClose) {
            if (anythingUp()) awaitTeardown("telemetry lost")
            return
        }

        // A missing gear is not "not reverse": fall back to the last snapshot that read cleanly,
        // which the loss watchdog above keeps younger than 3 s.
        val gearIsReverse = (sample?.gear ?: state.lastValid?.gear) == BLIND_SPOT_GEAR_REVERSE
        val decision = decideBlindSpot(
            BlindSpotInput(
                blink = sample?.blink,
                speedKmh = sample?.speedKmh,
                gearIsReverse = gearIsReverse,
                thresholdKmh = prefs.thresholdKmh,
                telemetryAgeMs = state.ageMs,
            )
        )

        if (gearIsReverse) {
            if (anythingUp()) awaitTeardown("reverse")
            return
        }

        // A fresh signal cancels the error backoff once: the driver is asking for the view now.
        // Edge, not level — otherwise a held blinker would retry the camera every tick.
        if (decision.show != lastRequestedSide) {
            if (decision.show != BlindSpotSide.NONE) retryAt = 0L
            lastRequestedSide = decision.show
        }

        if (decision.cameraWarm) {
            coolingSince = 0L
            ensureWarm(now)
        } else {
            if (coolingSince == 0L) coolingSince = now
            if (now - coolingSince >= COOL_DOWN_MS && anythingUp()) {
                awaitTeardown("cold for ${COOL_DOWN_MS / 1000} s")
            }
        }

        applyPipGeometry()
        applyShow(if (cameraOpen) decision.show else BlindSpotSide.NONE)
        if (glow && pairs != null) {
            clusterWindow?.setGlow(
                shownSide == BlindSpotSide.LEFT && pairs.intAt(INDEX_BSD_LEFT) == BSD_OBJECT)
            pipWindow?.setGlow(
                shownSide == BlindSpotSide.RIGHT && pairs.intAt(INDEX_BSD_RIGHT) == BSD_OBJECT)
        }
    }

    /** Brings up the windows and the camera, and watches the two ways they fail to start. */
    private suspend fun ensureWarm(now: Long) {
        if (now < retryAt) return

        if (clusterWindow == null && pipWindow == null) {
            attachWindows()
            windowsAttachedAt = now
            // Nothing attached at all (no overlay permission, WindowManager refused): back off
            // instead of hammering addView every tick.
            if (clusterWindow == null && pipWindow == null) failCamera("no window", now)
            return
        }

        if (!cameraOpen) {
            val surfaces = buildMap {
                clusterWindow?.surface?.let { put(PREVIEW_INDEX_LEFT, it) }
                pipWindow?.surface?.let { put(PREVIEW_INDEX_RIGHT, it) }
            }
            if (surfaces.size < attachedWindowCount()) {
                if (now - windowsAttachedAt >= SURFACE_TIMEOUT_MS) failCamera("no surface", now)
                return
            }
            val opened = withContext(cameraDispatcher()) {
                if (!discovered) {
                    discovered = true
                    probe.discover()
                }
                probe.cameraId >= 0 && probe.openWarm(surfaces)
            }
            if (!opened) {
                failCamera("open failed", now)
                return
            }
            // The windows can have gone away while the vendor stack was opening (a teardown from
            // the poll thread, a destroyed surface): do not keep a camera nobody can see.
            if (clusterWindow == null && pipWindow == null) {
                withContext(cameraDispatcher()) { probe.close() }
                return
            }
            cameraOpen = true
            cameraOpenedAt = now
            Log.i(TAG, "camera warm on indexes ${surfaces.keys.joinToString()}")
            return
        }

        // The first update after open carries a stale buffer, so #2 is the first honest frame.
        val gotFrame = clusterWindow?.hasValidFrame == true || pipWindow?.hasValidFrame == true
        if (!gotFrame && now - cameraOpenedAt >= FIRST_FRAME_TIMEOUT_MS) failCamera("no frame", now)
    }

    private suspend fun failCamera(reason: String, now: Long) {
        Log.w(TAG, "camera error: $reason; retry in ${RETRY_DELAY_MS / 1000} s")
        awaitTeardown(reason)
        retryAt = now + RETRY_DELAY_MS
    }

    private fun attachedWindowCount(): Int =
        (if (clusterWindow != null) 1 else 0) + (if (pipWindow != null) 1 else 0)

    private fun anythingUp(): Boolean =
        clusterWindow != null || pipWindow != null || cameraOpen || compositorPowered || compositorTarget

    private fun attachWindows() {
        // No projection display (non-Leopard-3 trims, or the cluster is not fissioned): the left
        // camera falls back to a mirrored window on the main screen, and setClusterContainerMode
        // is never called — powering a compositor that does not exist would black the cluster out.
        val display = clusterDisplay()
        if (display == null) {
            attachMirrorWindow()
        } else {
            clusterGeometry(display)?.let { geo ->
                val params = WindowManager.LayoutParams(
                    geo.width, geo.height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    OVERLAY_FLAGS,
                    // TRANSLUCENT let the navi underneath bleed through on-car; the window is
                    // parked off screen instead, which works on an opaque format too.
                    PixelFormat.OPAQUE,
                ).apply {
                    // Absolute position on the panel, and hiding the window means moving it
                    // off screen — both need the corner gravity.
                    gravity = Gravity.TOP or Gravity.START
                    x = geo.xOffset
                    y = geo.yOffset
                }
                val window = PreviewWindow("cluster")
                val displayContext = context.createDisplayContext(display)
                if (window.attach(displayContext, params, offscreenX(geo.width))) {
                    clusterWindow = window
                }
            }
        }
        val rect = pipRect()
        val pip = PreviewWindow("pip")
        if (pip.attach(context, pipParams(rect), offscreenX(rect.width()))) {
            pipWindow = pip
            appliedPipRect = rect
        }
    }

    /**
     * Cluster window in the geometry the driver already tuned for the navigation projection: the
     * same preferences and the same formula, so the camera covers exactly the area the map does.
     * On cars whose visible cluster zone is smaller than the projection display (Sea Lion 07) a
     * full-panel window would spill past the zone. The content scale is deliberately not read —
     * it sets the interface density inside the navi window and means nothing to a camera frame.
     * The defaults (full size, centered) give the whole panel, which is what this window was.
     */
    private fun clusterGeometry(display: Display): ClusterGeometry? {
        val metrics = realMetrics(display)
        val prefs = context.getSharedPreferences(
            ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
        return geometryFor(
            ClusterMode.FULLSCREEN,
            metrics.widthPixels,
            metrics.heightPixels,
            prefs.getInt(ClusterProjectionManager.KEY_WIDTH_PCT, MAX_PROJECTION_PCT),
            prefs.getInt(ClusterProjectionManager.KEY_HEIGHT_PCT, MAX_PROJECTION_PCT),
            prefs.getInt(ClusterProjectionManager.KEY_OFFSET_X_PCT, CENTER_OFFSET_PCT),
            prefs.getInt(ClusterProjectionManager.KEY_OFFSET_Y_PCT, CENTER_OFFSET_PCT),
        )
    }

    /** Left camera on a car without a cluster to project onto: the right PiP mirrored across the
     *  screen, so the two windows sit symmetrically wherever the driver placed the right one. */
    private fun attachMirrorWindow() {
        val rect = mirrorRect(pipRect())
        val window = PreviewWindow("left-pip")
        if (window.attach(context, pipParams(rect), offscreenX(rect.width()))) {
            clusterWindow = window
            clusterOnMainScreen = true
        }
    }

    /** Re-applies the PiP geometry after the user changed the width or dragged the window. */
    private fun applyPipGeometry() {
        if (pipWindow == null && !clusterOnMainScreen) return
        val rect = pipRect()
        if (rect == appliedPipRect) return
        var applied = true
        pipWindow?.let { if (!it.setGeometry(rect, offscreenX(rect.width()))) applied = false }
        if (clusterOnMainScreen) {
            val mirror = mirrorRect(rect)
            clusterWindow?.let {
                if (!it.setGeometry(mirror, offscreenX(mirror.width()))) applied = false
            }
        }
        if (applied) appliedPipRect = rect
    }

    /** Reflection of a main-screen rect across the vertical axis of the screen. */
    private fun mirrorRect(rect: Rect): Rect {
        val screenWidth = realMetrics(defaultDisplay()).widthPixels
        val left = (screenWidth - rect.right).coerceAtLeast(0)
        return Rect(left, rect.top, left + rect.width(), rect.bottom)
    }

    /** Main-screen PiP geometry: 16:9 of the width slider, at the corner the user dragged it to
     *  (the default slot until then), clamped to the screen. */
    private fun pipRect(): Rect {
        val metrics = realMetrics(defaultDisplay())
        val widthPct = prefs.pipWidthPct
        val x = prefs.pipXPx
        val y = prefs.pipYPx
        if (x == BlindSpotPreferences.UNSET_PX || y == BlindSpotPreferences.UNSET_PX) {
            return BlindSpotPreferences.defaultPipRect(metrics.widthPixels, metrics.heightPixels, widthPct)
        }
        val size = BlindSpotPreferences.pipSize(metrics.widthPixels, widthPct)
        val left = x.coerceIn(0, (metrics.widthPixels - size.width).coerceAtLeast(0))
        val top = y.coerceIn(0, (metrics.heightPixels - size.height).coerceAtLeast(0))
        return Rect(left, top, left + size.width, top + size.height)
    }

    private fun pipParams(rect: Rect): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            rect.width(), rect.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            OVERLAY_FLAGS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left
            y = rect.top
        }

    private fun offscreenX(width: Int): Int = -(width + OFFSCREEN_MARGIN_PX)

    /** Display of the cluster projection panel, or null when this car has none. */
    private fun clusterDisplay(): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val projection = dm.displays.filter {
            it.name.contains(PROJECTION_DISPLAY_NAME, ignoreCase = true)
        }
        val display = projection.firstOrNull { it.name.endsWith("_1") } ?: projection.firstOrNull()
        if (display == null) Log.i(TAG, "no projection display; cluster window skipped")
        return display
    }

    private fun defaultDisplay(): Display? =
        (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)

    /** The app's own displayMetrics follow its window (split-screen shrinks them), so overlay
     *  geometry has to come from the display itself or the position drifts between shows. */
    private fun realMetrics(display: Display?): DisplayMetrics {
        if (display == null) return context.resources.displayMetrics
        @Suppress("DEPRECATION")
        return DisplayMetrics().also { display.getRealMetrics(it) }
    }

    /** Flips the windows; the state moves only once the flip actually landed, so a rejected
     *  updateViewLayout is retried on the next tick instead of being assumed applied. */
    private fun applyShow(side: BlindSpotSide) {
        if (side == shownSide) return
        val clusterOk = clusterWindow?.setShown(side == BlindSpotSide.LEFT) ?: true
        val pipOk = pipWindow?.setShown(side == BlindSpotSide.RIGHT) ?: true
        if (!clusterOk || !pipOk) {
            Log.w(TAG, "show $shownSide -> $side not applied; retrying next tick")
            return
        }
        val previous = shownSide
        shownSide = side
        Log.i(TAG, "show $previous -> $side")
        if (side == BlindSpotSide.LEFT) {
            requestCompositor(true)
        } else {
            clusterWindow?.setGlow(false)
            if (previous == BlindSpotSide.LEFT) requestCompositor(false)
        }
        if (side != BlindSpotSide.RIGHT) pipWindow?.setGlow(false)
    }

    /** Fire-and-forget compositor switch for the show path; one job at a time. */
    private fun requestCompositor(on: Boolean) {
        if (on == compositorTarget) return
        // Only the window that actually sits on the projection display needs the compositor;
        // the mirrored main-screen fallback must never touch a cluster that has none.
        if (on && (clusterWindow == null || clusterOnMainScreen)) return
        compositorTarget = on
        compositorJob?.cancel()
        compositorJob = ownScope.launch { applyCompositor(on) }
    }

    /**
     * The cluster only shows our window while its compositor is powered (on-car 2026-07-31).
     * The projection check happens HERE, immediately before the call: a navigation projection
     * that started while this was queued owns the compositor, and powering it down would black
     * out the cluster under it.
     */
    private suspend fun applyCompositor(on: Boolean) {
        if (!on && ClusterProjectionManager.isProjectionActive()) {
            Log.i(TAG, "compositor stays up: projection active")
            compositorPowered = false
            compositorTarget = false
            return
        }
        val ok = runCatching { helper.setClusterContainerMode(on) }.getOrDefault(false)
        // State follows the confirmed result: a failed call leaves the previous state, so the
        // next transition retries instead of assuming the cluster is where we asked for.
        if (ok) compositorPowered = on else compositorTarget = compositorPowered
        Log.i(TAG, "compositor power-${if (on) "up" else "down"} ok=$ok")
    }

    /** Runs the teardown in [ownScope] (so a cancelled fast loop cannot abandon a half-closed
     *  camera) and waits for it. */
    private suspend fun awaitTeardown(reason: String) {
        ownScope.launch { teardownNow(reason) }.join()
    }

    private suspend fun teardownNow(reason: String) {
        if (!anythingUp()) return
        if (tearingDown) return
        tearingDown = true
        Log.i(TAG, "teardown: $reason")
        try {
            // Camera first: nothing may write into a surface that is about to be released.
            if (cameraOpen) {
                withContext(cameraDispatcher()) { probe.close() }
                cameraOpen = false
            }
            applyShow(BlindSpotSide.NONE)
            compositorJob?.cancel()
            compositorJob = null
            compositorTarget = false
            if (compositorPowered) applyCompositor(false)
            releaseWindow(clusterWindow)
            clusterWindow = null
            clusterOnMainScreen = false
            releaseWindow(pipWindow)
            pipWindow = null
            appliedPipRect = null
        } finally {
            // No windows left, so nothing is shown regardless of where the flips left them.
            shownSide = BlindSpotSide.NONE
            lastRequestedSide = BlindSpotSide.NONE
            windowsAttachedAt = 0L
            cameraOpenedAt = 0L
            coolingSince = 0L
            tearingDown = false
        }
    }

    private fun releaseWindow(window: PreviewWindow?) {
        if (window == null) return
        if (!window.detach()) pendingDetach += window
    }

    private fun retryPendingDetach() {
        if (pendingDetach.isEmpty()) return
        pendingDetach.removeAll { it.detach() }
        if (pendingDetach.isNotEmpty()) {
            Log.w(TAG, "${pendingDetach.size} window(s) still attached; retrying next tick")
        }
    }

    private fun cameraDispatcher(): ExecutorCoroutineDispatcher =
        cameraThread ?: Executors.newSingleThreadExecutor { r -> Thread(r, "blind-spot-camera") }
            .asCoroutineDispatcher().also { cameraThread = it }

    /**
     * One overlay window: the camera preview plus the BSD border drawn around it.
     * Hidden by moving it off screen rather than by visibility or window alpha, so the vendor
     * keeps filling the SurfaceTexture and a show costs one updateViewLayout.
     */
    private inner class PreviewWindow(
        private val label: String,
    ) : TextureView.SurfaceTextureListener {
        private var wm: WindowManager? = null
        private var params: WindowManager.LayoutParams? = null
        private var container: View? = null
        private var textureView: TextureView? = null
        private var glow: View? = null
        private var glowAnimator: ValueAnimator? = null
        private var frames = 0

        private var shownX = 0
        private var hiddenX = 0
        private var shown = false

        var surface: Surface? = null
            private set

        val hasValidFrame: Boolean get() = frames >= 2

        fun attach(
            windowContext: Context,
            layoutParams: WindowManager.LayoutParams,
            offscreenX: Int,
        ): Boolean = try {
            val texture = TextureView(windowContext).apply {
                surfaceTextureListener = this@PreviewWindow
                setOpaque(true)
            }
            val border = View(windowContext).apply {
                background = GradientDrawable().apply {
                    setStroke(
                        (GLOW_STROKE_DP * windowContext.resources.displayMetrics.density).roundToInt(),
                        GLOW_COLOR,
                    )
                    setColor(Color.TRANSPARENT)
                }
                visibility = View.GONE
            }
            val frame = FrameLayout(windowContext).apply {
                setBackgroundColor(Color.BLACK)
                addView(texture, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                addView(border, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            shownX = layoutParams.x
            hiddenX = offscreenX
            layoutParams.x = hiddenX
            val windowManager =
                windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.addView(frame, layoutParams)
            wm = windowManager
            params = layoutParams
            container = frame
            textureView = texture
            glow = border
            Log.i(TAG, "$label window attached")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "$label window failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }

        /** True when the window is in the requested state; false when the flip was rejected. */
        fun setShown(shown: Boolean): Boolean {
            val view = container ?: return true
            val p = params ?: return true
            if (this.shown == shown) return true
            val previous = p.x
            p.x = if (shown) shownX else hiddenX
            return runCatching { wm?.updateViewLayout(view, p) }
                .onSuccess { this.shown = shown }
                .onFailure {
                    p.x = previous
                    Log.w(TAG, "$label show=$shown failed: ${it.javaClass.simpleName}: ${it.message}")
                }
                .isSuccess
        }

        /** New size and working position; a hidden window stays off screen with the new size. */
        fun setGeometry(rect: Rect, offscreenX: Int): Boolean {
            val view = container ?: return true
            val p = params ?: return true
            val prevShownX = shownX
            val prevHiddenX = hiddenX
            val prevWidth = p.width
            val prevHeight = p.height
            val prevX = p.x
            val prevY = p.y
            shownX = rect.left
            hiddenX = offscreenX
            p.width = rect.width()
            p.height = rect.height()
            p.y = rect.top
            p.x = if (shown) shownX else hiddenX
            return runCatching { wm?.updateViewLayout(view, p) }
                .onFailure {
                    // Rejected geometry must not stay in the fields: the next setShown would
                    // apply a position the window manager never accepted.
                    shownX = prevShownX
                    hiddenX = prevHiddenX
                    p.width = prevWidth
                    p.height = prevHeight
                    p.x = prevX
                    p.y = prevY
                    Log.w(TAG, "$label geometry failed: ${it.javaClass.simpleName}: ${it.message}")
                }
                .isSuccess
        }

        fun setGlow(active: Boolean) {
            val border = glow ?: return
            if (active == (glowAnimator != null)) return
            if (!active) {
                glowAnimator?.cancel()
                glowAnimator = null
                border.visibility = View.GONE
                return
            }
            border.visibility = View.VISIBLE
            glowAnimator = ValueAnimator.ofFloat(1f, GLOW_MIN_ALPHA).apply {
                duration = GLOW_HALF_PERIOD_MS
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { border.alpha = it.animatedValue as Float }
                start()
            }
        }

        /**
         * Removes the window. Returns false when it may still be on screen — the caller keeps
         * the object and retries, since dropping the references here would leak the overlay for
         * the life of the process. "Not attached" (IllegalArgumentException) counts as removed.
         */
        fun detach(): Boolean {
            setGlow(false)
            val view = container ?: return true
            val error = runCatching { wm?.removeView(view) }.exceptionOrNull()
            if (error != null && error !is IllegalArgumentException) {
                Log.w(TAG, "$label window remove failed: ${error.javaClass.simpleName}: ${error.message}")
                return false
            }
            if (error != null) Log.i(TAG, "$label window was already detached")
            container = null
            surface?.release()
            surface = null
            textureView = null
            glow = null
            params = null
            wm = null
            frames = 0
            shown = false
            Log.i(TAG, "$label window removed")
            return true
        }

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            surface?.release()
            surface = Surface(texture)
            frames = 0
            applyCrop(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            applyCrop(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            // We keep ownership (false) and release the buffer ourselves only after the vendor
            // stack is closed — returning true would free it while the camera may still write.
            ownScope.launch {
                teardownNow("$label surface destroyed")
                runCatching { texture.release() }
            }
            return false
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            if (frames < 2) frames++
        }

        /** Centered crop of the side buffer to the window's own aspect, scaled to fill it —
         *  the cluster (1280×480) and the 16:9 PiP need different crops of the same source. */
        private fun applyCrop(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val view = textureView ?: return
            val aspect = width.toFloat() / height
            val cropW = min(SOURCE_WIDTH, SOURCE_HEIGHT * aspect)
            val cropH = min(SOURCE_HEIGHT, SOURCE_WIDTH / aspect)
            val left = (SOURCE_WIDTH - cropW) / 2f
            val top = (SOURCE_HEIGHT - cropH) / 2f
            // Untransformed, the whole buffer fills the view — so the crop in view coordinates
            // is the source rect scaled by view/source, and FILL blows it back up to the window.
            val src = RectF(
                left / SOURCE_WIDTH * width, top / SOURCE_HEIGHT * height,
                (left + cropW) / SOURCE_WIDTH * width, (top + cropH) / SOURCE_HEIGHT * height,
            )
            val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
            view.setTransform(Matrix().apply { setRectToRect(src, dst, Matrix.ScaleToFit.FILL) })
        }
    }

    private companion object {
        const val TAG = "BlindSpot"

        // Telemetry read through the daemon (Leopard 3, on-car 2026-07-31).
        const val TX_INT = 5
        const val TX_FLOAT = 7
        val BATCH_CORE = listOf(
            BatchReadItem(TX_INT, 1004, 950009900),      // turn signal mask: 1=off 2=L 4=R 6=hazard
            BatchReadItem(TX_FLOAT, 1013, -1807745016),  // speed, km/h
            BatchReadItem(TX_INT, 1011, 555745336),      // gear: 1=P 2=R 3=N 4=D
        )
        val BATCH_WITH_BSD = BATCH_CORE + listOf(
            BatchReadItem(TX_INT, 1038, 1098907664),     // BSD left
            BatchReadItem(TX_INT, 1038, 1098907666),     // BSD right
        )
        const val INDEX_BLINK = 0
        const val INDEX_SPEED = 1
        const val INDEX_GEAR = 2
        const val INDEX_BSD_LEFT = 3
        const val INDEX_BSD_RIGHT = 4

        /** BSD fid: 2 = an object is in the blind spot. */
        const val BSD_OBJECT = 2

        /** AVM preview indexes on Leopard 3 (on-car sweep): 2 = left side, 3 = right side. */
        const val PREVIEW_INDEX_LEFT = 2
        const val PREVIEW_INDEX_RIGHT = 3

        const val TICK_MS = 150L
        const val COOL_DOWN_MS = 10_000L
        const val SURFACE_TIMEOUT_MS = 8_000L
        const val FIRST_FRAME_TIMEOUT_MS = 3_000L
        const val RETRY_DELAY_MS = 3_000L
        const val GLOW_HALF_PERIOD_MS = 800L
        const val GLOW_MIN_ALPHA = 0.35f
        const val GLOW_STROKE_DP = 12f
        /** Orange: red draws the eye worse against the camera picture (field feedback). */
        const val GLOW_COLOR = 0xFFFF7A00.toInt()

        /** Extra px past the edge so a hidden window cannot show a seam. */
        const val OFFSCREEN_MARGIN_PX = 100

        const val PROJECTION_DISPLAY_NAME = "XDJAScreenProjection"
        // Deliberately no FLAG_NOT_TOUCHABLE: stock AOSP (DisplayPolicy.adjustWindowParamsLw)
        // clamps a system-alert window that is NOT_TOUCHABLE and not a trusted overlay down to
        // the maximum obscuring opacity for touch (0.8), and re-applies it on every relayout, so
        // the camera picture showed through whatever was underneath. On-car probe A..F
        // (2026-08-02): the same window without the flag comes back alpha=1.0, and the trusted
        // overlay flag cannot be set from an app uid. The price is that a shown window swallows
        // the touches inside its bounds; a hidden one is off screen and swallows none.
        const val OVERLAY_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        /** Per-side AVM buffer on Leopard 3; only its aspect matters for the crop. */
        const val SOURCE_WIDTH = 1920f
        const val SOURCE_HEIGHT = 1300f

        /** Raw (status, value) pair → int, with the same sentinel filtering a single read applies. */
        fun List<Pair<Int, Int>>.intAt(index: Int): Int? = getOrNull(index)
            ?.let { (status, word) -> if (status == 0) SentinelDecoder.decodeInt(word) else null }

        fun List<Pair<Int, Int>>.floatAt(index: Int): Float? = getOrNull(index)
            ?.let { (status, word) -> if (status == 0) SentinelDecoder.parseFloatFromShellInt(word) else null }
    }
}
