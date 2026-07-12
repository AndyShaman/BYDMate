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
 * A session's SOC is read live during driving (the same value sent to ABRP and
 * shown in the widget); the matching TripEntity is created later, when
 * HistoryImporter syncs energydata. These happen in different process lifetimes,
 * so the bookmarks are persisted (SharedPreferences) and kept as a short, bounded
 * list — not a single in-memory slot. That lets a batch import (several trips
 * driven between two syncs) match each trip to its own session, and survives a
 * service/process restart between session end and the next sync.
 *
 * Surviving ignition-off: on DiLink the head unit (and our process) dies the
 * instant the car is switched off, long before the 30-sec idle-close that fires
 * [onSessionEnd] can run. So the in-progress session is persisted too — its
 * running end SOC is rewritten on every live tick via [updateLiveSoc], so the
 * last reading before the power-cut is already on disk. On the next startup
 * [reconcileStaleOpenSession] promotes that stale open session into a completed
 * bookmark. Without this the end SOC would never reach disk and no trip would
 * ever get enriched on a head unit that cuts power at ignition-off.
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

    // In-progress session — persisted to disk so its live SOC survives a hard
    // power-cut at ignition-off. Reconciled into [completed] on the next startup.
    private var pending: Snapshot? = loadPending()

    // Completed sessions, oldest first. Loaded from disk so they survive restart.
    private val completed: MutableList<Snapshot> = loadCompleted().toMutableList()

    // Bookkeeping for the updateLiveSoc write-gate below -- last soc/ts that
    // actually reached disk (not just the in-memory pending, which updates every
    // tick regardless of the gate).
    private var lastPersistedSoc: Int? = null
    private var lastPersistedTs: Long = 0L

    fun onSessionStart(soc: Int?, ts: Long) = synchronized(lock) {
        // Seed end = start so a session that ends before any further tick still
        // carries a sane endSoc.
        pending = Snapshot(startSoc = soc, endSoc = soc, startTs = ts, endTs = ts)
        persistPendingLocked()
    }

    /**
     * Lazy-init: fill the in-progress session's start SOC when it was null at the
     * exact start tick (autoservice SOC fid can sentinel-out during cold start).
     * Mirrors the mileage/totalElec lazy-init in TrackingService. No-op once a
     * non-null start SOC has been captured, or when no session is in progress.
     */
    fun fillStartSocIfMissing(soc: Int) = synchronized(lock) {
        val p = pending ?: return
        if (p.startTs != null && p.startSoc == null) {
            pending = p.copy(startSoc = soc)
            persistPendingLocked()
        }
    }

    /**
     * Persist the live SOC read each active tick as the running session end (and
     * lazily fill the start SOC if it sentinelled-out at the start tick). This is
     * what makes the end SOC survive a power-cut: the last value written here is
     * the SOC at the last live moment of the drive — effectively the trip-end SOC.
     * No-op when no session is in progress.
     *
     * The disk write is gated to actual soc changes or a [HEARTBEAT_MS] backstop.
     * `pending` is updated in memory unconditionally, and onSessionEnd (never gated)
     * always persists an exact endTs on the clean-shutdown path. The hard power-cut
     * path is the one that constrains the gate: the process dies, in-memory `pending`
     * is lost, and on the next start reconcileStaleOpenSession finalizes the trip from
     * the *persisted* endTs — only as fresh as the last gated write. [HEARTBEAT_MS] is
     * therefore held below END_TOLERANCE_MS so the persisted endTs stays inside
     * HistoryImporter's trip-end match window even across a power-cut.
     */
    fun updateLiveSoc(soc: Int, ts: Long) = synchronized(lock) {
        val p = pending ?: return
        pending = p.copy(startSoc = p.startSoc ?: soc, endSoc = soc, endTs = ts)
        if (soc == lastPersistedSoc && ts - lastPersistedTs < HEARTBEAT_MS) return
        lastPersistedSoc = soc
        lastPersistedTs = ts
        persistPendingLocked()
    }

    fun onSessionEnd(soc: Int?, ts: Long) = synchronized(lock) {
        val p = pending
        val startTs = p?.startTs
        // Store only completed sessions with a real window. A null startTs means
        // the session was already running at app launch (no onSessionStart fired) —
        // without a start there is nothing reliable to window-match on, so skip it.
        if (startTs != null) {
            // Engine-off can sentinel-out the SOC fid at the closing tick; fall back
            // to the last live reading rather than storing a null end.
            val endSoc = soc ?: p.endSoc
            completed.add(Snapshot(startSoc = p.startSoc, endSoc = endSoc, startTs = startTs, endTs = ts))
            trimLocked(ts)
            persistCompletedLocked()
        }
        pending = null
        clearPendingLocked()
    }

    /**
     * On startup, finalize a driving session that was left open by a hard
     * power-cut at ignition-off (the head unit died before the 30-sec idle-close
     * could fire [onSessionEnd]). If the persisted open session's last live tick
     * is older than [idleMs], promote it into a completed bookmark using its last
     * running end SOC, so HistoryImporter can still enrich that trip. No-op when
     * there is no open session, or when it is still live (a brief mid-drive
     * process restart, which should resume rather than close).
     */
    fun reconcileStaleOpenSession(now: Long, idleMs: Long) = synchronized(lock) {
        val p = pending ?: return
        val refTs = p.endTs ?: p.startTs
        if (refTs == null) {
            pending = null
            clearPendingLocked()
            return
        }
        if (now - refTs >= idleMs) {
            if (p.startTs != null) {
                completed.add(Snapshot(p.startSoc, p.endSoc, p.startTs, p.endTs ?: p.startTs))
                trimLocked(now)
                persistCompletedLocked()
            }
            pending = null
            clearPendingLocked()
        }
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
        persistCompletedLocked()
        return best
    }

    private fun trimLocked(nowTs: Long) {
        completed.removeAll { nowTs - (it.endTs ?: 0L) > MAX_AGE_MS }
        while (completed.size > MAX_COUNT) {
            completed.removeAt(0)
        }
    }

    private fun snapshotToJson(s: Snapshot): JSONObject = JSONObject().apply {
        put("startSoc", s.startSoc ?: JSONObject.NULL)
        put("endSoc", s.endSoc ?: JSONObject.NULL)
        put("startTs", s.startTs ?: JSONObject.NULL)
        put("endTs", s.endTs ?: JSONObject.NULL)
    }

    private fun snapshotFromJson(o: JSONObject): Snapshot = Snapshot(
        startSoc = if (o.isNull("startSoc")) null else o.getInt("startSoc"),
        endSoc = if (o.isNull("endSoc")) null else o.getInt("endSoc"),
        startTs = if (o.isNull("startTs")) null else o.getLong("startTs"),
        endTs = if (o.isNull("endTs")) null else o.getLong("endTs"),
    )

    private fun persistCompletedLocked() {
        val arr = JSONArray()
        for (s in completed) {
            arr.put(snapshotToJson(s))
        }
        prefs.edit().putString(KEY_SESSIONS, arr.toString()).apply()
    }

    private fun loadCompleted(): List<Snapshot> {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> snapshotFromJson(arr.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistPendingLocked() {
        val p = pending
        if (p == null) {
            clearPendingLocked()
            return
        }
        prefs.edit().putString(KEY_PENDING, snapshotToJson(p).toString()).apply()
    }

    private fun clearPendingLocked() {
        prefs.edit().remove(KEY_PENDING).apply()
    }

    private fun loadPending(): Snapshot? {
        val raw = prefs.getString(KEY_PENDING, null) ?: return null
        return try {
            snapshotFromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "session_soc_bookmarks"
        private const val KEY_SESSIONS = "completed_sessions"
        private const val KEY_PENDING = "pending_session"
        private const val END_TOLERANCE_MS = 30_000L
        // Max staleness of the on-disk pending endTs before updateLiveSoc forces a
        // write even without a soc change. MUST stay below END_TOLERANCE_MS: on a hard
        // power-cut (ignition-off, onSessionEnd never fires) reconcileStaleOpenSession
        // finalizes the trip from this *persisted* endTs, and HistoryImporter only
        // matches the energydata trip end within endTs + END_TOLERANCE_MS. A heartbeat
        // >= the tolerance lets endTs lag past the match window and silently drops the
        // trip's SOC enrichment. (Not SharedAdaptiveLoop's 60s: that heartbeat feeds
        // TripRecorder's 5-min cold-start gap, a far looser consumer than this one.)
        private const val HEARTBEAT_MS = 15_000L
        private const val MAX_COUNT = 20
        private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    }
}
