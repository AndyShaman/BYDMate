package com.bydmate.app.helper.dtc

import com.bydmate.app.helper.dtc.DtcFixtures.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcWireTest {

    private fun match(path: String, addr: Int, service: Int, sub: Int) =
        DtcWire.match(DtcFixtures.raw(path), addr, service, sub)

    @Test fun `count answer is matched and the stale tail is cut`() {
        val m = match("count-712.txt", 0x712, 0x19, 0x01)
        assertTrue(m is DtcMatch.Answer)
        val uds = (m as DtcMatch.Answer).uds
        assertEquals("590109010001", DtcFixtures.hex(uds))
        assertEquals(6, uds.size)
    }

    @Test fun `a frame of another block is not mine`() {
        assertTrue(match("list-782.txt", 0x712, 0x19, 0x01) is DtcMatch.NotMine)
    }

    @Test fun `a late list duplicate is never taken for the count answer`() {
        assertTrue(match("list-712.txt", 0x712, 0x19, 0x01) is DtcMatch.NotMine)
    }

    @Test fun `a frame shorter than the header is not mine`() {
        assertTrue(DtcWire.match(hex("0A071A0601"), 0x712, 0x19, 0x01) is DtcMatch.NotMine)
    }

    @Test fun `result 3 with the request echoed is a gateway timeout`() {
        assertTrue(match("count-722.txt", 0x722, 0x19, 0x01) is DtcMatch.GatewayTimeout)
    }

    @Test fun `negative answers carry their code`() {
        assertEquals(0x12, (match("count-7E4.txt", 0x7E4, 0x19, 0x01) as DtcMatch.Nrc).code)
        assertEquals(0x11, (match("count-7E5.txt", 0x7E5, 0x19, 0x01) as DtcMatch.Nrc).code)
    }

    @Test fun `nrc 78 means keep waiting`() {
        assertTrue(DtcWire.match(hex("0A07EC030101027F1978"), 0x7E4, 0x19, 0x01) is DtcMatch.Pending)
    }

    @Test fun `a frame cut short of its length is truncated`() {
        val raw = hex("0A071A100101015901")
        assertEquals(9, raw.size)
        assertTrue(DtcWire.match(raw, 0x712, 0x19, 0x01) is DtcMatch.Truncated)
    }

    @Test fun `the identity request matches only the identity answer`() {
        assertTrue(match("name-782.txt", 0x782, 0x22, 0xF194) is DtcMatch.NotMine)
        val id = DtcFixtures.frame(0x712, DtcFixtures.idUds(0x712)!!)
        assertTrue(DtcWire.match(id, 0x712, 0x22, 0xF194) is DtcMatch.Answer)
    }

    @Test fun `more than one frame is kept raw`() {
        val raw = hex("0A071A0602010159010901000100")
        val m = DtcWire.match(raw, 0x712, 0x19, 0x01)
        assertTrue(m is DtcMatch.Multiframe)
        assertArrayEquals(raw, (m as DtcMatch.Multiframe).raw)
    }

    @Test fun `only the three fixed read requests exist for the eighteen addresses`() {
        assertEquals(18, DtcWire.ADDRESSES.size)
        val forbidden = setOf(0x14, 0x2E, 0x2F, 0x31, 0x27, 0x10, 0x11, 0x85)
        for (addr in DtcWire.ADDRESSES) {
            val hi = (addr shr 8).toByte()
            val lo = addr.toByte()
            val all = listOf(
                DtcWire.countRequest(addr) to hex("0A %02X %02X 03 19 01 FF".format(hi, lo)),
                DtcWire.listRequest(addr, 0x0D) to hex("0A %02X %02X 03 19 02 0D".format(hi, lo)),
                DtcWire.listRequest(addr, 0x09) to hex("0A %02X %02X 03 19 02 09".format(hi, lo)),
                DtcWire.listRequest(addr, 0xFF) to hex("0A %02X %02X 03 19 02 FF".format(hi, lo)),
                DtcWire.identityRequest(addr) to hex("0A %02X %02X 03 22 F1 94".format(hi, lo)),
            )
            for ((actual, expected) in all) {
                assertArrayEquals(expected, actual)
                assertTrue((actual[4].toInt() and 0xFF) !in forbidden)
            }
        }
    }

    @Test fun `an address outside the whitelist is refused`() {
        for (addr in intArrayOf(0x7E8, 0x700, 0x713, 0)) {
            assertThrows(IllegalArgumentException::class.java) { DtcWire.countRequest(addr) }
            assertThrows(IllegalArgumentException::class.java) { DtcWire.listRequest(addr, 0xFF) }
            assertThrows(IllegalArgumentException::class.java) { DtcWire.identityRequest(addr) }
        }
    }

    @Test fun `a list mask outside the three known ones is refused`() {
        for (mask in intArrayOf(0x00, 0x0A, 0x7F, 0x100)) {
            assertThrows(IllegalArgumentException::class.java) { DtcWire.listRequest(0x712, mask) }
        }
    }
}
