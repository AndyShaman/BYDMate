package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcAddressResult
import com.bydmate.app.helper.dtc.DtcEraseResult
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import com.bydmate.app.helper.dtc.DtcWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A re-read list that is cut short must not count as read: the saved scan would otherwise drop
 * codes nobody erased (a refused block whose list came back without the status byte).
 */
class DtcRereadIntegrityTest {

    private val oneCode = hex("5901FF010001")

    private fun refused(list: String) = DtcEraseResult(
        0x712, DtcWire.CONFIRM_REFUSED, 0x22, "",
        DtcAddressResult(0x712, DtcWire.OUTCOME_OK, oneCode, hex(list), null),
    )

    @Test fun `a list record without its status byte is not a read`() {
        assertNull(rereadRows(refused("5902FF D16687")))
    }

    @Test fun `a list without the availability byte is not a read`() {
        assertNull(rereadRows(refused("5902")))
    }

    @Test fun `a cut list leaves the scan entry as it was`() {
        val scan = listOf(DtcAddressResult(0x712, DtcWire.OUTCOME_OK, oneCode, hex("5902FF D16687 08"), null))
        assertEquals(scan, dtcScanAfterErase(scan, listOf(refused("5902FF D16687"))))
    }

    @Test fun `a whole list is still a read`() {
        assertEquals(listOf("U1166-87"), rereadRows(refused("5902FF D16687 08"))!!.map { it.code })
        assertEquals(emptyList<DtcRow>(), rereadRows(refused("5902FF")))
    }
}
