package com.bydmate.app.helper.dtc

import com.bydmate.app.helper.dtc.DtcFixtures.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TpmsWireTest {

    private val f194 = hex("62F1944845001201F00056E0")
    private val newFl = 0x9A7BB4C2.toInt()
    private val plan1 = listOf(TpmsPlanEntry(0x3305, 0x9A4B4AC0.toInt(), newFl))

    private fun writeGuard(plan: List<TpmsPlanEntry> = plan1, latched: Boolean = false) =
        TpmsGuard(TpmsJobKind.WRITE, plan, latched)

    private fun TpmsGuard.ok(h: String, addr: Int = 0x720) = allowed(addr, hex(h))

    private fun keyHex(seed: String) = "%08X".format(TpmsWire.securityKey(hex(seed).fold(0) { a, b -> (a shl 8) or (b.toInt() and 0xFF) }))

    // slots as recorded on the car
    private val slotsWinter = mapOf(
        0x3305 to hex("6233059A7BB4C2000001011C0200"),
        0x3306 to hex("6233069A1561AF00000401FE0100"),
        0x3307 to hex("6233079A4B4AC00008B600F40100"),
        0x3308 to hex("6233089A36759300000401080200"),
    )

    // D.1

    @Test fun `allowlist 1 - only 10 03 and 10 01 session requests pass`() {
        val g = writeGuard()
        assertTrue(g.ok("1003"))
        assertTrue(g.ok("1001"))
        assertFalse(g.ok("1002"))
        assertFalse(g.ok("100300"))
    }

    @Test fun `allowlist 2 - 27 02 passes once and only with the key of the seed just received`() {
        val g = writeGuard()
        assertTrue(g.ok("2701"))
        assertFalse(g.ok("2702B8FEFFA3")) // before any seed
        g.onSeed(hex("12345678"))
        assertFalse(g.ok("27020000000A"))
        assertTrue(g.ok("2702B8FEFFA3"))
        assertFalse(g.ok("2702B8FEFFA3")) // a second 27 02 in the same run
        assertFalse(g.ok("2703"))
    }

    @Test fun `allowlist 2 - 27 01 is refused while the security key is latched`() {
        assertFalse(writeGuard(latched = true).ok("2701"))
    }

    @Test fun `allowlist 3 - reads only of F194 and the four tyre slots`() {
        val g = writeGuard()
        for (h in listOf("223305", "223306", "223307", "223308", "22F194")) assertTrue(h, g.ok(h))
        for (h in listOf("223304", "223309", "22F190")) assertFalse(h, g.ok(h))
    }

    @Test fun `allowlist 4 - a planned 2E passes once and nothing else of the 2E family does`() {
        val g = writeGuard()
        assertTrue(g.ok("2E33059A7BB4C2"))
        assertFalse(g.ok("2E33059A7BB4C2"))
        val g2 = writeGuard()
        assertFalse(g2.ok("2E33059A7BB4C2" + "00".repeat(11)))
        assertFalse(g2.ok("2E330511223344"))
        assertFalse(g2.ok("2E33099A7BB4C2"))
        assertTrue(g2.ok("2E33059A7BB4C2")) // the refusals consumed nothing
    }

    @Test fun `allowlist 5 - 19 02 FF passes, every other service is refused`() {
        val g = writeGuard()
        assertTrue(g.ok("1902FF"))
        for (h in listOf("19020D", "14FFFFFF", "3101532001", "3E00", "1101", "2F33000301", "8502")) assertFalse(h, g.ok(h))
    }

    @Test fun `allowlist 6 - any other address is refused`() {
        val g = writeGuard()
        for (a in listOf(0x728, 0x721, 0x747)) assertFalse(a.toString(16), g.ok("22F194", a))
    }

    @Test fun `allowlist 7 - the read job refuses 2E 10 03 and 27 01`() {
        val g = TpmsGuard(TpmsJobKind.READ, emptyList(), false)
        assertFalse(g.ok("2E33059A7BB4C2"))
        assertFalse(g.ok("1003"))
        assertFalse(g.ok("2701"))
        assertTrue(g.ok("223305"))
    }

    // D.2

    @Test fun `plan validation 19 - a bad plan is refused with a reason`() {
        val e = TpmsPlanEntry(0x3305, 1, 2)
        assertNull(TpmsWire.validatePlan(listOf(e)))
        assertNotNull(TpmsWire.validatePlan(emptyList()))
        assertNotNull(TpmsWire.validatePlan((0x3305..0x3308).map { TpmsPlanEntry(it, 1, 2) } + e))
        assertNotNull(TpmsWire.validatePlan(listOf(e, e)))
        assertNotNull(TpmsWire.validatePlan(listOf(TpmsPlanEntry(0x3309, 1, 2))))
        assertNotNull(TpmsWire.validatePlan(listOf(TpmsPlanEntry(0x3305, 5, 5))))
        assertNotNull(TpmsWire.validatePlan(listOf(TpmsPlanEntry(0x3305, 1, 0))))
        assertNotNull(TpmsWire.validatePlan(listOf(TpmsPlanEntry(0x3305, 1, -1))))
    }

    @Test fun `plan validation 20 - a slot that is neither old nor new is car_changed`() {
        val r = TpmsWire.cas(plan1, mapOf(0x3305 to 0x11111111))
        assertEquals(TpmsCas.Abort("car_changed"), r)
    }

    @Test fun `plan validation 21 - every slot already new means nothing to write`() {
        val r = TpmsWire.cas(plan1, mapOf(0x3305 to newFl))
        assertEquals(TpmsCas.NothingToWrite, r)
    }

    // D.3

    @Test fun `slot 22 - id, heard and tail of a heard sensor`() {
        val s = TpmsWire.parseSlot(0x3305, hex("6233059A4B4AC0000001011C0200"))
        assertEquals(TpmsSlot(0x3305, 0x9A4B4AC0.toInt(), true, hex("0001011C0200")), s)
    }

    @Test fun `slot 23 - status 01 means not heard`() {
        val s = TpmsWire.parseSlot(0x3308, hex("623308C179683601 00FF00CC0100"))
        assertEquals(TpmsSlot(0x3308, 0xC1796836.toInt(), false, hex("00FF00CC0100")), s)
    }

    @Test fun `slot 24 - only the 14 uds bytes of a raw frame are taken`() {
        val raw = hex("0A07280E0101016233059A4B4AC0000001011C02002850")
        val m = DtcWire.match(raw, 0x720, 0x22, 0x3305) as DtcMatch.Answer
        val s = TpmsWire.parseSlot(0x3305, m.uds)
        assertEquals(TpmsSlot(0x3305, 0x9A4B4AC0.toInt(), true, hex("0001011C0200")), s)
    }

    @Test fun `slot 25 - a 13 or 15 byte answer is not an L3 slot`() {
        assertNull(TpmsWire.parseSlot(0x3305, hex("6233059A4B4AC0000001011C02")))
        assertNull(TpmsWire.parseSlot(0x3305, hex("6233059A4B4AC0000001011C020000")))
    }

    @Test fun `slot 26 - status byte 02 is not an L3 slot`() {
        assertNull(TpmsWire.parseSlot(0x3305, hex("6233059A4B4AC0020001011C0200")))
    }

    @Test fun `slot 27 - the answer of another slot is not taken for this one`() {
        assertNull(TpmsWire.parseSlot(0x3305, hex("6233069A36759300000401FE0100")))
    }

    @Test fun `slot 28 - a negative answer and a multiframe leave the slot unread`() {
        assertNull(TpmsWire.parseSlot(0x3305, hex("7F2231")))
        val multi = hex("0A07280E0201016233059A4B4AC0000001011C0200")
        assertTrue(DtcWire.match(multi, 0x720, 0x22, 0x3305) is DtcMatch.Multiframe)
    }

    // D.4

    @Test fun `shape 29 - L3 identity and the four recorded slots pass`() {
        assertTrue(TpmsWire.l3Shape(f194, slotsWinter))
    }

    @Test fun `shape 30 - the post-VDS slot with the same identity passes`() {
        val s = slotsWinter + (0x3305 to hex("623305EA119FE0010 0FA00B80100".replace(" ", "")))
        assertTrue(TpmsWire.l3Shape(f194, s))
    }

    @Test fun `shape 31 - another body type, supplier or length fails`() {
        assertFalse(TpmsWire.l3Shape(hex("62F1944845001201F10056E0"), slotsWinter))
        assertFalse(TpmsWire.l3Shape(hex("62F1944846001201F00056E0"), slotsWinter))
        assertFalse(TpmsWire.l3Shape(hex("62F1944845001201F00056"), slotsWinter))
        assertFalse(TpmsWire.l3Shape(hex("62F1944845001201F00056E000"), slotsWinter))
    }

    @Test fun `shape 32 - a negative or missing identity fails`() {
        assertFalse(TpmsWire.l3Shape(hex("7F2231"), slotsWinter))
        assertFalse(TpmsWire.l3Shape(null, slotsWinter))
    }

    @Test fun `shape 33 - three slots or one slot of 15 bytes fails`() {
        assertFalse(TpmsWire.l3Shape(f194, slotsWinter - 0x3305))
        assertFalse(TpmsWire.l3Shape(f194, slotsWinter + (0x3305 to hex("6233059A7BB4C2000001011C020000"))))
    }

    // D.5

    @Test fun `key 35 - the unsigned shifts give the recorded keys`() {
        assertEquals("65000015", keyHex("00000001"))
        assertEquals("B8FEFFA3", keyHex("12345678"))
        assertEquals("DD706366", keyHex("DEADBEEF"))
        assertEquals("5AFFFFE2", keyHex("FFFFFFFF"))
        assertEquals("4500001D", keyHex("80000000"))
        assertEquals("1FF69346", keyHex("0A0B0C0D"))
    }

    @Test fun `isEmptyId treats FFFFFFFF and 0 as empty and real IDs as present`() {
        assertTrue(TpmsWire.isEmptyId(-1))
        assertTrue(TpmsWire.isEmptyId(0))
        assertFalse(TpmsWire.isEmptyId(0x1A2B3C4D))
    }

    @Test fun `tpms-k1 writableIdentity opens only for Leopard 3 and exact Song L EV 2025 F194`() {
        val songL = hex("62F1944B3100131BF00051E0")
        val songLChanged = hex("62F1944B3100131BF00051E1")
        val yuanPlus = hex("62F1944B31001156F0001101")
        assertTrue(TpmsWire.writableIdentity(DtcFixtures.idUds(0x720)))
        assertTrue(TpmsWire.writableIdentity(songL))
        assertFalse(TpmsWire.writableIdentity(songLChanged))
        assertFalse(TpmsWire.writableIdentity(yuanPlus))
        assertFalse(TpmsWire.writableIdentity(null))
        assertFalse(TpmsWire.writableIdentity(hex("62F1944B3100131BF00051")))

        assertTrue(TpmsWire.l3Shape(songL, slotsWinter))
        assertFalse(TpmsWire.l3Shape(yuanPlus, slotsWinter))
    }
}
