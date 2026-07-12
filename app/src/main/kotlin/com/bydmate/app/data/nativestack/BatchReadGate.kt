package com.bydmate.app.data.nativestack

import android.os.Build
import android.util.Log
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class BatchMode { VALIDATING, ACTIVE, OFF }

/**
 * Shadow-validation gate for the daemon TX_READ_BATCH transport.
 *
 * Every process starts (or restarts after a firmware OTA) in VALIDATING: both
 * the proven per-fid ADB path and the new batch path are read on every tick
 * and compared here, but only the ADB result is ever returned to callers.
 * After [OK_TARGET] clean matches the gate promotes to ACTIVE (batch becomes
 * primary, ADB stays as a fallback); after [MISMATCH_LIMIT] mismatches it
 * demotes to OFF (batch is never trusted again on this fingerprint). State
 * survives process restarts via [SettingsRepository]; a Build.FINGERPRINT
 * change invalidates it and forces re-validation.
 */
@Singleton
class BatchReadGate @Inject constructor(private val settings: SettingsRepository) {

    private val mutex = Mutex()

    @Volatile private var loaded = false
    @Volatile private var state: BatchMode = BatchMode.VALIDATING
    @Volatile private var ok = 0
    @Volatile private var mm = 0

    // Session-only: consecutive readBatch()==null ticks. Never persisted — an old daemon
    // (or one mid-restart) must not burn the on-disk VALIDATING budget; the next process
    // restart (APK/daemon update) gets a fresh session and retries the batch transport.
    @Volatile private var consecutiveUnavailable = 0
    @Volatile private var sessionOff = false

    suspend fun mode(): BatchMode {
        ensureLoaded()
        return if (sessionOff) BatchMode.OFF else state
    }

    /** Called on VALIDATING ticks after reading both transports. */
    suspend fun recordComparison(adb: DiParsData?, batch: DiParsData?) {
        if (adb == null || batch == null) return // transport hiccup, not convention evidence
        ensureLoaded()

        val matched = fieldsMatch(adb.soc?.toDouble(), batch.soc?.toDouble(), SOC_TOLERANCE.toDouble()) &&
            fieldsMatch(adb.mileage, batch.mileage, MILEAGE_TOLERANCE)

        mutex.withLock {
            if (matched) {
                consecutiveUnavailable = 0
                ok += 1
                if (ok >= OK_TARGET) persistState(BatchMode.ACTIVE) else persistCounters()
            } else {
                mm += 1
                ok = 0
                if (mm >= MISMATCH_LIMIT) {
                    Log.w(TAG, "batch read mismatch limit reached ($mm) — disabling batch transport")
                    persistState(BatchMode.OFF)
                } else {
                    persistCounters()
                }
            }
        }
    }

    /** Called on VALIDATING ticks when readBatch returned null (daemon down / old daemon). */
    fun recordBatchUnavailable() {
        consecutiveUnavailable += 1
        if (consecutiveUnavailable >= UNAVAILABLE_SESSION_LIMIT) {
            sessionOff = true
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return@withLock

            val storedFp = settings.getString(KEY_FP, "")
            val currentFp = currentFingerprint()
            if (storedFp != currentFp) {
                // No state stored yet, or firmware changed since the last write — re-validate.
                state = BatchMode.VALIDATING
                ok = 0
                mm = 0
                settings.setString(KEY_FP, currentFp)
                settings.setString(KEY_STATE, state.toStoredValue())
                settings.setString(KEY_OK, "0")
                settings.setString(KEY_MM, "0")
            } else {
                state = when (settings.getString(KEY_STATE, "validating")) {
                    "active" -> BatchMode.ACTIVE
                    "off" -> BatchMode.OFF
                    else -> BatchMode.VALIDATING
                }
                ok = settings.getString(KEY_OK, "0").toIntOrNull() ?: 0
                mm = settings.getString(KEY_MM, "0").toIntOrNull() ?: 0
            }
            loaded = true
        }
    }

    private suspend fun persistState(newState: BatchMode) {
        state = newState
        settings.setString(KEY_STATE, newState.toStoredValue())
        persistCounters()
    }

    private suspend fun persistCounters() {
        settings.setString(KEY_OK, ok.toString())
        settings.setString(KEY_MM, mm.toString())
    }

    // Build.FINGERPRINT is a non-null platform field on a real device, but resolves to null
    // under a plain JVM unit test (no Robolectric shadow) — normalize defensively so gate
    // load never NPEs on SettingsRepository's non-null String parameter.
    private fun currentFingerprint(): String = Build.FINGERPRINT ?: ""

    private fun BatchMode.toStoredValue(): String = when (this) {
        BatchMode.VALIDATING -> "validating"
        BatchMode.ACTIVE -> "active"
        BatchMode.OFF -> "off"
    }

    private fun fieldsMatch(a: Double?, b: Double?, tolerance: Double): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> abs(a - b) <= tolerance
    }

    companion object {
        const val OK_TARGET = 3            // clean matches to promote VALIDATING -> ACTIVE
        const val MISMATCH_LIMIT = 3       // total mismatches to demote -> OFF (sticky per fingerprint)
        const val UNAVAILABLE_SESSION_LIMIT = 5   // consecutive nulls -> stop trying until next process
        const val KEY_STATE = "batch_read_state"   // "validating" | "active" | "off"
        const val KEY_FP = "batch_read_fp"          // Build.FINGERPRINT at last state write
        const val KEY_OK = "batch_read_ok"          // Int
        const val KEY_MM = "batch_read_mm"          // Int
        const val SOC_TOLERANCE = 1                 // percent; absorbs value drift between the two reads
        const val MILEAGE_TOLERANCE = 0.15          // km; one 0.1 km odometer increment + fp epsilon

        private const val TAG = "BatchReadGate"
    }
}
