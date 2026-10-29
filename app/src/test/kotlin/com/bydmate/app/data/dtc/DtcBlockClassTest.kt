package com.bydmate.app.data.dtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtcBlockClassTest {

    private fun of(block: DtcBlock, addr: Int) = DtcBlockClass.of(DtcBlockName(block, addr))

    @Test fun `an identified block maps to its class whatever the address`() {
        assertEquals("ADAS", of(DtcBlock.ADAS, 0x999))
        assertEquals("BRAKES", of(DtcBlock.BRAKES, 0x999))
        assertEquals("STEERING", of(DtcBlock.STEERING, 0x783))
        assertEquals("AIRBAG", of(DtcBlock.AIRBAG, 0x999))
        assertEquals("RADAR", of(DtcBlock.RADAR, 0x999))
        assertEquals("BMS", of(DtcBlock.BMS, 0x7E7))
        assertEquals("CHARGER", of(DtcBlock.CHARGER, 0x7E3))
        assertEquals("URE", of(DtcBlock.URE, 0x7E0))
    }

    @Test fun `the body family is told apart by address`() {
        assertEquals("BODY_LEFT", of(DtcBlock.BODY, 0x720))
        assertEquals("BODY_RIGHT", of(DtcBlock.BODY, 0x747))
        assertEquals("BODY_REAR", of(DtcBlock.BODY, 0x726))
        assertNull(of(DtcBlock.BODY, 0x999))
    }

    @Test fun `an unknown block falls back to the address only where three cars agree`() {
        assertEquals("ADAS", of(DtcBlock.UNKNOWN, 0x712))
        assertEquals("BRAKES", of(DtcBlock.UNKNOWN, 0x782))
        assertEquals("AIRBAG", of(DtcBlock.UNKNOWN, 0x7F1))
        assertEquals("RADAR", of(DtcBlock.UNKNOWN, 0x7F2))
        assertEquals("BODY_LEFT", of(DtcBlock.UNKNOWN, 0x720))
        assertEquals("BODY_RIGHT", of(DtcBlock.UNKNOWN, 0x747))
        assertEquals("BODY_REAR", of(DtcBlock.UNKNOWN, 0x726))
    }

    @Test fun `an unknown block at any other address has no class`() {
        for (addr in intArrayOf(0x7E0, 0x7E2, 0x783, 0x7E7, 0x722, 0x123)) assertNull("0x%X".format(addr), of(DtcBlock.UNKNOWN, addr))
    }
}
