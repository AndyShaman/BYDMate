package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcFixtures
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuFirmwareDiffTest {

    private val f720 = hex("62F1944845001201F00056E0")
    private val f754 = hex("62F19448490011AD0C000004")
    private fun f195(data: String) = hex("62F195$data")

    private fun block(addr: Int, f194: ByteArray?, fw: String?) = FirmwareBlock(addr, f194, fw?.let { f195(it) })
    private fun check(at: Long, vararg blocks: FirmwareBlock, build: String = "X") = FirmwareCheck(at, build, blocks.toList())
    private fun row(rows: List<FirmwareRow>, addr: Int) = rows.first { it.addr == addr }

    // D.7 56

    @Test fun `56 - equal F195 bytes are UNCHANGED`() {
        val prev = check(1, block(0x720, f720, "9C4A1A040901"))
        val cur = check(2, block(0x720, f720, "9C4A1A040901"))
        val r = row(EcuFirmwareDiff.diff(prev, cur), 0x720)
        assertEquals(FirmwareChange.UNCHANGED, r.change)
        assertEquals("4.00.10", r.after!!.version)
    }

    @Test fun `56 - a reflashed block is UPDATED with the version and date before and after`() {
        val prev = check(1, block(0x720, f720, "9C4A1A040901"))
        val cur = check(2, block(0x720, f720, "9C4C1A090A01"))
        val r = row(EcuFirmwareDiff.diff(prev, cur), 0x720)
        assertEquals(FirmwareChange.UPDATED, r.change)
        assertEquals("4.00.10", r.before!!.version)
        assertEquals("2026-04-09", r.before!!.date)
        assertEquals("4.00.12", r.after!!.version)
        assertEquals("2026-09-10", r.after!!.date)
    }

    @Test fun `56 - the same version with another date is UPDATED`() {
        val prev = check(1, block(0x720, f720, "9C4A1A040901"))
        val cur = check(2, block(0x720, f720, "9C4A1A040A01"))
        assertEquals(FirmwareChange.UPDATED, row(EcuFirmwareDiff.diff(prev, cur), 0x720).change)
    }

    // D.7 57

    @Test fun `57 - an address missing from the previous check is FIRST, not updated`() {
        val prev = check(1, block(0x720, f720, "9C4A1A040901"))
        val cur = check(2, block(0x720, f720, "9C4A1A040901"), block(0x754, f754, "9C4118011401"))
        val rows = EcuFirmwareDiff.diff(prev, cur)
        assertEquals(FirmwareChange.FIRST, row(rows, 0x754).change)
        assertEquals(FirmwareChange.UNCHANGED, row(rows, 0x720).change)
    }

    @Test fun `57 - no previous check makes every row FIRST`() {
        val cur = check(2, block(0x720, f720, "9C4A1A040901"), block(0x754, f754, "9C4118011401"))
        val rows = EcuFirmwareDiff.diff(null, cur)
        assertEquals(listOf(0x720, 0x754), rows.map { it.addr })
        assertTrue(rows.all { it.change == FirmwareChange.FIRST })
    }

    @Test fun `57 - answered before and silent now is GONE`() {
        val prev = check(1, block(0x720, f720, "9C4A1A040901"))
        val cur = check(2, block(0x720, f720, null))
        val r = row(EcuFirmwareDiff.diff(prev, cur), 0x720)
        assertEquals(FirmwareChange.GONE, r.change)
        assertNull(r.after)
    }

    @Test fun `57 - silent now and before is SILENT`() {
        val prev = check(1, block(0x720, f720, null))
        val cur = check(2, block(0x720, f720, null))
        val r = row(EcuFirmwareDiff.diff(prev, cur), 0x720)
        assertEquals(FirmwareChange.SILENT, r.change)
        assertNull(r.before)
        assertNull(r.after)
    }

    // D.7 58

    @Test fun `58 - the history keeps 10 and the 11th check drops the oldest`() {
        var history = emptyList<FirmwareCheck>()
        for (i in 1..11) history = EcuFirmwareDiff.append(history, check(i.toLong(), block(0x720, f720, "9C4A1A040901")))
        assertEquals(EcuFirmwareDiff.HISTORY, history.size)
        assertEquals((2L..11L).toList(), history.map { it.at })
    }

    @Test fun `58 - the diff is the newest check against the one before it`() {
        var history = emptyList<FirmwareCheck>()
        history = EcuFirmwareDiff.append(history, check(1, block(0x720, f720, "9C4A1A040901")))
        history = EcuFirmwareDiff.append(history, check(2, block(0x720, f720, "9C4C1A090A01")))
        history = EcuFirmwareDiff.append(history, check(3, block(0x720, f720, "9C4C1A090A01")))
        val rows = EcuFirmwareDiff.diff(history[history.size - 2], history.last())
        assertEquals(FirmwareChange.UNCHANGED, row(rows, 0x720).change)
    }

    @Test fun `the history survives its own JSON, and bad JSON reads as empty`() {
        val history = listOf(
            check(5, block(0x720, f720, "9C4C1A090A01"), block(0x754, null, null), build = "Y"),
        )
        val back = EcuFirmwareDiff.fromJson(EcuFirmwareDiff.toJson(history))
        assertEquals(1, back.size)
        assertEquals(5L, back[0].at)
        assertEquals("Y", back[0].huBuild)
        assertEquals(listOf(0x720, 0x754), back[0].blocks.map { it.addr })
        assertArrayEquals(f195("9C4C1A090A01"), back[0].blocks[0].f195Uds)
        assertArrayEquals(f720, back[0].blocks[0].f194Uds)
        assertNull(back[0].blocks[1].f194Uds)
        assertNull(back[0].blocks[1].f195Uds)
        for (bad in listOf(null, "", "  ", "{not json", "[{\"at\":")) {
            assertTrue(bad.toString(), EcuFirmwareDiff.fromJson(bad).isEmpty())
        }
    }

    // D.7 59

    @Test fun `59 - 0x7E7 with its real F194 is the BMS and a new address stays unknown with its address`() {
        val bms = DtcFixtures.idUds(0x7E7)!!
        val cur = check(2, block(0x7E7, bms, "9C4A1A040901"), block(0x754, f754, "9C4118011401"))
        val rows = EcuFirmwareDiff.diff(null, cur)
        assertEquals(DtcBlockName(DtcBlock.BMS, 0x7E7), row(rows, 0x7E7).name)
        assertEquals(DtcBlockName(DtcBlock.UNKNOWN, 0x754), row(rows, 0x754).name)
    }

    // D.8 60-62

    @Test fun `60 - with no check and no baseline there is no highlight`() {
        assertFalse(EcuFirmwareDiff.huChanged(emptyList(), null, "X"))
    }

    @Test fun `61 - the build of the newest check decides, and a new check with the new build turns it off`() {
        val x = check(1, block(0x720, f720, "9C4A1A040901"), build = "X")
        assertFalse(EcuFirmwareDiff.huChanged(listOf(x), null, "X"))
        assertTrue(EcuFirmwareDiff.huChanged(listOf(x), null, "Y"))
        // The baseline is ignored once a check exists.
        assertTrue(EcuFirmwareDiff.huChanged(listOf(x), "Y", "Y"))
        assertFalse(EcuFirmwareDiff.huChanged(listOf(x), "Y", "X"))
        val y = check(2, block(0x720, f720, "9C4A1A040901"), build = "Y")
        assertFalse(EcuFirmwareDiff.huChanged(listOf(x, y), "X", "Y"))
    }

    @Test fun `62 - with no check the build is compared to the baseline, a blank build never highlights`() {
        assertTrue(EcuFirmwareDiff.huChanged(emptyList(), "X", "Y"))
        assertFalse(EcuFirmwareDiff.huChanged(emptyList(), "X", "X"))
        assertFalse(EcuFirmwareDiff.huChanged(emptyList(), "X", ""))
        assertFalse(EcuFirmwareDiff.huChanged(emptyList(), null, ""))
        val x = check(1, block(0x720, f720, "9C4A1A040901"), build = "X")
        assertFalse(EcuFirmwareDiff.huChanged(listOf(x), "X", ""))
    }
}
