package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.SentinelDecoder
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
 *  - terrain mode, speed now unknown or above the limit (re-checked right before the write)
 *                                       → SPEED, nothing written
 *  - write not accepted (daemon down)   → UNREACHABLE
 *  - target == requested within ~3 s    → OK
 *  - otherwise                          → NOT_CHANGED
 * This is where the terrain speed limit is enforced for every path (Smart Home calls VehicleApi
 * directly); ActionDispatcher checks the same limit first only to word the refusal. One command
 * at a time under [mutex], so a queued one sees the settled mode.
 */
class DriveModeChannel(
    private val writer: SeatWriter,
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

        // The support flag and target reads above both suspend; re-check speed right before
        // committing the write so a terrain mode can't slip through on a speed that was fine
        // when [run] checked it but no longer is.
        terrainSpeedRefusal(a)?.let { return it }

        val status = writer.write(mode.actionName, mode.value).also { a.status = it }
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

    /** SPEED refusal for a terrain mode whose speed, re-read right before the write, is now
     *  unknown or above the limit; null (proceed) for a non-terrain mode or an OK speed. */
    private suspend fun terrainSpeedRefusal(a: Attempt): Outcome? {
        if (!a.mode.terrain) return null
        a.kmh = readSpeed()
        val kmh = a.kmh
        if (kmh != null && kmh <= DriveMode.TERRAIN_MAX_SPEED_KMH) return null
        return a.done(Result.SPEED, if (kmh == null) "speed unknown" else "too fast", null)
    }

    /** What one attempt has seen so far; [done] writes its single log line. [kmh] is mutable:
     *  the pre-write re-check overwrites it so the log and the Outcome report the speed that
     *  actually decided the verdict, not the stale one from the first check. */
    private class Attempt(val mode: DriveMode, var kmh: Int?) {
        var flag: Int? = null
        var before: Int? = null
        var probed = false
        var status: WriteOutcome? = null
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
