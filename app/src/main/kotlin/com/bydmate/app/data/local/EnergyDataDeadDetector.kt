package com.bydmate.app.data.local

import android.util.Log

/**
 * Detects a dead leftover energydata DB (issue #63): the car keeps driving (mileage grows
 * across ignition sessions) while the DB file never changes.
 *
 * A live energydata (Leopard 3 / DiLink 4/5) gains a record around every drive cycle, so
 * its fingerprint changes between sessions and the streak below keeps resetting — by
 * construction the detector can never fire there. On a dead leftover the fingerprint is
 * frozen forever, the streak reaches [DEAD_STREAK_THRESHOLD] and the device is switched
 * to native trip recording until reset (Settings button) or auto-heal (file changes).
 *
 * All calls come from TripRecorder on the polling collector — no internal locking needed.
 * The "drove this session" marker is persisted mid-session, the moment the distance
 * threshold is crossed, rather than on the ignition-off edge: in the field the head unit
 * powers down with the car, killing the process before an off tick is ever delivered.
 */
class EnergyDataDeadDetector(
    private val reader: EnergyDataReader,
    private val store: EnergyDataLivenessStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private var sessionRunning = false
    private var sessionStartMileage: Double? = null
    private var sessionPendingMarked = false

    /** Cached verdict so the polling hot path never hits SharedPreferences per tick. */
    @Volatile
    private var cachedDead: Boolean? = null

    fun isDead(): Boolean {
        cachedDead?.let { return it }
        return store.isDead().also { cachedDead = it }
    }

    /** Feed every polling tick; edges are derived internally, repeats are no-ops. */
    fun onTick(ignition: Boolean, mileage: Double?) {
        if (ignition && !sessionRunning) {
            evaluate()
            sessionRunning = true
            // SentinelDecoder can pass a garbage raw 0.0 as a valid odometer read; a real
            // Leopard 3 fleet never has a genuinely 0.0 km odometer, so treat it the same
            // as "not known yet" and let the mid-session backfill below try again.
            sessionStartMileage = mileage?.takeIf { it > 0.0 }
            sessionPendingMarked = false
        } else if (ignition && sessionRunning) {
            // #63 regression: in the field the process dies WITH the car, so the
            // ignition-off edge below is almost never observed and pendingDriving was
            // never persisted (streak stayed 0 forever). Persist the "drove this
            // session" marker the moment the distance threshold is crossed instead —
            // one prefs write per session, guarded by sessionPendingMarked. The off
            // edge below still marks too (kept as a fallback for when it IS observed),
            // guarded by the same flag so it never double-writes.
            if (sessionStartMileage == null) sessionStartMileage = mileage?.takeIf { it > 0.0 }
            val start = sessionStartMileage
            if (!sessionPendingMarked && start != null && mileage != null &&
                mileage - start >= MIN_SESSION_KM
            ) {
                sessionPendingMarked = true
                store.setPendingDriving(true)
            }
        } else if (!ignition && sessionRunning) {
            sessionRunning = false
            val start = sessionStartMileage
            sessionStartMileage = null
            if (!sessionPendingMarked && start != null && mileage != null &&
                mileage - start >= MIN_SESSION_KM
            ) {
                store.setPendingDriving(true)
            }
            sessionPendingMarked = false
        }
    }

    /** Settings button: forget the verdict, re-detect from scratch. */
    fun reset() {
        store.reset()
        cachedDead = null
        sessionRunning = false
        sessionStartMileage = null
        sessionPendingMarked = false
    }

    /** One line for the diagnostic dump. */
    fun debugState(): String =
        "dead=${store.isDead()} streak=${store.streak()} pending=${store.pendingDriving()} " +
            "stored_fp=${store.fingerprint()} current_fp=${reader.sourceFingerprint()}"

    private fun evaluate() {
        val fpNow = reader.sourceFingerprint() ?: return
        val stored = store.fingerprint()
        when {
            stored == null -> {
                store.setFingerprint(fpNow.first, fpNow.second)
                store.setPendingDriving(false)
            }
            fpNow != stored -> {
                // The car is appending to the file — energydata is alive. Also heals a
                // false "dead" verdict so HistoryImporter and TripRecorder can never run
                // in parallel on a live file (that would duplicate trips).
                if (store.isDead()) {
                    Log.i(TAG, "dead-marked energydata changed on disk — healing back to passive")
                }
                store.reset()
                store.setFingerprint(fpNow.first, fpNow.second)
                cachedDead = false
            }
            store.pendingDriving() -> {
                store.setPendingDriving(false)
                if (now() - store.lastIncrementTs() < MIN_INCREMENT_SPACING_MS) return
                val streak = store.streak() + 1
                store.setStreak(streak)
                store.setLastIncrementTs(now())
                Log.i(TAG, "driving session over a frozen energydata file ($streak/$DEAD_STREAK_THRESHOLD)")
                if (streak >= DEAD_STREAK_THRESHOLD) {
                    Log.i(TAG, "energydata marked DEAD for this device — native trip recording ON")
                    store.setDead()
                    cachedDead = true
                }
            }
        }
    }

    companion object {
        // Reuse the tag already whitelisted in the in-app log dump.
        private const val TAG = "EnergyDataReader"
        internal const val DEAD_STREAK_THRESHOLD = 3
        internal const val MIN_SESSION_KM = 1.0
        internal const val MIN_INCREMENT_SPACING_MS = 30 * 60_000L
    }
}
