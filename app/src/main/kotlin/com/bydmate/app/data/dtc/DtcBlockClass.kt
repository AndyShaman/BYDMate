package com.bydmate.app.data.dtc

/**
 * The block class of the description tables (`rows.tsv`): from the block's own F194 name, the body
 * family told apart by address. An unnamed block falls back to its address only where L3, Denza
 * Z9GT and Xia agree; anywhere else the class is unknown (null).
 */
object DtcBlockClass {
    private val BODY_SIDES = mapOf(0x720 to "BODY_LEFT", 0x747 to "BODY_RIGHT", 0x726 to "BODY_REAR")

    private val BY_ADDRESS = mapOf(0x712 to "ADAS", 0x782 to "BRAKES", 0x7F1 to "AIRBAG", 0x7F2 to "RADAR") + BODY_SIDES

    fun of(name: DtcBlockName): String? = when (name.block) {
        DtcBlock.ADAS, DtcBlock.BRAKES, DtcBlock.STEERING, DtcBlock.AIRBAG, DtcBlock.RADAR,
        DtcBlock.BMS, DtcBlock.CHARGER, DtcBlock.URE -> name.block.name
        DtcBlock.BODY -> BODY_SIDES[name.addr]
        DtcBlock.UNKNOWN -> BY_ADDRESS[name.addr]
    }
}
