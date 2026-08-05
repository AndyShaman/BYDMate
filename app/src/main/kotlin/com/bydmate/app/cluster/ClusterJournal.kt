package com.bydmate.app.cluster

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent ring of cluster-projection transitions, printed in the diagnostic dump.
 *
 * The field reports about the cluster (#134 grey screen in extended mode, #135 the cluster
 * snapping back to the map after a blind-spot camera) arrive hours after the fact, and the
 * DiLink logcat ring (256 KiB) has rotated the projection window out long before. Only what
 * survives on disk is readable at all, so every [ClusterProjectionManager] decision that a
 * Log.i/Log.e already marks — mode transitions with their trigger, display resolution,
 * VirtualDisplay create/release, the direct-freeform verdict, and each distinguishable
 * failure — lands here too.
 *
 * Consecutive identical payloads collapse into one line with an `(xN)` counter and the latest
 * timestamp: a projection that keeps failing is retried on every star press and would
 * otherwise flush the whole ring.
 *
 * Every append rewrites the whole ring, so two instances over the same prefs key lose each
 * other's lines — [Synchronized] guards an instance, not the key. The projection and the
 * blind-spot camera share one timeline (#135), so they must share one instance: take it from
 * [shared] (or, inside the Hilt graph, by injection — the provider returns the same object).
 */
class ClusterJournal(
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {

    @Synchronized
    fun append(payload: String) {
        val ts = SimpleDateFormat(TS_FORMAT, Locale.US).format(Date(now()))
        val lines = lines().toMutableList()

        val last = lines.lastOrNull()
        if (last != null && last.length > TS_PREFIX_LEN) {
            val match = REPEAT_SUFFIX.find(last)
            val lastPayload =
                (if (match != null) last.removeRange(match.range) else last).substring(TS_PREFIX_LEN)
            if (lastPayload == payload) {
                val count = (match?.groupValues?.get(1)?.toIntOrNull() ?: 1) + 1
                lines[lines.size - 1] = "$ts $payload (x$count)"
                write(lines)
                return
            }
        }

        lines.add("$ts $payload")
        write(lines.takeLast(MAX_ENTRIES))
    }

    /** Ring content, oldest first. */
    fun lines(): List<String> =
        prefs.getString(KEY_JOURNAL, "").orEmpty().lines().filter { it.isNotBlank() }

    private fun write(lines: List<String>) {
        prefs.edit().putString(KEY_JOURNAL, lines.joinToString("\n")).apply()
    }

    companion object {
        @Volatile private var instance: ClusterJournal? = null

        /**
         * The process-wide journal over the projection prefs file. [ClusterProjectionManager] is a
         * plain object outside the Hilt graph and has to ask for it here; injected consumers get
         * this very instance through the AppModule provider.
         */
        fun shared(context: Context): ClusterJournal =
            instance ?: synchronized(this) {
                instance ?: ClusterJournal(
                    context.applicationContext.getSharedPreferences(
                        ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE
                    )
                ).also { instance = it }
            }

        const val MAX_ENTRIES = 30
        private const val KEY_JOURNAL = "projection_journal"
        private const val TS_FORMAT = "yyyy-MM-dd HH:mm:ss"
        // "yyyy-MM-dd HH:mm:ss " prefix length — payload starts here.
        private const val TS_PREFIX_LEN = 20
        private val REPEAT_SUFFIX = Regex(""" \(x(\d+)\)$""")
    }
}
