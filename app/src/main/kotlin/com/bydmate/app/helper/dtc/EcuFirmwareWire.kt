package com.bydmate.app.helper.dtc

import android.os.Parcel
import java.time.LocalDate

/**
 * Firmware of a block from `22 F1 95`: on BYD blocks 6 data bytes, u16 N then YY MM DD binary
 * (not BCD); version = N/10000 . (N/100)%100 (2 digits) . N%100 (2 digits), e.g. 9C 4C 1A 09 0A 01 →
 * 4.00.12, 2026-09-10. Design `.research/tpms-l3-2026-11-16/design-tpms-fw-2026-11-20.md` D.7.
 */
data class EcuFirmware(val version: String?, val date: String?, val raw: ByteArray) {
    override fun equals(other: Any?) =
        other is EcuFirmware && version == other.version && date == other.date && raw.contentEquals(other.raw)
    override fun hashCode() = raw.contentHashCode()
}

object EcuFirmwareWire {
    const val DID_SOFTWARE = 0xF195

    private const val POSITIVE_READ = 0x62
    private const val DID_HEAD = 3
    private const val BYD_BYTES = 6
    private const val CENTURY = 2000
    private const val MAJOR = 10_000
    private const val MINOR = 100
    private const val PRINTABLE_FIRST = 0x20
    private const val PRINTABLE_LAST = 0x7E
    private const val FRAME_PREFIX = 0x0A
    private const val REQUEST_LEN = 0x03

    /**
     * A `62 F1 95 …` answer → firmware; [EcuFirmware.raw] = the data after the DID. A 6-byte BYD
     * layout gives version and date (an impossible month/day → date null, version kept); any other
     * length → version = the printable ASCII trimmed, else hex; date null. NRC or anything else → null.
     */
    fun parse(uds: ByteArray?): EcuFirmware? {
        if (uds == null || uds.size < DID_HEAD || u(uds, 0) != POSITIVE_READ) return null
        if (((u(uds, 1) shl 8) or u(uds, 2)) != DID_SOFTWARE) return null
        val raw = uds.copyOfRange(DID_HEAD, uds.size)
        // Six printable bytes are a text version (other models); a BYD year byte (0x1A.. for 2026) never is.
        if (raw.size == BYD_BYTES && !printable(raw)) {
            val n = (u(raw, 0) shl 8) or u(raw, 1)
            val version = "${n / MAJOR}.%02d.%02d".format((n / MINOR) % MINOR, n % MINOR)
            val date = runCatching { LocalDate.of(CENTURY + u(raw, 2), u(raw, 3), u(raw, 4)).toString() }.getOrNull()
            return EcuFirmware(version, date, raw)
        }
        val padding = { b: Byte -> (b.toInt() and 0xFF).let { it == 0 || it.toChar().isWhitespace() } }
        val text = raw.dropWhile(padding).dropLastWhile(padding).toByteArray()
        val version = when {
            raw.isEmpty() -> null
            text.isNotEmpty() && printable(text) -> String(text, Charsets.US_ASCII)
            else -> raw.joinToString("") { "%02X".format(it) }
        }
        return EcuFirmware(version, null, raw)
    }

    /** The firmware job's allowlist: only `22 F1 94` and `22 F1 95` to an address of [DtcWire.ADDRESSES]. */
    fun allowed(addr: Int, uds: ByteArray): Boolean =
        addr in DtcWire.ADDRESSES && uds.size == REQUEST_LEN && u(uds, 0) == DtcWire.SERVICE_READ_DID &&
            ((u(uds, 1) shl 8) or u(uds, 2)).let { it == DtcWire.DID_IDENTITY || it == DID_SOFTWARE }

    /** `22 F1 95` to [addr] of [DtcWire.ADDRESSES]: the block's software version. */
    fun softwareRequest(addr: Int): ByteArray {
        require(addr in DtcWire.ADDRESSES) { "address not in read whitelist" }
        return byteArrayOf(
            FRAME_PREFIX.toByte(), (addr shr 8).toByte(), addr.toByte(), REQUEST_LEN.toByte(),
            DtcWire.SERVICE_READ_DID.toByte(), (DID_SOFTWARE shr 8).toByte(), DID_SOFTWARE.toByte(),
        )
    }

    private fun printable(b: ByteArray): Boolean = b.all { (it.toInt() and 0xFF) in PRINTABLE_FIRST..PRINTABLE_LAST }

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
}

/** One address of a firmware check: [outcome] one of DtcWire.OUTCOME_* strings; raw answers as received. */
class FirmwareAddressResult(val addr: Int, val outcome: String, val f194Uds: ByteArray?, val f195Uds: ByteArray?)

class FirmwareStatus(
    val state: Int,
    val done: Int,
    val total: Int,
    val reason: String,
    val results: List<FirmwareAddressResult>,
)

/** Upper bound of one UDS answer the parcel carries; a frame is at most 1024 bytes. */
private const val MAX_UDS_BYTES = 1024
private const val MAX_TEXT = 256
private const val INT_BYTES = 4

/**
 * The firmware part of TX_DIAG_JOB_STATUS, after the tyre part: see
 * HelperBinderProtocol.TX_DIAG_JOB_STATUS.
 */
fun writeFirmwareStatus(p: Parcel, status: FirmwareStatus) {
    p.writeInt(status.state)
    p.writeInt(status.done)
    p.writeInt(status.total)
    p.writeString(status.reason.take(MAX_TEXT))
    p.writeInt(status.results.size)
    status.results.forEach {
        p.writeInt(it.addr)
        p.writeString(it.outcome)
        p.writeByteArray(it.f194Uds)
        p.writeByteArray(it.f195Uds)
    }
}

/**
 * The whole TX_DIAG_JOB_STATUS reply read for the firmware part: the tyre part is read and
 * dropped. Null on a truncated, oversized or unknown-state reply, or a daemon without the part.
 */
fun readFirmwareStatus(p: Parcel): FirmwareStatus? {
    readTpmsStatus(p) ?: return null
    if (p.dataAvail() < 3 * INT_BYTES) return null
    val state = p.readInt()
    if (state !in DtcWire.STATE_IDLE..DtcWire.STATE_ABORTED) return null
    val done = p.readInt()
    val total = p.readInt()
    val reason = p.readString()?.takeIf { it.length <= MAX_TEXT } ?: return null
    if (p.dataAvail() < INT_BYTES) return null
    val count = p.readInt()
    if (count !in 0..DtcWire.ADDRESSES.size) return null
    val results = ArrayList<FirmwareAddressResult>(count)
    repeat(count) {
        if (p.dataAvail() < INT_BYTES) return null
        val addr = p.readInt()
        val outcome = p.readString()?.takeIf { it.length <= MAX_TEXT } ?: return null
        val f194 = p.createByteArray()
        val f195 = p.createByteArray()
        if ((f194?.size ?: 0) > MAX_UDS_BYTES || (f195?.size ?: 0) > MAX_UDS_BYTES) return null
        results += FirmwareAddressResult(addr, outcome, f194, f195)
    }
    return FirmwareStatus(state, done, total, reason, results)
}
