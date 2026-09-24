package com.bydmate.app.ui.dashboard

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Test

/** Recent trips on Главная: at most 6 whole rows, stretched so the last one ends at the bottom
 *  edge and no empty band stays under the list. */
class FitWholeRowsTest {

    private fun total(heights: List<Int>, spacing: Int) =
        heights.sum() + spacing * (heights.size - 1).coerceAtLeast(0)

    @Test fun `six rows fit and the leftover is spread so they fill the height exactly`() {
        // 6 x 51 + 5 x 6 = 336, 102 px left over
        val heights = fitWholeRows(List(7) { 51 }, spacing = 6, availableHeight = 438, maxRows = 6)
        assertEquals(6, heights.size)
        assertEquals(438, total(heights, 6))
        assertEquals(List(6) { 68 }, heights)
    }

    @Test fun `leftover that does not divide evenly goes to the first rows`() {
        // 6 x 56 + 5 x 6 = 366, 34 px left over: 5 each, 4 rows get one more
        val heights = fitWholeRows(List(7) { 56 }, spacing = 6, availableHeight = 400, maxRows = 6)
        assertEquals(listOf(62, 62, 62, 62, 61, 61), heights)
        assertEquals(400, total(heights, 6))
    }

    @Test fun `only five fit, so five are shown and stretched to the bottom`() {
        // 6 x 61 + 5 x 6 = 396 > 362; 5 x 61 + 4 x 6 = 329
        val heights = fitWholeRows(List(7) { 61 }, spacing = 6, availableHeight = 362, maxRows = 6)
        assertEquals(5, heights.size)
        assertEquals(362, total(heights, 6))
    }

    @Test fun `more room than six need still shows six, stretched`() {
        val heights = fitWholeRows(List(7) { 40 }, spacing = 4, availableHeight = 1000, maxRows = 6)
        assertEquals(6, heights.size)
        assertEquals(1000, total(heights, 4))
    }

    @Test fun `exactly six trips are stretched like a longer list`() {
        val heights = fitWholeRows(List(6) { 40 }, spacing = 4, availableHeight = 500, maxRows = 6)
        assertEquals(6, heights.size)
        assertEquals(500, total(heights, 4))
    }

    @Test fun `a short list that fits keeps its natural heights`() {
        assertEquals(listOf(40, 40), fitWholeRows(listOf(40, 40), spacing = 4, availableHeight = 500, maxRows = 6))
    }

    @Test fun `unbounded height keeps all rows at natural height`() {
        val natural = listOf(51, 55, 51)
        assertEquals(natural, fitWholeRows(natural, spacing = 6, availableHeight = Constraints.Infinity, maxRows = 6))
    }

    @Test fun `a row that fits only partly is never shown`() {
        assertEquals(emptyList<Int>(), fitWholeRows(listOf(51, 51), spacing = 6, availableHeight = 50, maxRows = 6))
    }

    @Test fun `zero rows give an empty list`() {
        assertEquals(emptyList<Int>(), fitWholeRows(emptyList(), spacing = 6, availableHeight = 400, maxRows = 6))
        assertEquals(emptyList<Int>(), fitWholeRows(emptyList(), spacing = 6, availableHeight = Constraints.Infinity, maxRows = 6))
    }
}
