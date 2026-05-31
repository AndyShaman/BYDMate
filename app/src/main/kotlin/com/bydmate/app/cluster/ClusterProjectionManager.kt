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
 * Concurrency: every cycleMode press runs under [mutex], so two fast presses can't
 * build overlapping overlays or clobber [remoteDisplayId]. The whole transition —
 * including the daemon's VirtualDisplay create + launchAndForce — runs inside the
 * lock, so a press during an in-flight transition is queued, not dropped.
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
    private const val SWITCH_SETTLE_MS = 500L                 // OpenBYD refresh delay between pull-back and re-cast
    private const val SURFACE_TIMEOUT_MS = 3000L              // give up if the overlay Surface never gets created

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()

    @Volatile
    var currentMode: ClusterMode = ClusterMode.OFF
        private set

    private var overlayView: View? = null
    private var remoteDisplayId: Int = -1
    private var clusterWidth: Int = 1280
    private var clusterHeight: Int = 480
    private var clusterDensityDpi: Int = 320

    /** Button entry point: advance OFF → MINI → FULLSCREEN → OFF and apply, serialized. */
    fun cycleMode(context: Context, helper: HelperClient, bootstrap: HelperBootstrap) {
        val appContext = context.applicationContext
        scope.launch {
            mutex.withLock {
                val next = currentMode.next()
                Log.i(TAG, "cycleMode: $currentMode -> $next")
                applyModeLocked(appContext, next, helper, bootstrap)
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
            else -> {
                if (currentMode != ClusterMode.OFF) {
                    // switching MINI <-> FULLSCREEN: pull Navi back, tear down, re-cast at new size
                    pullBackToMain(helper, focus = false)
                    hideOverlay(helper)
                    delay(SWITCH_SETTLE_MS)
                }
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

        val surface = withTimeoutOrNull(SURFACE_TIMEOUT_MS) {
            addOverlayAndAwaitSurface(context, display, geo, helper)
        }
        if (surface == null) {
            Log.e(TAG, "overlay Surface not ready within ${SURFACE_TIMEOUT_MS}ms")
            hideOverlay(helper); return false
        }
        // Release a stale VD (e.g. a prior release that failed) before overwriting the id,
        // otherwise that VirtualDisplay would leak on the daemon side.
        if (remoteDisplayId != -1) {
            helper.releaseVirtualDisplay(remoteDisplayId); remoteDisplayId = -1
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
        return true
    }

    /** App-side display lookup (matches OpenBYD getClusterDisplayId). Updates cluster W/H/dpi. */
    private fun resolveClusterDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val match = dm.displays.firstOrNull {
            it.name.contains("fission", ignoreCase = true) || it.name.contains("cluster", ignoreCase = true)
        } ?: dm.getDisplay(DEFAULT_CLUSTER_DISPLAY_ID)
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
                    scope.launch { mutex.withLock { releaseRemoteDisplayIfAlive(helper) } }
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
        if (Settings.canDrawOverlays(context)) return true
        Log.w(TAG, "SYSTEM_ALERT_WINDOW missing; requesting grant via daemon")
        helper.grantOverlayPermission()
        repeat(10) {
            delay(200)
            if (Settings.canDrawOverlays(context)) return true
        }
        return false
    }
}
