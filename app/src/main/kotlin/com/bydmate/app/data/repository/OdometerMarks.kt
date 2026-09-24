package com.bydmate.app.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Live odometer marks for trips imported from energydata, which carries the trip distance
 * but not the odometer. One entry per app process ("mark session"): the first and the last
 * valid odometer reading this process saw, plus when the odometer last changed. An entry that
 * was alive at the end of a trip and whose odometer stopped by then holds the odometer at the
 * finish of that trip, however long the car then stayed on. HistoryImporter matches an
 * imported trip to such an entry ([match]).
 *
 * Writes: in memory on every valid reading, to disk (apply()) whenever the odometer changed
 * or at most every [HEARTBEAT_MS] otherwise, so lastTs keeps tracking "process still alive"
 * while the car stands on with the odometer frozen.
 *
 * The prefs file is deliberately outside BackupManager.PREFS_FILES: the marks are only
 * meaningful on the head unit that recorded them.
 */
@Singleton
class OdometerMarks @Inject constructor(
    @ApplicationContext context: Context
) {

    data class Mark(
        val processStartTs: Long,
        val firstTs: Long,
        val firstKm: Double,
        val lastTs: Long,
        val lastKm: Double,
        /** Ts of the last reading where the odometer changed; firstTs for a fresh entry. */
        val lastChangeTs: Long = lastTs,
    )

    /** Odometer at both ends of a trip; [reason] names why a side is null, for the log. */
    data class Match(
        val startKm: Double?,
        val finishKm: Double?,
        val markChangeTs: Long?,
        val reason: String,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val processStartTs = System.currentTimeMillis()

    // Oldest first; the last element is this process's entry once [current] is set.
    private val marks: MutableList<Mark> = load().toMutableList()
    private var current: Mark? = null
    private var lastPersistedKm: Double? = null
    private var lastPersistedTs = 0L

    fun onReading(km: Double?, ts: Long) = synchronized(lock) {
        if (km == null || km < MIN_VALID_ODOMETER_KM) return
        val cur = current
        if (cur == null || abs(km - cur.lastKm) > MAX_JUMP_KM) {
            val mark = Mark(processStartTs, ts, km, ts, km, lastChangeTs = ts)
            if (cur == null) {
                Log.i(TAG, "marks: entry opened km=$km ts=$ts")
            } else {
                // The pre-jump readings are on the wrong scale: left in, they would still
                // match a trip that ended before the jump.
                val before = marks.size
                marks.removeAll { it.processStartTs == processStartTs }
                Log.w(TAG, "marks: scale jump ${cur.lastKm} -> $km, dropped ${before - marks.size} pre-jump entries")
            }
            current = mark
            marks.add(mark)
            trimLocked(ts)
            persistLocked(km, ts)
            return
        }
        val updated = cur.copy(
            lastTs = ts,
            lastKm = km,
            lastChangeTs = if (km != cur.lastKm) ts else cur.lastChangeTs,
        )
        current = updated
        marks[marks.lastIndex] = updated
        if (km == lastPersistedKm && ts - lastPersistedTs < HEARTBEAT_MS) return
        persistLocked(km, ts)
    }

    fun snapshot(): List<Mark> = synchronized(lock) { marks.toList() }

    private fun trimLocked(nowTs: Long) {
        marks.removeAll { it !== current && nowTs - it.lastTs > MAX_AGE_MS }
        while (marks.size > MAX_COUNT) marks.removeAt(0)
    }

    private fun persistLocked(km: Double, ts: Long) {
        lastPersistedKm = km
        lastPersistedTs = ts
        val arr = JSONArray()
        for (m in marks) {
            arr.put(JSONObject().apply {
                put("processStartTs", m.processStartTs)
                put("firstTs", m.firstTs)
                put("firstKm", m.firstKm)
                put("lastTs", m.lastTs)
                put("lastKm", m.lastKm)
                put("lastChangeTs", m.lastChangeTs)
            })
        }
        prefs.edit().putString(KEY_MARKS, arr.toString()).apply()
    }

    private fun load(): List<Mark> {
        val raw = prefs.getString(KEY_MARKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val lastTs = o.getLong("lastTs")
                Mark(
                    processStartTs = o.getLong("processStartTs"),
                    firstTs = o.getLong("firstTs"),
                    firstKm = o.getDouble("firstKm"),
                    lastTs = lastTs,
                    lastKm = o.getDouble("lastKm"),
                    // Entries written before the key existed: the last reading stands in.
                    lastChangeTs = o.optLong("lastChangeTs", lastTs),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        // The importer's tag: already whitelisted by LogRecorder, and the marks exist for it.
        private const val TAG = "HistoryImporter"
        private const val PREFS_NAME = "odometer_marks"
        private const val KEY_MARKS = "marks"

        /** Startup guard: the odometer fid reads 0 before the car reports it. */
        private const val MIN_VALID_ODOMETER_KM = 1.0

        /** A step this large is a scale switch (catalog resolved), not driving. */
        private const val MAX_JUMP_KM = 1500.0
        private const val HEARTBEAT_MS = 15_000L
        private const val MAX_COUNT = 50
        private const val MAX_AGE_MS = 14L * 24 * 3600 * 1000

        /** Slack for the skew between energydata timestamps and our readings. */
        private const val END_SKEW_MS = 10_000L

        /** The process must have read the odometer this close to the trip end or later. */
        private const val ALIVE_BEFORE_END_MS = 60_000L

        /** Allowed shortfall of the start below an earlier reading: 0.1-km and whole-km cars. */
        private const val BELOW_TOLERANCE_KM = 0.15
        private const val BELOW_TOLERANCE_WHOLE_KM = 1.0

        /**
         * Odometer at the finish and the start of the trip [startTs]..[endTs] of [km]. An entry
         * qualifies when it was alive at the trip end (firstTs <= endTs + 10 s and
         * lastTs >= endTs - 60 s) and its odometer stopped by then (lastChangeTs <= endTs + 10 s):
         * how long the car stayed on afterwards does not matter, driving on does. Among the
         * qualifying entries the greatest lastChangeTs wins; finish = its lastKm, start = finish
         * minus the trip distance (energydata km equals the odometer delta within 0.1 km).
         * Both rounded to 0.1 km; a zero-km record gets start = finish.
         *
         * The odometer never decreases, so the start cannot lie below any reading taken at or
         * before [startTs] (lastKm of entries with lastTs <= startTs, firstKm of entries with
         * firstTs <= startTs). A start more than 0.15 km below the greatest such reading (1 km
         * when no stored reading ever showed a tenth, i.e. a whole-km firmware) means the finish is too low, typically a
         * process that died before the car stopped: both sides are then null.
         */
        fun match(marks: List<Mark>, startTs: Long, endTs: Long, km: Double?): Match {
            val alive = marks.filter {
                it.firstTs <= endTs + END_SKEW_MS && it.lastTs >= endTs - ALIVE_BEFORE_END_MS
            }
            if (alive.isEmpty()) return Match(null, null, null, "no entry alive at end")
            val best = alive.filter { it.lastChangeTs <= endTs + END_SKEW_MS }.maxByOrNull { it.lastChangeTs }
            if (best == null) {
                val moved = alive.minOf { it.lastChangeTs }
                return Match(null, null, moved, "moved after end (+${(moved - endTs) / 1000}s)")
            }
            val finish = round1(best.lastKm)
            val start = km?.let { round1(finish - it) }?.takeIf { it >= 0.0 }
            val earlier = marks.flatMap { m ->
                listOfNotNull(m.lastKm.takeIf { m.lastTs <= startTs }, m.firstKm.takeIf { m.firstTs <= startTs })
            }.maxOrNull()
            if (start != null && earlier != null) {
                // Whole-km firmware only when no stored reading ever showed a tenth: two values
                // that happen to be whole on a 0.1 km car must not widen the tolerance.
                val wholeKmCar = marks.all { isWhole(it.firstKm) && isWhole(it.lastKm) }
                val tol = if (wholeKmCar) BELOW_TOLERANCE_WHOLE_KM else BELOW_TOLERANCE_KM
                if (start < earlier - tol) {
                    return Match(null, null, best.lastChangeTs, "start below earlier reading $earlier")
                }
            }
            val reason = when {
                km == null -> "no trip km"
                start == null -> "start < 0"
                else -> "ok"
            }
            return Match(start, finish, best.lastChangeTs, reason)
        }

        private fun round1(v: Double): Double = (v * 10.0).roundToLong() / 10.0

        private fun isWhole(v: Double): Boolean = abs(v - v.roundToLong()) < 1e-6
    }
}
