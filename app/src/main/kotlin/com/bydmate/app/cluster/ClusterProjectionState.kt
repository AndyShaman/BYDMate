package com.bydmate.app.cluster

/** Default projection target. The actual package is user-selectable in settings (KEY_TARGET_PACKAGE). */
const val NAVI_PACKAGE = "ru.yandex.yandexnavi"

/** Cluster projection state (OFF / FULLSCREEN). */
enum class ClusterMode { OFF, FULLSCREEN }

/** Where Navi renders on the cluster overlay. VirtualDisplay size == SurfaceView size (1:1). */
data class ClusterGeometry(val width: Int, val height: Int, val xOffset: Int, val yOffset: Int)

/** Window size bounds (% of the cluster panel), shared by the settings sliders and [geometryFor]. */
const val MIN_PROJECTION_PCT = 50
const val MAX_PROJECTION_PCT = 100

/**
 * Window position bounds (% of the free space left by a sub-100% window), shared by the position
 * sliders and [geometryFor]. 0 = pinned to the left/top edge, 50 = centered (legacy behaviour),
 * 100 = pinned to the right/bottom edge. Lets a tester slide the rendered map into the visible
 * region of the native mini-cluster window (#48).
 */
const val MIN_OFFSET_PCT = 0
const val MAX_OFFSET_PCT = 100
const val CENTER_OFFSET_PCT = 50

/**
 * Content-scale levers for the projected app, tuning what it renders INSIDE the window (how much map
 * fits) rather than where/how big the window is. Orthogonal to [geometryFor].
 *
 * [MIN_SCALE_PCT]..[MAX_SCALE_PCT] set the VirtualDisplay density as a % of the cluster's native dpi:
 * below 100 the app sees a "roomier" logical screen (UI smaller, more map), 100 = native (current
 * behaviour), above 100 = bigger UI / less map. [OVERSAMPLE_FACTOR] applies when the oversample
 * toggle is on — the VirtualDisplay renders at this multiple of the window pixel size and the Surface
 * downscales it to the window, giving the app a larger pixel viewport (more map) at the cost of a
 * heavier render and slightly softer text. Density reinterprets the same buffer; oversample enlarges
 * the buffer — the two combine independently.
 */
const val MIN_SCALE_PCT = 50
const val MAX_SCALE_PCT = 150
const val DEFAULT_SCALE_PCT = 100
const val OVERSAMPLE_FACTOR = 2

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
 * pixels the projected app renders into (== the window unless oversampling); the Surface downscales
 * them to the on-screen window. [densityDpi] is the logical density the app sees.
 */
data class RenderPlan(val bufferWidth: Int, val bufferHeight: Int, val densityDpi: Int)

/**
 * Render plan for [geo] given the content-scale levers. [clusterDensityDpi] is the cluster panel's
 * native density; [scalePct] (coerced to [MIN_SCALE_PCT]..[MAX_SCALE_PCT]) scales it. [oversample]
 * multiplies the buffer by [OVERSAMPLE_FACTOR] while leaving density untouched, so the app draws a
 * larger scene that is then downscaled into the same window. Defaults reproduce the pre-lever
 * behaviour: buffer == window, density == cluster dpi.
 */
fun renderPlanFor(
    geo: ClusterGeometry,
    clusterDensityDpi: Int,
    scalePct: Int = DEFAULT_SCALE_PCT,
    oversample: Boolean = false,
): RenderPlan {
    val factor = if (oversample) OVERSAMPLE_FACTOR else 1
    val dpi = clusterDensityDpi * scalePct.coerceIn(MIN_SCALE_PCT, MAX_SCALE_PCT) / 100
    return RenderPlan(geo.width * factor, geo.height * factor, dpi)
}

/** The other projection state — drives the steering-wheel toggle (приборка ↔ центр). */
fun nextMode(current: ClusterMode): ClusterMode =
    if (current == ClusterMode.FULLSCREEN) ClusterMode.OFF else ClusterMode.FULLSCREEN
