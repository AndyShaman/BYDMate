package com.bydmate.app.helper.dtc

import android.os.Parcel

/**
 * Wire types and pure helpers of the manual ECU fault-code scan, shared by the daemon (which
 * owns the UDS exchange) and the app (which parses the raw answers). The daemon builds only the
 * fixed read requests below; nothing else is ever sent.
 */

/** What [DtcWire.match] made of one received frame for the request being waited on. */
sealed interface DtcMatch {
    /** Another block's frame, a late duplicate of an earlier answer, or too short to read. */
    data object NotMine : DtcMatch
    /** The positive answer; [uds] is exactly `len` bytes with the frame header stripped. */
    class Answer(val uds: ByteArray) : DtcMatch
    class Nrc(val code: Int) : DtcMatch
    /** NRC 0x78: the block is still working, keep waiting in the same window. */
    data object Pending : DtcMatch
    /** The gateway timed out (result byte 3, the request echoed back). */
    data object GatewayTimeout : DtcMatch
    /** The frame is shorter than its own length byte says. */
    data object Truncated : DtcMatch
    /** More than one frame; kept raw, never assembled. */
    class Multiframe(val raw: ByteArray) : DtcMatch
}

/** One address of a finished scan, raw UDS bytes only (null = that request was not sent or not answered). */
class DtcAddressResult(
    val addr: Int,
    val outcome: String,
    val countUds: ByteArray?,
    val listUds: ByteArray?,
    val idUds: ByteArray?,
)

/** Full TX_DTC_SCAN_STATUS reply. */
class DtcScanStatus(
    val state: Int,
    val done: Int,
    val total: Int,
    val reason: String,
    val results: List<DtcAddressResult>,
)

object DtcWire {
    const val STATE_IDLE = 0
    const val STATE_RUNNING = 1
    const val STATE_DONE = 2
    const val STATE_UNAVAILABLE = 3
    const val STATE_ABORTED = 4

    const val OUTCOME_OK = "ok"
    const val OUTCOME_NO_ANSWER = "no_answer"
    const val OUTCOME_GW_TIMEOUT = "gw_timeout"
    const val OUTCOME_NRC = "nrc"
    const val OUTCOME_MULTIFRAME = "multiframe"
    const val OUTCOME_TRUNCATED = "truncated"
    const val OUTCOME_SKIPPED = "skipped"

    /** Value of `sys.diagmgrsrv.task_bk` while the diagnostic manager is idle. */
    const val GUARD_BASELINE = "r@-1_0&w@-1_0"

    /** The only addresses ever read (the Leopard 3 blocks). */
    val ADDRESSES: IntArray = intArrayOf(
        0x712, 0x720, 0x722, 0x726, 0x747, 0x762, 0x782, 0x783, 0x7E0,
        0x7E1, 0x7E2, 0x7E3, 0x7E4, 0x7E5, 0x7E6, 0x7E7, 0x7F1, 0x7F2,
    )

    /** Services of the only requests ever built: read DTC information, read data by identifier. */
    const val SERVICE_READ_DTC = 0x19
    const val SERVICE_READ_DID = 0x22
    const val SUB_COUNT = 0x01
    const val SUB_LIST = 0x02
    const val DID_IDENTITY = 0xF194
    /** The block's own system name, ASCII (L3: "URE", "IPB", "SEC30-P50"); logged only. */
    const val DID_NAME = 0xF197

    /** List masks: pending|confirmed|failed, confirmed|failed, everything (the validated fallback). */
    val LIST_MASKS: IntArray = intArrayOf(0x0D, 0x09, 0xFF)

    /** `0A rid(2) len frames idx result`, then the UDS bytes. */
    private const val HEADER = 7
    /** `62` plus the 16-bit DID echo ahead of the data of a DID answer. */
    private const val DID_HEAD = 3
    private const val FRAME_PREFIX = 0x0A
    private const val REQUEST_LEN = 0x03
    private const val REPLY_ID_OFFSET = 8
    private const val POSITIVE_OFFSET = 0x40
    private const val NEGATIVE = 0x7F
    private const val NRC_PENDING = 0x78
    private const val RESULT_GATEWAY_TIMEOUT = 3

    /** `19 01 FF`: count and availability mask. */
    fun countRequest(addr: Int): ByteArray = request(addr, SERVICE_READ_DTC, SUB_COUNT, 0xFF)

    /** `19 02 <mask>`; [mask] is one of 0x0D, 0x09, 0xFF, anything else throws. */
    fun listRequest(addr: Int, mask: Int): ByteArray {
        require(mask in LIST_MASKS) { "list mask not allowed" }
        return request(addr, SERVICE_READ_DTC, SUB_LIST, mask)
    }

    /** `22 F1 94`: the block's identity. */
    fun identityRequest(addr: Int): ByteArray =
        request(addr, SERVICE_READ_DID, DID_IDENTITY shr 8, DID_IDENTITY and 0xFF)

    /** `22 F1 97`: the block's own name. */
    fun nameRequest(addr: Int): ByteArray =
        request(addr, SERVICE_READ_DID, DID_NAME shr 8, DID_NAME and 0xFF)

    private fun request(addr: Int, service: Int, b1: Int, b2: Int): ByteArray {
        require(addr in ADDRESSES) { "address not in read whitelist" }
        return byteArrayOf(
            FRAME_PREFIX.toByte(), (addr shr 8).toByte(), addr.toByte(), REQUEST_LEN.toByte(),
            service.toByte(), b1.toByte(), b2.toByte(),
        )
    }

    /**
     * Matches [raw] (`0A rid(2) len frames idx result uds…tail`) against the request whose reply
     * is awaited: reply id = [addr] + 8, positive service = [service] + 0x40 with its [sub] echo
     * (one byte for 0x19, the 16-bit DID for 0x22).
     */
    fun match(raw: ByteArray, addr: Int, service: Int, sub: Int): DtcMatch {
        if (raw.size < HEADER) return DtcMatch.NotMine
        if (replyId(raw) != addr + REPLY_ID_OFFSET) return DtcMatch.NotMine
        if (u(raw, 4) > 1) return DtcMatch.Multiframe(raw.copyOf())
        val len = u(raw, 3)
        if (raw.size < HEADER + len) return DtcMatch.Truncated
        val uds = raw.copyOfRange(HEADER, HEADER + len)
        if (u(raw, 6) == RESULT_GATEWAY_TIMEOUT && echoes(uds, service, service, sub)) return DtcMatch.GatewayTimeout
        if (uds.size >= 3 && u(uds, 0) == NEGATIVE && u(uds, 1) == service) {
            return if (u(uds, 2) == NRC_PENDING) DtcMatch.Pending else DtcMatch.Nrc(u(uds, 2))
        }
        if (echoes(uds, service + POSITIVE_OFFSET, service, sub)) return DtcMatch.Answer(uds)
        return DtcMatch.NotMine
    }

    /** The F197 log text: `f197=URE` for a `62 F1 97` answer (ASCII, trimmed), `nrc=0x31`, or `none`. */
    fun nameText(m: DtcMatch?): String = when (m) {
        is DtcMatch.Answer -> "f197=" + String(m.uds, DID_HEAD, m.uds.size - DID_HEAD, Charsets.US_ASCII)
            .trim { it.isWhitespace() || it == '\u0000' }
        is DtcMatch.Nrc -> "nrc=0x${"%02X".format(m.code)}"
        else -> "none"
    }

    /** `rid` of a frame of at least the header's size. */
    fun replyId(raw: ByteArray): Int = (u(raw, 1) shl 8) or u(raw, 2)

    /** [head] first, then the sub-function byte (0x19) or the DID (0x22). */
    private fun echoes(uds: ByteArray, head: Int, service: Int, sub: Int): Boolean =
        if (service == SERVICE_READ_DID) {
            uds.size >= 3 && u(uds, 0) == head && ((u(uds, 1) shl 8) or u(uds, 2)) == sub
        } else {
            uds.size >= 2 && u(uds, 0) == head && u(uds, 1) == sub
        }

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
}

/** Upper bound of one UDS answer the parcel carries; a frame is at most 1024 bytes. */
private const val MAX_UDS_BYTES = 1024
private const val MAX_TEXT = 256
private const val INT_BYTES = 4

/** TX_DTC_SCAN_STATUS reply: see HelperBinderProtocol.TX_DTC_SCAN_STATUS. */
fun writeDtcScanStatus(p: Parcel, status: DtcScanStatus) {
    p.writeInt(status.state)
    p.writeInt(status.done)
    p.writeInt(status.total)
    p.writeString(status.reason.take(MAX_TEXT))
    p.writeInt(status.results.size)
    status.results.forEach {
        p.writeInt(it.addr)
        p.writeString(it.outcome)
        p.writeByteArray(it.countUds)
        p.writeByteArray(it.listUds)
        p.writeByteArray(it.idUds)
    }
}

/** Null on a truncated, oversized or unknown-state reply (the daemon writes -1 when it failed). */
fun readDtcScanStatus(p: Parcel): DtcScanStatus? {
    if (p.dataAvail() < 3 * INT_BYTES) return null
    val state = p.readInt()
    if (state !in DtcWire.STATE_IDLE..DtcWire.STATE_ABORTED) return null
    val done = p.readInt()
    val total = p.readInt()
    val reason = p.readString()?.takeIf { it.length <= MAX_TEXT } ?: return null
    if (p.dataAvail() < INT_BYTES) return null
    val count = p.readInt()
    if (count !in 0..DtcWire.ADDRESSES.size) return null
    val results = ArrayList<DtcAddressResult>(count)
    repeat(count) {
        if (p.dataAvail() < INT_BYTES) return null
        val addr = p.readInt()
        val outcome = p.readString()?.takeIf { it.length <= MAX_TEXT } ?: return null
        val countUds = p.createByteArray()
        val listUds = p.createByteArray()
        val idUds = p.createByteArray()
        if (listOf(countUds, listUds, idUds).any { (it?.size ?: 0) > MAX_UDS_BYTES }) return null
        results += DtcAddressResult(addr, outcome, countUds, listUds, idUds)
    }
    return DtcScanStatus(state, done, total, reason, results)
}
