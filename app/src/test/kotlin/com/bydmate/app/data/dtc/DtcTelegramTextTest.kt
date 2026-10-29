package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcTelegramTextTest {

    private val labels = DtcFixtures.labels

    private fun text(summary: DtcScanSummary) = DtcTelegramText.build(summary, "28.10 16:34", labels)

    private val preClear get() = DtcParser.summarize(DtcFixtures.preClearScan())

    @Test fun `the message lists the current code first and the old ones after`() {
        val lines = text(preClear).lines()
        assertEquals("BYDMate · ошибки в блоках", lines[0])
        assertEquals("Проверено 28.10 16:34, ответили 14 блоков из 18", lines[1])
        val now = lines.indexOf("Есть сейчас:")
        val old = lines.indexOf("Старые:")
        assertTrue(now in 1 until old)
        assertEquals("ассистенты (ADAS) (0x712)", lines[now + 1])
        assertEquals("  U1166-87", lines[now + 2])
        // fourteen old codes, under their block headers, between the header and the closing line
        val footer = lines.indexOf(labels.footer)
        assertTrue(footer > old)
        assertEquals(14, lines.subList(old + 1, footer).count { it.startsWith("  ") })
    }

    @Test fun `old rows carry the block name and the code`() {
        val lines = text(preClear).lines()
        assertTrue(lines.contains("URE (0x7E0)"))
        assertTrue(lines.contains("  U0164-87"))
        assertTrue(lines.contains("кузовной блок 0x747"))
        assertTrue(lines.contains("  B2249-13"))
        assertTrue(lines.contains("блок 0x782"))
        assertTrue(lines.contains("  U0397-87"))
    }

    @Test fun `the text has no markup, no dash, no untested rows and no silent block`() {
        val t = text(preClear)
        assertFalse(t.contains('<') || t.contains('>'))
        assertFalse(t.contains('\u2014'))
        assertFalse(t.contains("не проверялся"))
        for (silent in listOf("0x720", "0x722", "0x7E1", "0x7E6")) assertFalse(silent, t.contains(silent))
        assertTrue(t.trimEnd().endsWith("Покажите коды сервису."))
    }

    @Test fun `a pending row sits under the old ones with its label`() {
        val pending = DtcScanSummary(
            answered = 1, total = 18, now = 0, old = 0, pending = 1,
            found = listOf(
                DtcFound(0x7E0, DtcRow("U0164-87", 0x04, DtcKind.PENDING, 0x87), DtcBlockName(DtcBlock.URE, 0x7E0)),
            ),
        )
        val lines = text(pending).lines()
        assertFalse(lines.contains("Есть сейчас:"))
        assertTrue(lines.indexOf("Старые:") >= 0)
        assertTrue(lines.contains("URE (0x7E0)"))
        assertTrue(lines.contains("  U0164-87 · ожидающая"))
    }

    @Test fun `a scan without rows says no errors were found`() {
        val empty = DtcScanSummary(answered = 12, total = 18, now = 0, old = 0, pending = 0, found = emptyList())
        val t = text(empty)
        assertTrue(t.contains("Ошибок в блоках не найдено"))
        assertFalse(t.contains("Есть сейчас:") || t.contains("Старые:"))
        assertTrue(t.contains("ответили 12 блоков из 18"))
    }

    @Test fun `a scan whose only codes are unread is not reported as clean`() {
        val unread = DtcScanSummary(
            answered = 12, total = 18, now = 0, old = 0, pending = 0, found = emptyList(), unreadBlocks = 1,
        )
        val t = text(unread)
        assertTrue(t.contains("прочитать не удалось"))
        assertFalse(t.contains("Ошибок в блоках не найдено"))
    }

    private fun manyCodes(n: Int) = DtcScanSummary(
        answered = 14, total = 18, now = 0, old = n, pending = 0,
        found = (0 until n).map {
            DtcFound(0x747, DtcRow("B%04X-13".format(it), 0x08, DtcKind.OLD, 0x13), DtcBlockName(DtcBlock.BODY, 0x747))
        },
    )

    @Test fun `a long list is cut to fit one Telegram message and says how many are left out`() {
        // 500 codes of ~11 chars each do not fit in 4096
        val t = text(manyCodes(500))
        assertTrue("length ${t.length}", t.length <= 4096)
        val lines = t.lines()
        val shown = lines.count { it.startsWith("  B") }
        val last = lines.indexOfLast { it.startsWith("  B") }
        assertTrue(shown in 1 until 500)
        assertTrue(lines[last + 1], lines[last + 1].contains("ещё ${500 - shown}"))
    }

    @Test fun `a short list is unchanged, with no left-out line`() {
        val t = text(manyCodes(3))
        assertEquals(3, t.lines().count { it.startsWith("  B") })
        assertFalse(t.contains("ещё"))
    }

    private fun found(addr: Int, code: String, kind: DtcKind, block: DtcBlock) =
        DtcFound(addr, DtcRow(code, if (kind == DtcKind.NOW) 0x09 else 0x08, kind, 0x87), DtcBlockName(block, addr))

    private val lost = DtcDescription("Lost CAN communication with SEC", "与SEC丢失CAN通信", DtcDescLevel.EXACT)

    private val andys = DtcScanSummary(
        answered = 14, total = 18, now = 1, old = 0, pending = 0,
        found = listOf(found(0x712, "U1166-87", DtcKind.NOW, DtcBlock.ADAS)),
    )

    @Test fun `Andy's case is the block, the code with its description and the key hint`() {
        val lines = DtcTelegramText.build(andys, "29.10 12:00", labels, describe = { lost }).lines()
        val now = lines.indexOf("Есть сейчас:")
        assertEquals("ассистенты (ADAS) (0x712)", lines[now + 1])
        assertEquals("  U1166-87 · Lost CAN communication with SEC", lines[now + 2])
        assertEquals("  " + labels.keyHint, lines[now + 3])
        assertFalse(lines.contains("Старые:"))
        assertFalse(lines.contains(labels.similarNote))
        assertFalse(lines.joinToString("\n").contains("D16687"))
    }

    @Test fun `the Chinese language picks the Chinese description`() {
        val t = DtcTelegramText.build(andys, "29.10 12:00", labels, describe = { lost }, zh = true)
        assertTrue(t.contains("  U1166-87 · 与SEC丢失CAN通信"))
        assertFalse(t.contains("Lost CAN"))
    }

    @Test fun `the key hint shows without a description but only under U1166-87 of the ADAS block`() {
        val t = DtcTelegramText.build(andys, "29.10 12:00", labels)
        assertTrue(t.lines().contains("  U1166-87"))
        assertTrue(t.contains(labels.keyHint))
        val other = andys.copy(found = listOf(found(0x7E0, "U1166-87", DtcKind.NOW, DtcBlock.URE)))
        assertFalse(DtcTelegramText.build(other, "29.10 12:00", labels).contains(labels.keyHint))
    }

    private val three = DtcScanSummary(
        answered = 14, total = 18, now = 1, old = 3, pending = 0,
        found = listOf(
            found(0x712, "U1166-87", DtcKind.NOW, DtcBlock.ADAS),
            found(0x7E0, "U0164-87", DtcKind.OLD, DtcBlock.URE),
            found(0x7E0, "U1711-87", DtcKind.OLD, DtcBlock.URE),
            found(0x783, "U1113-87", DtcKind.OLD, DtcBlock.STEERING),
        ),
    )

    @Test fun `three blocks are grouped by block under now and old`() {
        val similar = DtcDescription("Communication fault with air conditioning", "与空调通讯故障", DtcDescLevel.SIMILAR)
        val describe = { f: DtcFound ->
            when (f.row.code) {
                "U1166-87" -> lost
                "U0164-87" -> similar
                else -> null
            }
        }
        val lines = DtcTelegramText.build(three, "29.10 12:00", labels, describe = describe).lines()
        val now = lines.indexOf("Есть сейчас:")
        val old = lines.indexOf("Старые:")
        assertTrue(now in 0 until old)
        val ure = lines.indexOf("URE (0x7E0)")
        assertTrue(ure > old)
        assertEquals("  U0164-87 · Communication fault with air conditioning", lines[ure + 1])
        assertEquals("  U1711-87", lines[ure + 2])
        assertTrue(lines.indexOf("блок 0x783") > ure + 2)
        assertEquals(1, lines.count { it == "URE (0x7E0)" })
        // one SIMILAR description puts one note before the closing line
        val note = lines.indexOf(labels.similarNote)
        assertTrue(note > 0)
        assertEquals(labels.footer, lines[lines.indexOf(labels.footer)])
        assertTrue(note < lines.indexOf(labels.footer))
        assertEquals(1, lines.count { it == labels.similarNote })
    }
}
