package com.bydmate.app.ui.tech

import com.bydmate.app.data.dtc.DtcErasePhase
import com.bydmate.app.data.dtc.DtcEraseState
import com.bydmate.app.helper.dtc.DtcEraseResult
import com.bydmate.app.helper.dtc.DtcWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtcEraseTextTest {

    private fun confirmed(addr: Int) = DtcEraseResult(addr, DtcWire.CONFIRM_CONFIRMED, null, "", null)

    private fun state(reason: String, vararg results: DtcEraseResult) =
        DtcEraseState(DtcErasePhase.ENDED, results.size, results.size, results.toList(), reason)

    @Test fun `an aborted run gives its reason even when every block was confirmed`() {
        assertEquals("not_park", dtcEraseStopReasonKey(state("not_park", confirmed(0x712), confirmed(0x7E0))))
    }

    @Test fun `a foreign stop on the last block gives its reason`() {
        val last = DtcEraseResult(0x7E2, DtcWire.CONFIRM_UNCONFIRMED, null, "foreign", null)
        assertEquals("foreign", dtcEraseStopReasonKey(state("foreign", confirmed(0x712), last)))
    }

    @Test fun `a run that ended normally gives no reason`() {
        assertNull(dtcEraseStopReasonKey(state("", confirmed(0x712))))
    }

    @Test fun `a title keeps a block text that already holds the address`() {
        assertEquals("Кузовной блок 0x712", dtcEraseTitleName("Кузовной блок 0x712", listOf(0x712)))
    }

    @Test fun `a title appends the addresses when the block text lacks them`() {
        assertEquals(
            "Блок силовой установки URE (0x7E0, 0x7E2)",
            dtcEraseTitleName("Блок силовой установки URE", listOf(0x7E0, 0x7E2)),
        )
    }
}
