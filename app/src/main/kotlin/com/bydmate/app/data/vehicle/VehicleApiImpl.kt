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

    // Owns the window position samples taken around a write. A write that fails before the
    // verdict cancels its sample (see doWrite), so no read outlives its dispatch.
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
            // doWrite protects its own window verdict from cancellation, so no wrapper here.
            return doWrite(r.actionName, r.value)
        }
        // Composite command (e.g. window aggregates, fridge presets) → fan out to several
        // per-door writes. Attempt every sub-write (no short-circuit: a partial open beats
        // stopping at the first stuck pane); succeed only if all do.
        // NonCancellable: once the sequence has started, run ALL writes to completion so a
        // hard stop between sub-writes never leaves the car half-commanded (e.g. two windows
        // open, two still closed). Cancellation takes effect after the loop exits.
        var firstError: Throwable? = null
        val windowChecks = mutableListOf<WindowVerify>()
        withContext(NonCancellable) {
            for ((i, r) in resolved.withIndex()) {
                // Song L (#97): a burst of window writes ~5 ms apart all return status=1,
                // but only the later panes actuate — the first write is silently dropped.
                // Spacing the sub-writes out keeps every write effective on that platform.
                if (i > 0) delay(COMPOSITE_WRITE_STAGGER_MS)
                val res = doWrite(r.actionName, r.value, verifyInto = windowChecks)
                if (res.isFailure && firstError == null) firstError = res.exceptionOrNull()
            }
            // All panes were commanded, so they can be judged together: one verification
            // window for the burst instead of one per pane (~400 ms instead of ~1.6 s).
            verifyWindowBurst(windowChecks)?.let { stuck ->
                if (firstError == null) firstError = VehicleWriteError.ReadbackMismatch(commandString, stuck)
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
    // [verifyInto] collects the window checks instead of running them inline, so a burst of
    // pane writes shares ONE verification window (see [dispatch]); null = verify right here.
    internal suspend fun doWrite(
        requestedAction: String,
        requestedValue: Int,
        verifyInto: MutableList<WindowVerify>? = null,
    ): Result<Unit> {
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
        val windowCheck = windowCheckFor(actionName, requestedAction, requestedValue, entry, value)

        logAttempt(actionName, entry, value)

        val wrote: Boolean = try {
            if (windowCheck != null) {
                // Bounded wait so the "before" sample usually precedes the write, but a hung
                // read channel costs at most WINDOW_BEFORE_READ_BUDGET_MS.
                withTimeoutOrNull(WINDOW_BEFORE_READ_BUDGET_MS) { windowCheck.before.await() }
            }
            helper.write(entry.dev, entry.writeFid, value)
        } catch (e: Exception) {
            // Rethrow cancellation so callers outside the NonCancellable write unit
            // (status reads, channel resolution) can still be cancelled normally.
            if (e is CancellationException) throw e
            Log.w(TAG, "doWrite: action=$actionName helper.write threw: ${e.message}")
            windowCheck?.before?.cancel()
            logWrite(actionName, entry.dev, entry.writeFid, value, null, false, "helper_exception", entry.validated)
            val err = VehicleWriteError.HelperUnreachable(actionName, e.message ?: "io error")
            maybeReportValidatedFailure(actionName, err, entry)
            return Result.failure(err)
        }

        if (!wrote) {
            windowCheck?.before?.cancel()
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
            windowCheck?.before?.cancel()
            val err = if (entry.validated) VehicleWriteError.Sentinel(actionName) else VehicleWriteError.Unsupported(actionName)
            logWrite(actionName, entry.dev, entry.writeFid, value, readback.toInt(), false, "readback_sentinel", entry.validated)
            maybeReportValidatedFailure(actionName, err, entry)
            return Result.failure(err)
        }

        if (readback != null && readback.toInt() != value) {
            // For non-validated entries, mismatch is expected (crowd-validation in progress) — surface as Unsupported.
            // DAO error string stays "readback_mismatch" for diagnostic analysis.
            windowCheck?.before?.cancel()
            val err = if (entry.validated) VehicleWriteError.ReadbackMismatch(actionName, "expected=$value got=$readback") else VehicleWriteError.Unsupported(actionName)
            logWrite(actionName, entry.dev, entry.writeFid, value, readback.toInt(), false, "readback_mismatch", entry.validated)
            maybeReportValidatedFailure(actionName, err, entry)
            return Result.failure(err)
        }

        // The pane accepted the command but never moved: report the truth instead of a
        // success the driver can see is wrong (#97 — the first write of a burst is dropped).
        // In a burst the check is handed to the caller, which runs one window for all panes
        // under its own NonCancellable.
        //
        // NonCancellable here: the physical write has already landed, so the verdict (up to
        // ~1 s of reads) and the audit row that follows it must survive a caller cancelled in
        // that window — a command the car obeyed must never surface as an exception, whichever
        // entry point (dispatch, writeWindowDriver, …) started the write.
        if (windowCheck != null && verifyInto == null) {
            return withContext(NonCancellable) {
                val failure = windowFailure(windowCheck)
                if (failure != null) return@withContext Result.failure(failure)
                logSuccess(actionName, entry, value, readback)
                Result.success(Unit)
            }
        }
        if (windowCheck != null) verifyInto?.add(windowCheck)

        logSuccess(actionName, entry, value, readback)
        return Result.success(Unit)
    }

    /** Logcat line plus audit row for a write that went through. */
    private suspend fun logSuccess(actionName: String, entry: WriteEntry, value: Int, readback: Long?) {
        // INFO so a successful dispatch is visible in logcat (the DAO row is private).
        // Pair with the HelperClient "status=" line to tell a real action from a no-op.
        Log.i(TAG, "doWrite OK: action=$actionName dev=${entry.dev} fid=${entry.writeFid} value=$value readback=$readback validated=${entry.validated}")
        logWrite(actionName, entry.dev, entry.writeFid, value, readback?.toInt(), true, null, entry.validated)
    }

    // ─── Window readback ───────────────────────────────────────────────────────

    /** One pane whose movement still has to be judged: everything the verdict needs, plus the
     *  position sample taken around the write. */
    internal class WindowVerify(
        val actionName: String,
        val entry: WriteEntry,
        val value: Int,
        val target: Int,
        val before: Deferred<Pair<FidAddress, Int?>>,
    )

    /** The movement check this write needs, or null when the pane or the target is unknown. */
    private fun windowCheckFor(
        actionName: String,
        requestedAction: String,
        requestedValue: Int,
        entry: WriteEntry,
        value: Int,
    ): WindowVerify? {
        val target = windowTargetPercent(requestedAction, requestedValue) ?: return null
        val field = WINDOW_READ_FIELDS[actionName.lowercase()] ?: return null
        return WindowVerify(actionName, entry, value, target,
            readbackScope.async { resolveWindowReadFid(field) })
    }

    /** Verdict for a single write, as the error it should fail with (null when it is fine). */
    private suspend fun windowFailure(check: WindowVerify): VehicleWriteError? {
        val stuck = verifyWindowBurst(listOf(check)) ?: return null
        val err = VehicleWriteError.ReadbackMismatch(check.actionName, stuck)
        maybeReportValidatedFailure(check.actionName, err, check.entry)
        return err
    }

    /**
     * Did the panes actually start moving after their writes were accepted? ONE verification
     * window for the whole burst: every pane is read in the same pass, the passes are
     * [WINDOW_VERIFY_DELAY_MS] apart (the seat channel's pattern), and the loop exits as soon
     * as nothing is left to judge. A four-window command therefore costs the same wait as one.
     * Any movement — even a pane still travelling — proves its write landed.
     *
     * Returns null when every pane is accounted for, else a short Russian reason naming the
     * panes that never moved.
     *
     * Deliberately fail-open: a read that does not complete, a sentinel, a pane already at the
     * requested position, or a command with no comparable target (CTRL detents) all count as
     * "no evidence", never as a failure.
     */
    private suspend fun verifyWindowBurst(checks: List<WindowVerify>): String? {
        val pending = pendingPanes(checks).toMutableList()
        repeat(WINDOW_VERIFY_ATTEMPTS) {
            if (pending.isEmpty()) return null
            delay(WINDOW_VERIFY_DELAY_MS)
            val settled = pending.filter { paneSettled(it) }
            settled.forEach { logWindowVerdict(it, null) }
            pending.removeAll(settled)
        }
        if (pending.isEmpty()) return null
        pending.forEach { pane ->
            logWindowVerdict(pane, "не сдвинулось")
            logWrite(pane.check.actionName, pane.check.entry.dev, pane.check.entry.writeFid,
                pane.check.value, null, false, "window_noop", pane.check.entry.validated)
        }
        val panes = pending.map { paneLabel(it.check.actionName) }
        return if (panes.size == 1) "${panes[0]} не сдвинулось с места, команда не сработала"
        else "не сдвинулись с места: " + panes.joinToString(", ")
    }

    /** Panes worth watching: the "before" sample resolved, was readable, and is not already
     *  at the requested position. Everything else is "no evidence" and stays out. */
    private suspend fun pendingPanes(checks: List<WindowVerify>): List<PendingPane> =
        checks.mapNotNull { check ->
            val sample = withTimeoutOrNull(WINDOW_BEFORE_READ_BUDGET_MS) { check.before.await() }
            val before = sample?.second?.let { SentinelDecoder.decodeInt(it) }
            if (before == null || kotlin.math.abs(before - check.target) <= WINDOW_POSITION_TOLERANCE_PCT) null
            else PendingPane(check, sample.first, before)
        }

    /** One more read of a pane: true when it moved, or when the read gives no evidence. */
    private suspend fun paneSettled(pane: PendingPane): Boolean {
        val after = readWindowRaw(pane.readFid)?.let { SentinelDecoder.decodeInt(it) }
        pane.seen += after?.toString() ?: "err"
        return after == null || kotlin.math.abs(after - pane.before) > WINDOW_POSITION_TOLERANCE_PCT
    }

    /** A pane that had somewhere to travel, with the samples taken so far. */
    private class PendingPane(
        val check: WindowVerify,
        val readFid: FidAddress,
        val before: Int,
        val seen: MutableList<String> = mutableListOf(),
    )

    /** Single log line per pane: the samples and the verdict. */
    private fun logWindowVerdict(pane: PendingPane, verdict: String?) {
        Log.i(
            TAG,
            "window readback action=${pane.check.actionName} dev=${pane.check.entry.dev} " +
                "fid=${pane.check.entry.writeFid} value=${pane.check.value} before=${pane.before} " +
                "after=${pane.seen.joinToString(",")} verdict=${verdict ?: "moved"}"
        )
    }

    /**
     * Position the pane is expected to end up at, or null when the command carries no
     * comparable target. Open and close are their own fids on the car but still name a
     * position; a CTRL detent (the DiLink 3.0 re-route) names none.
     */
    private fun windowTargetPercent(requestedAction: String, requestedValue: Int): Int? = when {
        requestedAction.endsWith("_pos") -> requestedValue.takeIf { it in 0..100 }
        requestedAction.endsWith("_open") -> 100
        requestedAction.endsWith("_close") -> 0
        else -> null
    }

    /** Pane name for the failure the driver hears. */
    private fun paneLabel(actionName: String): String = when {
        actionName.startsWith("window_driver") -> "окно водителя"
        actionName.startsWith("window_passenger") -> "окно пассажира"
        actionName.startsWith("window_rear_left") -> "заднее левое окно"
        actionName.startsWith("window_rear_right") -> "заднее правое окно"
        else -> "стекло"
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
