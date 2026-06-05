package com.bydmate.app.cluster

import android.content.Context
import android.graphics.Point
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the cluster projection lifecycle. An overlay SurfaceView is placed on the
 * cluster display in the app process; its Surface backs a VirtualDisplay created
 * in the shell-uid daemon (Phase 1), onto which Navi's task is pinned.
 *
 * Mirrors OpenBYD ClusterOverlayManager but uses our suspend HelperClient instead
 * of ICarControl shell strings. Single in-memory instance — mode resets to OFF on
 * process death (acceptable for v1; persistence is a later concern).
 *
 * Concurrency: every setMode call runs under [mutex], so two fast polls can't
 * build overlapping overlays or clobber [remoteDisplayId]. The whole transition —
 * including the daemon's VirtualDisplay create + launchAndForce — runs inside the
 * lock, so a poll during an in-flight transition is queued, not dropped.
 *
 * State honesty: [currentMode] advances to the requested mode ONLY when the projection
 * fully succeeds (overlay up + VirtualDisplay created + Navi pinned). On ANY failure it
 * falls back to OFF and Navi is pulled back to the main display, so the in-memory mode
 * always matches what is actually on screen.
 *
 * Threading: WindowManager add/removeView run on Main; daemon calls are suspend
 * (HelperClient switches to IO internally).
 */
object ClusterProjectionManager {
    private const val TAG = "ClusterProjection"
    private const val DEFAULT_CLUSTER_DISPLAY_ID = 2          // Phase 0: fission display id
    private const val VIRTUAL_DISPLAY_FLAGS = 322             // TRUSTED | OWN_CONTENT_ONLY | PRESENTATION (OpenBYD)
    private const val VD_NAME = "BYDMate_Cluster_VD"
    private const val OVERLAY_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY  // 2038, minSdk 29
    private const val OVERLAY_FLAGS = 264                     // FLAG_NOT_FOCUSABLE(8) | FLAG_LAYOUT_IN_SCREEN(256)
    private const val SURFACE_TIMEOUT_MS = 3000L              // give up if the overlay Surface never gets created

    const val PREFS_NAME = "cluster_projection"
    // Master enable for star-controlled projection (settings switch). Read by SteeringWheelKeyService.
    const val KEY_MIRROR_ENABLED = "mirror_enabled"
    // Steering-wheel keycode that toggles projection. Default = right star (DEFAULT_TRIGGER_KEYCODE).
    // Stored independently of the master switch so the choice survives turning the feature off.
    const val KEY_TRIGGER_KEYCODE = "trigger_keycode"
    // User-tunable window size, % of the cluster panel (MIN_PROJECTION_PCT..MAX, default = full).
    const val KEY_WIDTH_PCT = "width_pct"
    const val KEY_HEIGHT_PCT = "height_pct"
    // User-tunable window position within the free space (MIN_OFFSET_PCT..MAX, default = centered).
    const val KEY_OFFSET_X_PCT = "offset_x_pct"
    const val KEY_OFFSET_Y_PCT = "offset_y_pct"
    // User-tunable content scale: VirtualDisplay density as % of cluster dpi (MIN_SCALE_PCT..MAX,
    // default 100 = native) and an oversample toggle (render bigger, then downscale). These tune
    // what the projected app renders INSIDE the window, independent of the window size/position.
    const val KEY_SCALE_PCT = "scale_pct"
    const val KEY_OVERSAMPLE = "oversample"
    // App to project onto the cluster (default Yandex Navi). Label is cached only for the settings row.
    const val KEY_TARGET_PACKAGE = "target_package"
    const val KEY_TARGET_LABEL = "target_label"
    // Last VirtualDisplay id we created. Persisted so a fresh app process can release the display
    // a prior (dead) process left orphaned in the long-lived daemon, instead of leaking it.
    private const val KEY_LAST_VD_ID = "last_vd_id"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()

    @Volatile
    var currentMode: ClusterMode = ClusterMode.OFF
        private set

    private var overlayView: View? = null
    private var remoteDisplayId: Int = -1
    // Package actually pinned on the cluster (the one we launchAndForce'd). pullBackToMain tugs THIS
    // back, not the live settings target — the two differ when the user switches the projection app
    // mid-projection, and tugging the new target would strand the old app on the cluster.
    private var projectedPackage: String? = null
    // PROJECT_MEDIA has no app-side query API (unlike SYSTEM_ALERT_WINDOW / canDrawOverlays),
    // so we grant both via the daemon once per process the first time we project.
    private var projectionPermissionsGranted = false
    private var clusterWidth: Int = 1280
    private var clusterHeight: Int = 480
    private var clusterDensityDpi: Int = 320

    /**
     * Drive the projection to [mode], serialized under [mutex]. Idempotent — a no-op when already
     * in [mode]. Auto-launch is delegated to the daemon's [launchAndForce] (it [launchApp]s Navi
     * when its task is absent), so a press with Navi closed launches it onto the cluster.
     * OFF always tears the projection down.
     */
    fun setMode(context: Context, mode: ClusterMode, helper: HelperClient, bootstrap: HelperBootstrap) {
        val appContext = context.applicationContext
        scope.launch {
            mutex.withLock {
                if (mode == currentMode) return@withLock
                Log.i(TAG, "setMode: $currentMode -> $mode")
                applyModeLocked(appContext, mode, helper, bootstrap)
            }
        }
    }

    /**
     * Steering-wheel toggle: flip projection приборка ↔ центр. Reads [currentMode] (success-honest —
     * a failed FULLSCREEN stays OFF, so the next press retries) and drives [setMode] to the other
     * state. Safe from the a11y key thread: setMode hops to [scope] and serializes under [mutex].
     */
    fun toggle(context: Context, helper: HelperClient, bootstrap: HelperBootstrap) =
        setMode(context, nextMode(currentMode), helper, bootstrap)

    /**
     * Self-enable our steering-wheel accessibility service via the daemon, so star control works on
     * a clean install with NO ADB (DiLink has no a11y settings UI). Called when the user turns the
     * settings switch on. [bootstrap] starts the daemon first if it is not up yet; the daemon op is
     * idempotent (force re-bind of our own Secure-settings entry, never clobbering other apps).
     */
    fun enableStarControl(helper: HelperClient, bootstrap: HelperBootstrap) {
        scope.launch {
            if (!bootstrap.ensureRunning()) {
                Log.e(TAG, "helper daemon not running; cannot self-enable a11y"); return@launch
            }
            val ok = helper.enableAccessibilityService()
            Log.i(TAG, "enableStarControl: a11y enabled=$ok")
        }
    }

    /**
     * Apply the size currently saved in prefs to the live projection (size-slider change). No-op
     * unless we are actively projecting FULLSCREEN — when OFF the new size is picked up on the next
     * star press, since [project] reads the size prefs fresh. When projecting, [swapToNewSize] does
     * an in-place make-before-break resize so Navi never bounces to the main screen; only if that
     * fails do we fall back to a full (visibly bouncing) rebuild. Serialized under [mutex].
     */
    fun reproject(context: Context, helper: HelperClient, bootstrap: HelperBootstrap) {
        val appContext = context.applicationContext
        scope.launch {
            mutex.withLock {
                if (currentMode != ClusterMode.FULLSCREEN) return@withLock
                Log.i(TAG, "reproject: in-place resize")
                if (!swapToNewSize(appContext, helper, bootstrap)) {
                    Log.w(TAG, "in-place resize failed; rebuilding projection")
                    applyModeLocked(appContext, ClusterMode.FULLSCREEN, helper, bootstrap)
                }
            }
        }
    }

    /**
     * Resize the live projection without dropping Navi back to the main screen. Stands up a fresh
     * overlay + VirtualDisplay at the saved size and moves Navi straight onto it, THEN releases the
     * old overlay + VirtualDisplay. Relocating a task to another display is fine, but *removing* a
     * task's only display is what bounces it to the main screen — so by moving Navi onto the new
     * cluster display before releasing the old one, the cluster just redraws at the new size.
     *
     * Caller holds [mutex]. Returns false ONLY when state is left uncertain (Navi may have moved
     * onto a released display) so [reproject] does a clean rebuild; on the benign failures (new
     * overlay/VD never came up) it returns true and leaves the existing projection running untouched.
     */
    private suspend fun swapToNewSize(
        context: Context, helper: HelperClient, bootstrap: HelperBootstrap,
    ): Boolean {
        if (!bootstrap.ensureRunning()) return true        // daemon gone; keep what's on screen
        val oldOverlay = overlayView ?: return true
        val oldVdId = remoteDisplayId
        val display = resolveClusterDisplay(context) ?: return true
        val (widthPct, heightPct) = readSizePct(context)
        val (offsetXPct, offsetYPct) = readOffsetPct(context)
        val geo = geometryFor(
            ClusterMode.FULLSCREEN, clusterWidth, clusterHeight, widthPct, heightPct, offsetXPct, offsetYPct,
        ) ?: return true
        val (scalePct, oversample) = readContentScale(context)
        val plan = renderPlanFor(geo, clusterDensityDpi, scalePct, oversample)

        // addOverlayAndAwaitSurface points overlayView at the NEW container; oldOverlay keeps the old
        // one so we can drop it after Navi has moved. remoteDisplayId is untouched until we commit.
        val surface = try {
            withTimeoutOrNull(SURFACE_TIMEOUT_MS) {
                addOverlayAndAwaitSurface(context, display, geo, plan, helper)
            }
        } catch (e: Exception) {
            Log.e(TAG, "resize: new overlay threw: ${e.message}"); null
        }
        if (surface == null) {
            Log.e(TAG, "resize: new overlay Surface not ready; keeping current size")
            discardNewOverlayKeepOld(oldOverlay); return true
        }
        val newVdId = helper.createVirtualDisplay(
            VD_NAME, plan.bufferWidth, plan.bufferHeight, plan.densityDpi, VIRTUAL_DISPLAY_FLAGS, surface,
        )
        if (newVdId == null) {
            Log.e(TAG, "resize: createVirtualDisplay failed; keeping current size")
            discardNewOverlayKeepOld(oldOverlay); return true
        }
        val pkg = targetPackage(context)
        if (!helper.launchAndForce(pkg, newVdId, plan.bufferWidth, plan.bufferHeight)) {
            // Navi may already have been moved onto newVd; release it and let the caller rebuild.
            Log.e(TAG, "resize: launchAndForce failed")
            helper.releaseVirtualDisplay(newVdId)
            discardNewOverlayKeepOld(oldOverlay); return false
        }
        // New projection holds Navi. Commit the new id, then drop the old overlay + VirtualDisplay.
        remoteDisplayId = newVdId
        saveLastVdId(context, newVdId)
        projectedPackage = pkg
        if (oldVdId != -1) helper.releaseVirtualDisplay(oldVdId)
        removeOverlayView(oldOverlay)
        Log.i(TAG, "resize: swapped to ${geo.width}x${geo.height} (vd $oldVdId -> $newVdId)")
        return true
    }

    /**
     * Failed-resize cleanup: remove the half-built NEW overlay and restore [overlayView] to the
     * still-running old projection, so nothing leaks and the surfaceDestroyed guard keeps matching.
     */
    private suspend fun discardNewOverlayKeepOld(oldOverlay: View) {
        val newOverlay = overlayView
        overlayView = oldOverlay
        if (newOverlay != null && newOverlay !== oldOverlay) removeOverlayView(newOverlay)
    }

    /** Remove an overlay container from its display's WindowManager (main thread, never throws). */
    private suspend fun removeOverlayView(view: View) {
        withContext(Dispatchers.Main) {
            try {
                (view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed: ${e.message}")
            }
        }
    }

    /** Saved window size as (widthPct, heightPct); defaults to a full-screen window. */
    private fun readSizePct(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_WIDTH_PCT, MAX_PROJECTION_PCT) to
            prefs.getInt(KEY_HEIGHT_PCT, MAX_PROJECTION_PCT)
    }

    /** Saved window position as (offsetXPct, offsetYPct); defaults to centered. */
    private fun readOffsetPct(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_OFFSET_X_PCT, CENTER_OFFSET_PCT) to
            prefs.getInt(KEY_OFFSET_Y_PCT, CENTER_OFFSET_PCT)
    }

    /** Saved content-scale levers as (scalePct, oversample); defaults reproduce native rendering. */
    private fun readContentScale(context: Context): Pair<Int, Boolean> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_SCALE_PCT, DEFAULT_SCALE_PCT) to
            prefs.getBoolean(KEY_OVERSAMPLE, false)
    }

    /** Package to project — user-selectable in settings, defaults to Yandex Navi. */
    private fun targetPackage(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TARGET_PACKAGE, NAVI_PACKAGE) ?: NAVI_PACKAGE

    /** Persist the user's chosen trigger keycode (from the learn-button dialog). */
    fun setTriggerKeyCode(context: Context, keyCode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TRIGGER_KEYCODE, keyCode).apply()
    }

    /** Remember the last VirtualDisplay id so a future process can release it if we die holding it. */
    private fun saveLastVdId(context: Context, id: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LAST_VD_ID, id).apply()
    }

    /**
     * Release a cluster display a prior process orphaned in the long-lived daemon. No-op when none
     * was recorded or the id is already gone (the daemon only releases displays in its own map).
     */
    private suspend fun releaseOrphanedDisplay(context: Context, helper: HelperClient) {
        val orphan = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_VD_ID, -1)
        if (orphan != -1) {
            Log.i(TAG, "releasing orphaned VirtualDisplay id=$orphan from a prior session")
            helper.releaseVirtualDisplay(orphan)
        }
    }

    /** Caller MUST hold [mutex]. Sets currentMode = mode only on full success, else OFF. */
    private suspend fun applyModeLocked(
        context: Context, mode: ClusterMode, helper: HelperClient, bootstrap: HelperBootstrap,
    ) {
        when (mode) {
            ClusterMode.OFF -> {
                pullBackToMain(context, helper, focus = true)
                hideOverlay(helper)
                projectedPackage = null
                currentMode = ClusterMode.OFF
            }
            ClusterMode.FULLSCREEN -> {
                if (project(context, mode, helper, bootstrap)) {
                    currentMode = mode
                } else {
                    // projection failed: keep state honest. project() already tore down the
                    // overlay/VD on its failure paths; make sure Navi is back on the main screen.
                    Log.e(TAG, "projection failed; falling back to OFF")
                    pullBackToMain(context, helper, focus = true)
                    projectedPackage = null
                    currentMode = ClusterMode.OFF
                }
            }
        }
    }

    /** Returns true only when the overlay is up, the VirtualDisplay exists, and Navi is pinned. */
    private suspend fun project(
        context: Context, mode: ClusterMode, helper: HelperClient, bootstrap: HelperBootstrap,
    ): Boolean {
        if (!bootstrap.ensureRunning()) {
            Log.e(TAG, "helper daemon not running; aborting projection"); return false
        }
        if (overlayView != null) hideOverlay(helper)  // defensive: never stack overlays
        if (!ensureOverlayPermission(context, helper)) {
            Log.e(TAG, "overlay permission unavailable; aborting projection"); return false
        }
        val display = resolveClusterDisplay(context) ?: run {
            Log.e(TAG, "cluster display not found"); return false
        }
        val (widthPct, heightPct) = readSizePct(context)
        val (offsetXPct, offsetYPct) = readOffsetPct(context)
        val geo = geometryFor(
            mode, clusterWidth, clusterHeight, widthPct, heightPct, offsetXPct, offsetYPct,
        ) ?: return false
        val (scalePct, oversample) = readContentScale(context)
        val plan = renderPlanFor(geo, clusterDensityDpi, scalePct, oversample)

        return try {
            val surface = withTimeoutOrNull(SURFACE_TIMEOUT_MS) {
                addOverlayAndAwaitSurface(context, display, geo, plan, helper)
            }
            if (surface == null) {
                Log.e(TAG, "overlay Surface not ready within ${SURFACE_TIMEOUT_MS}ms")
                hideOverlay(helper); return false
            }
            // Release a stale VD (a prior release that failed) before overwriting the id. If it
            // fails AGAIN, abort rather than dropping the only handle — otherwise the daemon-side
            // VirtualDisplay leaks unrecoverably.
            if (remoteDisplayId != -1) {
                val staleId = remoteDisplayId
                if (!helper.releaseVirtualDisplay(staleId)) {
                    Log.w(TAG, "stale releaseVirtualDisplay($staleId) failed; aborting to keep retry handle")
                    hideOverlay(helper); return false
                }
                remoteDisplayId = -1
            } else {
                // Cold start (fresh process): release any display this app orphaned in the daemon
                // before its last death, so cluster displays can't pile up across restarts. The
                // daemon only releases ids in its own map, so a reused/stale id is a safe no-op.
                releaseOrphanedDisplay(context, helper)
            }
            val id = helper.createVirtualDisplay(
                VD_NAME, plan.bufferWidth, plan.bufferHeight, plan.densityDpi, VIRTUAL_DISPLAY_FLAGS, surface,
            )
            if (id == null) {
                Log.e(TAG, "createVirtualDisplay failed"); hideOverlay(helper); return false
            }
            remoteDisplayId = id
            saveLastVdId(context, id)
            val pkg = targetPackage(context)
            Log.i(TAG, "VirtualDisplay id=$id ${plan.bufferWidth}x${plan.bufferHeight}@${plan.densityDpi}; launchAndForce $pkg")
            val ok = helper.launchAndForce(pkg, id, plan.bufferWidth, plan.bufferHeight)
            if (!ok) {
                Log.e(TAG, "launchAndForce failed"); hideOverlay(helper); return false
            }
            projectedPackage = pkg
            true
        } catch (e: Exception) {
            // wm.addView (BadTokenException) or any reflective daemon call can throw — tear the
            // overlay down and report failure so applyModeLocked falls back to OFF + pull-back,
            // keeping currentMode honest.
            Log.e(TAG, "projection threw: ${e.message}", e)
            hideOverlay(helper); false
        }
    }

    /**
     * App-side display lookup. The cluster's projection surfaces are virtual displays owned by
     * com.byd.containerservice, named "*XDJAScreenProjection*" (1280x480). Validated on-car
     * 2026-06-02: the panel composites the "..._1" surface in Full mode, so we pick it by name;
     * if it is absent we take the first projection surface, else fall back to id 2. Name-based
     * selection survives containerservice reassigning display ids at boot. Updates cluster W/H/dpi.
     */
    private fun resolveClusterDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val projectionDisplays = dm.displays.filter {
            it.name.contains("XDJAScreenProjection", ignoreCase = true)
        }
        val match = projectionDisplays.firstOrNull { it.name.endsWith("_1") }
            ?: projectionDisplays.firstOrNull()
            ?: dm.getDisplay(DEFAULT_CLUSTER_DISPLAY_ID)
        if (match != null) {
            val point = Point()
            @Suppress("DEPRECATION") match.getRealSize(point)
            clusterWidth = point.x
            clusterHeight = point.y
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") match.getMetrics(metrics)
            if (metrics.densityDpi > 0) clusterDensityDpi = metrics.densityDpi
            Log.i(TAG, "cluster display id=${match.displayId} ${clusterWidth}x$clusterHeight dpi=$clusterDensityDpi")
        }
        return match
    }

    /**
     * Builds the overlay container + SurfaceView on the cluster display (main thread) and
     * suspends until the Surface is created, returning the live Surface for the daemon to wrap.
     * Sets [overlayView] so a later hideOverlay() can tear it down even if we time out waiting.
     */
    private suspend fun addOverlayAndAwaitSurface(
        context: Context, display: Display, geo: ClusterGeometry, plan: RenderPlan, helper: HelperClient,
    ): Surface {
        val ready = CompletableDeferred<Surface>()
        withContext(Dispatchers.Main) {
            val displayContext = context.createDisplayContext(display)
            val wm = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val container = FrameLayout(displayContext)
            val surfaceView = SurfaceView(displayContext)
            // Layout = the physical window on the cluster (geo); the Surface buffer = the render plan
            // (plan), which is larger when oversampling. The compositor scales the buffer to the view,
            // so an oversampled buffer is downscaled into the same window (aspect preserved).
            val surfaceParams = FrameLayout.LayoutParams(geo.width, geo.height, Gravity.TOP or Gravity.START).apply {
                leftMargin = geo.xOffset
                topMargin = geo.yOffset
            }
            container.addView(surfaceView, surfaceParams)

            surfaceView.holder.setFixedSize(plan.bufferWidth, plan.bufferHeight)
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.d(TAG, "surfaceCreated buffer ${plan.bufferWidth}x${plan.bufferHeight} window ${geo.width}x${geo.height}")
                    if (!ready.isCompleted) ready.complete(holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.d(TAG, "surfaceDestroyed")
                    // Safety net: if the system tore the Surface down outside hideOverlay(), the
                    // VirtualDisplay would render into a dead Surface — release it (mirrors OpenBYD).
                    // Identity guard: during an OFF->FULLSCREEN re-projection this OLD overlay's
                    // callback must not release the NEW VirtualDisplay created for the next overlay.
                    scope.launch {
                        mutex.withLock {
                            if (overlayView === container) releaseRemoteDisplayIfAlive(helper)
                        }
                    }
                }
            })

            val overlayParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                OVERLAY_TYPE,
                OVERLAY_FLAGS,
                PixelFormat.TRANSLUCENT,  // -3
            )
            wm.addView(container, overlayParams)
            overlayView = container
        }
        return ready.await()
    }

    /** Move the projected app's task back to the main display and (optionally) refocus it. */
    private suspend fun pullBackToMain(context: Context, helper: HelperClient, focus: Boolean) {
        val pkg = projectedPackage ?: targetPackage(context)
        val taskId = helper.getTaskId(pkg) ?: run {
            Log.d(TAG, "pullBackToMain: projected task ($pkg) not found"); return
        }
        helper.moveTaskToDisplay(taskId, 0)
        helper.setTaskBounds(taskId, 0, 0, 0, 0)
        if (focus) helper.setFocusedTask(taskId)
    }

    /**
     * Release the VirtualDisplay and remove the overlay (main thread). [remoteDisplayId] is
     * cleared ONLY after a confirmed release so a failed release can be retried instead of
     * leaking the daemon-side VirtualDisplay.
     */
    private suspend fun hideOverlay(helper: HelperClient) {
        val id = remoteDisplayId
        if (id != -1) {
            if (helper.releaseVirtualDisplay(id)) remoteDisplayId = -1
            else Log.w(TAG, "releaseVirtualDisplay($id) failed; keeping id for retry")
        }
        withContext(Dispatchers.Main) {
            overlayView?.let { v ->
                try {
                    val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeView(v)
                } catch (e: Exception) {
                    Log.w(TAG, "removeView failed: ${e.message}")
                }
            }
            overlayView = null
        }
    }

    /** surfaceDestroyed safety net; caller holds [mutex]. No-op once the id is already cleared. */
    private suspend fun releaseRemoteDisplayIfAlive(helper: HelperClient) {
        val id = remoteDisplayId
        if (id != -1) {
            Log.d(TAG, "surfaceDestroyed: releasing leaked VirtualDisplay $id")
            if (helper.releaseVirtualDisplay(id)) remoteDisplayId = -1
        }
    }

    private suspend fun ensureOverlayPermission(context: Context, helper: HelperClient): Boolean {
        // Grant SYSTEM_ALERT_WINDOW + PROJECT_MEDIA via the daemon once per process. We can't gate
        // on PROJECT_MEDIA (no app-side query), and SYSTEM_ALERT_WINDOW may already be granted, so
        // the daemon call must run unconditionally — not only when canDrawOverlays is false.
        if (!projectionPermissionsGranted) {
            Log.i(TAG, "granting overlay + project_media via daemon")
            if (helper.grantOverlayPermission()) projectionPermissionsGranted = true
        }
        if (Settings.canDrawOverlays(context)) return true
        Log.w(TAG, "SYSTEM_ALERT_WINDOW still missing; waiting for grant to apply")
        repeat(10) {
            delay(200)
            if (Settings.canDrawOverlays(context)) return true
        }
        return false
    }

    /**
     * Diagnostic snapshot for the cluster mini-screen investigation (#48). Captures the display
     * topology visible to the app, our live projection state, and the native instrument-mode codes
     * read via the daemon, appending a labelled block to a file the tester can pull without ADB.
     * [label] marks which native cluster position (off / full / mini / arrows) was active when the
     * snapshot was taken. Returns a short human summary for a toast. EXPERIMENTAL — this and the
     * diagnostic button are removed before any release.
     */
    suspend fun captureDiagnostics(
        context: Context, helper: HelperClient, bootstrap: HelperBootstrap, label: String,
    ): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val daemonUp = try { bootstrap.ensureRunning() } catch (e: Exception) { false }

        // Native instrument-feature reads (dev=1007, tx=5). 1086337074 returned OFF=1/MINI=2/FULL=4
        // on Leopard 3 (which has no mini); 1276313665 was -10011 there. We read both to learn how
        // Sea Lion 07 encodes the mini position. Add candidates here if both come back as sentinels.
        val modeFids = listOf(1086337074, 1276313665)
        val modeReads = if (daemonUp) {
            modeFids.map { fid ->
                val v = try { helper.read(1007, fid, 5) } catch (e: Exception) { null }
                "  read(1007, $fid, tx5) = ${v ?: "null"}"
            }
        } else listOf("  (daemon not running, autoservice reads skipped)")

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val report = buildString {
            appendLine("=========== CLUSTER DIAG ===========")
            appendLine("label: $label")
            appendLine("timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            try {
                val pi = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    pi.longVersionCode.toString() else @Suppress("DEPRECATION") pi.versionCode.toString()
                appendLine("app: v${pi.versionName} (code=$code)")
            } catch (e: Exception) {
                appendLine("app: (version unavailable: ${e.message})")
            }
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")

            appendLine("--- our projection state ---")
            appendLine("currentMode: $currentMode")
            appendLine("remoteDisplayId (active VD): $remoteDisplayId")
            appendLine("picked cluster: ${clusterWidth}x$clusterHeight dpi=$clusterDensityDpi")
            appendLine(
                "prefs: enabled=${prefs.getBoolean(KEY_MIRROR_ENABLED, false)}" +
                    " width%=${prefs.getInt(KEY_WIDTH_PCT, MAX_PROJECTION_PCT)}" +
                    " height%=${prefs.getInt(KEY_HEIGHT_PCT, MAX_PROJECTION_PCT)}" +
                    " offsetX%=${prefs.getInt(KEY_OFFSET_X_PCT, CENTER_OFFSET_PCT)}" +
                    " offsetY%=${prefs.getInt(KEY_OFFSET_Y_PCT, CENTER_OFFSET_PCT)}" +
                    " scale%=${prefs.getInt(KEY_SCALE_PCT, DEFAULT_SCALE_PCT)}" +
                    " oversample=${prefs.getBoolean(KEY_OVERSAMPLE, false)}"
            )
            appendLine(
                "target: ${prefs.getString(KEY_TARGET_PACKAGE, NAVI_PACKAGE)}" +
                    " trigger=${prefs.getInt(KEY_TRIGGER_KEYCODE, DEFAULT_TRIGGER_KEYCODE)}"
            )

            appendLine("--- native instrument mode (dev=1007, tx=5) ---")
            modeReads.forEach { appendLine(it) }

            appendLine("--- displays (DisplayManager) ---")
            try {
                val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                dm.displays.forEach { d ->
                    val p = Point()
                    @Suppress("DEPRECATION") d.getRealSize(p)
                    val m = DisplayMetrics()
                    @Suppress("DEPRECATION") d.getMetrics(m)
                    appendLine(
                        "  id=${d.displayId} \"${d.name}\" ${p.x}x${p.y}" +
                            " dpi=${m.densityDpi} state=${d.state} rot=${d.rotation}" +
                            " flags=0x${Integer.toHexString(d.flags)}"
                    )
                }
            } catch (e: Exception) {
                appendLine("  (failed to enumerate displays: ${e.message})")
            }
            appendLine("====================================")
            appendLine()
        }

        val saveDir = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/storage/emulated/0/Download"),
            appContext.getExternalFilesDir(null),
        ).firstOrNull { it != null && (it.exists() || it.mkdirs()) && it.canWrite() }

        if (saveDir == null) {
            Log.e(TAG, "captureDiagnostics: no writable dir")
            return@withContext "Снимок не сохранён: нет доступа к файловой системе"
        }
        val outFile = File(saveDir, "bydmate-cluster-diag.txt")
        try {
            FileWriter(outFile, /* append = */ true).use { it.write(report) }
            Log.i(TAG, "captureDiagnostics: appended \"$label\" to ${outFile.absolutePath}")
            "Снимок \"$label\" записан:\n${outFile.absolutePath}"
        } catch (e: Exception) {
            Log.e(TAG, "captureDiagnostics write failed: ${e.message}")
            "Ошибка записи снимка: ${e.message}"
        }
    }
}
