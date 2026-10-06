package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.SentinelDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Raw value of a dev=1023 HUD fid, sentinels kept. Null means the read did not complete. */
fun interface HudReader {
    suspend fun read(fid: Int): Int?
}

/**
 * HUD master switch on/off (#292), the stock settings' write (1 = on, 2 = off). Not validated on
 * a car with a HUD, so the status feedback decides:
 *  - config read failed or a read error  → UNREACHABLE, nothing written
 *    (1048575 not initialized, -10011/-10013)
 *  - config not 1 (W-HUD) or 2 (AR-HUD) → NOT_EQUIPPED, nothing written (65535 on a car without one)
 *  - status == requested                → OK
 *  - the opposite status (1↔2)          → NO_EFFECT
 *  - a failed read or any other value   → UNCONFIRMED
 * A TRANSIENT write (daemon down, car off) ends the attempt without reading.
 * Commands run one at a time under [mutex], so the next one reads the real status.
 */
class HudSwitchChannel(
    private val writer: SeatWriter,
    private val reader: HudReader,
) {
    enum class Result { OK, NOT_EQUIPPED, NO_EFFECT, UNREACHABLE, UNCONFIRMED }

    /**
     * [verdict] ends the channel's log line (OK, no hud, config unreadable, unreachable, no effect,
     * unconfirmed read=<v>); [written] says whether the switch was written; [state] is the last
     * status read (null when the read failed or never happened).
     */
    data class Outcome(val result: Result, val verdict: String, val written: Boolean, val state: Int?)

    private val mutex = Mutex()

    suspend fun actuate(on: Boolean): Outcome = mutex.withLock { run(on) }

    private suspend fun run(on: Boolean): Outcome {
        val want = if (on) STATE_ON else STATE_OFF
        val config = read(WriteAllowlist.HUD_CONFIG_FID)
        if (config == null || config !in HUD_TYPES) {
            // 65535 (no CAN link) is how a car without a HUD answers; the other sentinels are read errors.
            val unreadable = config == null ||
                (config != SentinelDecoder.FEATURE_LINK_ERROR && SentinelDecoder.decodeInt(config) == null)
            val outcome = if (unreadable) Outcome(Result.UNREACHABLE, "config unreadable", false, null)
                else Outcome(Result.NOT_EQUIPPED, "no hud", false, null)
            Log.i(TAG, "Hud: want=$want config=${render(config)} -> ${outcome.verdict}, nothing written")
            return outcome
        }
        val status = writer.write(if (on) "hud_on" else "hud_off", want)
        val after = mutableListOf<Int?>()
        fun log(verdict: String) = Log.i(
            TAG,
            "Hud: want=$want config=$config status=$status after=[${after.joinToString(",") { render(it) }}] -> $verdict",
        )
        if (status == WriteOutcome.TRANSIENT) {
            log("unreachable")
            return Outcome(Result.UNREACHABLE, "unreachable", false, null)
        }
        repeat(READBACK_ATTEMPTS) {
            delay(READBACK_DELAY_MS)
            val value = read(WriteAllowlist.HUD_SWITCH_STATUS_FID)
            after += value
            if (value == want) {
                log("OK")
                return Outcome(Result.OK, "OK", true, value)
            }
        }
        val state = after.last()
        val outcome = if (state == (if (on) STATE_OFF else STATE_ON)) {
            Outcome(Result.NO_EFFECT, "no effect", true, state)
        } else {
            Outcome(Result.UNCONFIRMED, "unconfirmed read=${render(state)}", true, state)
        }
        log(outcome.verdict)
        return outcome
    }

    /** A throwing read is a failed read, not a verdict. */
    private suspend fun read(fid: Int): Int? = runCatching { reader.read(fid) }
        .onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Hud: read fid=$fid failed: ${it.message}")
        }
        .getOrNull()

    private fun render(value: Int?) = value?.toString() ?: "err"

    private companion object {
        const val TAG = "HudSwitchChannel"
        const val STATE_ON = 1
        const val STATE_OFF = 2
        val HUD_TYPES = setOf(1, 2)
        const val READBACK_ATTEMPTS = 2
        /** Same budget as the steering heat channel: CAN publishes the new status with a delay. */
        const val READBACK_DELAY_MS = 400L
    }
}
