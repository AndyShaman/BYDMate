package com.bydmate.app.ui.automation

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automation tab in narrow windows (DiLink 3/4, split screen): list width = window - 32dp
 * screen padding, so 1280 / 800 / 640 / 512 dp windows give 1248 / 768 / 608 / 480 dp.
 */
class RuleListLayoutTest {

    // Four labeled list buttons, ru «Дублировать» the widest label: 1.0 ~83dp, 1.3 ~108dp.
    private val labeled10 = (58.dp + 83.dp) * 4 + 18.dp
    private val labeled13 = (58.dp + 108.dp) * 4 + 18.dp
    private val minText10 = 240.dp
    private val minText13 = 312.dp

    @Test fun `grid keeps three columns on the car screen and at 800dp`() {
        assertEquals(3, gridColumns(1248.dp))
        assertEquals(3, gridColumns(768.dp))
    }

    @Test fun `grid drops to two columns at 640 and 512dp`() {
        assertEquals(2, gridColumns(608.dp))
        assertEquals(2, gridColumns(480.dp))
    }

    /** 962 px at density 1.5 (list 609.3dp): three columns give 48.1dp on paper but 47.3dp
     *  buttons once Compose rounds cards and cells to whole pixels. */
    @Test fun `grid leaves room for pixel rounding at the column boundary`() {
        assertEquals(2, gridColumns(609.33.dp))
    }

    @Test fun `grid goes to one column when two would squeeze a button under 48dp`() {
        assertEquals(1, gridColumns(400.dp))
    }

    @Test fun `every grid button cell is at least 48dp wide for any width`() {
        for (w in 200..1400 step 7) {
            val cols = gridColumns(w.dp)
            if (cols > 1) assertTrue("width $w cols $cols", footCellWidth(w.dp, cols) >= MIN_TOUCH)
        }
    }

    @Test fun `car screen keeps labeled buttons beside the text at every text size`() {
        assertEquals(ListActionMode.LABELED, listActionMode(1248.dp, labeled10, minText10))
        assertEquals(ListActionMode.LABELED, listActionMode(1248.dp, labeled13, minText13))
    }

    @Test fun `800dp drops the labels and keeps icon buttons beside the text`() {
        assertEquals(ListActionMode.ICONS, listActionMode(768.dp, labeled10, minText10))
        assertEquals(ListActionMode.ICONS, listActionMode(768.dp, labeled13, minText13))
    }

    @Test fun `640dp keeps icons beside the text at normal size and moves them under at very large`() {
        assertEquals(ListActionMode.ICONS, listActionMode(608.dp, labeled10, minText10))
        assertEquals(ListActionMode.BELOW_LABELED, listActionMode(608.dp, labeled10, minText13))
        assertEquals(ListActionMode.BELOW_ICONS, listActionMode(608.dp, labeled13, minText13))
    }

    @Test fun `512dp moves the buttons under the text`() {
        assertEquals(ListActionMode.BELOW_ICONS, listActionMode(480.dp, labeled10, minText10))
        assertEquals(ListActionMode.BELOW_ICONS, listActionMode(480.dp, labeled13, minText13))
    }

    @Test fun `beside the text the text always keeps its minimum width`() {
        for (w in 300..1400 step 11) {
            val mode = listActionMode(w.dp, labeled13, minText13)
            val actions = when (mode) {
                ListActionMode.LABELED -> labeled13
                ListActionMode.ICONS -> ICON_ACTIONS_WIDTH
                else -> continue
            }
            assertTrue("width $w", w.dp - 20.dp - 24.dp - 64.dp - actions >= minText13)
        }
    }

    @Test fun `header groups share a line only when both fit whole`() {
        assertTrue(headerOnOneLine(leftWidth = 500, rightWidth = 560, gap = 12, width = 1248))
        assertTrue(headerOnOneLine(leftWidth = 500, rightWidth = 560, gap = 12, width = 1072))
        assertFalse(headerOnOneLine(leftWidth = 500, rightWidth = 560, gap = 12, width = 1071))
    }
}
