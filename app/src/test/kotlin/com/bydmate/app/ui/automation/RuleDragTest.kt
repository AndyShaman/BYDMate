package com.bydmate.app.ui.automation

import androidx.compose.ui.geometry.Rect
import com.bydmate.app.data.local.entity.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #249: where a held rule goes and when the list scrolls under it, list and grid. */
class RuleDragTest {

    private val ten = (1L..10L).toList()

    /** A list row of rule [id] (index id - 1), 800 px wide. Rows are 100 px tall, 6 px apart. */
    private fun row(id: Long, top: Float, height: Float = 100f) =
        RuleCell(id, (id - 1).toInt(), Rect(0f, top, 800f, top + height))

    /** The lifted list card at [top]. */
    private fun listCard(top: Float) = Rect(0f, top, 800f, top + 100f)

    /** A grid card of rule [id] at [i]: 3 columns of 200x150 px cells, 12 px apart. */
    private fun gridCell(id: Long, scroll: Float = 0f, i: Int = (id - 1).toInt()): RuleCell {
        val left = (i % 3) * 212f
        val top = (i / 3) * 162f - scroll
        return RuleCell(id, i, Rect(left, top, left + 200f, top + 150f))
    }

    /** The lifted grid card centred at ([x], [y]). */
    private fun gridCard(x: Float, y: Float) = Rect(x - 100f, y - 75f, x + 100f, y + 75f)

    // --- Where the held rule goes ---

    @Test fun `list - held on top and dragged past the bottom edge goes to the last rule on screen`() {
        // Rows 1-5 on screen, 5 partly; the card's centre (610) is below them all.
        val visible = (1L..5L).map { row(it, (it - 1) * 106f) }
        assertEquals(5L, dragTarget(visible, ten, held = 1L, card = listCard(560f)))
    }

    @Test fun `list - held rule scrolled off screen goes to the last rule on screen past the bottom edge`() {
        // Scrolled down: rows 4-8 on screen, rule 1 (held) above them.
        val visible = (4L..8L).map { row(it, -20f + (it - 4) * 106f) }
        assertEquals(8L, dragTarget(visible, ten, held = 1L, card = listCard(480f)))
    }

    @Test fun `list - held rule scrolled off screen still moves under the finger`() {
        val visible = (4L..8L).map { row(it, -20f + (it - 4) * 106f) }
        // Centre 250 is over row 6 (192..292).
        assertEquals(6L, dragTarget(visible, ten, held = 1L, card = listCard(200f)))
    }

    @Test fun `list - past the top edge goes to the first rule on screen`() {
        val visible = (4L..8L).map { row(it, -20f + (it - 4) * 106f) }
        assertEquals(4L, dragTarget(visible, ten, held = 8L, card = listCard(-150f)))
    }

    @Test fun `list - no move while the layout has not caught up with the last one, held on or off screen`() {
        val moved = RuleOrder.move(ten, 1L, 8L)
        val stale = (4L..8L).map { row(it, -20f + (it - 4) * 106f) }
        assertNull(dragTarget(stale, moved, held = 1L, card = listCard(480f)))
        assertNull(dragTarget(stale, moved, held = 8L, card = listCard(200f)))
    }

    @Test fun `grid - a move the layout was late for is taken once the layout catches up`() {
        val nine = (1L..9L).toList()
        // Rule 1 went onto 3; the finger is on to rule 6 before the grid has re-flowed.
        val moved = RuleOrder.move(nine, 1L, 3L)
        val onSix = gridCard(524f, 237f)
        assertNull(dragTarget(nine.map { gridCell(it) }, moved, held = 1L, card = onSix))
        // Next frame, laid out in the new order: 6 is where it was, 1 now third.
        val caughtUp = moved.mapIndexed { i, id -> gridCell(id, i = i) }
        assertEquals(6L, dragTarget(caughtUp, moved, held = 1L, card = onSix))
    }

    @Test fun `list - held rule off screen comes back over a taller row`() {
        // Rule 1 (100 px) scrolled off the top; rule 2 spans 0..400 and has the centre (150).
        val visible = listOf(row(2L, 0f, height = 400f), row(3L, 406f))
        assertEquals(2L, dragTarget(visible, ten, held = 1L, card = listCard(100f)))
    }

    @Test fun `a single rule stays put wherever it is held`() {
        val one = listOf(1L)
        val visible = listOf(row(1L, 0f))
        for (top in listOf(-300f, 0f, 50f, 600f)) {
            assertNull(dragTarget(visible, one, held = 1L, card = listCard(top)))
        }
        assertNull(dragTarget(listOf(gridCell(1L)), one, held = 1L, card = gridCard(530f, 399f)))
    }

    @Test fun `no rules on screen, no target`() {
        assertNull(dragTarget(emptyList(), emptyList(), held = 1L, card = listCard(0f)))
        assertNull(dragTarget(emptyList(), ten, held = 1L, card = listCard(200f)))
    }

    @Test fun `the drag lets go when the rules shown change, not when they re-order or update`() {
        fun rule(id: Long, enabled: Boolean = true) =
            RuleEntity(id = id, name = "r$id", enabled = enabled, triggers = "[]", actions = "[]", createdAt = id)
        val held = listOf(1L, 2L, 3L)
        assertTrue(sameRules(held, listOf(rule(3), rule(1), rule(2))))
        assertTrue(sameRules(held, listOf(rule(1), rule(2, enabled = false), rule(3))))
        // «Активные» switched on with 2 disabled; a rule added; a rule deleted.
        assertFalse(sameRules(held, listOf(rule(1), rule(3))))
        assertFalse(sameRules(held, listOf(rule(4), rule(1), rule(2), rule(3))))
        assertFalse(sameRules(held, listOf(rule(1), rule(2))))
    }

    @Test fun `list - the last rule held past the bottom stays put`() {
        val visible = (6L..10L).map { row(it, (it - 6) * 106f) }
        assertNull(dragTarget(visible, ten, held = 10L, card = listCard(560f)))
    }

    @Test fun `list - short list takes a rule dropped below the last one to the end`() {
        val three = listOf(1L, 2L, 3L)
        val visible = three.map { row(it, (it - 1) * 106f) }
        assertEquals(3L, dragTarget(visible, three, held = 1L, card = listCard(350f)))
    }

    @Test fun `list - the gap between rows keeps an on-screen held rule where it is`() {
        val visible = (1L..5L).map { row(it, (it - 1) * 106f) }
        // Centre 315 is between rows 3 (..312) and 4 (318..).
        assertNull(dragTarget(visible, ten, held = 1L, card = listCard(265f)))
    }

    @Test fun `list - a taller row is taken only once the centre reaches where the held rule goes`() {
        val visible = listOf(row(1L, 0f), row(2L, 106f, height = 200f))
        assertNull(dragTarget(visible, ten, held = 1L, card = listCard(100f)))
        assertEquals(2L, dragTarget(visible, ten, held = 1L, card = listCard(170f)))
    }

    @Test fun `grid - past the bottom edge goes to the last rule on screen, not the one nearest the finger`() {
        val twelve = (1L..12L).toList()
        val visible = (1L..9L).map { gridCell(it) }
        assertEquals(9L, dragTarget(visible, twelve, held = 2L, card = gridCard(100f, 575f)))
    }

    @Test fun `grid - the empty cells after the last rule take the held one to the end`() {
        val eight = (1L..8L).toList()
        val visible = eight.map { gridCell(it) }
        // Rule 8 is in the middle column of the last row; the centre is in the empty cell right of it.
        assertEquals(8L, dragTarget(visible, eight, held = 1L, card = gridCard(530f, 399f)))
    }

    @Test fun `grid - a card over a rule in another row and column takes its place`() {
        val visible = (1L..9L).map { gridCell(it) }
        assertEquals(6L, dragTarget(visible, (1L..9L).toList(), held = 1L, card = gridCard(524f, 237f)))
    }

    @Test fun `grid - held rule scrolled off screen comes back from a gutter to the nearest rule`() {
        val fifteen = (1L..15L).toList()
        // Scrolled two rows down: rules 7-15 on screen, rule 1 (held) above them. The centre is in the
        // gutter between rules 10 and 11, nearer to 11.
        val visible = (7L..15L).map { gridCell(it, scroll = 324f) }
        assertEquals(11L, dragTarget(visible, fifteen, held = 1L, card = gridCard(208f, 237f)))
    }

    @Test fun `grid - a gutter keeps an on-screen held rule where it is`() {
        val visible = (1L..9L).map { gridCell(it) }
        assertNull(dragTarget(visible, (1L..9L).toList(), held = 1L, card = gridCard(208f, 237f)))
    }

    // --- Auto-scroll: a 1000 px list, 64 px bands ---

    private fun scroll(card: Rect, movedUp: Boolean, movedDown: Boolean) =
        edgeScroll(card, viewportHeight = 1000, edge = 64f, movedUp = movedUp, movedDown = movedDown)

    @Test fun `a card lifted inside the top band does not scroll until the finger moves it up`() {
        assertEquals(0f, scroll(listCard(20f), movedUp = false, movedDown = false), 0f)
        assertEquals(0f, scroll(listCard(30f), movedUp = false, movedDown = true), 0f)
        assertEquals(-0.5f, scroll(listCard(32f), movedUp = true, movedDown = false), 1e-6f)
    }

    @Test fun `a card lifted inside the bottom band does not scroll until the finger moves it down`() {
        assertEquals(0f, scroll(listCard(880f), movedUp = false, movedDown = false), 0f)
        assertEquals(0f, scroll(listCard(870f), movedUp = true, movedDown = false), 0f)
        assertEquals(0.5f, scroll(listCard(868f), movedUp = false, movedDown = true), 1e-6f)
    }

    @Test fun `back in the top band after scrolling down, the list scrolls up`() {
        // Lifted at top 0, dragged down into the bottom band and scrolled, then brought back to top 20.
        assertEquals(1f, scroll(listCard(950f), movedUp = false, movedDown = true), 0f)
        assertEquals(-44f / 64f, scroll(listCard(20f), movedUp = true, movedDown = true), 1e-6f)
    }

    @Test fun `speed grows with depth and is full at the edge and past it`() {
        assertEquals(-1f, scroll(listCard(0f), movedUp = true, movedDown = true), 0f)
        assertEquals(-1f, scroll(listCard(-80f), movedUp = true, movedDown = true), 0f)
        assertEquals(1f, scroll(listCard(900f), movedUp = true, movedDown = true), 0f)
        assertEquals(1f, scroll(listCard(1100f), movedUp = true, movedDown = true), 0f)
    }

    @Test fun `the middle of the list does not scroll`() {
        assertEquals(0f, scroll(listCard(400f), movedUp = true, movedDown = true), 0f)
        assertEquals(0f, scroll(listCard(64f), movedUp = true, movedDown = true), 0f)
        assertEquals(0f, scroll(listCard(836f), movedUp = true, movedDown = true), 0f)
    }

    @Test fun `a card taller than the list goes by its centre and never flips back and forth`() {
        fun tall(top: Float) = Rect(0f, top, 800f, top + 1200f)
        // Centred (500): still. 16 px above or below the middle: half speed that way.
        assertEquals(0f, scroll(tall(-100f), movedUp = true, movedDown = true), 0f)
        assertEquals(-0.5f, scroll(tall(-116f), movedUp = true, movedDown = true), 1e-6f)
        assertEquals(0.5f, scroll(tall(-84f), movedUp = true, movedDown = true), 1e-6f)
        // One speed per position, only growing as the card goes down: no up-down-up on the way.
        val speeds = (-300..100 step 4).map { scroll(tall(it.toFloat()), movedUp = true, movedDown = true) }
        assertEquals(speeds.sorted(), speeds)
        // Moved only down: the top band does not count yet.
        assertEquals(1f, scroll(tall(-100f), movedUp = false, movedDown = true), 0f)
    }

    @Test fun `a grid card scrolls the same wherever it is across`() {
        val gridCard = Rect(424f, 20f, 624f, 170f)
        assertEquals(scroll(listCard(20f), true, true), scroll(gridCard, true, true), 0f)
        assertEquals(0f, scroll(gridCard, movedUp = false, movedDown = true), 0f)
    }
}
