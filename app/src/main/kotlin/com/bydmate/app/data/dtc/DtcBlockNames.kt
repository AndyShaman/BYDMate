@file:Suppress("UnusedParameter", "UnusedPrivateProperty", "FunctionOnlyReturningConstant", "EmptyFunctionBlock") // compile stub, remove with the implementation

package com.bydmate.app.data.dtc

/** A block the identity table knows by its own F194 answer; everything else is [UNKNOWN]. */
enum class DtcBlock { ADAS, BRAKES, STEERING, AIRBAG, RADAR, BMS, CHARGER, URE, BODY, UNKNOWN }

/** [block] plus the address, which the display text appends for [DtcBlock.BODY] and [DtcBlock.UNKNOWN]. */
data class DtcBlockName(val block: DtcBlock, val addr: Int)

object DtcBlockNames {
    /** Names a block only from its identity answer `62 F1 94 SS SS 00 GG xx TT…` (supplier + type). */
    fun identify(addr: Int, idUds: ByteArray?): DtcBlockName = DtcBlockName(DtcBlock.UNKNOWN, addr)
}
