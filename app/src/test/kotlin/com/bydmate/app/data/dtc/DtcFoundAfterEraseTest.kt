package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcAddressResult
import com.bydmate.app.helper.dtc.DtcEraseResult
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import com.bydmate.app.helper.dtc.DtcWire
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Andy's L3, 2026-11-02 16:26 (bydmate_logs_20261102_162406): both URE blocks confirmed the clear and
 * re-read with zero codes, the result lines said «Стёрто», yet the window kept listing U1711-87 and
 * U10FF-CE from the scan before the erase. After an erase the window shows what the re-read read.
 */
class DtcFoundAfterEraseTest {

    private val ure0 = DtcBlockName(DtcBlock.URE, 0x7E0)
    private val ure2 = DtcBlockName(DtcBlock.URE, 0x7E2)
    private val adas = DtcBlockName(DtcBlock.ADAS, 0x712)

    private val found = listOf(
        DtcFound(0x712, DtcRow("U1166-87", 0x08, DtcKind.OLD, 0x87), adas),
        DtcFound(0x7E0, DtcRow("U1711-87", 0x08, DtcKind.OLD, 0x87), ure0),
        DtcFound(0x7E0, DtcRow("U10FF-CE", 0x08, DtcKind.OLD, 0xCE), ure0),
        DtcFound(0x7E2, DtcRow("U1711-87", 0x08, DtcKind.OLD, 0x87), ure2),
        DtcFound(0x7E2, DtcRow("U10FF-CE", 0x08, DtcKind.OLD, 0xCE), ure2),
    )

    // Verbatim re-read answers from the log: zero count, no list request sent.
    private fun zeroCount(addr: Int) = DtcAddressResult(addr, DtcWire.OUTCOME_OK, hex("5901FF000000"), null, null)

    private fun erased(addr: Int, reread: DtcAddressResult?) =
        DtcEraseResult(addr, DtcWire.CONFIRM_CONFIRMED, null, "", reread)

    @Test fun `blocks re-read empty after the erase drop their old rows`() {
        val after = dtcFoundAfterErase(found, listOf(erased(0x7E0, zeroCount(0x7E0)), erased(0x7E2, zeroCount(0x7E2))))
        assertEquals(listOf(0x712), after.map { it.addr })
        assertEquals("U1166-87", after.single().row.code)
    }

    @Test fun `a block that could not be re-read keeps the rows of the scan`() {
        val after = dtcFoundAfterErase(found, listOf(erased(0x712, null)))
        assertEquals(found, after)
    }

    @Test fun `a code that came back is shown as the re-read read it, under the same block`() {
        val back = DtcAddressResult(0x712, DtcWire.OUTCOME_OK, hex("5901FF010001"), hex("5902FF D16687 09"), null)
        val after = dtcFoundAfterErase(found, listOf(erased(0x712, back)))
        val row = after.first()
        assertEquals(0x712, row.addr)
        assertEquals(adas, row.block)
        assertEquals("U1166-87", row.row.code)
        assertEquals(DtcKind.NOW, row.row.kind)
        // the untouched URE rows stay, after the current code
        assertEquals(listOf(0x712, 0x7E0, 0x7E0, 0x7E2, 0x7E2), after.map { it.addr })
    }

    @Test fun `no erase leaves the scan as it is`() {
        assertEquals(found, dtcFoundAfterErase(found, emptyList()))
    }
}
