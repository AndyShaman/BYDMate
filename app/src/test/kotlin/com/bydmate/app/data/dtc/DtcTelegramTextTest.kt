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
        assertEquals("D16687 · ассистенты (ADAS)", lines[now + 1])
        // fourteen old rows between the header and the closing line
        val footer = lines.indexOf(labels.footer)
        assertTrue(footer > old)
        assertEquals(14, lines.subList(old + 1, footer).count { it.isNotBlank() })
    }

    @Test fun `old rows carry the block name and the code`() {
        val lines = text(preClear).lines()
        assertTrue(lines.contains("U0164-87 · URE"))
        assertTrue(lines.contains("B2249-13 · кузовной блок 0x747"))
        assertTrue(lines.contains("U0397-87 · блок 0x782"))
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
        assertTrue(lines.contains("U0164-87 · URE · ожидающая"))
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
        val t = text(manyCodes(200))
        assertTrue("length ${t.length}", t.length <= 4096)
        val lines = t.lines()
        val shown = lines.count { it.contains(" · кузовной блок") }
        val last = lines.indexOfLast { it.contains(" · кузовной блок") }
        assertTrue(shown in 1 until 200)
        assertTrue(lines[last + 1], lines[last + 1].contains("ещё ${200 - shown}"))
    }

    @Test fun `a short list is unchanged, with no left-out line`() {
        val t = text(manyCodes(3))
        assertEquals(3, t.lines().count { it.contains(" · кузовной блок") })
        assertFalse(t.contains("ещё"))
    }
}
