package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.nativestack.FidCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RearSeat { LEFT, RIGHT }

/** [level]: 0 = off, 1..2 = heat stage. */
data class RearSeatCommand(val seat: RearSeat, val level: Int)

/** One rear seat's fids on this firmware: the fitted flag and the state (dev=1023), the set (dev=1001). */
data class RearSeatAddresses(val hasFid: Int, val stateFid: Int, val setFid: Int)

/**
 * Rear seat heating (#327), the stock settings' write: the car takes level + 1 (1 = off,
 * 2 = stage 1, 3 = stage 2) on the dev=1001 set fid and reports the same value on the
 * dev=1023 state fid. The fitted flag and the state sit on different fids per platform
 * (Song Plus DL3 vs DL4/DL5), so they come from the car's own catalog; the set fid must
 * match the allowlist constant, or the car is treated as not having it.
 */
object RearSeatHeat {
    const val READ_DEV = 1023
    const val SET_FID_LEFT = 1125122091
    const val SET_FID_RIGHT = 1125122099
    const val MAX_LEVEL = 2

    fun actionName(seat: RearSeat): String = when (seat) {
        RearSeat.LEFT -> "rear_left_seat_heat"
        RearSeat.RIGHT -> "rear_right_seat_heat"
    }

    val ACTIONS: Set<String> = RearSeat.entries.map { actionName(it) }.toSet()

    private fun hasSymbol(seat: RearSeat) = "Setting.SET_HAS_REAR_${seat.name}_SEAT_HEATING"
    private fun stateSymbol(seat: RearSeat) = "Setting.SET_REAR_${seat.name}_SEAT_HEATING_STATE"
    private fun setSymbol(seat: RearSeat) = "Setting.SET_REAR_${seat.name}_SEAT_HEATING_STATE_SET"

    val SYMBOLS: List<String> = RearSeat.entries.flatMap { listOf(hasSymbol(it), stateSymbol(it), setSymbol(it)) }

    fun writeValue(level: Int): Int = level + 1

    fun setFid(seat: RearSeat): Int = if (seat == RearSeat.LEFT) SET_FID_LEFT else SET_FID_RIGHT

    /** Null when there is no catalog, a symbol is missing, or the car's set fid is not ours. */
    fun addresses(catalog: FidCatalog?, seat: RearSeat): RearSeatAddresses? {
        catalog ?: return null
        val has = catalog.fidOf(hasSymbol(seat)) ?: return null
        val state = catalog.fidOf(stateSymbol(seat)) ?: return null
        val set = catalog.fidOf(setSymbol(seat)) ?: return null
        return if (set == setFid(seat)) RearSeatAddresses(has, state, set) else null
    }

    /**
     * A fitted flag that says nothing: no answer or a read error sentinel. 65535 (no CAN
     * link) is an answer, the way a car without the function replies.
     */
    fun hasUnreadable(has: Int?): Boolean =
        has == null || (has != SentinelDecoder.FEATURE_LINK_ERROR && SentinelDecoder.decodeInt(has) == null)

    /** Why a side whose fitted flag read [has] stays hidden, or null when it is shown (has == 1). */
    fun hiddenReason(has: Int?): String? = when {
        has == 1 -> null
        hasUnreadable(has) -> "has unreadable"
        else -> "has=$has"
    }
}

/** Raw value of a dev=1023 rear seat fid, sentinels kept. Null means the read did not complete. */
fun interface RearSeatReader {
    suspend fun read(fid: Int): Int?
}

/**
 * Rear seat heat stage (#327). Not validated on a car, so the state feedback decides:
 *  - no catalog or an unreadable fitted flag → UNREACHABLE, nothing written
 *  - rear symbols missing, set fid not ours, fitted flag not 1 → NOT_EQUIPPED, nothing written
 *  - a write the daemon did not take as REAL → UNREACHABLE, not read back
 *  - state == level + 1 within two reads    → OK
 *  - state still the one before             → NO_EFFECT
 *  - anything else                          → UNCONFIRMED
 * Commands run one at a time under [mutex], so the next one reads the real state.
 */
class RearSeatHeatChannel(
    private val writer: SeatWriter,
    private val reader: RearSeatReader,
    private val catalog: () -> FidCatalog?,
) {
    enum class Result { OK, NOT_EQUIPPED, NO_EFFECT, UNREACHABLE, UNCONFIRMED }

    /**
     * [verdict] ends the channel's log line; [written] says whether the set write reached the
     * car; [state] is the last state read (null when the read failed or never happened).
     */
    data class Outcome(val result: Result, val verdict: String, val written: Boolean, val state: Int?)

    private val mutex = Mutex()

    suspend fun actuate(seat: RearSeat, level: Int): Outcome = mutex.withLock { run(seat, level) }

    /** The car has heat on [seat]: full catalog and the fitted flag reads 1. Logs one gate line. */
    suspend fun equipped(seat: RearSeat): Boolean {
        val cat = catalog()
        val addr = RearSeatHeat.addresses(cat, seat)
        if (addr == null) {
            Log.i(TAG, "RearSeatHeat: gate seat=$seat -> hidden reason=${if (cat == null) "no catalog" else "not in catalog"}")
            return false
        }
        val has = read(addr.hasFid)
        val reason = RearSeatHeat.hiddenReason(has)
        Log.i(
            TAG,
            "RearSeatHeat: gate seat=$seat has[dev=${RearSeatHeat.READ_DEV} fid=${addr.hasFid}]=${render(has)} " +
                "state[fid=${addr.stateFid}] set[fid=${addr.setFid}] -> " + (reason?.let { "hidden reason=$it" } ?: "shown"),
        )
        return reason == null
    }

    private suspend fun run(seat: RearSeat, level: Int): Outcome {
        val want = RearSeatHeat.writeValue(level)
        val head = "RearSeatHeat: seat=$seat lvl=$level want=$want"
        val cat = catalog()
        val addr = RearSeatHeat.addresses(cat, seat)
        if (addr == null) {
            val outcome = if (cat == null) Outcome(Result.UNREACHABLE, "no catalog", false, null)
                else Outcome(Result.NOT_EQUIPPED, "not in catalog", false, null)
            Log.i(TAG, "$head -> ${outcome.verdict}, nothing written")
            return outcome
        }
        val has = read(addr.hasFid)
        if (has != 1) {
            val outcome = if (RearSeatHeat.hasUnreadable(has)) Outcome(Result.UNREACHABLE, "has unreadable", false, null)
                else Outcome(Result.NOT_EQUIPPED, "has=$has", false, null)
            Log.i(TAG, "$head has=${render(has)} -> ${outcome.verdict}, nothing written")
            return outcome
        }
        val before = read(addr.stateFid)
        val status = writer.write(RearSeatHeat.actionName(seat), want)
        val after = mutableListOf<Int?>()
        fun log(verdict: String) = Log.i(
            TAG,
            "$head has=$has before=${render(before)} write=$status after=[${after.joinToString(",") { render(it) }}] -> $verdict",
        )
        if (status != WriteOutcome.REAL) {
            val verdict = "write $status"
            log(verdict)
            return Outcome(Result.UNREACHABLE, verdict, status != WriteOutcome.TRANSIENT, before)
        }
        repeat(READBACK_ATTEMPTS) {
            delay(READBACK_DELAY_MS)
            val value = read(addr.stateFid)
            after += value
            if (value == want) {
                log("OK")
                return Outcome(Result.OK, "OK", true, value)
            }
        }
        val state = after.last()
        val outcome = if (state == before && before != want) {
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
            Log.w(TAG, "RearSeatHeat: read fid=$fid failed: ${it.message}")
        }
        .getOrNull()

    private fun render(value: Int?) = value?.toString() ?: "err"

    private companion object {
        const val TAG = "RearSeatHeatChannel"
        const val READBACK_ATTEMPTS = 2
        /** The author's Han confirms in 0.33-0.5 s; same budget as the HUD and steering heat channels. */
        const val READBACK_DELAY_MS = 400L
    }
}
