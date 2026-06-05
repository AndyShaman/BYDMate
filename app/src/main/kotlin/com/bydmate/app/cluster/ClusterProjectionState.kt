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

/** The other projection state — drives the steering-wheel toggle (приборка ↔ центр). */
fun nextMode(current: ClusterMode): ClusterMode =
    if (current == ClusterMode.FULLSCREEN) ClusterMode.OFF else ClusterMode.FULLSCREEN
