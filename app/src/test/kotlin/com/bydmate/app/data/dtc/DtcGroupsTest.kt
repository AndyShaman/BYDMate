package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcGroupsTest {

    private val found get() = DtcParser.summarize(DtcFixtures.preClearScan()).found

    private fun row(code: String, status: Int = 0x08, kind: DtcKind = DtcKind.OLD) = DtcRow(code, status, kind, 0x87)

    private fun at(addr: Int, block: DtcBlock, vararg rows: DtcRow) =
        rows.map { DtcFound(addr, it, DtcBlockName(block, addr)) }

    @Test fun `the identical URE lists of 0x7E0 and 0x7E2 form one group, the rest stay single`() {
        val groups = DtcParser.groups(found)
        val ure = groups.single { 0x7E0 in it.addrs }
        assertEquals(listOf(0x7E0, 0x7E2), ure.addrs)
        assertEquals(DtcBlock.URE, ure.block.block)
        assertEquals(5, ure.rows.size)
        val others = groups.filter { it !== ure }
        assertTrue(others.all { it.addrs.size == 1 })
        val withRows = found.map { it.addr }.distinct().filter { it != 0x7E0 && it != 0x7E2 }
        assertEquals(withRows, others.map { it.addrs.single() })
        // groups come ordered by their first address
        assertEquals(groups.map { it.addrs.first() }.sorted(), groups.map { it.addrs.first() })
    }

    @Test fun `identical rows under different block names stay two groups`() {
        val rows = arrayOf(row("U0164-87"), row("U0334-87"))
        val groups = DtcParser.groups(at(0x7E0, DtcBlock.URE, *rows) + at(0x783, DtcBlock.STEERING, *rows))
        assertEquals(listOf(listOf(0x7E0), listOf(0x783)), groups.map { it.addrs })
    }

    @Test fun `the same name with one differing status byte stays two groups`() {
        val groups = DtcParser.groups(
            at(0x7E0, DtcBlock.URE, row("U0164-87", 0x08), row("U0334-87", 0x08)) +
                at(0x7E2, DtcBlock.URE, row("U0164-87", 0x08), row("U0334-87", 0x28)),
        )
        assertEquals(listOf(listOf(0x7E0), listOf(0x7E2)), groups.map { it.addrs })
    }

    @Test fun `two body blocks with identical rows stay two groups`() {
        val rows = arrayOf(row("U1166-87"))
        val groups = DtcParser.groups(at(0x720, DtcBlock.BODY, *rows) + at(0x747, DtcBlock.BODY, *rows))
        assertEquals(listOf(listOf(0x720), listOf(0x747)), groups.map { it.addrs })
    }

    @Test fun `two unknown blocks with identical rows stay two groups`() {
        val rows = arrayOf(row("U1166-87"))
        val groups = DtcParser.groups(at(0x712, DtcBlock.UNKNOWN, *rows) + at(0x7F2, DtcBlock.UNKNOWN, *rows))
        assertEquals(listOf(listOf(0x712), listOf(0x7F2)), groups.map { it.addrs })
    }

    @Test fun `the summary counts the codes of a merged group once`() {
        val results = DtcFixtures.preClearScan()
        val perAddress = results.sumOf { r ->
            val list = r.listUds ?: return@sumOf 0
            val format = r.countUds?.let { DtcParser.parseCount(it) }?.format
            DtcParser.parseList(list, format).rows.count { it.kind == DtcKind.OLD }
        }
        assertEquals(perAddress - 5, DtcParser.summarize(results).old)
    }
}
