package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcAddressResult
import com.bydmate.app.helper.dtc.DtcEraseResult
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import com.bydmate.app.helper.dtc.DtcWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** The daemon asks no list when the re-read count answers a positive 0. */
    private val zeroCount = DtcAddressResult(0x712, DtcWire.OUTCOME_OK, count, null, null)

    @Test fun `a zero-count re-read without a list is an empty re-read`() {
        assertEquals(DtcEraseVerdict.ERASED, verdict(DtcWire.CONFIRM_CONFIRMED, zeroCount))
        assertEquals(DtcEraseVerdict.LIKELY_ERASED, verdict(DtcWire.CONFIRM_UNCONFIRMED, zeroCount))
    }

    @Test fun `a zero-count block in a scan still means no codes`() {
        assertTrue(DtcParser.summarize(listOf(zeroCount)).found.isEmpty())
    }

    private fun eraseResult(addr: Int, confirm: String, reread: DtcAddressResult? = null) =
        DtcEraseResult(addr, confirm, null, "", reread)

    @Test fun `E1 the locks of the eight results in the Song Plus log are the three unverified blocks`() {
        val results = listOf(
            eraseResult(0x712, DtcWire.CONFIRM_CONFIRMED),
            eraseResult(0x720, DtcWire.CONFIRM_CONFIRMED),
            eraseResult(0x726, DtcWire.CONFIRM_UNCONFIRMED),
            eraseResult(0x782, DtcWire.CONFIRM_NOT_RUN),
            eraseResult(0x783, DtcWire.CONFIRM_NOT_RUN),
            eraseResult(0x7E0, DtcWire.CONFIRM_NOT_RUN),
            eraseResult(0x7E2, DtcWire.CONFIRM_NOT_RUN),
            eraseResult(0x7F2, DtcWire.CONFIRM_NOT_RUN),
        )
        assertEquals(setOf(0x712, 0x720, 0x726), dtcEraseLocks(results))
    }

    @Test fun `E2 every other verdict leaves no lock`() {
        val results = listOf(
            eraseResult(0x712, DtcWire.CONFIRM_CONFIRMED, empty),
            eraseResult(0x720, DtcWire.CONFIRM_CONFIRMED, nowRow),
            eraseResult(0x726, DtcWire.CONFIRM_UNCONFIRMED, empty),
            eraseResult(0x782, DtcWire.CONFIRM_UNCONFIRMED, oldRow),
            eraseResult(0x783, DtcWire.CONFIRM_REFUSED),
            eraseResult(0x7E0, DtcWire.CONFIRM_NOT_RUN),
        )
        assertTrue(dtcEraseLocks(results).isEmpty())
    }
}
