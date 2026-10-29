package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcAddressResult
import com.bydmate.app.helper.dtc.DtcFixtures
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcParserTest {

    private fun count(addr: Int) = DtcParser.parseCount(DtcFixtures.uds("count-${DtcFixtures.hexAddr(addr)}.txt"))

    /** The list of [path] read with the format its block reported in the count answer. */
    private fun list(path: String, addr: Int): DtcList {
        val format = count(addr)?.format
        return DtcParser.parseList(DtcFixtures.uds(path), format)
    }

    private fun codes(l: DtcList) = l.rows.map { it.code }

    @Test fun `count answers give mask, format and count`() {
        assertEquals(DtcCount(0x09, 1, 1), count(0x712))
        assertEquals(DtcCount(0xFF, 0, 5), count(0x7E0))
        assertEquals(0, count(0x726)!!.count)
        assertEquals(DtcCount(0x09, 1, 0), count(0x7E3))
    }

    @Test fun `a negative count answer is null`() {
        assertNull(count(0x7E4))
        assertNull(DtcParser.parseCount(hex("7F1911")))
        assertNull(DtcParser.parseCount(hex("5901")))
    }

    @Test fun `the 0x712 list is one now row and the tail is not parsed`() {
        val l = list("list-712.txt", 0x712)
        assertEquals(1, l.records)
        assertEquals(listOf(DtcRow("U1166-87", 0x09, DtcKind.NOW, 0x87)), l.rows)
    }

    @Test fun `Andy's 0x712 answer in format 01 reads as the manual code U1166-87`() {
        assertEquals(1, count(0x712)!!.format)
        assertEquals(listOf("U1166-87"), codes(list("list-712.txt", 0x712)))
    }

    @Test fun `the 0x782 list keeps one visible record out of thirty-six`() {
        val l = list("list-782.txt", 0x782)
        assertEquals(36, l.records)
        assertEquals(listOf(DtcRow("U0397-87", 0x28, DtcKind.OLD, 0x87)), l.rows)
    }

    @Test fun `the old codes of the other blocks read as old rows in order`() {
        val five = listOf("U0164-87", "U0334-87", "U1711-87", "U042F-87", "U10FF-CE")
        assertEquals(five, codes(list("list-7E0.txt", 0x7E0)))
        assertEquals(five, codes(list("list-7E2.txt", 0x7E2)))
        assertEquals(listOf("B2249-13"), codes(list("list-747.txt", 0x747)))
        assertEquals(listOf("U1113-87"), codes(list("list-783.txt", 0x783)))
        assertEquals(listOf("U0164-00"), codes(list("list-7E7.txt", 0x7E7)))
        for (path in listOf("list-7E0.txt", "list-747.txt", "list-783.txt", "list-7E7.txt")) {
            val addr = path.substringAfter("list-").substringBefore(".").toInt(16)
            assertTrue(path, list(path, addr).rows.all { it.kind == DtcKind.OLD })
        }
    }

    @Test fun `the lists after the codes were cleared have no visible rows except 0x712`() {
        assertEquals(0, DtcParser.parseList(DtcFixtures.uds("after2/list-747.txt"), 0).rows.size)
        val l782 = DtcParser.parseList(DtcFixtures.uds("after2/list-782.txt"), 0)
        assertEquals(51, l782.records)
        assertEquals(0, l782.rows.size)
        val l712 = DtcParser.parseList(DtcFixtures.uds("after2/list-712.txt"), 1)
        assertEquals(1, l712.rows.size)
        assertEquals(DtcKind.NOW, l712.rows[0].kind)
    }

    @Test fun `status bits are classified now, old or pending, the rest is dropped`() {
        for (s in intArrayOf(0x09, 0x01)) assertEquals("0x%02X".format(s), DtcKind.NOW, DtcParser.classify(s))
        for (s in intArrayOf(0x28, 0x08, 0x0C)) assertEquals("0x%02X".format(s), DtcKind.OLD, DtcParser.classify(s))
        for (s in intArrayOf(0x04, 0x24)) assertEquals("0x%02X".format(s), DtcKind.PENDING, DtcParser.classify(s))
        for (s in intArrayOf(0x40, 0x50, 0x00, 0x20, 0x80)) assertNull("0x%02X".format(s), DtcParser.classify(s))
    }

    @Test fun `the code text follows the format byte`() {
        assertEquals("U0164-87", DtcParser.codeText(hex("C16487"), 0))
        assertEquals("P0562-FC", DtcParser.codeText(hex("0562FC"), 0))
        assertEquals("U1166-87", DtcParser.codeText(hex("D16687"), 0))
        assertEquals("U1166-87", DtcParser.codeText(hex("D16687"), 1))
        assertEquals("U1166-87", DtcParser.codeText(hex("D16687"), null))
        assertEquals("D166", DtcParser.codeText(hex("D166"), 1))
        assertEquals("D1668701", DtcParser.codeText(hex("D1668701"), 0))
    }

    @Test fun `a partial record is ignored and a negative list has no rows`() {
        val l = DtcParser.parseList(hex("5902FF C16487 08 C33487"), 0)
        assertEquals(1, l.records)
        assertEquals(listOf("U0164-87"), codes(l))
        val neg = DtcParser.parseList(hex("7F1931"), 0)
        assertTrue(neg.negative)
        assertEquals(0, neg.rows.size)
        assertFalse(l.negative)
    }

    @Test fun `fault types have a text only for the five known values`() {
        assertEquals(DtcFault.NO_SIGNAL, DtcParser.faultType(0x87))
        assertEquals(DtcFault.OPEN_CIRCUIT, DtcParser.faultType(0x13))
        assertEquals(DtcFault.SHORT_TO_GROUND, DtcParser.faultType(0x11))
        assertEquals(DtcFault.SHORT_TO_PLUS, DtcParser.faultType(0x12))
        assertEquals(DtcFault.COMPONENT, DtcParser.faultType(0x09))
        for (ftb in intArrayOf(0x00, 0xCE, 0x28, 0xFF)) assertNull("0x%02X".format(ftb), DtcParser.faultType(ftb))
    }

    @Test fun `the whole pre-clear scan reads as 14 of 18, one now and fourteen old`() {
        val s = DtcParser.summarize(DtcFixtures.preClearScan())
        assertEquals(14, s.answered)
        assertEquals(18, s.total)
        assertEquals(1, s.now)
        assertEquals(14, s.old)
        assertEquals(0, s.pending)
        // the card shows three rows and «+12 ещё»
        assertEquals(15, s.found.size)
        assertEquals(0x712, s.found.first().addr)
        assertEquals(DtcKind.NOW, s.found.first().row.kind)
    }

    @Test fun `a block that did not answer never shows up`() {
        val s = DtcParser.summarize(DtcFixtures.preClearScan())
        val silent = setOf(0x720, 0x722, 0x7E1, 0x7E6, 0x7E4, 0x7E5)
        assertTrue(s.found.none { it.addr in silent })
    }

    @Test fun `found rows carry the block name from the identity answer`() {
        val s = DtcParser.summarize(DtcFixtures.preClearScan())
        assertEquals(DtcBlock.ADAS, s.found.first { it.addr == 0x712 }.block.block)
        assertEquals(DtcBlock.URE, s.found.first { it.addr == 0x7E0 }.block.block)
    }

    private fun countUds(count: Int) = byteArrayOf(0x59, 0x01, 0x0D, 0x00, (count shr 8).toByte(), count.toByte())

    @Test fun `a block with codes counted but no readable list is an unread block`() {
        for (outcome in listOf("no_answer", "nrc", "multiframe", "truncated")) {
            val s = DtcParser.summarize(listOf(DtcAddressResult(0x712, outcome, countUds(2), null, null)))
            assertEquals(outcome, 1, s.unreadBlocks)
        }
    }

    @Test fun `a zero count is not an unread block, nor is a read list`() {
        val zero = DtcParser.summarize(listOf(DtcAddressResult(0x712, "ok", countUds(0), null, null)))
        assertEquals(0, zero.unreadBlocks)
        assertEquals(0, DtcParser.summarize(DtcFixtures.preClearScan()).unreadBlocks)
    }
}
