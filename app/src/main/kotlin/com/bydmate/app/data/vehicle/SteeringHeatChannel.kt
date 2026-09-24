package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.SentinelDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Raw steering wheel heat state (2=on, 1=off, 0=absent, 65535=no CAN link), supplied by
 * VehicleApiImpl. Null means the read did not complete — never a verdict by itself.
 */
fun interface SteeringHeatReadback {
    suspend fun read(): Int?
}

/**
 * Steering wheel heat on/off, verified by reading the state fid back. Not validated on a car
 * with a heated wheel, and an autoservice status=1 proves nothing on its own (seats answer 1
 * and actuate nothing on Song L), so only the state fid decides:
 *  - state == requested                 → OK
 *  - state 0 before the write           → NOT_EQUIPPED, nothing written
 *  - state 0 after the write            → NOT_EQUIPPED (no fallback)
 *  - 65535 (CAN link down), a failed read or any value outside {0, 1, 2, 65535}
 *                                       → UNCONFIRMED: the result is unknown, no fallback
 *  - the opposite state (1↔2) confirmed → one more read, then a retry on the competitor
 *    dev=1000 channel (wheel_heat_on/off, same fid); still opposite after it → NO_EFFECT.
 * A TRANSIENT write (daemon down, car off) ends the attempt without reading.
 * The [writer] is the same status-classified write the seat channel uses.
 *
 * Commands run one at a time under [mutex] (before-read, write, readbacks, fallback), so a
 * command that starts after another finished sees the real state. A command that is still
 * waiting for its fallback when a newer one has been issued gives up instead of writing
 * (SUPERSEDED): its late write would undo the newer command. The automation «toggle» reads
 * the state outside this lock (ActionDispatcher), so two concurrent toggles may still both
 * pick the same direction; each resulting on/off is serialised here.
 */
class SteeringHeatChannel(
    private val writer: SeatWriter,
    private val readback: SteeringHeatReadback,
) {
    enum class Result { OK, NOT_EQUIPPED, NO_EFFECT, UNREACHABLE, UNCONFIRMED }

    /**
     * [verdict] is the label that ends the channel's last log line (OK, OK late, fallback
     * dev=1000 OK, no effect, not equipped, unconfirmed, can link lost, superseded,
     * unreachable); [dev] the device of the last write (0 when nothing was written);
     * [state] the last raw state read (null when the read failed or never happened).
     */
    data class Outcome(val result: Result, val verdict: String, val dev: Int, val state: Int?)

    private val mutex = Mutex()
    private val sequence = AtomicLong()

    suspend fun actuate(on: Boolean): Outcome {
        // Taken before the lock: a command queued behind a running one already supersedes it.
        val seq = sequence.incrementAndGet()
        return mutex.withLock { run(on, seq) }
    }

    private suspend fun run(on: Boolean, seq: Long): Outcome {
        val want = if (on) STATE_ON else STATE_OFF
        val before = readState()
        if (before == STATE_ABSENT) {
            Log.i(TAG, "SteeringHeat: want=$want before=0 -> not equipped before write")
            return Outcome(Result.NOT_EQUIPPED, "not equipped", 0, before)
        }
        attempt(PRIMARY_DEV, want, before)?.let { return it }

        // The primary write left the confirmed opposite state. Look once more right before
        // the fallback: CAN may have published the new state after the readback window.
        val check = readState()
        fun logCheck(verdict: String) =
            Log.i(TAG, "SteeringHeat: want=$want check=${render(check)} -> $verdict")
        when {
            check == want -> {
                logCheck("OK late")
                return Outcome(Result.OK, "OK late", PRIMARY_DEV, check)
            }
            !isOpposite(check, want) -> {
                val (result, verdict) = classifyUnexpected(check)
                logCheck(detailed(verdict, check))
                return Outcome(result, verdict, PRIMARY_DEV, check)
            }
            sequence.get() != seq -> {
                logCheck("superseded")
                return Outcome(Result.UNCONFIRMED, "superseded", PRIMARY_DEV, check)
            }
        }
        return attempt(FALLBACK_DEV, want, check) ?: Outcome(Result.NO_EFFECT, "no effect", FALLBACK_DEV, check)
    }

    /**
     * One write + readback on [dev]: steering_heat_on/off on the primary device, the
     * competitor's wheel_heat_on/off on the fallback one. Null = the state is still the
     * confirmed opposite value.
     */
    private suspend fun attempt(dev: Int, want: Int, before: Int?): Outcome? {
        val primary = dev == PRIMARY_DEV
        val prefix = if (primary) "steering_heat" else "wheel_heat"
        val status = writer.write(if (want == STATE_ON) "${prefix}_on" else "${prefix}_off", want)
        val after = mutableListOf<Int?>()
        fun log(verdict: String) = Log.i(
            TAG,
            "SteeringHeat: want=$want dev=$dev status=$status before=${render(before)} " +
                "after=[${after.joinToString(",") { render(it) }}] -> $verdict",
        )
        if (status == WriteOutcome.TRANSIENT) {
            log("unreachable")
            return Outcome(Result.UNREACHABLE, "unreachable", dev, null)
        }
        repeat(READBACK_ATTEMPTS) {
            delay(READBACK_DELAY_MS)
            val value = readState()
            after += value
            if (value == want) {
                val verdict = if (primary) "OK" else "fallback dev=$FALLBACK_DEV OK"
                log(verdict)
                return Outcome(Result.OK, verdict, dev, value)
            }
        }
        val state = after.last()
        if (!isOpposite(state, want)) {
            val (result, verdict) = classifyUnexpected(state)
            log(detailed(verdict, state))
            return Outcome(result, verdict, dev, state)
        }
        log(if (primary) "fallback dev=$FALLBACK_DEV" else "no effect")
        return null
    }

    /** A state that is neither the wanted nor the opposite one: absent, link down or unknown. */
    private fun classifyUnexpected(state: Int?): Pair<Result, String> = when (state) {
        STATE_ABSENT -> Result.NOT_EQUIPPED to "not equipped"
        SentinelDecoder.FEATURE_LINK_ERROR -> Result.UNCONFIRMED to "can link lost"
        else -> Result.UNCONFIRMED to "unconfirmed"
    }

    private fun detailed(verdict: String, state: Int?) =
        if (verdict == "unconfirmed") "unconfirmed read=${render(state)}" else verdict

    /** Only a confirmed 1↔2 flip justifies another write. */
    private fun isOpposite(state: Int?, want: Int) =
        state == (if (want == STATE_ON) STATE_OFF else STATE_ON)

    /** A throwing read is a failed read, not a verdict. */
    private suspend fun readState(): Int? = runCatching { readback.read() }
        .onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "SteeringHeat: state read failed: ${it.message}")
        }
        .getOrNull()

    private fun render(value: Int?) = value?.toString() ?: "err"

    private companion object {
        const val TAG = "SteeringHeatChannel"
        const val STATE_ON = 2
        const val STATE_OFF = 1
        const val STATE_ABSENT = 0
        const val PRIMARY_DEV = 1023
        const val FALLBACK_DEV = 1000
        const val READBACK_ATTEMPTS = 2
        /** Same budget as the seat channel: CAN publishes the new state with a delay. */
        const val READBACK_DELAY_MS = 400L
    }
}
