package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.SentinelDecoder
import kotlinx.coroutines.delay

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
 *  - state == requested            → OK
 *  - state 0 / 65535               → NOT_EQUIPPED (the car reports no heater; no fallback)
 *  - state unchanged or unreadable → retry once on the competitor dev=1000 channel
 *    (wheel_heat_on/off, same fid), then NO_EFFECT.
 * A TRANSIENT write (daemon down, car off) ends the attempt without reading.
 * The [writer] is the same status-classified write the seat channel uses.
 */
class SteeringHeatChannel(
    private val writer: SeatWriter,
    private val readback: SteeringHeatReadback,
) {
    enum class Result { OK, NOT_EQUIPPED, NO_EFFECT, UNREACHABLE }

    suspend fun actuate(on: Boolean): Result {
        val want = if (on) STATE_ON else STATE_OFF
        val primary = attempt(if (on) "steering_heat_on" else "steering_heat_off", PRIMARY_DEV, want, last = false)
        if (primary != null) return primary
        return attempt(if (on) "wheel_heat_on" else "wheel_heat_off", FALLBACK_DEV, want, last = true)
            ?: Result.NO_EFFECT
    }

    /** One write + readback on [dev]. Null = the state did not confirm and a fallback is due. */
    private suspend fun attempt(action: String, dev: Int, want: Int, last: Boolean): Result? {
        val before = readback.read()
        val status = writer.write(action, want)
        val after = mutableListOf<Int?>()
        fun log(verdict: String) = Log.i(
            TAG,
            "SteeringHeat: want=$want dev=$dev status=$status before=${render(before)} " +
                "after=[${after.joinToString(",") { render(it) }}] -> $verdict",
        )
        if (status == WriteOutcome.TRANSIENT) {
            log("unreachable")
            return Result.UNREACHABLE
        }
        repeat(READBACK_ATTEMPTS) {
            delay(READBACK_DELAY_MS)
            val value = readback.read()
            after += value
            if (value == want) {
                log("OK")
                return Result.OK
            }
        }
        val state = after.last()
        if (state == STATE_ABSENT || state == SentinelDecoder.FEATURE_LINK_ERROR) {
            log("not equipped state=$state")
            return Result.NOT_EQUIPPED
        }
        if (last) {
            log("no effect")
            return Result.NO_EFFECT
        }
        log("fallback dev=$FALLBACK_DEV")
        return null
    }

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
