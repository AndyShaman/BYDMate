package com.bydmate.app.data.tpms

import com.bydmate.app.helper.dtc.TpmsPlanEntry
import com.bydmate.app.helper.dtc.TpmsSlot
import com.bydmate.app.helper.dtc.TpmsWire
import org.json.JSONArray
import org.json.JSONObject

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

/** The wheel count of a set and of the block: FL, FR, RL, RR. */
internal const val WHEELS = 4

/** An ID as the window and the sets show it: 8 uppercase hex digits. */
internal fun tyreHex(id: Int): String = "%08X".format(id)

object TyreSetsCodec {
    fun toJson(sets: TyreSets): String =
        JSONObject().put(KEY_WINTER, array(sets.winter)).put(KEY_SUMMER, array(sets.summer)).toString()

    /** Null, empty or malformed JSON → empty sets, never a throw. */
    fun fromJson(json: String?): TyreSets {
        if (json.isNullOrBlank()) return TyreSets()
        return runCatching {
            val o = JSONObject(json)
            TyreSets(set(o.getJSONArray(KEY_WINTER)), set(o.getJSONArray(KEY_SUMMER)))
        }.getOrDefault(TyreSets())
    }

    // JSONObject.NULL, not a Kotlin null: Android's put(null) drops the cell.
    private fun array(set: TyreSet) = JSONArray().apply { set.ids.forEach { put(it ?: JSONObject.NULL) } }

    private fun set(a: JSONArray): TyreSet {
        require(a.length() == WHEELS)
        return TyreSet(
            (0 until WHEELS).map { i ->
                if (a.isNull(i)) null else TyrePlans.normalize(a.get(i) as String)
            },
        )
    }

    private const val KEY_WINTER = "winter"
    private const val KEY_SUMMER = "summer"
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
    fun normalize(raw: String?): String? = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    /**
     * [current] = the IDs last read from the block (FL..RR, null = not read); [target] = the cells
     * of the window (null or blank = leave that wheel). Not read (any current null) → NOT_READ.
     * Invalid hex, length != 8, 00000000 / FFFFFFFF → the matching error. The final four IDs
     * (current with the targets applied) must be distinct, else DUPLICATE naming the clashing wheels.
     */
    fun build(current: List<Int?>, target: List<String?>): TyrePlan {
        val unread = (0 until WHEELS).filter { current.getOrNull(it) == null }
        if (unread.isNotEmpty()) return TyrePlan.Invalid(TyrePlanError.NOT_READ, unread)
        val errors = (0 until WHEELS).mapNotNull { i -> normalize(target.getOrNull(i))?.let { cellError(it) }?.let { i to it } }
        errors.firstOrNull()?.let { (_, error) ->
            return TyrePlan.Invalid(error, errors.filter { it.second == error }.map { it.first })
        }
        val final = (0 until WHEELS).map { i ->
            normalize(target.getOrNull(i))?.let { it.toLong(HEX).toInt() } ?: current[i]!!
        }
        val clashing = (0 until WHEELS).filter { i -> final.count { it == final[i] } > 1 }
        if (clashing.isNotEmpty()) return TyrePlan.Invalid(TyrePlanError.DUPLICATE, clashing)
        val entries = (0 until WHEELS).filter { final[it] != current[it] }
            .map { TpmsPlanEntry(TpmsWire.DIDS[it], current[it]!!, final[it]) }
        return if (entries.isEmpty()) TyrePlan.NothingToWrite else TyrePlan.Ok(entries)
    }

    /** The error of one normalised cell, null when it is a writable ID. */
    fun cellError(id: String): TyrePlanError? = when {
        id.any { it !in HEX_DIGITS } -> TyrePlanError.INVALID_HEX
        id.length != ID_CHARS -> TyrePlanError.INVALID_LENGTH
        id == RESERVED_ZERO || id == RESERVED_ALL_SET -> TyrePlanError.INVALID_VALUE
        else -> null
    }

    private const val HEX = 16
    private const val ID_CHARS = 8
    private const val HEX_DIGITS = "0123456789ABCDEF"
    private const val RESERVED_ZERO = "00000000"
    private const val RESERVED_ALL_SET = "FFFFFFFF"
}

object TyreSetUpdate {
    /**
     * Sets after a write. [complete] = all four slots were read back as the confirmed target;
     * otherwise the sets stay as they were. [written] = the set the user wrote from the table: it
     * becomes [readBack] (so a dragged order is kept). [written] null = a single wheel ([wheel] is
     * the written index): the set whose four IDs equal [before] (the block before the write) takes
     * [readBack]; without one, the only set whose other three IDs equal [readBack]'s other three;
     * when no set matches, nothing changes.
     */
    @Suppress("LongParameterList") // the sets, what was written and the block before and after it
    fun afterWrite(
        sets: TyreSets,
        written: TyreSetName?,
        wheel: Int?,
        before: List<Int?>,
        readBack: List<Int?>,
        complete: Boolean,
    ): TyreSets {
        if (!complete || readBack.size != WHEELS || readBack.any { it == null }) return sets
        val back = TyreSet(readBack.map { tyreHex(it!!) })
        val name = written ?: run {
            val w = wheel ?: return sets
            val was = before.map { it?.let(::tyreHex) }
            TyreSetName.entries.firstOrNull { n -> was.none { it == null } && sets.of(n).ids == was }
                // Only an unambiguous match: the set on the car is the one whose other three wheels agree.
                ?: TyreSetName.entries.singleOrNull { n ->
                    (0 until WHEELS).all { it == w || sets.of(n).ids.getOrNull(it) == back.ids[it] }
                }
                ?: return sets
        }
        return if (name == TyreSetName.WINTER) sets.copy(winter = back) else sets.copy(summer = back)
    }
}

enum class TyreBattery { OK, LOW, UNKNOWN, NOT_REGISTERED }

/** One wheel in the window: the ID in the block, whether the block hears the sensor, its battery. */
data class TyreWheelStatus(val id: Int?, val heard: Boolean?, val battery: TyreBattery)

object TyreWheelStatuses {
    /**
     * [slots] FL..RR as parsed from 0x720 (null = not read); [dtcUds] = the `59 02 …` answer of
     * 0x720. Empty slot IDs (0xFFFFFFFF or 0) map to [TyreBattery.NOT_REGISTERED]. Battery LOW
     * only for an active record (status bit 0x01) of exactly C100D-00 / C100E-00 / C100F-00 /
     * C1010-00 (bytes 50 0D 00 … 50 10 00) for that wheel; a stored-only record or any other FTB
     * (C1010-08) is not LOW. Null [dtcUds] or not a `59 02` answer -> UNKNOWN.
     */
    fun of(slots: List<TpmsSlot?>, dtcUds: ByteArray?): List<TyreWheelStatus> {
        val low = lowBattery(dtcUds)
        return (0 until WHEELS).map { i ->
            val slot = slots.getOrNull(i)
            val battery = when {
                slot != null && TpmsWire.isEmptyId(slot.id) -> TyreBattery.NOT_REGISTERED
                low == null -> TyreBattery.UNKNOWN
                i in low -> TyreBattery.LOW
                else -> TyreBattery.OK
            }
            TyreWheelStatus(slot?.id, slot?.heard, battery)
        }
    }

    /** Wheels with an active battery record; null when [dtcUds] is not a `59 02` answer. */
    private fun lowBattery(dtcUds: ByteArray?): Set<Int>? {
        if (dtcUds == null || dtcUds.size < LIST_HEAD) return null
        if (u(dtcUds, 0) != POSITIVE_LIST || u(dtcUds, 1) != SUB_LIST) return null
        val low = HashSet<Int>()
        var i = LIST_HEAD
        while (i + RECORD_BYTES <= dtcUds.size) {
            val wheel = u(dtcUds, i + 1) - BATTERY_FL_LO
            val battery = u(dtcUds, i) == BATTERY_HI && wheel in 0 until WHEELS && u(dtcUds, i + 2) == BATTERY_FTB
            if (battery && (u(dtcUds, i + 3) and STATUS_ACTIVE) != 0) low += wheel
            i += RECORD_BYTES
        }
        return low
    }

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private const val POSITIVE_LIST = 0x59
    private const val SUB_LIST = 0x02
    /** `59 02 <availability mask>`, then 4-byte records: 3 code bytes and the status. */
    private const val LIST_HEAD = 3
    private const val RECORD_BYTES = 4
    /** C100D..C1010 (FL..RR) = `50 0D..10`, FTB 00; a C1010-08 is a software fault, not a battery. */
    private const val BATTERY_HI = 0x50
    private const val BATTERY_FL_LO = 0x0D
    private const val BATTERY_FTB = 0x00
    private const val STATUS_ACTIVE = 0x01
}
