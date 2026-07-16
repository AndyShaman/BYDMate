package com.bydmate.app.cluster

/** Default projection target. The actual package is user-selectable in settings (KEY_TARGET_PACKAGE). */
const val NAVI_PACKAGE = "ru.yandex.yandexnavi"

/** Cluster projection state (OFF / FULLSCREEN). */
enum class ClusterMode { OFF, FULLSCREEN }

/** Where Navi renders on the cluster overlay. VirtualDisplay size == SurfaceView size (1:1). */
data class ClusterGeometry(val width: Int, val height: Int, val xOffset: Int, val yOffset: Int)

/**
 * Window size bounds (% of the cluster panel), shared by the settings sliders and [geometryFor].
 * The minimum must stay below the native mini-window width: on Sea Lion 07 the mini zone is roughly
 * a third of the 1920 px panel, so the old 50% floor made the window wider than the zone and the
 * panel cut off Navi's left edge (where the maneuver/ETA panels live) (#48).
 */
const val MIN_PROJECTION_PCT = 20
const val MAX_PROJECTION_PCT = 100

/**
 * Window position bounds (% of the free space left by a sub-100% window), shared by the position
 * sliders and [geometryFor]. 0 = pinned to the left/top edge, 50 = centered (legacy behaviour),
 * 100 = pinned to the right/bottom edge. Lets the user slide the rendered map into the visible
 * region of the native mini-cluster window (#48).
 */
const val MIN_OFFSET_PCT = 0
const val MAX_OFFSET_PCT = 100
const val CENTER_OFFSET_PCT = 50

/**
 * Content scale for the projected app, tuning what it renders INSIDE the window (how much map
 * fits) rather than where/how big the window is. Orthogonal to [geometryFor].
 *
 * [MIN_SCALE_PCT]..[MAX_SCALE_PCT] set the VirtualDisplay density as a % of the cluster's native dpi:
 * below 100 the app sees a "roomier" logical screen (UI smaller, more map), 100 = native (current
 * behaviour), above 100 = bigger UI / less map.
 */
const val MIN_SCALE_PCT = 50
const val MAX_SCALE_PCT = 150
const val DEFAULT_SCALE_PCT = 100

/**
 * Geometry for [mode] on a [clusterW] x [clusterH] cluster. OFF → null. FULLSCREEN → a
 * rectangle scaled to [widthPct]/[heightPct] (% of the panel, each coerced to
 * [MIN_PROJECTION_PCT]..[MAX_PROJECTION_PCT]) and positioned by [offsetXPct]/[offsetYPct] within
 * the free space (% coerced to [MIN_OFFSET_PCT]..[MAX_OFFSET_PCT], 50 = centered). 100/100 size =
 * the whole cluster (no free space, so position has no effect). Smaller values shrink Navi's render
 * target, so the native cluster shows through the translucent overlay around the window.
 */
fun geometryFor(
    mode: ClusterMode,
    clusterW: Int,
    clusterH: Int,
    widthPct: Int = MAX_PROJECTION_PCT,
    heightPct: Int = MAX_PROJECTION_PCT,
    offsetXPct: Int = CENTER_OFFSET_PCT,
    offsetYPct: Int = CENTER_OFFSET_PCT,
): ClusterGeometry? = when (mode) {
    ClusterMode.OFF -> null
    ClusterMode.FULLSCREEN -> {
        val w = clusterW * widthPct.coerceIn(MIN_PROJECTION_PCT, MAX_PROJECTION_PCT) / 100
        val h = clusterH * heightPct.coerceIn(MIN_PROJECTION_PCT, MAX_PROJECTION_PCT) / 100
        val x = (clusterW - w) * offsetXPct.coerceIn(MIN_OFFSET_PCT, MAX_OFFSET_PCT) / 100
        val y = (clusterH - h) * offsetYPct.coerceIn(MIN_OFFSET_PCT, MAX_OFFSET_PCT) / 100
        ClusterGeometry(w, h, x, y)
    }
}

/**
 * VirtualDisplay buffer size + logical density for a window. [bufferWidth]/[bufferHeight] are the
 * pixels the projected app renders into (== the window). [densityDpi] is the logical density the
 * app sees.
 */
data class RenderPlan(val bufferWidth: Int, val bufferHeight: Int, val densityDpi: Int)

/**
 * Render plan for [geo] given the content scale. [clusterDensityDpi] is the cluster panel's
 * native density; [scalePct] (coerced to [MIN_SCALE_PCT]..[MAX_SCALE_PCT]) scales it. The default
 * reproduces native rendering: buffer == window, density == cluster dpi.
 */
fun renderPlanFor(
    geo: ClusterGeometry,
    clusterDensityDpi: Int,
    scalePct: Int = DEFAULT_SCALE_PCT,
): RenderPlan {
    val dpi = clusterDensityDpi * scalePct.coerceIn(MIN_SCALE_PCT, MAX_SCALE_PCT) / 100
    return RenderPlan(geo.width, geo.height, dpi)
}

/**
 * Freeform window bounds on the cluster display for [geo]: [left, top, right, bottom].
 * Direct projection reuses the exact overlay-window geometry, so the user's per-car
 * width/height/offset presets carry over unchanged from the VirtualDisplay pipeline.
 */
fun freeformBounds(geo: ClusterGeometry): IntArray =
    intArrayOf(geo.xOffset, geo.yOffset, geo.xOffset + geo.width, geo.yOffset + geo.height)

/** The other projection state — drives the steering-wheel toggle (приборка ↔ центр). */
fun nextMode(current: ClusterMode): ClusterMode =
    if (current == ClusterMode.FULLSCREEN) ClusterMode.OFF else ClusterMode.FULLSCREEN

/**
 * True when a compositor-on marker persisted by a PRIOR process should be recovered at service
 * start: the car shut down mid-projection, the off sequence (18 -> pause -> 0) never ran, and the
 * compositor woke up in projection mode with nobody drawing — a black cluster. A live projection
 * in THIS process ([mode] != OFF) owns the compositor and must not be powered down under it; with
 * auto-container off the user manages compositor power manually.
 */
fun shouldRecoverCompositor(markerSet: Boolean, mode: ClusterMode, autoContainer: Boolean): Boolean =
    markerSet && mode == ClusterMode.OFF && autoContainer

/** Direct-task crash recovery fires only when a marker survives AND no projection is live. */
fun shouldRecoverDirectTask(markerDisplayId: Int, mode: ClusterMode): Boolean =
    markerDisplayId != -1 && mode == ClusterMode.OFF

/**
 * The display's logical density is trustworthy as the native base only when no direct-mode
 * density override of ours can be active: no live member AND no surviving crash marker.
 * A surviving marker means a prior override may still be applied to the display —
 * absorbing it would compound the scale (320 -> 230 -> 161 -> ...).
 */
fun shouldAbsorbDisplayDensity(liveDirectDisplayId: Int, markerDisplayId: Int, metricsDpi: Int): Boolean =
    liveDirectDisplayId == -1 && markerDisplayId == -1 && metricsDpi > 0

/**
 * The direct-mode crash marker may be cleared only when the density reset was CONFIRMED and
 * the stranded task was verifiably reclaimed — or is gone (nothing to reclaim). A false from
 * mode/move keeps the marker so the next service start retries.
 */
fun shouldClearDirectMarker(resetOk: Boolean, taskFound: Boolean, modeOk: Boolean, moveOk: Boolean): Boolean =
    resetOk && (!taskFound || (modeOk && moveOk))
