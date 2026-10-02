package com.bydmate.app.media

/** Field diagnostics for the navigator notification (issue #199): whether it exists while the
 *  a11y read loses guidance. A post is logged when its shape (id, ongoing, kind, maneuver or not)
 *  changes, else once per [MIN_INTERVAL_MS]; a removal at most once per [MIN_INTERVAL_MS], since
 *  the Navigator flickers its notification on refresh, and the post after a logged removal is
 *  logged too. Lines carry numbers and ids only: titles and texts name the route's streets. */
internal class NaviNotifTraceGate {
    private var lastPostKey: String? = null
    private var lastPostLineMs: Long? = null
    private var lastRemovalLineMs: Long? = null
    private var removalLogged = false

    @Synchronized
    fun takePost(key: String, nowMs: Long): Boolean {
        val last = lastPostLineMs
        val take = key != lastPostKey || removalLogged || last == null || nowMs - last >= MIN_INTERVAL_MS
        lastPostKey = key
        if (take) {
            lastPostLineMs = nowMs
            removalLogged = false
        }
        return take
    }

    @Synchronized
    fun takeRemoval(nowMs: Long): Boolean {
        val last = lastRemovalLineMs
        if (last != null && nowMs - last < MIN_INTERVAL_MS) return false
        lastRemovalLineMs = nowMs
        removalLogged = true
        return true
    }

    companion object {
        const val MIN_INTERVAL_MS = 60_000L

        fun postKey(id: Int, ongoing: Boolean, kind: String, maneuverGaode: Int): String =
            "$id/$ongoing/$kind/${maneuverGaode != 0}"

        @Suppress("LongParameterList") // one value per field of the line
        fun postLine(
            pkg: String,
            id: Int,
            ongoing: Boolean,
            channel: String?,
            kind: String,
            maneuverGaode: Int,
            distanceMeters: Int,
            roadLength: Int,
        ): String = "navi notif posted: pkg=$pkg id=$id ongoing=$ongoing channel=$channel kind=$kind " +
            "man=$maneuverGaode dist=$distanceMeters roadLen=$roadLength"

        fun removedLine(pkg: String, id: Int): String = "navi notif removed: pkg=$pkg id=$id"
    }
}
