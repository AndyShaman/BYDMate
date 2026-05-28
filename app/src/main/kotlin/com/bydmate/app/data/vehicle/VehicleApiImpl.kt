package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.local.entity.VehicleWriteLogEntity
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.DiParsData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleApiImpl @Inject constructor(
    private val parsReader: ParsReader,
    private val autoservice: AutoserviceClient,
    private val helper: HelperClient,
    private val allowlist: WriteAllowlist,
    private val writeLogDao: VehicleWriteLogDao,
) : VehicleApi {

    // Liveness + snapshots — passthroughs.
    override suspend fun isAvailable(): Boolean = autoservice.isAvailable()
    override suspend fun readBatterySnapshot(): BatteryReading? = autoservice.readBatterySnapshot()
    override suspend fun readSnapshot(): DiParsData? = parsReader.fetch()

    // Individual readers — direct autoservice fid hits.
    override suspend fun readSoc(): Float? = autoservice.getFloat(1014, 1246777400)
    override suspend fun readSpeed(): Float? = autoservice.getFloat(1013, -1807745016)
    override suspend fun readMileageKm(): Float? =
        autoservice.getInt(1014, 1246765072)?.let { it / 10f }
    override suspend fun readPowerKw(): Int? = autoservice.getInt(1012, 339738656)
    override suspend fun readAcStatus(): Int? = autoservice.getInt(1000, 1077936144)
    override suspend fun readAcTemp(): Int? = autoservice.getInt(1000, 1077936168)
    override suspend fun readInsideTemp(): Int? = autoservice.getInt(1000, 1031798832)
    override suspend fun readExteriorTemp(): Int? = autoservice.getInt(1000, 1077936184)
    override suspend fun readFanLevel(): Int? = autoservice.getInt(1000, 1077936156)
    override suspend fun readWindowDriver(): Int? = autoservice.getInt(1001, 947912728)
    override suspend fun readWindowPassenger(): Int? = autoservice.getInt(1001, 1267728400)
    override suspend fun readWindowRearLeft(): Int? = autoservice.getInt(1001, 947912736)
    override suspend fun readWindowRearRight(): Int? = autoservice.getInt(1001, 947912752)

    // ─── Writes ────────────────────────────────────────────────────────────────

    override suspend fun dispatch(commandString: String): Result<Unit> {
        val resolved = CommandTranslator.resolve(commandString)
        if (resolved == null) {
            Log.w(TAG, "dispatch: unknown command '$commandString'")
            logWrite(commandString, -1, -1, 0, null, false, "no_translator_mapping", validated = false)
            return Result.failure(VehicleWriteError.AllowlistMiss(commandString, "no translator mapping"))
        }
        return doWrite(resolved.actionName, resolved.value)
    }

    // Climate
    override suspend fun writeAcOn(): Result<Unit> = doWrite("ac_on", 2)
    override suspend fun writeAcOff(): Result<Unit> = doWrite("ac_off", 1)
    override suspend fun writeSetDriverTemp(celsius: Int): Result<Unit> =
        doWrite("ac_temp_main", celsius)

    // Windows
    override suspend fun writeWindowDriver(percent: Int): Result<Unit> =
        doWrite("window_driver_pos", percent)
    override suspend fun writeWindowPassenger(percent: Int): Result<Unit> =
        doWrite("window_passenger_pos", percent)
    override suspend fun writeWindowRearLeft(percent: Int): Result<Unit> =
        doWrite("window_rear_left_pos", percent)
    override suspend fun writeWindowRearRight(percent: Int): Result<Unit> =
        doWrite("window_rear_right_pos", percent)

    // Locks
    override suspend fun writeLockDoors(): Result<Unit> = doWrite("doors_lock", 2)
    override suspend fun writeUnlockDoors(): Result<Unit> = doWrite("doors_unlock", 1)

    // Sunroof — one allowlist entry per mode (sunroof_open, sunroof_close, etc.)
    override suspend fun writeSunroof(mode: SunroofMode): Result<Unit> =
        doWrite("sunroof_${mode.name.lowercase()}", mode.value)

    // Sunshade
    override suspend fun writeSunshade(open: Boolean): Result<Unit> =
        doWrite(if (open) "sunshade_open" else "sunshade_close", if (open) 1 else 2)

    // ─── Core write helper ─────────────────────────────────────────────────────

    /**
     * Fail-soft write via WriteAllowlist + HelperClient. Never throws — wraps
     * all failures in Result.failure(VehicleWriteError). Audit entry always
     * written via [logWrite]. Validated failures additionally logged via
     * [maybeReportValidatedFailure].
     *
     * Flow (matches plan C.6 step 3):
     * 1. Allowlist miss      → AllowlistMiss
     * 2. Out of range        → OutOfRange
     * 3. helper.write throws → HelperUnreachable (IOException or other)
     * 4. helper.write false  → HelperUnreachable (validated) / Unsupported (non-validated)
     * 5. Readback == -10011  → Sentinel
     * 6. Readback != value   → ReadbackMismatch
     * 7. Otherwise           → Result.success(Unit)
     *
     * Sentinel -10011 in readback = "no data / permission denied" (transient).
     * Readback null = entry has no readbackFid → trust the write result.
     *
     * Best-effort semantics: Result.success(Unit) means the helper daemon accepted
     * the setInt call with status>=0. For entries without readbackFid (windows %,
     * climate, sunroof/sunshade), there is no independent verification that the
     * physical actuator moved. Locks and select climate flags do have readback.
     */
    // internal for testing the Unsupported path (non-validated helper-false flow).
    internal suspend fun doWrite(actionName: String, value: Int): Result<Unit> {
        val entry = allowlist.find(actionName) ?: run {
            Log.w(TAG, "doWrite: action=$actionName not in allowlist")
            logWrite(actionName, -1, -1, value, null, false, "allowlist_miss", validated = false)
            val err = VehicleWriteError.AllowlistMiss(actionName)
            return Result.failure(err)
        }

        if (value < entry.valueMin || value > entry.valueMax) {
            Log.w(TAG, "doWrite: action=$actionName value=$value out of range [${entry.valueMin}..${entry.valueMax}]")
            logWrite(actionName, entry.dev, entry.writeFid, value, null, false, "range", entry.validated)
            val err = VehicleWriteError.OutOfRange(actionName, "value=$value range=[${entry.valueMin}..${entry.valueMax}]")
            return Result.failure(err)
        }

        logAttempt(actionName, entry, value)

        val wrote: Boolean = try {
            helper.write(entry.dev, entry.writeFid, value)
        } catch (e: Exception) {
            Log.w(TAG, "doWrite: action=$actionName helper.write threw: ${e.message}")
            logWrite(actionName, entry.dev, entry.writeFid, value, null, false, "helper_exception", entry.validated)
            val err = VehicleWriteError.HelperUnreachable(actionName, e.message ?: "io error")
            maybeReportValidatedFailure(actionName, err, entry)
            return Result.failure(err)
        }

        if (!wrote) {
            return if (entry.validated) {
                Log.w(TAG, "doWrite: action=$actionName helper.write returned false (validated)")
                logWrite(actionName, entry.dev, entry.writeFid, value, null, false, "helper_fail", entry.validated)
                val err = VehicleWriteError.HelperUnreachable(actionName, "helper.write returned false")
                maybeReportValidatedFailure(actionName, err, entry)
                Result.failure(err)
            } else {
                Log.w(TAG, "doWrite: action=$actionName helper.write returned false (non-validated)")
                logWrite(actionName, entry.dev, entry.writeFid, value, null, false, "helper_fail_nonvalidated", entry.validated)
                val err = VehicleWriteError.Unsupported(actionName)
                Result.failure(err)
            }
        }

        val readback: Long? = entry.readbackFid?.let { helper.read(entry.dev, it) }

        if (readback == -10011L) {
            // For non-validated entries, sentinel is expected (crowd-validation in progress) — surface as Unsupported.
            // DAO error string stays "readback_sentinel" for diagnostic analysis.
            val err = if (entry.validated) VehicleWriteError.Sentinel(actionName) else VehicleWriteError.Unsupported(actionName)
            logWrite(actionName, entry.dev, entry.writeFid, value, readback.toInt(), false, "readback_sentinel", entry.validated)
            maybeReportValidatedFailure(actionName, err, entry)
            return Result.failure(err)
        }

        if (readback != null && readback.toInt() != value) {
            // For non-validated entries, mismatch is expected (crowd-validation in progress) — surface as Unsupported.
            // DAO error string stays "readback_mismatch" for diagnostic analysis.
            val err = if (entry.validated) VehicleWriteError.ReadbackMismatch(actionName, "expected=$value got=$readback") else VehicleWriteError.Unsupported(actionName)
            logWrite(actionName, entry.dev, entry.writeFid, value, readback.toInt(), false, "readback_mismatch", entry.validated)
            maybeReportValidatedFailure(actionName, err, entry)
            return Result.failure(err)
        }

        logWrite(actionName, entry.dev, entry.writeFid, value, readback?.toInt(), true, null, entry.validated)
        return Result.success(Unit)
    }

    // ─── Observability hook ────────────────────────────────────────────────────

    /**
     * Logs a WARN for validated actions that unexpectedly fail with
     * HelperUnreachable or ReadbackMismatch. These are the highest-signal
     * failures: the action was live-confirmed on Leopard 3, so a failure means
     * the helper daemon is down or the vehicle state machine rejected the command.
     *
     * TODO: route to Crashlytics when Firebase is integrated
     *   (requires google-services.json + Firebase gradle plugin — out of scope for Phase 2).
     */
    private fun maybeReportValidatedFailure(actionName: String, err: VehicleWriteError, entry: WriteEntry) {
        if (entry.validated && (err is VehicleWriteError.HelperUnreachable || err is VehicleWriteError.ReadbackMismatch)) {
            Log.w(VALIDATED_FAILURE_TAG, "action=$actionName error=${err::class.simpleName}: ${err.message}")
        }
    }

    // ─── Audit logger ──────────────────────────────────────────────────────────

    /**
     * Inserts a "pending" audit row (status=-2) before calling helper.write.
     * Ensures that if the coroutine is cancelled mid-helper (timeout, shutdown),
     * the attempted-write record is not lost. Wrapped in runCatching so a DAO
     * failure does not abort the write itself.
     *
     * Callers: doWrite, after range gate passes, BEFORE helper.write.
     * Skip on AllowlistMiss / OutOfRange — no helper call → no cancellation risk.
     */
    private suspend fun logAttempt(action: String, entry: WriteEntry, value: Int) {
        runCatching {
            writeLogDao.insert(
                VehicleWriteLogEntity(
                    ts = System.currentTimeMillis(),
                    actionName = action,
                    dev = entry.dev,
                    fid = entry.writeFid,
                    requested = value,
                    readback = null,
                    status = -2, // sentinel: attempted, outcome unknown
                    error = "attempt",
                    validated = entry.validated,
                )
            )
        }
    }

    private suspend fun logWrite(
        action: String, dev: Int, fid: Int, requested: Int, readback: Int?,
        ok: Boolean, error: String?, validated: Boolean,
    ) {
        writeLogDao.insert(
            VehicleWriteLogEntity(
                ts = System.currentTimeMillis(),
                actionName = action,
                dev = dev,
                fid = fid,
                requested = requested,
                readback = readback,
                status = if (ok) 0 else -1,
                error = error,
                validated = validated,
            )
        )
    }

    companion object {
        private const val TAG = "VehicleApiImpl"
        private const val VALIDATED_FAILURE_TAG = "VehicleApi.ValidatedFailure"
        // TODO: route to Crashlytics when Firebase is integrated
    }
}
