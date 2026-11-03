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

/**
 * Blocks shown as one: the same visible rows in the same order under the same block name (the
 * L3 URE answers at 0x7E0 and 0x7E2 alike). [addrs] ascending, [block] and [rows] of the first.
 */
data class DtcBlockGroup(val addrs: List<Int>, val block: DtcBlockName, val rows: List<DtcRow>)

/**
 * [found]: NOW rows first, then PENDING and OLD in address order, every address of a merged
 * group included; [now], [old] and [pending] count the codes of a merged group once.
 */
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

    private val LETTERS = charArrayOf('P', 'C', 'B', 'U')
    private val UNMERGED = setOf(DtcBlock.BODY, DtcBlock.UNKNOWN)

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

    /**
     * The SAE letter form `U1166-87` for every 3-byte code, whatever the [format] byte (00, 01 or
     * unknown): the BYD manuals write the ISO 14229-1 bytes `D1 66 87` as U1166-87 too. Raw hex
     * only for a code that is not 3 bytes; the daemon log keeps the raw bytes.
     */
    @Suppress("UNUSED_PARAMETER", "UnusedParameter") // the format byte no longer changes the text
    fun codeText(code: ByteArray, format: Int?): String {
        if (code.size != CODE_BYTES) return code.joinToString("") { "%02X".format(it) }
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
        val rows = groups(found).flatMap { it.rows }
        return DtcScanSummary(
            answered = results.count { it.outcome in PRESENT },
            total = results.size,
            now = rows.count { it.kind == DtcKind.NOW },
            old = rows.count { it.kind == DtcKind.OLD },
            pending = rows.count { it.kind == DtcKind.PENDING },
            found = found,
            unreadBlocks = results.count { unread(it) },
        )
    }

    /**
     * One group per address with rows, addresses with identical rows and block merged (addrs
     * ascending); groups in the order their first address appears in [found], which for
     * [summarize]'s list is the current code first, then address order. Only a named block merges:
     * BODY and UNKNOWN name a family or nothing, so two of them are two control units.
     */
    fun groups(found: List<DtcFound>): List<DtcBlockGroup> = found
        .groupBy { it.addr }
        .map { (addr, list) -> DtcBlockGroup(listOf(addr), list.first().block, list.map { it.row }) }
        .groupBy { g ->
            val unit = if (g.block.block in UNMERGED) g.addrs.single() else null
            Triple(g.block.block, g.rows, unit)
        }
        .values
        .map { same ->
            val addrs = same.map { it.addrs.single() }.sorted()
            same.first { it.addrs.single() == addrs.first() }.copy(addrs = addrs)
        }

    /** The count answer says there are codes, but no list was read (no answer, NRC, multiframe, cut). */
    private fun unread(r: DtcAddressResult): Boolean {
        val count = r.countUds?.let { parseCount(it) }?.count ?: return false
        if (count == 0) return false
        val list = r.listUds
        return r.outcome != DtcWire.OUTCOME_OK || list == null || parseList(list, null).negative
    }

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
}
