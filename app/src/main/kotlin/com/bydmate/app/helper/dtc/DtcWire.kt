@file:Suppress("UnusedParameter", "UnusedPrivateProperty") // compile stub, remove with the implementation

package com.bydmate.app.helper.dtc

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

    /** `19 01 FF`: count and availability mask. */
    fun countRequest(addr: Int): ByteArray = TODO()

    /** `19 02 <mask>`; [mask] is one of 0x0D, 0x09, 0xFF, anything else throws. */
    fun listRequest(addr: Int, mask: Int): ByteArray = TODO()

    /** `22 F1 94`: the block's identity. */
    fun identityRequest(addr: Int): ByteArray = TODO()

    /**
     * Matches [raw] (`0A rid(2) len frames idx result uds…tail`) against the request whose reply
     * is awaited: reply id = [addr] + 8, positive service = [service] + 0x40 with its [sub] echo
     * (one byte for 0x19, the 16-bit DID for 0x22).
     */
    fun match(raw: ByteArray, addr: Int, service: Int, sub: Int): DtcMatch = TODO()
}
