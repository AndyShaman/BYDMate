package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcAddressResult
import com.bydmate.app.helper.dtc.DtcEraseResult
import com.bydmate.app.helper.dtc.DtcWire

/** The Leopard 3 autotypes, whose firmware from 1.6.0 has the diagnostic service. */
private val LEOPARD3_AUTOTYPES = setOf(164, 259, 260, 261, 262, 357)

/** True when «Недоступно» may add «update the head unit to 1.6.0 or newer». */
fun dtcFirmwareHint(autotype: Int?): Boolean = autotype in LEOPARD3_AUTOTYPES

/**
 * Which blocks an erase may ask for: only those with a shown code in the scan the user looks at,
 * and only while that scan is at most [MAX_SCAN_AGE_MS] old. A group holding an address of
 * [DtcSavedScan.eraseLocked] is left out.
 */
object DtcErasePlan {
    const val MAX_SCAN_AGE_MS = 600_000L

    /** Null without a scan or with a stale one; else the addresses with visible rows, ascending. */
    fun eligible(saved: DtcSavedScan?, nowMs: Long): IntArray? =
        groups(saved, nowMs)?.flatMap { it.addrs }?.sorted()?.toIntArray()

    /** All addresses of the group [addr] belongs to; empty for an address without rows. */
    fun eligibleBlock(saved: DtcSavedScan?, addr: Int, nowMs: Long): IntArray? =
        groups(saved, nowMs)?.let { gs -> gs.firstOrNull { addr in it.addrs }?.addrs.orEmpty().toIntArray() }

    private fun groups(saved: DtcSavedScan?, nowMs: Long): List<DtcBlockGroup>? {
        if (saved == null || nowMs - saved.at > MAX_SCAN_AGE_MS) return null
        return DtcParser.groups(DtcParser.summarize(saved.results).found)
            .filter { g -> g.addrs.none { it in saved.eraseLocked } }
    }
}

/**
 * One block after an erase: whether the block confirmed the clear, and what the re-read found.
 * Codes back after a confirmed clear mean the fault is there now.
 */
enum class DtcEraseVerdict {
    ERASED, ERASED_BUT_BACK, UNKNOWN_CODES_PRESENT, LIKELY_ERASED,
    CONFIRMED_UNVERIFIED, UNCONFIRMED_UNVERIFIED, REFUSED, NOT_RUN,
}

fun dtcEraseVerdict(r: DtcEraseResult): DtcEraseVerdict {
    if (r.confirm == DtcWire.CONFIRM_REFUSED) return DtcEraseVerdict.REFUSED
    if (r.confirm == DtcWire.CONFIRM_NOT_RUN) return DtcEraseVerdict.NOT_RUN
    val confirmed = r.confirm == DtcWire.CONFIRM_CONFIRMED
    val rows = rereadRows(r) ?: return if (confirmed) {
        DtcEraseVerdict.CONFIRMED_UNVERIFIED
    } else {
        DtcEraseVerdict.UNCONFIRMED_UNVERIFIED
    }
    return when {
        confirmed && rows.isEmpty() -> DtcEraseVerdict.ERASED
        confirmed -> DtcEraseVerdict.ERASED_BUT_BACK
        rows.isEmpty() -> DtcEraseVerdict.LIKELY_ERASED
        else -> DtcEraseVerdict.UNKNOWN_CODES_PRESENT
    }
}

/** The blocks whose erase result is unknown: no erase of them until a new scan. */
fun dtcEraseLocks(erase: List<DtcEraseResult>): Set<Int> = erase.filter {
    dtcEraseVerdict(it).let { v -> v == DtcEraseVerdict.CONFIRMED_UNVERIFIED || v == DtcEraseVerdict.UNCONFIRMED_UNVERIFIED }
}.map { it.addr }.toSet()

/** `59 02`: a positive list answer, the only one an empty re-read may be trusted from. */
private const val POSITIVE_LIST_HEAD = 0x59
private const val LIST_HEADER_BYTES = 3
private const val LIST_RECORD_BYTES = 4

/**
 * The visible rows of the re-read; null when it was not made or no positive answer came back.
 * A positive count of 0 is an empty re-read: the daemon asks no list then.
 */
fun rereadRows(r: DtcEraseResult): List<DtcRow>? {
    val reread = r.reread ?: return null
    if (reread.outcome != DtcWire.OUTCOME_OK) return null
    val count = reread.countUds?.let { DtcParser.parseCount(it) }
    val list = reread.listUds ?: return if (count?.count == 0) emptyList() else null
    // `59 02 <availability mask>` then whole 4-byte records; a cut list is not a read.
    if (list.size < LIST_HEADER_BYTES || (list.size - LIST_HEADER_BYTES) % LIST_RECORD_BYTES != 0) return null
    if (list[0].toInt() and 0xFF != POSITIVE_LIST_HEAD || list[1].toInt() != DtcWire.SUB_LIST) return null
    return DtcParser.parseList(list, count?.format).rows
}

/**
 * The saved scan after an erase: each block whose re-read was read ([rereadRows] not null) holds
 * that re-read, under the scan's block id; every other entry stays as scanned.
 */
fun dtcScanAfterErase(results: List<DtcAddressResult>, erase: List<DtcEraseResult>): List<DtcAddressResult> {
    val reread = erase.mapNotNull { e -> e.reread?.takeIf { rereadRows(e) != null }?.let { e.addr to it } }.toMap()
    return results.map { r ->
        val after = reread[r.addr] ?: return@map r
        DtcAddressResult(r.addr, after.outcome, after.countUds, after.listUds, r.idUds)
    }
}
