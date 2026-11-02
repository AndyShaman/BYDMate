package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcAddressResult
import com.bydmate.app.helper.dtc.DtcEraseResult
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import com.bydmate.app.helper.dtc.DtcWire
import org.junit.Assert.assertEquals
import org.junit.Test

class DtcEraseVerdictTest {

    private val count = hex("5901FF010000")

    /** A re-read of 0x712 with the given list bytes. */
    private fun reread(list: String) = DtcAddressResult(0x712, DtcWire.OUTCOME_OK, count, hex(list), null)

    private val empty = reread("5902FF")
    private val nowRow = reread("5902FF D16687 09")
    private val oldRow = reread("5902FF C16487 08")

    private fun verdict(confirm: String, reread: DtcAddressResult?) =
        dtcEraseVerdict(DtcEraseResult(0x712, confirm, null, "", reread))

    @Test fun `confirmed with an empty re-read is erased`() {
        assertEquals(DtcEraseVerdict.ERASED, verdict(DtcWire.CONFIRM_CONFIRMED, empty))
    }

    @Test fun `confirmed with a now row back is erased but back`() {
        assertEquals(DtcEraseVerdict.ERASED_BUT_BACK, verdict(DtcWire.CONFIRM_CONFIRMED, nowRow))
    }

    @Test fun `unconfirmed with rows is unknown codes present, with none likely erased`() {
        assertEquals(DtcEraseVerdict.UNKNOWN_CODES_PRESENT, verdict(DtcWire.CONFIRM_UNCONFIRMED, oldRow))
        assertEquals(DtcEraseVerdict.LIKELY_ERASED, verdict(DtcWire.CONFIRM_UNCONFIRMED, empty))
    }

    @Test fun `without a re-read the verdict stays unverified`() {
        assertEquals(DtcEraseVerdict.CONFIRMED_UNVERIFIED, verdict(DtcWire.CONFIRM_CONFIRMED, null))
        assertEquals(DtcEraseVerdict.UNCONFIRMED_UNVERIFIED, verdict(DtcWire.CONFIRM_UNCONFIRMED, null))
    }

    @Test fun `refused and not run map to their own verdicts`() {
        assertEquals(DtcEraseVerdict.REFUSED, verdict(DtcWire.CONFIRM_REFUSED, null))
        assertEquals(DtcEraseVerdict.NOT_RUN, verdict(DtcWire.CONFIRM_NOT_RUN, null))
    }
}
