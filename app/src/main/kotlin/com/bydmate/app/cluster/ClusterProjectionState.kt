package com.bydmate.app.cluster

/** Package of the projection target (Yandex Navigator) on the DiLink head unit. */
const val NAVI_PACKAGE = "ru.yandex.yandexnavi"

/** Cluster projection state (OFF / FULLSCREEN). */
enum class ClusterMode { OFF, FULLSCREEN }

/** Where Navi renders on the cluster overlay. VirtualDisplay size == SurfaceView size (1:1). */
data class ClusterGeometry(val width: Int, val height: Int, val xOffset: Int, val yOffset: Int)

/** Geometry for [mode] on a [clusterW] x [clusterH] cluster. OFF → null, FULLSCREEN → whole cluster. */
fun geometryFor(mode: ClusterMode, clusterW: Int, clusterH: Int): ClusterGeometry? = when (mode) {
    ClusterMode.OFF -> null
    ClusterMode.FULLSCREEN -> ClusterGeometry(clusterW, clusterH, 0, 0)
}

/** The other projection state — drives the steering-wheel toggle (приборка ↔ центр). */
fun nextMode(current: ClusterMode): ClusterMode =
    if (current == ClusterMode.FULLSCREEN) ClusterMode.OFF else ClusterMode.FULLSCREEN
