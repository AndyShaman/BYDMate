package com.bydmate.app.ui.dashboard

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Test

/** Recent trips on Главная: at most 6 whole rows, stretched so the last one ends at the bottom
 *  edge and no empty band stays under the list. */
class FitWholeRowsTest {

    private fun total(heights: List<Int>, spacing: Int) =
        heights.sum() + spacing * (heights.size - 1).coerceAtLeast(0)

    private class Stretched(
        val label: String,
        val rowCount: Int,
        val rowHeight: Int,
        val spacing: Int,
        val availableHeight: Int,
        val shown: Int,
        val exactHeights: List<Int>? = null,
    )

    @Test fun `rows that fit are shown and the leftover is spread so they fill the height exactly`() {
        val cases = listOf(
            // 6 x 51 + 5 x 6 = 336, 102 px left over
            Stretched("six rows fit, leftover spread evenly", 7, 51, 6, 438, 6, List(6) { 68 }),
            // 6 x 56 + 5 x 6 = 366, 34 px left over: 5 each, 4 rows get one more
            Stretched("uneven leftover goes to the first rows", 7, 56, 6, 400, 6, listOf(62, 62, 62, 62, 61, 61)),
            // 6 x 61 + 5 x 6 = 396 > 362; 5 x 61 + 4 x 6 = 329
            Stretched("only five fit, five are stretched to the bottom", 7, 61, 6, 362, 5),
            Stretched("more room than six need still shows six", 7, 40, 4, 1000, 6),
            Stretched("exactly six trips are stretched like a longer list", 6, 40, 4, 500, 6),
        )
        for (c in cases) {
            val heights = fitWholeRows(List(c.rowCount) { c.rowHeight }, c.spacing, c.availableHeight, maxRows = 6)
            assertEquals("case: ${c.label}", c.shown, heights.size)
            assertEquals("case: ${c.label}", c.availableHeight, total(heights, c.spacing))
            c.exactHeights?.let { assertEquals("case: ${c.label}", it, heights) }
        }
    }

    private class Unstretched(
        val label: String,
        val heights: List<Int>,
        val spacing: Int,
        val availableHeight: Int,
        val expected: List<Int>,
    )

    @Test fun `short, unbounded, partly fitting and empty lists are not stretched`() {
        val natural = listOf(51, 55, 51)
        val cases = listOf(
            Unstretched("a short list that fits keeps its natural heights", listOf(40, 40), 4, 500, listOf(40, 40)),
            Unstretched("unbounded height keeps all rows natural", natural, 6, Constraints.Infinity, natural),
            Unstretched("a row that fits only partly is never shown", listOf(51, 51), 6, 50, emptyList()),
            Unstretched("zero rows give an empty list", emptyList(), 6, 400, emptyList()),
            Unstretched("zero rows, unbounded height", emptyList(), 6, Constraints.Infinity, emptyList()),
        )
        for (c in cases) {
            assertEquals("case: ${c.label}", c.expected, fitWholeRows(c.heights, c.spacing, c.availableHeight, maxRows = 6))
        }
    }
}
