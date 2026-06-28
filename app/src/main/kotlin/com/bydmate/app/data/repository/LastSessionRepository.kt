package com.bydmate.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Holds SOC bookmarks for completed driving sessions so HistoryImporter can
 * graft socStart/socEnd onto trips imported from energydata (which carries no
 * SOC of its own).
 *
 * A session's SOC is read live during driving; the matching TripEntity is
 * created later, when HistoryImporter syncs energydata. These happen in
 * different process lifetimes, so the bookmarks are persisted (SharedPreferences)
 * and kept as a short, bounded list — not a single in-memory slot. That lets a
 * batch import (several trips driven between two syncs) match each trip to its
 * own session, and survives a service/process restart between session end and
 * the next sync.
 *
 * Only completed sessions (both startTs and endTs known) are stored, so every
 * stored bookmark has a real time window to match against. The in-progress
 * session is kept in memory only; a restart mid-session drops it (best-effort,
 * same as before the native-stack migration).
 *
 * Thread safety: all mutation and matching is guarded by [lock]; SharedPreferences
 * writes use apply() so the non-suspend TrackingService hot path never blocks.
 */
@Singleton
class LastSessionRepository @Inject constructor(
    @ApplicationContext context: Context
) {

    data class Snapshot(
        val startSoc: Int?,
        val endSoc: Int?,
        val startTs: Long?,
        val endTs: Long?,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    // In-progress session (in-memory only) — promoted to [completed] on session end.
    private var pendingStartSoc: Int? = null
    private var pendingStartTs: Long? = null

    // Completed sessions, oldest first. Loaded from disk so they survive restart.
    private val completed: MutableList<Snapshot> = loadCompleted().toMutableList()

    fun onSessionStart(soc: Int?, ts: Long) = synchronized(lock) {
        pendingStartSoc = soc
        pendingStartTs = ts
    }

    /**
     * Lazy-init: fill the in-progress session's start SOC when it was null at the
     * exact start tick (autoservice SOC fid can sentinel-out during cold start).
     * Mirrors the mileage/totalElec lazy-init in TrackingService. No-op once a
     * non-null start SOC has been captured, or when no session is in progress.
     */
    fun fillStartSocIfMissing(soc: Int) = synchronized(lock) {
        if (pendingStartTs != null && pendingStartSoc == null) {
            pendingStartSoc = soc
        }
    }

    fun onSessionEnd(soc: Int?, ts: Long) = synchronized(lock) {
        val startTs = pendingStartTs
        // Store only completed sessions with a real window. A null startTs means
        // the session was already running at app launch (no onSessionStart fired) —
        // without a start there is nothing reliable to window-match on, so skip it.
        if (startTs != null) {
            completed.add(Snapshot(startSoc = pendingStartSoc, endSoc = soc, startTs = startTs, endTs = ts))
            trimLocked(ts)
            persistLocked()
        }
        pendingStartSoc = null
        pendingStartTs = null
    }

    /**
     * Find the best-matching completed session for a trip that ended at [tripEndTs],
     * remove it (consume so it can't bind to a second trip), and return it. Null when
     * nothing matches. Match = tripEndTs within [startTs .. endTs + END_TOLERANCE_MS];
     * among candidates, the session whose endTs is nearest to tripEndTs.
     */
    fun takeMatch(tripEndTs: Long): Snapshot? = synchronized(lock) {
        val best = completed
            .filter { s ->
                val st = s.startTs
                val en = s.endTs
                st != null && en != null && tripEndTs in st..(en + END_TOLERANCE_MS)
            }
            .minByOrNull { abs((it.endTs ?: 0L) - tripEndTs) }
            ?: return null
        completed.remove(best)
        persistLocked()
        return best
    }

    private fun trimLocked(nowTs: Long) {
        completed.removeAll { nowTs - (it.endTs ?: 0L) > MAX_AGE_MS }
        while (completed.size > MAX_COUNT) {
            completed.removeAt(0)
        }
    }

    private fun persistLocked() {
        val arr = JSONArray()
        for (s in completed) {
            arr.put(
                JSONObject().apply {
                    put("startSoc", s.startSoc ?: JSONObject.NULL)
                    put("endSoc", s.endSoc ?: JSONObject.NULL)
                    put("startTs", s.startTs)
                    put("endTs", s.endTs)
                }
            )
        }
        prefs.edit().putString(KEY_SESSIONS, arr.toString()).apply()
    }

    private fun loadCompleted(): List<Snapshot> {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Snapshot(
                    startSoc = if (o.isNull("startSoc")) null else o.getInt("startSoc"),
                    endSoc = if (o.isNull("endSoc")) null else o.getInt("endSoc"),
                    startTs = if (o.isNull("startTs")) null else o.getLong("startTs"),
                    endTs = if (o.isNull("endTs")) null else o.getLong("endTs"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "session_soc_bookmarks"
        private const val KEY_SESSIONS = "completed_sessions"
        private const val END_TOLERANCE_MS = 30_000L
        private const val MAX_COUNT = 20
        private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    }
}
