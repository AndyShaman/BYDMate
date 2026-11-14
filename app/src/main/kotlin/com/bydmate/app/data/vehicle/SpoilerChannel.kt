package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.SentinelDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Raw value of a spoiler fid (or of the pilot state), sentinels kept. Null means the read did not complete. */
fun interface SpoilerReader {
    suspend fun read(fid: Int): Int?
}

/** The verdict of a spoiler the anti-pinch stopped; ActionDispatcher words it apart from a refusal. */
internal const val SPOILER_ANTI_PINCH = "anti-pinch"

/**
 * Electric spoiler up/down (#383), the BYD voice assistant's command (1 = up, 2 = down). Not
 * validated on a car, so nothing is written unless the config says a spoiler is fitted, and the
 * status feedback decides:
 *  - config read failed or a read error → UNREACHABLE, nothing written
 *  - config not 2                       → NOT_EQUIPPED, nothing written (65535 on a car without one)
 *  - repair mode, a spoiler warning, motor protection or the navigation pilot → BLOCKED, nothing
 *    written: the values the voice assistant refuses on (GetAllError). A check that does not
 *    read, or reads anything else, does not hold the command, as in BYD's code.
 *  - up: status 2 or 5, down: 3 or 6    → OK (moving or already there)
 *  - status 4                           → ANTI_PINCH, at once and without a retry
 *  - status still as before the write   → NO_EFFECT
 *  - a failed read or any other value   → UNCONFIRMED
 * The car's automatic mode is read before and after for the log only: the command is expected to
 * switch the spoiler to manual, and the first owner's log has to show whether it does.
 * One write per attempt; a TRANSIENT write (daemon down, car off) ends it without reading.
 * Commands run one at a time under [mutex], so the next one reads the real status.
 */
class SpoilerChannel(
    private val writer: SeatWriter,
    private val reader: SpoilerReader,
) {
    enum class Result { OK, NOT_EQUIPPED, BLOCKED, NO_EFFECT, ANTI_PINCH, UNREACHABLE, UNCONFIRMED }

    /**
     * [verdict] ends the channel's log line (OK, no spoiler, config unreadable, blocked <check>=<v>,
     * unreachable, anti-pinch, no effect, unconfirmed read=<v>); [written] says whether the command was written;
     * [state] is the last status read (null when the read failed or never happened).
     */
    data class Outcome(val result: Result, val verdict: String, val written: Boolean, val state: Int?)

    private val mutex = Mutex()

    suspend fun actuate(up: Boolean): Outcome = mutex.withLock { run(up) }

    private suspend fun run(up: Boolean): Outcome {
        val want = if (up) "up" else "down"
        val config = read(WriteAllowlist.SPOILER_CONFIG_FID)
        if (config != CONFIG_FITTED) return refused(want, config)
        val checks = CHECKS.map { it to read(it.fid) }
        val seen = checks.joinToString(",") { (check, value) -> "${check.label}=${render(value)}" }
        checks.firstOrNull { (check, value) -> value in check.refuseOn }?.let { (check, value) ->
            val verdict = "blocked ${check.label}=$value"
            Log.i(TAG, "Spoiler: want=$want config=$config checks=[$seen] -> $verdict, nothing written")
            return Outcome(Result.BLOCKED, verdict, false, null)
        }
        val before = read(WriteAllowlist.SPOILER_STATUS_FID)
        val autoBefore = read(WriteAllowlist.SPOILER_AUTO_MODE_FID)
        val status = if (up) writer.write("spoiler_up", CMD_UP) else writer.write("spoiler_down", CMD_DOWN)
        val after = mutableListOf<Int?>()
        // auto: the car's automatic mode before and after (2 = automatic, 1 = manual).
        suspend fun log(verdict: String) = Log.i(
            TAG,
            "Spoiler: want=$want config=$config checks=[$seen] before=${render(before)} status=$status " +
                "after=[${after.joinToString(",") { render(it) }}] " +
                "auto=${render(autoBefore)}->${render(read(WriteAllowlist.SPOILER_AUTO_MODE_FID))} -> $verdict",
        )
        if (status == WriteOutcome.TRANSIENT) {
            log("unreachable")
            return Outcome(Result.UNREACHABLE, "unreachable", false, null)
        }
        val done = if (up) STATES_UP else STATES_DOWN
        repeat(READBACK_ATTEMPTS) {
            delay(READBACK_DELAY_MS)
            val value = read(WriteAllowlist.SPOILER_STATUS_FID)
            after += value
            val outcome = when (value) {
                in done -> Outcome(Result.OK, "OK", true, value)
                STATE_ANTI_PINCH -> Outcome(Result.ANTI_PINCH, SPOILER_ANTI_PINCH, true, value)
                else -> null
            }
            if (outcome != null) {
                log(outcome.verdict)
                return outcome
            }
        }
        val outcome = unsettled(before, after.last())
        log(outcome.verdict)
        return outcome
    }

    /** The readback gave no verdict. A failed read or the same error code on both sides proves nothing. */
    private fun unsettled(before: Int?, state: Int?): Outcome =
        if (state != null && SentinelDecoder.decodeInt(state) != null && state == before) {
            Outcome(Result.NO_EFFECT, "no effect", true, state)
        } else {
            Outcome(Result.UNCONFIRMED, "unconfirmed read=${render(state)}", true, state)
        }

    /** The exit without a write: the config read failed, or no electric spoiler is fitted. */
    private fun refused(want: String, config: Int?): Outcome {
        // 65535 (no CAN link) is how a car without a spoiler answers; the other sentinels are read errors.
        val unreadable = config == null ||
            (config != SentinelDecoder.FEATURE_LINK_ERROR && SentinelDecoder.decodeInt(config) == null)
        val outcome = if (unreadable) Outcome(Result.UNREACHABLE, "config unreadable", false, null)
            else Outcome(Result.NOT_EQUIPPED, "no spoiler", false, null)
        Log.i(TAG, "Spoiler: want=$want config=${render(config)} -> ${outcome.verdict}, nothing written")
        return outcome
    }

    /** A throwing read is a failed read, not a verdict. */
    private suspend fun read(fid: Int): Int? =
        readGuarded({ Log.w(TAG, "Spoiler: read fid=$fid failed: ${it.message}") }) { reader.read(fid) }

    /** One of the car's own states the voice assistant refuses on, and the values that refuse. */
    private class Check(val label: String, val fid: Int, val refuseOn: Set<Int>)

    private companion object {
        const val TAG = "SpoilerChannel"
        val CHECKS = listOf(
            Check("repair", WriteAllowlist.SPOILER_REPAIR_FID, setOf(1)),
            Check("warning", WriteAllowlist.SPOILER_WARNING_FID, setOf(1, 2, 3, 4)),
            Check("thermal", WriteAllowlist.SPOILER_THERMAL_FID, setOf(1)),
            Check("pilot", WriteAllowlist.SPOILER_PILOT_FID, setOf(4, 6, 8)),
        )
        const val CONFIG_FITTED = 2
        const val CMD_UP = 1
        const val CMD_DOWN = 2
        /** Opening or opened; closing or closed (status 2 → 5 → 3 → 6 in the owner's log). */
        val STATES_UP = setOf(2, 5)
        val STATES_DOWN = setOf(3, 6)
        const val STATE_ANTI_PINCH = 4
        const val READBACK_ATTEMPTS = 6
        /** The spoiler takes seconds to move; 6 x 500 ms gives the status time to change. */
        const val READBACK_DELAY_MS = 500L
    }
}
