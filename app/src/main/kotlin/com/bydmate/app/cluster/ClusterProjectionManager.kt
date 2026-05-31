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
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the cluster projection lifecycle. An overlay SurfaceView is placed on the
 * cluster display in the app process; its Surface backs a VirtualDisplay created
 * in the shell-uid daemon (Phase 1), onto which Navi's task is pinned.
 *
 * Mirrors OpenBYD ClusterOverlayManager but uses our suspend HelperClient instead
 * of ICarControl shell strings. Single in-memory instance — mode resets to OFF on
 * process death (acceptable for v1; persistence is a later concern).
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    var currentMode: ClusterMode = ClusterMode.OFF
        private set

    private var overlayView: View? = null
    private var remoteDisplayId: Int = -1
    private var clusterWidth: Int = 1280
    private var clusterHeight: Int = 480
    private var clusterDensityDpi: Int = 320

    /** Button entry point: advance OFF → MINI → FULLSCREEN → OFF and apply. */
    fun cycleMode(context: Context, helper: HelperClient, bootstrap: HelperBootstrap) {
        val appContext = context.applicationContext
        val next = currentMode.next()
        Log.i(TAG, "cycleMode: $currentMode -> $next")
        scope.launch { applyMode(appContext, next, helper, bootstrap) }
    }

    private suspend fun applyMode(
        context: Context, mode: ClusterMode, helper: HelperClient, bootstrap: HelperBootstrap,
    ) {
        when (mode) {
            ClusterMode.OFF -> {
                pullBackToMain(helper, focus = true)
                hideOverlay(helper)
            }
            else -> {
                if (currentMode != ClusterMode.OFF) {
                    // switching MINI <-> FULLSCREEN: pull Navi back, tear down, re-cast at new size
                    pullBackToMain(helper, focus = false)
                    hideOverlay(helper)
                    delay(SWITCH_SETTLE_MS)
                }
                project(context, mode, helper, bootstrap)
            }
        }
        currentMode = mode
    }

    private suspend fun project(
        context: Context, mode: ClusterMode, helper: HelperClient, bootstrap: HelperBootstrap,
    ) {
        bootstrap.ensureRunning()  // best-effort; if the daemon is down the calls below fail soft
        if (overlayView != null) hideOverlay(helper)  // defensive: never stack overlays
        if (!ensureOverlayPermission(context, helper)) {
            Log.e(TAG, "overlay permission unavailable; aborting projection")
            return
        }
        val display = resolveClusterDisplay(context) ?: run {
            Log.e(TAG, "cluster display not found"); return
        }
        val geo = geometryFor(mode, clusterWidth, clusterHeight) ?: return
        withContext(Dispatchers.Main) { addOverlay(context, display, geo, helper) }
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

    /** Builds the overlay container + SurfaceView on the cluster display. Main thread. */
    private fun addOverlay(context: Context, display: Display, geo: ClusterGeometry, helper: HelperClient) {
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
                Log.d(TAG, "surfaceCreated; creating VirtualDisplay ${geo.width}x${geo.height}")
                val surface = holder.surface
                scope.launch { onSurfaceReady(geo, surface, helper) }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "surfaceDestroyed")
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

    /** Daemon side: create the VirtualDisplay from the Surface, then pin Navi onto it. */
    private suspend fun onSurfaceReady(geo: ClusterGeometry, surface: android.view.Surface, helper: HelperClient) {
        val id = helper.createVirtualDisplay(VD_NAME, geo.width, geo.height, clusterDensityDpi, VIRTUAL_DISPLAY_FLAGS, surface)
        if (id == null) {
            Log.e(TAG, "createVirtualDisplay failed"); return
        }
        remoteDisplayId = id
        Log.i(TAG, "VirtualDisplay id=$id; launchAndForce $NAVI_PACKAGE")
        val ok = helper.launchAndForce(NAVI_PACKAGE, id, geo.width, geo.height)
        Log.i(TAG, "launchAndForce ok=$ok")
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

    /** Release the VirtualDisplay and remove the overlay (main thread). */
    private suspend fun hideOverlay(helper: HelperClient) {
        if (remoteDisplayId != -1) {
            val id = remoteDisplayId
            remoteDisplayId = -1
            helper.releaseVirtualDisplay(id)
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
