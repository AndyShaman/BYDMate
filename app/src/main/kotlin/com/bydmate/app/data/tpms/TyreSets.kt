package com.bydmate.app.data.tpms

import com.bydmate.app.helper.dtc.TpmsPlanEntry
import com.bydmate.app.helper.dtc.TpmsSlot

/**
 * The owner's winter and summer sensor sets (FL, FR, RL, RR) and the pure rules around them:
 * the write plan from the window, the set update after a write, the wheel status. Decisions:
 * CONTEXT.md «Номера датчиков колёс (2026-11-20)»; design `.research/tpms-l3-2026-11-16/design-tpms-fw-2026-11-20.md` D.2, D.8, D.9.
 */
enum class TyreSetName { WINTER, SUMMER }

/** Four IDs in wheel order FL, FR, RL, RR as 8-char uppercase hex; null = empty cell. */
data class TyreSet(val ids: List<String?> = listOf(null, null, null, null))

data class TyreSets(val winter: TyreSet = TyreSet(), val summer: TyreSet = TyreSet()) {
    fun of(name: TyreSetName) = if (name == TyreSetName.WINTER) winter else summer
}

object TyreSetsCodec {
    fun toJson(sets: TyreSets): String = TODO()
    /** Null, empty or malformed JSON → empty sets, never a throw. */
    fun fromJson(json: String?): TyreSets = TODO()
}

enum class TyrePlanError { INVALID_HEX, INVALID_LENGTH, INVALID_VALUE, DUPLICATE, NOT_READ }

sealed class TyrePlan {
    /** Entries in DID order 0x3305..0x3308, only the wheels that change. */
    data class Ok(val entries: List<TpmsPlanEntry>) : TyrePlan()
    /** Every target equals what the block holds: nothing is sent. */
    object NothingToWrite : TyrePlan()
    /** [wheels] are indexes 0..3 (FL..RR) the error is about. */
    data class Invalid(val error: TyrePlanError, val wheels: List<Int>) : TyrePlan()
}

object TyrePlans {
    /** Trimmed, uppercase; null or blank → null. Inner spaces or a `0x` prefix stay and fail as hex. */
    fun normalize(raw: String?): String? = TODO()

    /**
     * [current] = the IDs last read from the block (FL..RR, null = not read); [target] = the cells
     * of the window (null or blank = leave that wheel). Not read (any current null) → NOT_READ.
     * Invalid hex, length != 8, 00000000 / FFFFFFFF → the matching error. The final four IDs
     * (current with the targets applied) must be distinct, else DUPLICATE naming the clashing wheels.
     */
    fun build(current: List<Int?>, target: List<String?>): TyrePlan = TODO()
}

object TyreSetUpdate {
    /**
     * Sets after a write. [complete] = every planned wheel came back WRITTEN and all four slots
     * were read back; otherwise the sets stay as they were. [written] = the set the user wrote
     * from the table: it becomes [readBack] (so a dragged order is kept). [written] null = a
     * single wheel: the set whose other three IDs equal [readBack]'s other three ([wheel] is the
     * written index) takes [readBack]; when no set matches, nothing changes.
     */
    fun afterWrite(sets: TyreSets, written: TyreSetName?, wheel: Int?, readBack: List<Int?>, complete: Boolean): TyreSets = TODO()
}

enum class TyreBattery { OK, LOW, UNKNOWN }

/** One wheel in the window: the ID in the block, whether the block hears the sensor, its battery. */
data class TyreWheelStatus(val id: Int?, val heard: Boolean?, val battery: TyreBattery)

object TyreWheelStatuses {
    /**
     * [slots] FL..RR as parsed from 0x720 (null = not read); [dtcUds] = the `59 02 …` answer of
     * 0x720. Battery LOW only for an active record (status bit 0x01) of exactly C100D-00 / C100E-00 /
     * C100F-00 / C1010-00 (bytes 50 0D 00 … 50 10 00) for that wheel; a stored-only record or any
     * other FTB (C1010-08) is not LOW. Null [dtcUds] or not a `59 02` answer → UNKNOWN.
     */
    fun of(slots: List<TpmsSlot?>, dtcUds: ByteArray?): List<TyreWheelStatus> = TODO()
}
