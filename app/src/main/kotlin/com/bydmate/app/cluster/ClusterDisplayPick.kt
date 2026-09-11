package com.bydmate.app.cluster

import com.bydmate.app.helper.DisplayDevice

/**
 * Which display of the daemon's inventory is the cluster (#194), for firmwares where the app uid
 * sees display 0 only and [ClusterProjectionManager]'s own DisplayManager lookup finds nothing.
 *
 * Deliberately conservative, following byd-dashcast's enumerator: the projection moves the user's
 * navigator onto whatever this returns, so an unknown display is worse than no display at all —
 * hence null rather than "the first non-main one".
 *
 *  - display 0 is the head unit itself;
 *  - a FLAG_PRIVATE display owned by an ordinary app (uid >= 10000) belongs to that app — a
 *    third-party projection tool's own surface, never ours to move a task onto;
 *  - the cluster surfaces of every BYD firmware seen so far carry `xdja` or `fission` in their
 *    name (`fission_bg_XDJAScreenProjection` on Leopard 3, `fission_bg_xdjaVirtualSurface` on
 *    DiLink 4.0); `cluster` is the weaker second guess.
 *
 * Among equally-named candidates the `XDJAScreenProjection` family wins and is narrowed by
 * [pickProjectionDisplayName], so a firmware exposing both the `_0` and `_1` mirrors resolves to
 * exactly the surface the app-uid path would have picked; anything else falls back to the lowest
 * display id for a deterministic answer.
 */
fun pickClusterFromDaemon(devices: List<DisplayDevice>, preferFull: Boolean = false): DisplayDevice? {
    val eligible = devices.filter { d ->
        d.id != 0 && !(d.flags.contains("FLAG_PRIVATE") && d.ownerUid >= FIRST_APP_UID)
    }
    val candidates = eligible
        .filter { it.name.contains("xdja", ignoreCase = true) || it.name.contains("fission", ignoreCase = true) }
        .ifEmpty { eligible.filter { it.name.contains("cluster", ignoreCase = true) } }
    if (candidates.isEmpty()) return null
    val projection = candidates.filter { it.name.contains("XDJAScreenProjection", ignoreCase = true) }
    if (projection.isNotEmpty()) {
        val name = pickProjectionDisplayName(projection.map { it.name }, preferFull)
        projection.firstOrNull { it.name == name }?.let { return it }
    }
    return candidates.minByOrNull { it.id }
}

/** android.os.Process.FIRST_APPLICATION_UID — the boundary between system and ordinary apps. */
private const val FIRST_APP_UID = 10000
