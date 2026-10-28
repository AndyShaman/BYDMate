package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcAddressResult
import com.bydmate.app.helper.dtc.DtcWire

enum class DtcKind { NOW, OLD, PENDING }

/** Fault type from the third code byte; only the five known values have a text. */
enum class DtcFault { NO_SIGNAL, OPEN_CIRCUIT, SHORT_TO_GROUND, SHORT_TO_PLUS, COMPONENT }

/** `59 01` answer: availability [mask], DTC [format] and [count]. */
data class DtcCount(val mask: Int, val format: Int, val count: Int)

data class DtcRow(val code: String, val status: Int, val kind: DtcKind, val ftb: Int)

/** [records] = every 4-byte record read; [rows] = the visible ones. [negative] = the block answered an NRC. */
data class DtcList(val records: Int, val rows: List<DtcRow>, val negative: Boolean)

data class DtcFound(val addr: Int, val row: DtcRow, val block: DtcBlockName)

/** [found]: NOW rows first, then PENDING and OLD in address order. */
data class DtcScanSummary(
    val answered: Int,
    val total: Int,
    val now: Int,
    val old: Int,
    val pending: Int,
    val found: List<DtcFound>,
    /** Blocks that counted codes but whose list could not be read. */
    val unreadBlocks: Int = 0,
)

/**
 * Pure reading of the raw UDS answers the daemon hands over. Status bits (ISO 14229): 0x01 failing
 * now, 0x04 pending, 0x08 confirmed. A record with none of them (not tested, or only the
 * "since clear" bits) is never shown.
 */
object DtcParser {
    private const val POSITIVE_READ_DTC = 0x59
    private const val NEGATIVE = 0x7F
    private const val COUNT_LEN = 6
    private const val LIST_HEAD = 3
    private const val RECORD = 4
    private const val CODE_BYTES = 3

    private const val TEST_FAILED = 0x01
    private const val PENDING = 0x04
    private const val CONFIRMED = 0x08

    /** DTC format byte of ISO 15031-6 (the letter form). */
    private const val FORMAT_ISO15031 = 0
    private val LETTERS = charArrayOf('P', 'C', 'B', 'U')

    private val FAULTS = mapOf(
        0x87 to DtcFault.NO_SIGNAL,
        0x13 to DtcFault.OPEN_CIRCUIT,
        0x11 to DtcFault.SHORT_TO_GROUND,
        0x12 to DtcFault.SHORT_TO_PLUS,
        0x09 to DtcFault.COMPONENT,
    )

    /** Outcomes of a block that is there: it answered, positively or not. */
    private val PRESENT = setOf(
        DtcWire.OUTCOME_OK, DtcWire.OUTCOME_NRC, DtcWire.OUTCOME_MULTIFRAME, DtcWire.OUTCOME_TRUNCATED,
    )

    /** Null for a negative or too short answer. */
    fun parseCount(uds: ByteArray): DtcCount? {
        if (uds.size < COUNT_LEN || u(uds, 0) != POSITIVE_READ_DTC || u(uds, 1) != DtcWire.SUB_COUNT) return null
        return DtcCount(mask = u(uds, 2), format = u(uds, 3), count = (u(uds, 4) shl 8) or u(uds, 5))
    }

    /** [format] is the DTC format byte of the count answer, null when unknown. */
    fun parseList(uds: ByteArray, format: Int?): DtcList {
        if (uds.isNotEmpty() && u(uds, 0) == NEGATIVE) return DtcList(0, emptyList(), negative = true)
        if (uds.size < LIST_HEAD || u(uds, 0) != POSITIVE_READ_DTC || u(uds, 1) != DtcWire.SUB_LIST) {
            return DtcList(0, emptyList(), negative = false)
        }
        val records = (uds.size - LIST_HEAD) / RECORD
        val rows = (0 until records).mapNotNull { i ->
            val at = LIST_HEAD + i * RECORD
            val status = u(uds, at + CODE_BYTES)
            classify(status)?.let { kind ->
                DtcRow(codeText(uds.copyOfRange(at, at + CODE_BYTES), format), status, kind, u(uds, at + 2))
            }
        }
        return DtcList(records, rows, negative = false)
    }

    /** Null = the record is dropped (not tested, no failure bit). */
    fun classify(status: Int): DtcKind? = when {
        status and TEST_FAILED != 0 -> DtcKind.NOW
        status and CONFIRMED != 0 -> DtcKind.OLD
        status and PENDING != 0 -> DtcKind.PENDING
        else -> null
    }

    /** `U0164-87` for format 00, raw hex `D16687` for format 01, unknown or null. */
    fun codeText(code: ByteArray, format: Int?): String {
        val hex = code.joinToString("") { "%02X".format(it) }
        if (format != FORMAT_ISO15031 || code.size != CODE_BYTES) return hex
        val b0 = u(code, 0)
        return "${LETTERS[b0 shr 6]}${(b0 shr 4) and 0x3}${"%X".format(b0 and 0xF)}${"%02X".format(u(code, 1))}" +
            "-${"%02X".format(u(code, 2))}"
    }

    fun faultType(ftb: Int): DtcFault? = FAULTS[ftb]

    fun summarize(results: List<DtcAddressResult>): DtcScanSummary {
        val found = results
            .filter { it.outcome == DtcWire.OUTCOME_OK }
            .sortedBy { it.addr }
            .flatMap { r ->
                val list = r.listUds ?: return@flatMap emptyList()
                val format = r.countUds?.let { parseCount(it) }?.format
                val block = DtcBlockNames.identify(r.addr, r.idUds)
                parseList(list, format).rows.map { DtcFound(r.addr, it, block) }
            }
            .sortedBy { if (it.row.kind == DtcKind.NOW) 0 else 1 }
        return DtcScanSummary(
            answered = results.count { it.outcome in PRESENT },
            total = results.size,
            now = found.count { it.row.kind == DtcKind.NOW },
            old = found.count { it.row.kind == DtcKind.OLD },
            pending = found.count { it.row.kind == DtcKind.PENDING },
            found = found,
        )
    }

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
}
