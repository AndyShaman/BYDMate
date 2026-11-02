package com.bydmate.app.ui.tech

import com.bydmate.app.data.dtc.DtcParser
import com.bydmate.app.helper.dtc.DtcAddressResult
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import com.bydmate.app.helper.dtc.DtcWire
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TechPanelEraseLockTest {

    private fun rows(addr: Int, list: String) =
        DtcAddressResult(addr, DtcWire.OUTCOME_OK, hex("5901FF010001"), hex(list), null)

    private val summary = DtcParser.summarize(
        listOf(rows(0x712, "5902FF D16687 08"), rows(0x726, "5902FF D16787 08")),
    )

    @Test fun `E9 a locked block cannot be erased and the erase buttons show while another block can`() {
        val one = TechPanelUiState(dtcSummary = summary, dtcEraseLocked = setOf(0x726))
        assertFalse(one.canEraseBlock(0x726))
        assertTrue(one.canEraseBlock(0x712))
        assertTrue(one.showErase)

        val both = TechPanelUiState(dtcSummary = summary, dtcEraseLocked = setOf(0x712, 0x726))
        assertFalse(both.showErase)
    }
}
