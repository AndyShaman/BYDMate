package com.bydmate.app.cluster

/** Default projection target. The actual package is user-selectable in settings (KEY_TARGET_PACKAGE). */
const val NAVI_PACKAGE = "ru.yandex.yandexnavi"

/** Cluster projection state (OFF / FULLSCREEN). */
enum class ClusterMode { OFF, FULLSCREEN }

/** Where Navi renders on the cluster overlay: the window rectangle on the panel. */
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
 * [MIN_SCALE_PCT]..[MAX_SCALE_PCT] size the VirtualDisplay BUFFER as the inverse % of the window:
 * below 100 the app renders into a buffer larger than the window and the compositor shrinks it
 * (UI smaller, more map), 100 = 1:1 (native), above 100 = a smaller buffer stretched up (bigger
 * UI / less map). See [renderPlanFor] for why the density is never touched.
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
 * pixels the projected app renders into; the compositor scales them onto the window. [densityDpi]
 * is the logical density the app sees.
 */
data class RenderPlan(val bufferWidth: Int, val bufferHeight: Int, val densityDpi: Int)

/**
 * Render plan for [geo] given the content scale. [scalePct] (coerced to
 * [MIN_SCALE_PCT]..[MAX_SCALE_PCT]) sizes the buffer inversely — 50% renders into a buffer twice
 * the window, which the compositor shrinks — while [clusterDensityDpi], the panel's native
 * density, is passed through untouched. The default reproduces native rendering: buffer == window.
 *
 * The density is deliberately never scaled (#121, diagnosed on-car by Chpohan): a VirtualDisplay
 * created with a non-native density hands the projected app a Configuration change its engine may
 * treat as fatal — 2GIS (Qt) exits a few seconds after launch at every dpi except the native 320,
 * 100% reproducible. Same family as the cross-display relaunch fixed in v3.10.
 */
fun renderPlanFor(
    geo: ClusterGeometry,
    clusterDensityDpi: Int,
    scalePct: Int = DEFAULT_SCALE_PCT,
): RenderPlan {
    val clamped = scalePct.coerceIn(MIN_SCALE_PCT, MAX_SCALE_PCT)
    return RenderPlan(
        (geo.width * 100 / clamped).coerceAtLeast(1),
        (geo.height * 100 / clamped).coerceAtLeast(1),
        clusterDensityDpi,
    )
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

/**
 * #85/#62: power-down (18 -> pause -> 0) is an ИПЦ write too - it must only be sent when OUR
 * write-ahead marker shows this app powered the compositor up. Platforms with no cluster
 * display (Song family, DiLink 3-4) never set the marker, so their cluster stays untouched.
 */
fun shouldPowerDownCompositor(markerSet: Boolean): Boolean = markerSet

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

/**
 * Settings.Global enable_freeform_support value for the chosen projection transport.
 * Direct (freeform) needs 1; the VD pipeline needs nothing, so VD writes the factory
 * value 0 back. The flag is system-wide and survives an app uninstall, which is why
 * VD mode doubles as the "return the car to factory state" switch.
 *
 * [splitEnabled] is the split-screen master switch: when true, the flag stays at 1
 * regardless of the cluster projection transport — the OS needs freeform active for
 * split to work. Default false keeps the behavior byte-identical to the pre-split code.
 */
internal fun freeformFlagValue(directEnabled: Boolean, splitEnabled: Boolean = false): Int =
    if (directEnabled || splitEnabled) 1 else 0
