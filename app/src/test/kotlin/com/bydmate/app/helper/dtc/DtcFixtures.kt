package com.bydmate.app.helper.dtc

import com.bydmate.app.data.dtc.DtcBlock
import com.bydmate.app.data.dtc.DtcBlockName
import com.bydmate.app.data.dtc.DtcLabels

/**
 * Real Leopard 3 answers recorded on the car (resources `dtc-l3/`), read WITHOUT the code under
 * test: the frame layout is split by hand here so a wire bug cannot hide a parser bug.
 */
object DtcFixtures {
    fun hex(text: String): ByteArray {
        val clean = text.replace(" ", "")
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    private fun lines(path: String): List<String>? =
        DtcFixtures::class.java.getResourceAsStream("/dtc-l3/$path")?.bufferedReader()?.use { it.readLines() }

    /** The first `raw=` frame of a recorded run; the second one in the file is the duplicate. */
    fun rawOrNull(path: String): ByteArray? =
        lines(path)?.firstOrNull { "raw=" in it }?.substringAfter("raw=")?.trim()?.let { hex(it) }

    fun raw(path: String): ByteArray = requireNotNull(rawOrNull(path)) { "no fixture $path" }

    /** The UDS bytes of a frame: `len` bytes after the 7-byte header, the tail is garbage. */
    fun udsOf(raw: ByteArray): ByteArray = raw.copyOfRange(7, 7 + (raw[3].toInt() and 0xFF))

    fun uds(path: String): ByteArray = udsOf(raw(path))

    fun hexAddr(addr: Int): String = "%X".format(addr)

    /** The F194 answer recorded for [addr], null when that block did not answer. */
    fun idUds(addr: Int): ByteArray? =
        lines("id-runs/id-${hexAddr(addr)}.txt")
            ?.firstOrNull { "uds=" in it }?.substringAfter("uds=")?.trim()?.let { hex(it) }

    /** A frame as the device delivers it: `0A rid(2) len frames idx result uds` plus a stale tail. */
    fun frame(addr: Int, uds: ByteArray, result: Int = 1): ByteArray {
        val rid = addr + 8
        val head = byteArrayOf(
            0x0A, (rid shr 8).toByte(), rid.toByte(), uds.size.toByte(), 1, 1, result.toByte(),
        )
        return head + uds + byteArrayOf(0x55, 0x55, 0x55, 0x55, 0, 0, 0, 0)
    }

    /** What the daemon would hand over for [addr] from the recorded count answer, or null. */
    private fun result(addr: Int): DtcAddressResult {
        val h = hexAddr(addr)
        val count = rawOrNull("count-$h.txt")
            ?: return DtcAddressResult(addr, DtcWire.OUTCOME_NO_ANSWER, null, null, null)
        val countUds = udsOf(count)
        val outcome = when {
            count[6].toInt() == 3 -> DtcWire.OUTCOME_GW_TIMEOUT
            countUds[0] == 0x7F.toByte() -> DtcWire.OUTCOME_NRC
            else -> DtcWire.OUTCOME_OK
        }
        val listUds = rawOrNull("list-$h.txt")?.let { udsOf(it) }
        return DtcAddressResult(addr, outcome, countUds, listUds, idUds(addr))
    }

    /** The whole L3 scan from before the codes were cleared: 14 of 18 answered, 1 now, 14 old. */
    fun preClearScan(): List<DtcAddressResult> = DtcWire.ADDRESSES.map { result(it) }

    /** Plain Russian labels, as the app would resolve them. */
    val labels = object : DtcLabels {
        override val title = "BYDMate · ошибки в блоках"
        override fun checked(at: String, answered: Int, total: Int) =
            "Проверено $at, ответили $answered блоков из $total"
        override val nowHeader = "Есть сейчас:"
        override val oldHeader = "Старые:"
        override val pendingMark = "ожидающая"
        override val noErrors = "Ошибок в блоках не найдено"
        override val footer = "Сканирование ничего не меняет в машине. Покажите коды сервису."
        override fun blockName(name: DtcBlockName) = when (name.block) {
            DtcBlock.ADAS -> "ассистенты (ADAS)"
            DtcBlock.URE -> "URE"
            DtcBlock.BODY -> "кузовной блок 0x%X".format(name.addr)
            else -> "блок 0x%X".format(name.addr)
        }
    }
}
