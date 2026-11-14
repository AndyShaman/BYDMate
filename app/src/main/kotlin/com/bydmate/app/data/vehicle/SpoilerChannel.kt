package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.SentinelDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Raw value of a dev=1023 spoiler fid, sentinels kept. Null means the read did not complete. */
fun interface SpoilerReader {
    suspend fun read(fid: Int): Int?
}

/**
 * Electric spoiler up/down (#383), the BYD voice assistant's command (1 = up, 2 = down). Not
 * validated on a car, so nothing is written unless the config says a spoiler is fitted, and the
 * status feedback decides:
 *  - config read failed or a read error → UNREACHABLE, nothing written
 *  - config not 2                       → NOT_EQUIPPED, nothing written (65535 on a car without one)
 *  - up: status 2 or 5, down: 3 or 6    → OK (moving or already there)
 *  - status 4                           → ANTI_PINCH, at once and without a retry
 *  - status still as before the write   → NO_EFFECT
 *  - a failed read or any other value   → UNCONFIRMED
 * One write per attempt; a TRANSIENT write (daemon down, car off) ends it without reading.
 * Commands run one at a time under [mutex], so the next one reads the real status.
 */
class SpoilerChannel(
    private val writer: SeatWriter,
    private val reader: SpoilerReader,
) {
    enum class Result { OK, NOT_EQUIPPED, NO_EFFECT, ANTI_PINCH, UNREACHABLE, UNCONFIRMED }

    /**
     * [verdict] ends the channel's log line (OK, no spoiler, config unreadable, unreachable,
     * anti-pinch, no effect, unconfirmed read=<v>); [written] says whether the command was written;
     * [state] is the last status read (null when the read failed or never happened).
     */
    data class Outcome(val result: Result, val verdict: String, val written: Boolean, val state: Int?)

    private val mutex = Mutex()

    suspend fun actuate(up: Boolean): Outcome = mutex.withLock { run(up) }

    private suspend fun run(up: Boolean): Outcome {
        val want = if (up) "up" else "down"
        val config = read(WriteAllowlist.SPOILER_CONFIG_FID)
        if (config != CONFIG_FITTED) return refused(want, config)
        val before = read(WriteAllowlist.SPOILER_STATUS_FID)
        val status = if (up) writer.write("spoiler_up", CMD_UP) else writer.write("spoiler_down", CMD_DOWN)
        val after = mutableListOf<Int?>()
        fun log(verdict: String) = Log.i(
            TAG,
            "Spoiler: want=$want config=$config before=${render(before)} status=$status " +
                "after=[${after.joinToString(",") { render(it) }}] -> $verdict",
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
                STATE_ANTI_PINCH -> Outcome(Result.ANTI_PINCH, "anti-pinch", true, value)
                else -> null
            }
            if (outcome != null) {
                log(outcome.verdict)
                return outcome
            }
        }
        val state = after.last()
        // A failed read on both sides proves nothing, so it stays unconfirmed.
        val outcome = if (state != null && state == before) {
            Outcome(Result.NO_EFFECT, "no effect", true, state)
        } else {
            Outcome(Result.UNCONFIRMED, "unconfirmed read=${render(state)}", true, state)
        }
        log(outcome.verdict)
        return outcome
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

    private companion object {
        const val TAG = "SpoilerChannel"
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
