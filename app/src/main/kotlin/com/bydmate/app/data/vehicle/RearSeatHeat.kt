package com.bydmate.app.data.vehicle

import com.bydmate.app.data.nativestack.FidCatalog

// STUB for the red tests of #327 (rear seat heat): signatures only, every body returns a neutral wrong value.

enum class RearSeat { LEFT, RIGHT }

/** [level]: 0 = off, 1..2 = heat stage. */
data class RearSeatCommand(val seat: RearSeat, val level: Int)

data class RearSeatAddresses(val hasFid: Int, val stateFid: Int, val setFid: Int)

object RearSeatHeat {
    const val READ_DEV = 1023
    const val SET_FID_LEFT = 1125122091
    const val SET_FID_RIGHT = 1125122099
    const val MAX_LEVEL = 2

    fun actionName(seat: RearSeat): String = ""

    val ACTIONS: Set<String> = emptySet()

    val SYMBOLS: List<String> = emptyList()

    fun writeValue(level: Int): Int = level

    fun addresses(catalog: FidCatalog?, seat: RearSeat): RearSeatAddresses? = null
}

/** Raw value of a dev=1023 rear seat fid, sentinels kept. Null means the read did not complete. */
fun interface RearSeatReader {
    suspend fun read(fid: Int): Int?
}

class RearSeatHeatChannel(
    private val writer: SeatWriter,
    private val reader: RearSeatReader,
    private val catalog: () -> FidCatalog?,
) {
    enum class Result { OK, NOT_EQUIPPED, NO_EFFECT, UNREACHABLE, UNCONFIRMED }

    data class Outcome(val result: Result, val verdict: String, val written: Boolean, val state: Int?)

    suspend fun actuate(seat: RearSeat, level: Int): Outcome = Outcome(Result.NOT_EQUIPPED, "stub", false, null)

    suspend fun equipped(seat: RearSeat): Boolean = false
}
