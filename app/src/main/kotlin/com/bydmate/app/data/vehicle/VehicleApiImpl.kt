package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.nativestack.FidAddress
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.local.entity.VehicleWriteLogEntity
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.DiParsData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleApiImpl @Inject constructor(
    private val parsReader: ParsReader,
    private val autoservice: AutoserviceClient,
    private val helper: HelperClient,
    private val allowlist: WriteAllowlist,
    private val writeLogDao: VehicleWriteLogDao,
    private val seatStore: SeatChannelStore,
    private val windowStore: WindowChannelStore,
    // Null in unit tests (no SharedPreferences); production gets the singleton from AppModule.
    private val seatJournal: SeatCommandJournal? = null,
) : VehicleApi {

    private val seatChannel = AdaptiveSeatChannel(
        SeatWriter { name, value -> doWriteOutcome(name, value) },
        seatStore,
        SeatReadback { group -> readSeatStatus(group) },
        seatJournal,
    )

    private val windowChannel = WindowChannelRouter(helper, windowStore)

    // Owns the delayed window readback logging only — a write never waits for it.
    // internal var (not a constructor param — Hilt's @Inject constructor can't carry a
    // default here) so tests can swap in a deterministic scope (e.g. Dispatchers.Unconfined)
    // instead of racing the real Dispatchers.IO scheduler.
    internal var readbackScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    /**
     * DiLink 5.0 fid, falling back to its alternative-generation twin ONLY when the primary
     * reports DEVICE_THE_FEATURE_LINK_ERROR — the fid is absent on this generation (#79).
     * Any other sentinel is a transient fault and must not be answered from a different fid.
     */
    override suspend fun readWindowRearRight(): Int? {
        val raw = autoservice.getIntRaw(1001, 947912752) ?: return null
        SentinelDecoder.decodeInt(raw)?.let { return it }
        if (raw != SentinelDecoder.FEATURE_LINK_ERROR) return null
        return autoservice.getInt(1001, 1267728408)
    }

    // ─── Writes ────────────────────────────────────────────────────────────────

    override suspend fun dispatch(commandString: String): Result<Unit> {
        CommandTranslator.resolveSeat(commandString)?.let { seat ->
            // Seat writes are switch then level (two binder transacts). Wrap in NonCancellable
            // so a hard stop between the two writes never leaves the seat half-commanded
            // (e.g. heat switched on but level unset).
            return withContext(NonCancellable) {
                if (seatChannel.actuate(seat.group, seat.level)) Result.success(Unit)
                else Result.failure(VehicleWriteError.Unsupported("seat:${seat.group}:${seat.level}"))
            }
        }
        val resolved = CommandTranslator.resolve(commandString)
        if (resolved.isEmpty()) {
            Log.w(TAG, "dispatch: unknown command '$commandString'")
            logWrite(commandString, -1, -1, 0, null, false, "no_translator_mapping", validated = false)
            return Result.failure(VehicleWriteError.AllowlistMiss(commandString, "no translator mapping"))
        }
        if (resolved.size == 1) {
            val r = resolved[0]
            return doWrite(r.actionName, r.value)
        }
        // Composite command (e.g. window aggregates, fridge presets) → fan out to several
        // per-door writes. Attempt every sub-write (no short-circuit: a partial open beats
        // stopping at the first stuck pane); succeed only if all do.
        // NonCancellable: once the sequence has started, run ALL writes to completion so a
        // hard stop between sub-writes never leaves the car half-commanded (e.g. two windows
        // open, two still closed). Cancellation takes effect after the loop exits.
        var firstError: Throwable? = null
        withContext(NonCancellable) {
            for ((i, r) in resolved.withIndex()) {
                // Song L (#97): a burst of window writes ~5 ms apart all return status=1,
                // but only the later panes actuate — the first write is silently dropped.
                // Spacing the sub-writes out keeps every write effective on that platform.
                if (i > 0) delay(COMPOSITE_WRITE_STAGGER_MS)
                val res = doWrite(r.actionName, r.value)
                if (res.isFailure && firstError == null) firstError = res.exceptionOrNull()
            }
        }
        return firstError?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    // Climate
    override suspend fun writeAcOn(): Result<Unit> = doWrite("ac_on", 1)
    override suspend fun writeAcOff(): Result<Unit> = doWrite("ac_off", 0)
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
    internal suspend fun doWrite(requestedAction: String, requestedValue: Int): Result<Unit> {
        // Percent window writes are re-targeted to the CTRL channel on firmwares without
        // the percent family (#79). Percent-capable units and an undecided probe get the
        // request back unchanged, so their write path is untouched.
        val routed = windowChannel.route(requestedAction, requestedValue)
        val actionName = routed.actionName
        val value = routed.value

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

        // Window writes have no readback fid and a history of "accepted but nothing moved"
        // (#97 Song L, #64): sample the pane position now, and again after the write, to tell
        // a real actuation from a silently dropped one. Started before helper.write on its own
        // scope so a slow/hung read channel can never delay the write itself.
        val windowReadField = WINDOW_READ_FIELDS[actionName.lowercase()]

        logAttempt(actionName, entry, value)

        val windowBefore = windowReadField?.let { field -> readbackScope.async { resolveWindowReadFid(field) } }

        val wrote: Boolean = try {
            if (windowBefore != null) {
                // Bounded wait so the "before" sample usually precedes the write, but a hung
                // read channel costs at most WINDOW_BEFORE_READ_BUDGET_MS.
                withTimeoutOrNull(WINDOW_BEFORE_READ_BUDGET_MS) { windowBefore.await() }
            }
            helper.write(entry.dev, entry.writeFid, value)
        } catch (e: Exception) {
            // Rethrow cancellation so callers outside the NonCancellable write unit
            // (status reads, channel resolution) can still be cancelled normally.
            if (e is CancellationException) throw e
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

        // The pane accepted the command but never moved: report the truth instead of a
        // success the driver can see is wrong (#97 — the first write of a burst is dropped).
        if (windowBefore != null) {
            val stuck = verifyWindowMoved(actionName, requestedAction, requestedValue, entry, value, windowBefore)
            if (stuck != null) {
                logWrite(actionName, entry.dev, entry.writeFid, value, null, false, "window_noop", entry.validated)
                val err = VehicleWriteError.ReadbackMismatch(actionName, stuck)
                maybeReportValidatedFailure(actionName, err, entry)
                return Result.failure(err)
            }
        }

        // INFO so a successful dispatch is visible in logcat (the DAO row is private).
        // Pair with the HelperClient "status=" line to tell a real action from a no-op.
        Log.i(TAG, "doWrite OK: action=$actionName dev=${entry.dev} fid=${entry.writeFid} value=$value readback=$readback validated=${entry.validated}")
        logWrite(actionName, entry.dev, entry.writeFid, value, readback?.toInt(), true, null, entry.validated)
        return Result.success(Unit)
    }

    // ─── Window readback logging ───────────────────────────────────────────────

    /**
     * Did the pane actually start moving after an accepted write? Samples the position twice,
     * 400 ms apart (the seat channel's pattern), and compares each sample with the "before"
     * position taken around the write. Any movement — even a pane still travelling — proves
     * the write landed. Returns null when the write is good or when there is no evidence
     * against it, else a short Russian reason.
     *
     * Deliberately fail-open: a read that does not complete, a sentinel, a pane already at the
     * requested position, or a command whose target percent is unknown (CTRL detents, vent)
     * all count as "no evidence", never as a failure. Only the requested percent actions can
     * be judged, and only when the pane had somewhere to travel.
     */
    @Suppress("LongParameterList")
    private suspend fun verifyWindowMoved(
        actionName: String,
        requestedAction: String,
        requestedValue: Int,
        entry: WriteEntry,
        value: Int,
        beforeSample: Deferred<Pair<FidAddress, Int?>>,
    ): String? {
        val target = requestedValue.takeIf { requestedAction.endsWith(POS_ACTION_SUFFIX) && it in 0..100 }
            ?: return null
        val (readFid, beforeRaw) = withTimeoutOrNull(WINDOW_BEFORE_READ_BUDGET_MS) { beforeSample.await() }
            ?: return null
        val before = beforeRaw?.let { SentinelDecoder.decodeInt(it) } ?: return null
        if (kotlin.math.abs(before - target) <= WINDOW_POSITION_TOLERANCE_PCT) return null
        val prefix = "window readback action=$actionName dev=${entry.dev} fid=${entry.writeFid} " +
            "value=$value before=$before"
        val seen = mutableListOf<String>()
        repeat(WINDOW_VERIFY_ATTEMPTS) {
            delay(WINDOW_VERIFY_DELAY_MS)
            val after = readWindowRaw(readFid)?.let { SentinelDecoder.decodeInt(it) }
            seen += after?.toString() ?: "err"
            // A read we could not take is not evidence against the write.
            if (after == null) return logWindowVerdict(prefix, seen, null)
            if (kotlin.math.abs(after - before) > WINDOW_POSITION_TOLERANCE_PCT) {
                return logWindowVerdict(prefix, seen, null)
            }
        }
        return logWindowVerdict(prefix, seen, "стекло не сдвинулось с места, команда не сработала")
    }

    /** Single log line for the verdict (kept from the diagnostics-only readback) + the verdict. */
    private fun logWindowVerdict(prefix: String, seen: List<String>, verdict: String?): String? {
        Log.i(TAG, "$prefix after=${seen.joinToString(",")} verdict=${verdict ?: "moved"}")
        return verdict
    }

    /** Read address actually sampled, plus the position read right now (raw, sentinels kept as-is). */
    private suspend fun resolveWindowReadFid(primaryField: String): Pair<FidAddress, Int?> {
        val primary = FidAddresses.of(primaryField)
        val raw = readWindowRaw(primary)
        if (primaryField == WINDOW_RR_FIELD && raw == SentinelDecoder.FEATURE_LINK_ERROR) {
            val gen3 = FidAddresses.of(WINDOW_RR_GEN3_FIELD)
            return gen3 to readWindowRaw(gen3)
        }
        return primary to raw
    }

    /** Raw position read; a failed read is logged as null and never fails the write. */
    private suspend fun readWindowRaw(address: FidAddress): Int? = try {
        autoservice.getIntRaw(address.device, address.fid)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "window readback read fid=${address.fid} failed: ${e.message}")
        null
    }

    /**
     * Status-classified write for the seat adaptive channel. Same allowlist + range
     * gate + audit row as [doWrite], but returns a [WriteOutcome] derived from the raw
     * autoservice status instead of a Result. A config error (allowlist miss / out of
     * range) maps to TRANSIENT so the adaptive channel never switches channels because
     * of a code bug. seat entries have no readbackFid, so no read-back verification.
     */
    internal suspend fun doWriteOutcome(actionName: String, value: Int): WriteOutcome {
        val entry = allowlist.find(actionName) ?: run {
            Log.w(TAG, "doWriteOutcome: action=$actionName not in allowlist")
            logWrite(actionName, -1, -1, value, null, false, "allowlist_miss", validated = false)
            return WriteOutcome.TRANSIENT
        }
        if (value < entry.valueMin || value > entry.valueMax) {
            Log.w(TAG, "doWriteOutcome: action=$actionName value=$value out of range [${entry.valueMin}..${entry.valueMax}]")
            logWrite(actionName, entry.dev, entry.writeFid, value, null, false, "range", entry.validated)
            return WriteOutcome.TRANSIENT
        }
        logAttempt(actionName, entry, value)
        val status: Int? = try {
            helper.writeStatus(entry.dev, entry.writeFid, value)
        } catch (e: Exception) {
            // Rethrow cancellation so callers outside the NonCancellable write unit
            // (channel resolution, probe logic) can still be cancelled normally.
            if (e is CancellationException) throw e
            Log.w(TAG, "doWriteOutcome: action=$actionName helper threw: ${e.message}")
            logWrite(actionName, entry.dev, entry.writeFid, value, null, false, "helper_exception", entry.validated)
            return WriteOutcome.TRANSIENT
        }
        val outcome = WriteOutcome.fromStatus(status)
        val ok = outcome == WriteOutcome.REAL
        logWrite(actionName, entry.dev, entry.writeFid, value, status, ok, if (ok) null else "outcome_$outcome", entry.validated)
        seatJournal?.appendWrite(actionName, entry.dev, entry.writeFid, value, status, outcome)
        return outcome
    }

    /**
     * dev=1000 status fid of [group] (1=on, 2=off), or null when the read carries no verdict:
     * the group has no validated status fid (passenger), the daemon did not answer, or the
     * value is a sentinel (65535 link error, 1048575 uninitialised, -10013/-10011 wrong
     * transact/direction) — a sentinel says nothing about the write that preceded it, and two
     * of them in a row would otherwise push a healthy Leopard 3 onto the fallback channel.
     * A literal 0 is NOT filtered: that is the Song L signal of a status fid nobody wired.
     */
    // internal for testing the sentinel filtering (private has no other seam).
    internal suspend fun readSeatStatus(group: SeatGroup): Int? =
        WriteAllowlist.SEAT_STATUS_FIDS[group]
            ?.let { helper.read(WriteAllowlist.SEAT_STATUS_DEV, it) }
            ?.let { SentinelDecoder.decodeInt(it.toInt()) }

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

    /**
     * Best-effort audit logger. Wrapped in runCatching so a DAO failure (full
     * disk, DB locked, etc.) does not surface as VehicleWriteError to the
     * caller. The "never throws" contract of doWrite depends on this.
     */
    private suspend fun logWrite(
        action: String, dev: Int, fid: Int, requested: Int, readback: Int?,
        ok: Boolean, error: String?, validated: Boolean,
    ) {
        runCatching {
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
        }.onFailure { Log.w(TAG, "logWrite DAO insert failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "VehicleApiImpl"
        private const val VALIDATED_FAILURE_TAG = "VehicleApi.ValidatedFailure"
        private const val COMPOSITE_WRITE_STAGGER_MS = 150L

        // ── Window readback (write verdict) ────────────────────────────────────
        /** Per attempt; a pane that was commanded starts moving well inside two of these
         *  (same budget as the seat channel's status verification). */
        private const val WINDOW_VERIFY_DELAY_MS = 400L
        private const val WINDOW_VERIFY_ATTEMPTS = 2
        /** Percent noise between two reads of a standing pane. */
        private const val WINDOW_POSITION_TOLERANCE_PCT = 2
        /** Only the percent family carries a target we can compare the position with. */
        private const val POS_ACTION_SUFFIX = "_pos"
        /** Bounded wait so the "before" sample usually precedes the write, but a hung read
         *  channel costs at most this much. */
        private const val WINDOW_BEFORE_READ_BUDGET_MS = 300L
        private const val WINDOW_RR_FIELD = "windowRR"
        private const val WINDOW_RR_GEN3_FIELD = "windowRRGen3"
        /** Write action → the FidMap READ entry of the same pane (percent). The address
         *  itself is looked up per read, so it follows the firmware catalog. */
        private val WINDOW_READ_FIELDS: Map<String, String> = mapOf(
            "window_driver_pos" to "windowFL",
            "window_driver_open" to "windowFL",
            "window_driver_close" to "windowFL",
            "window_driver_ctrl" to "windowFL",
            "window_passenger_pos" to "windowFR",
            "window_passenger_open" to "windowFR",
            "window_passenger_close" to "windowFR",
            "window_passenger_ctrl" to "windowFR",
            "window_rear_left_pos" to "windowRL",
            "window_rear_left_open" to "windowRL",
            "window_rear_left_close" to "windowRL",
            "window_rear_left_ctrl" to "windowRL",
            "window_rear_right_pos" to WINDOW_RR_FIELD,
            "window_rear_right_open" to WINDOW_RR_FIELD,
            "window_rear_right_close" to WINDOW_RR_FIELD,
            "window_rear_right_ctrl" to WINDOW_RR_FIELD,
        )
        // TODO: route to Crashlytics when Firebase is integrated
    }
}
