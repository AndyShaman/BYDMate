package com.bydmate.app.helper.dtc

import com.bydmate.app.helper.dtc.DtcFixtures.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuFirmwareWireTest {

    private fun parse(hexUds: String) = EcuFirmwareWire.parse(hex(hexUds))

    // D.7 51

    @Test fun `51 - a 6 byte answer gives the version and the date of the real 0x720 reads`() {
        val before = parse("62F1959C4A1A040901")!!
        assertEquals("4.00.10", before.version)
        assertEquals("2026-04-09", before.date)
        val after = parse("62F1959C4C1A090A01")!!
        assertEquals("4.00.12", after.version)
        assertEquals("2026-09-10", after.date)
        assertArrayEquals(hex("9C4C1A090A01"), after.raw)
    }

    // D.7 52

    @Test fun `52 - the day is binary not BCD and the version is built from the u16`() {
        val cases = listOf(
            "62F19500011A021705" to Triple("0.00.01", "2026-02-23", "00011A021705"),
            "62F1959CAD19051F00" to Triple("4.01.09", "2025-05-31", "9CAD19051F00"),
            "62F1954E20180A1E00" to Triple("2.00.00", "2024-10-30", "4E20180A1E00"),
            "62F195753519071000" to Triple("3.00.05", "2025-07-16", "753519071000"),
        )
        for ((uds, expected) in cases) {
            val fw = parse(uds)!!
            assertEquals(uds, expected.first, fw.version)
            assertEquals(uds, expected.second, fw.date)
            assertArrayEquals(uds, hex(expected.third), fw.raw)
        }
    }

    // D.7 53

    @Test fun `53 - an impossible month or day drops the date, keeps the version and the raw bytes`() {
        for (tail in listOf("1A1309", "1A0400")) {
            val fw = parse("62F1959C4A${tail}01")!!
            assertEquals("4.00.10", fw.version)
            assertNull(tail, fw.date)
            assertArrayEquals(hex("9C4A${tail}01"), fw.raw)
        }
    }

    // D.7 54

    @Test fun `54 - an answer that is not 6 bytes shows the printable text, else hex, and never throws`() {
        val text = parse("62F195" + "  V1.2.3 ".toByteArray().joinToString("") { "%02X".format(it) })!!
        assertEquals("V1.2.3", text.version)
        assertNull(text.date)
        val binary = parse("62F195010203")!!
        assertEquals("010203", binary.version!!.replace(" ", "").uppercase())
        assertNull(binary.date)
        assertArrayEquals(hex("010203"), binary.raw)
        assertNotNull(parse("62F19500"))
        assertNotNull(parse("62F1950102030405060708"))
    }

    // D.7 55

    @Test fun `55 - a negative answer or anything else is no firmware`() {
        assertNull(parse("7F2231"))
        assertNull(EcuFirmwareWire.parse(null))
        assertNull(EcuFirmwareWire.parse(ByteArray(0)))
        assertNull(parse("62F1944845001201F00056E0"))
        assertNull(parse("62F1"))
    }

    // D.1 7, firmware half

    @Test fun `allowed lets only 22 F1 94 and 22 F1 95 go to a block of the list`() {
        assertTrue(EcuFirmwareWire.allowed(0x754, hex("22F195")))
        assertTrue(EcuFirmwareWire.allowed(0x754, hex("22F194")))
        assertTrue(EcuFirmwareWire.allowed(0x720, hex("22F195")))
        assertFalse(EcuFirmwareWire.allowed(0x7DF, hex("22F195")))
        assertFalse(EcuFirmwareWire.allowed(0x7DF, hex("22F194")))
        assertFalse(EcuFirmwareWire.allowed(0x720, hex("22F190")))
        assertFalse(EcuFirmwareWire.allowed(0x720, hex("2E330501020304")))
        assertFalse(EcuFirmwareWire.allowed(0x720, hex("1003")))
        assertFalse(EcuFirmwareWire.allowed(0x720, hex("1902FF")))
        assertFalse(EcuFirmwareWire.allowed(0x720, hex("14FFFFFF")))
    }

    // D.10 70

    @Test fun `70 - the address list holds the 38 distinct blocks with the 20 new ones and no 0x7DF`() {
        val all = DtcWire.ADDRESSES.toList()
        assertEquals(38, all.size)
        assertEquals(38, all.toSet().size)
        val added = listOf(
            0x703, 0x711, 0x723, 0x725, 0x733, 0x741, 0x751, 0x754, 0x760, 0x761,
            0x766, 0x770, 0x771, 0x785, 0x790, 0x793, 0x7A7, 0x7C2, 0x7D4, 0x7D6,
        )
        assertEquals(20, added.size)
        assertTrue(all.containsAll(added))
        assertFalse(0x7DF in all)
        assertEquals(180_000L, DtcScanner.BUDGET_MS)
    }
}
