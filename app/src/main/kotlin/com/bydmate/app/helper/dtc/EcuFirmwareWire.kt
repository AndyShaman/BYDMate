package com.bydmate.app.helper.dtc

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

    /**
     * A `62 F1 95 …` answer → firmware; [EcuFirmware.raw] = the data after the DID. A 6-byte BYD
     * layout gives version and date (an impossible month/day → date null, version kept); any other
     * length → version = the printable ASCII trimmed, else hex; date null. NRC or anything else → null.
     */
    fun parse(uds: ByteArray?): EcuFirmware? = TODO()

    /** The firmware job's allowlist: only `22 F1 94` and `22 F1 95` to an address of [DtcWire.ADDRESSES]. */
    fun allowed(addr: Int, uds: ByteArray): Boolean = TODO()
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
