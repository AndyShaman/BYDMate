package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.nativestack.FidAddresses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A drive mode the app can select: the SETTING_PRESELECTED_DRIVING_MODE_SET value (the same
 * encoding SETTING_TARGET_DRIVING_MODE reads back), the allowlist entry that writes it, and the
 * dev=1023 support flag the car answers 0 to when it has the mode. [terrain] modes are only
 * switched at low speed (ActionDispatcher.driveModeGateBlockReason).
 */
enum class DriveMode(val value: Int, val actionName: String, val supportFid: Int, val terrain: Boolean) {
    NORMAL(1, "drive_mode_normal", 1219518497, terrain = false),
    ECO(2, "drive_mode_eco", 1219518496, terrain = false),
    SPORT(3, "drive_mode_sport", 1219518498, terrain = false),
    SNOW(4, "drive_mode_snow", 1219518499, terrain = true),
    SAND(5, "drive_mode_sand", 1219518501, terrain = true),
    MUD(6, "drive_mode_mud", 1219518500, terrain = true),
    MOUNTAIN(7, "drive_mode_mountain", 1219518502, terrain = true),
    ROCK(8, "drive_mode_rock", 1219518503, terrain = true),
    SMART(21, "drive_mode_smart", 1219518505, terrain = true);

    companion object {
        /** Highest speed a terrain mode is switched at, km/h (owner's decision 2026-09-27). */
        const val TERRAIN_MAX_SPEED_KMH = 15

        /** SETTING_TARGET_DRIVING_MODE while the car holds the emergency flotation mode. */
        const val TARGET_FLOTATION = 10

        fun ofAction(actionName: String): DriveMode? = entries.firstOrNull { it.actionName == actionName }
    }
}

/** Raw dev=1023 int read (support flag or target mode). Null means the read did not complete. */
fun interface DriveModeReader {
    suspend fun read(fid: Int): Int?
}

/**
 * Status-classified single write with a last-instant guard, supplied by
 * VehicleApiImpl.doWriteOutcome. [beforeSend] runs after the write's (suspending) audit-row
 * insert, then inside HelperClient's own transport lock immediately before the transact — no
 * queue wait behind another helper request can separate the check from the send. It is handed a
 * [LockedReader] bound to that same lock, so the guard's own re-check reads through the daemon
 * instead of a separately blockable ADB call. Returning false aborts the send and the write
 * reports TRANSIENT. Own interface rather than [SeatWriter] because the seat channel has no
 * equivalent guard.
 */
fun interface DriveModeWriter {
    suspend fun write(actionName: String, value: Int, beforeSend: suspend (LockedReader) -> Boolean): WriteOutcome
}

/**
 * Drive mode switch, verified by the car's own target mode (SETTING_TARGET_DRIVING_MODE). BYD's
 * voice assistant writes the same fid without a speed or gear check; like it, a change is refused
 * while the car holds the emergency flotation mode. In order:
 *  - terrain mode, speed unknown or above [DriveMode.TERRAIN_MAX_SPEED_KMH]
 *                                       → SPEED, nothing read or written beyond the speed
 *  - support flag unreadable / a sentinel → UNREADABLE, nothing written
 *  - support flag a real non-zero value → NOT_SUPPORTED, nothing written
 *  - target unreadable / a sentinel     → UNREADABLE, nothing written
 *  - target == 10 (flotation)           → FLOTATION, nothing written
 *  - target == requested                → OK ("already"), nothing written
 *  - terrain mode, speed now unknown or above the limit (re-checked in the beforeSend guard,
 *    after the write's audit-log insert, right before the transact inside HelperClient's own
 *    transport lock)
 *                                       → SPEED, nothing written
 *  - write not accepted (daemon down)   → UNREACHABLE
 *  - target == requested within ~3 s    → OK
 *  - otherwise                          → NOT_CHANGED
 * This is where the terrain speed limit is enforced for every path (Smart Home calls VehicleApi
 * directly); ActionDispatcher checks the same limit first only to word the refusal. One command
 * at a time under [mutex], so a queued one sees the settled mode.
 */
class DriveModeChannel(
    private val writer: DriveModeWriter,
    private val reader: DriveModeReader,
    private val speed: suspend () -> Int?,
) {
    enum class Result { OK, SPEED, NOT_SUPPORTED, FLOTATION, UNREADABLE, UNREACHABLE, NOT_CHANGED }

    /** [target] is the last target mode read (null when unread or unreadable); [speed] the speed
     *  read before anything else (null = unknown). */
    data class Outcome(val result: Result, val verdict: String, val target: Int?, val speed: Int? = null)

    private val mutex = Mutex()

    suspend fun actuate(mode: DriveMode): Outcome = mutex.withLock { run(mode) }

    private suspend fun run(mode: DriveMode): Outcome {
        val kmh = readSpeed()
        val attempt = Attempt(mode, kmh)
        if (mode.terrain && (kmh == null || kmh > DriveMode.TERRAIN_MAX_SPEED_KMH)) {
            return attempt.done(Result.SPEED, if (kmh == null) "speed unknown" else "too fast", null)
        }
        return switchTo(attempt)
    }

    /** Support flag, flotation and "already there" checks, then the write and its readback. */
    private suspend fun switchTo(a: Attempt): Outcome {
        val mode = a.mode
        val flag = read(mode.supportFid).also { a.flag = it }
        val before = read(WriteAllowlist.DRIVE_MODE_TARGET_FID).also { a.before = it }
        a.probed = true
        if (flag == null) return a.done(Result.UNREADABLE, "support flag unreadable", null)
        val flagValue = SentinelDecoder.decodeInt(flag)
            ?: return a.done(Result.UNREADABLE, "support flag sentinel", null)
        if (flagValue != 0) return a.done(Result.NOT_SUPPORTED, "not supported", before)
        val current = before?.let { SentinelDecoder.decodeInt(it) }
            ?: return a.done(Result.UNREADABLE, "target unreadable", null)
        if (current == DriveMode.TARGET_FLOTATION) return a.done(Result.FLOTATION, "flotation", current)
        if (current == mode.value) return a.done(Result.OK, "already", current)

        // The guard runs inside writer.write, after its audit-log insert, then inside
        // HelperClient's own transport lock right before the transact — the actual
        // last-instant re-check, see terrainSpeedGuard.
        val status = writer.write(mode.actionName, mode.value) { locked -> terrainSpeedGuard(a, locked) }
            .also { a.status = it }
        a.speedVerdict?.let { return a.done(Result.SPEED, it, null) }
        if (status == WriteOutcome.TRANSIENT) return a.done(Result.UNREACHABLE, "unreachable", current)
        repeat(READBACK_ATTEMPTS) {
            delay(READBACK_DELAY_MS)
            val value = read(WriteAllowlist.DRIVE_MODE_TARGET_FID)
            a.after += value
            if (value == mode.value) return a.done(Result.OK, "OK", value)
        }
        return a.done(Result.NOT_CHANGED, "not changed", a.after.lastOrNull())
    }

    /** A throwing speed read is a failed read (unknown), not a verdict. */
    private suspend fun readSpeed(): Int? =
        runCatching { speed() }.onFailure { if (it is CancellationException) throw it }.getOrNull()

    /** [DriveModeWriter.beforeSend] guard: for a terrain mode, re-reads speed one last time
     *  through [reader] — the daemon, not ADB (2026-09-27, review round 3): AutoserviceClient's
     *  ADB path shares a `@Synchronized` monitor with every other in-flight ADB call, and a guard
     *  blocked on THAT monitor cannot be interrupted by HelperClient's own guard timeout, holding
     *  the transport mutex — and every unrelated helper call queued behind it — past its budget.
     *  This runs after doWriteOutcome's audit-row insert, inside HelperClient's own transport
     *  lock right before EACH transact attempt (retry included), so a speed that only rose
     *  during that suspend, while queued behind another helper request, or between a dead-binder
     *  retry, is still caught — and refuses when it is now unknown or above the limit; a
     *  non-terrain mode is not gated (no read). [Attempt.speedVerdict] is set to "speed unknown"
     *  BEFORE the read, not after: HelperClient bounds this guard to its own GUARD_TIMEOUT_MS,
     *  well under the shared write timeout, and a cut-off read must still leave switchTo seeing
     *  "speed unknown" instead of falling through to a bare "unreachable" — a timeout here is a
     *  refusal, not a crashed read. */
    private suspend fun terrainSpeedGuard(a: Attempt, locked: LockedReader): Boolean {
        if (!a.mode.terrain) return true
        a.speedVerdict = "speed unknown"
        var readFinished = false
        try {
            a.kmh = readGuardSpeed(locked)
            readFinished = true
        } finally {
            if (!readFinished) Log.w(TAG, "DriveMode: guard speed read cut off, refusing as speed unknown")
        }
        Log.i(TAG, "DriveMode: guard speed source=daemon value=${a.kmh ?: "unknown"}")
        val kmh = a.kmh
        if (kmh != null && kmh <= DriveMode.TERRAIN_MAX_SPEED_KMH) { a.speedVerdict = null; return true }
        if (kmh != null) a.speedVerdict = "too fast"
        return false
    }

    /** Rounded UP, not truncated — same reasoning as VehicleApiImpl's pre-write speed closure: a
     *  plain toInt() would turn 15.9 km/h into 15 and let a terrain mode through above the limit.
     *  A throwing read is a failed read (unknown), not a verdict. */
    private suspend fun readGuardSpeed(locked: LockedReader): Int? {
        val address = FidAddresses.of("speed")
        return runCatching { locked.readFloat(address.device, address.fid) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            ?.let { kotlin.math.ceil(it).toInt() }
    }

    /** What one attempt has seen so far; [done] writes its single log line. [kmh] is mutable:
     *  the guard's re-check overwrites it so the log and the Outcome report the speed that
     *  actually decided the verdict, not the stale one from the first check. [speedVerdict] is
     *  set only while a terrain guard is pending or has refused; cleared back to null once the
     *  guard passes. */
    private class Attempt(val mode: DriveMode, var kmh: Int?) {
        var flag: Int? = null
        var before: Int? = null
        var probed = false
        var status: WriteOutcome? = null
        var speedVerdict: String? = null
        val after = mutableListOf<Int?>()

        fun done(result: Result, verdict: String, target: Int?): Outcome {
            val flagLog = if (probed) render(flag) else "-"
            val beforeLog = if (probed) render(before) else "-"
            Log.i(
                TAG,
                "DriveMode: want=${mode.name}(${mode.value}) speed=${kmh ?: "unknown"} flag=$flagLog " +
                    "before=$beforeLog status=${status ?: "-"} after=[${after.joinToString(",") { render(it) }}] " +
                    "-> $verdict",
            )
            return Outcome(result, verdict, target, kmh)
        }
    }

    /** A throwing read is a failed read, not a verdict. */
    private suspend fun read(fid: Int): Int? = runCatching { reader.read(fid) }
        .onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "DriveMode: read fid=$fid failed: ${it.message}")
        }
        .getOrNull()

    private companion object {
        const val TAG = "DriveModeChannel"
        fun render(value: Int?) = value?.toString() ?: "err"
        /** 6 × 500 ms: the target followed within 2 s on the live test, 3 s leaves headroom. */
        const val READBACK_ATTEMPTS = 6
        const val READBACK_DELAY_MS = 500L
    }
}
