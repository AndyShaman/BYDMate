package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcAddressResult
import com.bydmate.app.helper.dtc.DtcFixtures
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import com.bydmate.app.helper.dtc.DtcWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcErasePlanTest {

    private val now = 10_000_000L
    private fun scan(ageMs: Long) = DtcSavedScan(now - ageMs, 18, DtcFixtures.preClearScan())

    @Test fun `a firmware hint only for the Leopard 3 autotypes`() {
        assertTrue(dtcFirmwareHint(260))
        for (t in intArrayOf(164, 259, 261, 262, 357)) assertTrue("$t", dtcFirmwareHint(t))
        assertTrue(!dtcFirmwareHint(173))
        assertTrue(!dtcFirmwareHint(null))
    }

    @Test fun `no scan or a scan older than ten minutes gives no plan`() {
        assertNull(DtcErasePlan.eligible(null, now))
        assertNull(DtcErasePlan.eligible(scan(11 * 60_000L), now))
        assertNull(DtcErasePlan.eligibleBlock(null, 0x7E0, now))
        assertNull(DtcErasePlan.eligibleBlock(scan(11 * 60_000L), 0x7E0, now))
    }

    @Test fun `a fresh scan gives the addresses with visible rows, ascending`() {
        val expected = DtcParser.summarize(DtcFixtures.preClearScan()).found.map { it.addr }.distinct().sorted()
        assertArrayEquals(expected.toIntArray(), DtcErasePlan.eligible(scan(9 * 60_000L), now))
    }

    @Test fun `a block of a merged group returns all its addresses, a block without rows none`() {
        assertArrayEquals(intArrayOf(0x7E0, 0x7E2), DtcErasePlan.eligibleBlock(scan(9 * 60_000L), 0x7E2, now))
        assertArrayEquals(intArrayOf(), DtcErasePlan.eligibleBlock(scan(9 * 60_000L), 0x720, now))
    }

    private fun rows(addr: Int, list: String, id: String? = null) =
        DtcAddressResult(addr, DtcWire.OUTCOME_OK, hex("5901FF010001"), hex(list), id?.let { hex(it) })

    /** The URE identity both 0x7E0 and 0x7E2 answer on the Leopard 3 (log of 2026-11-02). */
    private val ureId = "62F1944B30001202FD000102"

    private fun lockedScan(locked: Set<Int>) = DtcSavedScan(
        now - 60_000L, 3,
        listOf(
            rows(0x712, "5902FF D16687 08"),
            rows(0x726, "5902FF D16787 08"),
            rows(0x782, "5902FF D16887 08"),
        ),
        locked,
    )

    @Test fun `E3 a locked block is left out of the plan and of its own block plan`() {
        val saved = lockedScan(setOf(0x726))
        assertArrayEquals(intArrayOf(0x712, 0x782), DtcErasePlan.eligible(saved, now))
        assertArrayEquals(intArrayOf(), DtcErasePlan.eligibleBlock(saved, 0x726, now))
        assertArrayEquals(intArrayOf(0x782), DtcErasePlan.eligibleBlock(saved, 0x782, now))
    }

    @Test fun `E4 a merged group is locked when one of its addresses is locked`() {
        val ure = "5902FF D71187 08 D0FFCE 08"
        val saved = DtcSavedScan(
            now - 60_000L, 2,
            listOf(rows(0x7E0, ure, ureId), rows(0x7E2, ure, ureId)),
            setOf(0x7E2),
        )
        assertArrayEquals(intArrayOf(), DtcErasePlan.eligible(saved, now))
        assertArrayEquals(intArrayOf(), DtcErasePlan.eligibleBlock(saved, 0x7E0, now))
    }
}
