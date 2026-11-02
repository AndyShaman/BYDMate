package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcFixtures
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
}
