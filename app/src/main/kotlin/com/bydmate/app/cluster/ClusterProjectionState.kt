package com.bydmate.app.cluster

/** Package of the projection target (Yandex Navigator) on the DiLink head unit. */
const val NAVI_PACKAGE = "ru.yandex.yandexnavi"

/**
 * Native "карта на приборку / ИПЦ" lever, read RAW via autoservice transact 5 (getInt) under
 * shell uid — the SDK getInstrumentFeature path is closed (BYDAUTO_INSTRUMENT_COMMON). dev=1007
 * is the instrument device; fid 1086337074 is the lever naviauto writes. Validated on-car
 * 2026-06-02: value 1=OFF, 2=Simple, 4=Full. We only READ it (write stays denied, status -10011).
 *
 * Only "Full" hosts a pixel projection on this cluster. "Simple" is BYD's own native gauge widget
 * (signature-gated, no pixel surface we can target), so we treat it like OFF and tear projection down.
 */
const val IPC_LEVER_DEV = 1007
const val IPC_LEVER_FID = 1086337074

/** Cluster projection state, mirrored from the native ИПЦ lever (OFF / FULLSCREEN). */
enum class ClusterMode { OFF, FULLSCREEN }

/** Where Navi renders on the cluster overlay. VirtualDisplay size == SurfaceView size (1:1). */
data class ClusterGeometry(val width: Int, val height: Int, val xOffset: Int, val yOffset: Int)

/** Geometry for [mode] on a [clusterW] x [clusterH] cluster. OFF → null, FULLSCREEN → whole cluster. */
fun geometryFor(mode: ClusterMode, clusterW: Int, clusterH: Int): ClusterGeometry? = when (mode) {
    ClusterMode.OFF -> null
    ClusterMode.FULLSCREEN -> ClusterGeometry(clusterW, clusterH, 0, 0)
}

/**
 * Maps the native ИПЦ lever's raw value (fid [IPC_LEVER_FID]) to a projection mode.
 * Validated on-car 2026-06-02: 1=OFF, 2=Simple, 4=Full. Only Full is projectable, so both OFF (1)
 * and Simple (2) map to [ClusterMode.OFF] (projection torn down, native view shown). Any other value
 * — including the -10011 permission sentinel or an unexpected reading — returns null and the poller
 * skips the tick, leaving the current state untouched.
 */
fun clusterModeFromRaw(raw: Int): ClusterMode? = when (raw) {
    1 -> ClusterMode.OFF
    2 -> ClusterMode.OFF
    4 -> ClusterMode.FULLSCREEN
    else -> null
}
