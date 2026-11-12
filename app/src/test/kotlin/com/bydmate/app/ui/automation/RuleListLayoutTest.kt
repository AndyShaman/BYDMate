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

    /** 609.33dp: 962 px at density 1.5 (list 609.3dp): three columns give 48.1dp on paper but 47.3dp
     *  buttons once Compose rounds cards and cells to whole pixels. */
    @Test fun `grid columns by list width`() {
        val cases = listOf(
            Triple("car screen", 1248.dp, 3),
            Triple("800dp window", 768.dp, 3),
            Triple("640dp window", 608.dp, 2),
            Triple("512dp window", 480.dp, 2),
            Triple("pixel rounding at the column boundary", 609.33.dp, 2),
            Triple("two columns would squeeze a button under 48dp", 400.dp, 1),
        )
        for ((label, width, columns) in cases) assertEquals("case: $label", columns, gridColumns(width))
    }

    @Test fun `every grid button cell is at least 48dp wide for any width`() {
        for (w in 200..1400 step 7) {
            val cols = gridColumns(w.dp)
            if (cols > 1) assertTrue("width $w cols $cols", footCellWidth(w.dp, cols) >= MIN_TOUCH)
        }
    }

    @Test fun `action buttons placement by list width and text size`() {
        val cases = listOf(
            // label, (window width, labeled-buttons width, minimum text width), expected mode
            Triple("car screen, 1.0", Triple(1248.dp, labeled10, minText10), ListActionMode.LABELED),
            Triple("car screen, 1.3", Triple(1248.dp, labeled13, minText13), ListActionMode.LABELED),
            Triple("800dp drops the labels, 1.0", Triple(768.dp, labeled10, minText10), ListActionMode.ICONS),
            Triple("800dp drops the labels, 1.3", Triple(768.dp, labeled13, minText13), ListActionMode.ICONS),
            Triple("640dp keeps icons beside the text at normal size", Triple(608.dp, labeled10, minText10), ListActionMode.ICONS),
            Triple("640dp moves them under at very large (labeled row, 1.0 buttons)", Triple(608.dp, labeled10, minText13), ListActionMode.BELOW_LABELED),
            Triple("640dp moves them under at very large (icons)", Triple(608.dp, labeled13, minText13), ListActionMode.BELOW_ICONS),
            Triple("512dp, 1.0", Triple(480.dp, labeled10, minText10), ListActionMode.BELOW_ICONS),
            Triple("512dp, 1.3", Triple(480.dp, labeled13, minText13), ListActionMode.BELOW_ICONS),
        )
        for ((label, input, expected) in cases) {
            val (width, labeled, minText) = input
            assertEquals("case: $label", expected, listActionMode(width, labeled, minText))
        }
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
