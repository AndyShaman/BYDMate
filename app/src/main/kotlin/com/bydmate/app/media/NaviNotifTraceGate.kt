package com.bydmate.app.media

/** Field diagnostics for the navigator notification (issue #199): whether it exists while the
 *  a11y read loses guidance. A post is logged when its shape (id, ongoing, kind, maneuver or not)
 *  changes, at most once per [CHANGE_MIN_INTERVAL_MS] with the changes in between counted, else
 *  once per [MIN_INTERVAL_MS]; a removal at most once per [MIN_INTERVAL_MS], since the Navigator
 *  flickers its notification on refresh, and the post after a logged removal is logged too.
 *  Lines carry numbers and ids only: titles and texts name the route's streets. */
internal class NaviNotifTraceGate {
    private var lastPostKey: String? = null
    private var lastPostLineMs: Long? = null
    private var lastRemovalLineMs: Long? = null
    private var removalLogged = false
    private var skippedChanges = 0

    /** Null when no line is due; else how many shape changes were skipped since the last line. */
    @Synchronized
    fun takePost(key: String, nowMs: Long): Int? {
        val last = lastPostLineMs
        val changed = key != lastPostKey
        lastPostKey = key
        val take = removalLogged || last == null || nowMs - last >= MIN_INTERVAL_MS ||
            (changed && nowMs - last >= CHANGE_MIN_INTERVAL_MS)
        if (!take) {
            if (changed) skippedChanges++
            return null
        }
        lastPostLineMs = nowMs
        removalLogged = false
        return skippedChanges.also { skippedChanges = 0 }
    }

    /** Binder thread: true when a removal line is due, so a suppressed one enqueues nothing. */
    @Synchronized
    fun takeRemoval(nowMs: Long): Boolean {
        val last = lastRemovalLineMs
        if (last != null && nowMs - last < MIN_INTERVAL_MS) return false
        lastRemovalLineMs = nowMs
        return true
    }

    /** Lane thread, when the removal line is written: the next post line is due. Marked here,
     *  not in [takeRemoval], so a post queued before the removal does not take that line. */
    @Synchronized
    fun removalWritten() {
        removalLogged = true
    }

    companion object {
        const val MIN_INTERVAL_MS = 60_000L
        const val CHANGE_MIN_INTERVAL_MS = 5_000L

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
            skippedChanges: Int,
        ): String = "navi notif posted: pkg=$pkg id=$id ongoing=$ongoing channel=$channel kind=$kind " +
            "man=$maneuverGaode dist=$distanceMeters roadLen=$roadLength skippedChanges=$skippedChanges"

        fun removedLine(pkg: String, id: Int): String = "navi notif removed: pkg=$pkg id=$id"
    }
}
