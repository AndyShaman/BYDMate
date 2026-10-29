package com.bydmate.app.data.dtc

/** A block the identity table knows by its own F194 answer; everything else is [UNKNOWN]. */
enum class DtcBlock { ADAS, BRAKES, STEERING, AIRBAG, RADAR, BMS, CHARGER, URE, BODY, UNKNOWN }

/** [block] plus the address, which the display text appends for [DtcBlock.BODY] and [DtcBlock.UNKNOWN]. */
data class DtcBlockName(val block: DtcBlock, val addr: Int)

object DtcBlockNames {
    /** (supplier, type) of the Leopard 3 blocks, as their own F194 answers read on the car. */
    private val KNOWN: Map<Pair<Int, Int>, DtcBlock> = mapOf(
        (0x4B51 to 0x83) to DtcBlock.ADAS,
        (0x3233 to 0x0B) to DtcBlock.BRAKES,
        (0x3053 to 0x6F) to DtcBlock.STEERING,
        (0x4843 to 0x11) to DtcBlock.AIRBAG,
        (0x0031 to 0x70) to DtcBlock.RADAR,
        (0x4A52 to 0x43) to DtcBlock.BMS,
        (0x4835 to 0x0E) to DtcBlock.CHARGER,
        (0x4B30 to 0xFD) to DtcBlock.URE,
    )

    /** The body family (L3 0x720 / 0x726 / 0x747): one supplier, three types. */
    private const val BODY_SUPPLIER = 0x4845
    private val BODY_TYPES = 0xF0..0xF2

    private const val ID_HEAD_LEN = 9
    private const val TYPE_INDEX = 8

    /** Names a block only from its identity answer `62 F1 94 SS SS 00 GG xx TT…` (supplier + type). */
    fun identify(addr: Int, idUds: ByteArray?): DtcBlockName {
        val unknown = DtcBlockName(DtcBlock.UNKNOWN, addr)
        if (idUds == null || idUds.size < ID_HEAD_LEN) return unknown
        if (u(idUds, 0) != 0x62 || u(idUds, 1) != 0xF1 || u(idUds, 2) != 0x94) return unknown
        val supplier = (u(idUds, 3) shl 8) or u(idUds, 4)
        val type = u(idUds, TYPE_INDEX)
        if (supplier == BODY_SUPPLIER && type in BODY_TYPES) return DtcBlockName(DtcBlock.BODY, addr)
        return DtcBlockName(KNOWN[supplier to type] ?: DtcBlock.UNKNOWN, addr)
    }

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
}
