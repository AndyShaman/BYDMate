package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcFixtures
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import org.junit.Assert.assertEquals
import org.junit.Test

class DtcBlockNamesTest {

    private fun block(addr: Int) = DtcBlockNames.identify(addr, DtcFixtures.idUds(addr))

    @Test fun `known blocks are named from their own identity`() {
        assertEquals(DtcBlock.ADAS, block(0x712).block)
        assertEquals(DtcBlock.BRAKES, block(0x782).block)
        assertEquals(DtcBlock.STEERING, block(0x783).block)
        assertEquals(DtcBlock.AIRBAG, block(0x7F1).block)
        assertEquals(DtcBlock.RADAR, block(0x7F2).block)
        assertEquals(DtcBlock.BMS, block(0x7E7).block)
        assertEquals(DtcBlock.CHARGER, block(0x7E4).block)
        assertEquals(DtcBlock.URE, block(0x7E0).block)
        assertEquals(DtcBlock.URE, block(0x7E2).block)
    }

    @Test fun `the three body blocks share a name and keep their own address`() {
        for (addr in intArrayOf(0x720, 0x726, 0x747)) {
            assertEquals(DtcBlockName(DtcBlock.BODY, addr), block(addr))
        }
    }

    @Test fun `everything else falls back to the address`() {
        assertEquals(DtcBlockName(DtcBlock.UNKNOWN, 0x7E3), block(0x7E3))
        assertEquals(DtcBlockName(DtcBlock.UNKNOWN, 0x762), block(0x762))
    }

    @Test fun `a negative or missing identity answer names nothing`() {
        assertEquals(DtcBlockName(DtcBlock.UNKNOWN, 0x7E5), DtcBlockNames.identify(0x7E5, hex("7F2211")))
        assertEquals(DtcBlockName(DtcBlock.UNKNOWN, 0x7E1), DtcBlockNames.identify(0x7E1, null))
    }

    @Test fun `the same supplier with another type is not the known block`() {
        // 4B51 is the ADAS supplier, but only type 83 is the ADAS unit
        assertEquals(
            DtcBlockName(DtcBlock.UNKNOWN, 0x712),
            DtcBlockNames.identify(0x712, hex("62F194 4B51 00 12 02 84 000001")),
        )
        // 4845 with a type outside F0..F2 is no body block either
        assertEquals(
            DtcBlockName(DtcBlock.UNKNOWN, 0x726),
            DtcBlockNames.identify(0x726, hex("62F194 4845 00 12 01 F3 000010")),
        )
    }

    @Test fun `a truncated identity answer names nothing`() {
        assertEquals(DtcBlockName(DtcBlock.UNKNOWN, 0x712), DtcBlockNames.identify(0x712, hex("62F1944B51")))
    }
}
