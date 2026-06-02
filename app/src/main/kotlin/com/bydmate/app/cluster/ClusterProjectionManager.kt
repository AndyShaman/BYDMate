package com.bydmate.app.cluster

import android.content.Context
import android.graphics.Point
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
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
    // Master enable for the auto-mirror (settings checkbox). The poller in TrackingService reads it.
    const val KEY_MIRROR_ENABLED = "mirror_enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()

    @Volatile
    var currentMode: ClusterMode = ClusterMode.OFF
        private set

    private var overlayView: View? = null
    private var remoteDisplayId: Int = -1
    // PROJECT_MEDIA has no app-side query API (unlike SYSTEM_ALERT_WINDOW / canDrawOverlays),
    // so we grant both via the daemon once per process the first time we project.
    private var projectionPermissionsGranted = false
    private var clusterWidth: Int = 1280
    private var clusterHeight: Int = 480
    private var clusterDensityDpi: Int = 320

    /**
     * Mirror entry point: drive the projection to [mode] read from the native ИПЦ lever, serialized.
     * Idempotent — a no-op when already in [mode]. For FULLSCREEN we project only if Navi is already
     * running; we never auto-launch it, so when Navi is absent the call is a no-op and the next poll
     * retries once the user opens it. OFF always tears the projection down.
     */
    fun setMode(context: Context, mode: ClusterMode, helper: HelperClient, bootstrap: HelperBootstrap) {
        val appContext = context.applicationContext
        scope.launch {
            mutex.withLock {
                if (mode == currentMode) return@withLock
                if (mode != ClusterMode.OFF && helper.getTaskId(NAVI_PACKAGE) == null) {
                    Log.d(TAG, "setMode $mode deferred: $NAVI_PACKAGE not running")
                    return@withLock
                }
                Log.i(TAG, "setMode: $currentMode -> $mode")
                applyModeLocked(appContext, mode, helper, bootstrap)
            }
        }
    }

    /** Caller MUST hold [mutex]. Sets currentMode = mode only on full success, else OFF. */
    private suspend fun applyModeLocked(
        context: Context, mode: ClusterMode, helper: HelperClient, bootstrap: HelperBootstrap,
    ) {
        when (mode) {
            ClusterMode.OFF -> {
                pullBackToMain(helper, focus = true)
                hideOverlay(helper)
                currentMode = ClusterMode.OFF
            }
            ClusterMode.FULLSCREEN -> {
                if (project(context, mode, helper, bootstrap)) {
                    currentMode = mode
                } else {
                    // projection failed: keep state honest. project() already tore down the
                    // overlay/VD on its failure paths; make sure Navi is back on the main screen.
                    Log.e(TAG, "projection failed; falling back to OFF")
                    pullBackToMain(helper, focus = true)
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
        val geo = geometryFor(mode, clusterWidth, clusterHeight) ?: return false

        return try {
            val surface = withTimeoutOrNull(SURFACE_TIMEOUT_MS) {
                addOverlayAndAwaitSurface(context, display, geo, helper)
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
            }
            val id = helper.createVirtualDisplay(
                VD_NAME, geo.width, geo.height, clusterDensityDpi, VIRTUAL_DISPLAY_FLAGS, surface,
            )
            if (id == null) {
                Log.e(TAG, "createVirtualDisplay failed"); hideOverlay(helper); return false
            }
            remoteDisplayId = id
            Log.i(TAG, "VirtualDisplay id=$id; launchAndForce $NAVI_PACKAGE")
            val ok = helper.launchAndForce(NAVI_PACKAGE, id, geo.width, geo.height)
            if (!ok) {
                Log.e(TAG, "launchAndForce failed"); hideOverlay(helper); return false
            }
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
        context: Context, display: Display, geo: ClusterGeometry, helper: HelperClient,
    ): Surface {
        val ready = CompletableDeferred<Surface>()
        withContext(Dispatchers.Main) {
            val displayContext = context.createDisplayContext(display)
            val wm = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val container = FrameLayout(displayContext)
            val surfaceView = SurfaceView(displayContext)
            val surfaceParams = FrameLayout.LayoutParams(geo.width, geo.height, Gravity.TOP or Gravity.START).apply {
                leftMargin = geo.xOffset
                topMargin = geo.yOffset
            }
            container.addView(surfaceView, surfaceParams)

            surfaceView.holder.setFixedSize(geo.width, geo.height)
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.d(TAG, "surfaceCreated ${geo.width}x${geo.height}")
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

    /** Move Navi's task back to the main display and (optionally) refocus it. */
    private suspend fun pullBackToMain(helper: HelperClient, focus: Boolean) {
        val taskId = helper.getTaskId(NAVI_PACKAGE) ?: run {
            Log.d(TAG, "pullBackToMain: Navi task not found"); return
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
}
