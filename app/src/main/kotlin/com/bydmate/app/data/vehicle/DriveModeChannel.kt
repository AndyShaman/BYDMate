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
 *  - support flag unreadable            → UNREADABLE, nothing written
 *  - support flag != 0                  → NOT_SUPPORTED, nothing written
 *  - target unreadable / a sentinel     → UNREADABLE, nothing written
 *  - target == 10 (flotation)           → FLOTATION, nothing written
 *  - target == requested                → OK ("already"), nothing written
 *  - write not accepted (daemon down)   → UNREACHABLE
 *  - target == requested within ~3 s    → OK
 *  - otherwise                          → NOT_CHANGED
 * The speed gate for terrain modes runs before this, in ActionDispatcher; [speed] only feeds
 * the log line. One command at a time under [mutex], so a queued one sees the settled mode.
 */
class DriveModeChannel(
    private val writer: SeatWriter,
    private val reader: DriveModeReader,
    private val speed: suspend () -> Int?,
) {
    enum class Result { OK, NOT_SUPPORTED, FLOTATION, UNREADABLE, UNREACHABLE, NOT_CHANGED }

    /** [target] is the last target mode read (null when unread or unreadable). */
    data class Outcome(val result: Result, val verdict: String, val target: Int?)

    private val mutex = Mutex()

    suspend fun actuate(mode: DriveMode): Outcome = mutex.withLock { run(mode) }

    private suspend fun run(mode: DriveMode): Outcome {
        val flag = read(mode.supportFid)
        val before = read(WriteAllowlist.DRIVE_MODE_TARGET_FID)
        val after = mutableListOf<Int?>()
        var status: WriteOutcome? = null
        val kmh = runCatching { speed() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        fun done(result: Result, verdict: String, target: Int?): Outcome {
            Log.i(
                TAG,
                "DriveMode: want=${mode.name}(${mode.value}) flag=${render(flag)} before=${render(before)} " +
                    "status=${status ?: "-"} after=[${after.joinToString(",") { render(it) }}] " +
                    "speed=${kmh ?: "unknown"} -> $verdict",
            )
            return Outcome(result, verdict, target)
        }

        if (flag == null) return done(Result.UNREADABLE, "support flag unreadable", null)
        if (flag != 0) return done(Result.NOT_SUPPORTED, "not supported", before)
        val current = before?.let { SentinelDecoder.decodeInt(it) }
            ?: return done(Result.UNREADABLE, "target unreadable", null)
        if (current == DriveMode.TARGET_FLOTATION) return done(Result.FLOTATION, "flotation", current)
        if (current == mode.value) return done(Result.OK, "already", current)

        status = writer.write(mode.actionName, mode.value)
        if (status == WriteOutcome.TRANSIENT) return done(Result.UNREACHABLE, "unreachable", current)
        repeat(READBACK_ATTEMPTS) {
            delay(READBACK_DELAY_MS)
            val value = read(WriteAllowlist.DRIVE_MODE_TARGET_FID)
            after += value
            if (value == mode.value) return done(Result.OK, "OK", value)
        }
        return done(Result.NOT_CHANGED, "not changed", after.lastOrNull())
    }

    /** A throwing read is a failed read, not a verdict. */
    private suspend fun read(fid: Int): Int? = runCatching { reader.read(fid) }
        .onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "DriveMode: read fid=$fid failed: ${it.message}")
        }
        .getOrNull()

    private fun render(value: Int?) = value?.toString() ?: "err"

    private companion object {
        const val TAG = "DriveModeChannel"
        /** 6 × 500 ms: the target followed within 2 s on the live test, 3 s leaves headroom. */
        const val READBACK_ATTEMPTS = 6
        const val READBACK_DELAY_MS = 500L
    }
}
