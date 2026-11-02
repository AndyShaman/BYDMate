package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcAddressResult
import com.bydmate.app.helper.dtc.DtcEraseResult
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import com.bydmate.app.helper.dtc.DtcWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Andy's L3, 2026-11-02 16:26 (bydmate_logs_20261102_162406): both URE blocks confirmed the clear and
 * re-read with zero codes (`count=5901FF000000 list=null`, verdict ERASED, reread=[]), yet the window kept
 * listing U1711-87 and U10FF-CE under «Старые». After an erase the saved scan holds what the re-read read.
 */
class DtcScanAfterEraseTest {

    private val ureId = hex("62F1944B30001202FD000102")
    private val adasId = hex("62F194AA")

    private fun scanned(addr: Int, count: String, list: String, id: ByteArray?) =
        DtcAddressResult(addr, DtcWire.OUTCOME_OK, hex(count), hex(list), id)

    private val scan = listOf(
        scanned(0x712, "5901FF010001", "5902FF D16687 08", adasId),
        scanned(0x7E0, "5901FF010002", "5902FF D71187 08 D0FFCE 08", ureId),
        scanned(0x7E2, "5901FF010002", "5902FF D71187 08 D0FFCE 08", ureId),
    )

    // Verbatim re-read answer from the log: zero count, no list request sent.
    private fun zeroCount(addr: Int) = DtcAddressResult(addr, DtcWire.OUTCOME_OK, hex("5901FF000000"), null, null)

    private fun erased(addr: Int, reread: DtcAddressResult?) =
        DtcEraseResult(addr, DtcWire.CONFIRM_CONFIRMED, null, "", reread)

    @Test fun `blocks re-read empty after the erase lose their rows and keep their name`() {
        val after = dtcScanAfterErase(scan, listOf(erased(0x7E0, zeroCount(0x7E0)), erased(0x7E2, zeroCount(0x7E2))))
        val summary = DtcParser.summarize(after)
        assertEquals(listOf(0x712 to "U1166-87"), summary.found.map { it.addr to it.row.code })
        assertEquals(scan.map { it.addr }, after.map { it.addr })
        assertArrayEquals(ureId, after[1].idUds)
        assertArrayEquals(ureId, after[2].idUds)
    }

    @Test fun `a block that was not re-read keeps its scan entry`() {
        assertEquals(scan, dtcScanAfterErase(scan, listOf(erased(0x712, null))))
        val notOk = DtcAddressResult(0x712, DtcWire.OUTCOME_NO_ANSWER, null, null, null)
        assertEquals(scan, dtcScanAfterErase(scan, listOf(erased(0x712, notOk))))
    }

    @Test fun `a code that came back is shown as the re-read read it, under the same block`() {
        val back = scanned(0x712, "5901FF010001", "5902FF D16687 09", null)
        val after = dtcScanAfterErase(scan, listOf(erased(0x712, back)))
        val row = DtcParser.summarize(after).found.first()
        assertEquals(0x712, row.addr)
        assertEquals("U1166-87", row.code())
        assertEquals(DtcKind.NOW, row.row.kind)
        assertEquals(DtcParser.summarize(scan).found.first().block, row.block)
        assertArrayEquals(adasId, after[0].idUds)
    }

    @Test fun `no erase leaves the scan as it is`() {
        assertEquals(scan, dtcScanAfterErase(scan, emptyList()))
    }

    private fun DtcFound.code() = row.code
}
