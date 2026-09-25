package com.bydmate.app.ui.automation

import com.bydmate.app.data.local.entity.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** #249: the driver's own order of the automation rules, over the DAO's newest-first list. */
class RuleOrderTest {

    private fun rule(id: Long) = RuleEntity(id = id, name = "r$id", triggers = "[]", actions = "[]", createdAt = id)

    /** Newest first, as RuleDao.getAll gives them. */
    private fun rules(vararg ids: Long) = ids.map(::rule)

    private fun ids(list: List<RuleEntity>) = list.map { it.id }

    @Test fun `nothing saved keeps the newest first order`() {
        assertEquals(listOf(5L, 4L, 3L), ids(RuleOrder.apply(emptyList(), rules(5, 4, 3))))
        assertEquals(listOf(5L, 4L, 3L), ids(RuleOrder.apply(RuleOrder.parse(""), rules(5, 4, 3))))
    }

    @Test fun `a saved order is followed`() {
        assertEquals(listOf(3L, 5L, 4L), ids(RuleOrder.apply(listOf(3, 5, 4), rules(5, 4, 3))))
    }

    @Test fun `rules missing from the saved order go on top, newest first`() {
        assertEquals(
            listOf(7L, 6L, 3L, 5L, 4L),
            ids(RuleOrder.apply(listOf(3, 5, 4), rules(7, 6, 5, 4, 3))),
        )
    }

    @Test fun `ids of deleted rules are skipped`() {
        assertEquals(listOf(3L, 4L), ids(RuleOrder.apply(listOf(9, 3, 5, 4), rules(4, 3))))
    }

    @Test fun `the stored text round-trips and tolerates junk and repeats`() {
        val order = listOf(12L, 3L, 40L)
        assertEquals(order, RuleOrder.parse(RuleOrder.serialize(order)))
        assertEquals(listOf(3L, 7L), RuleOrder.parse(" 3, x,,7,3 "))
    }

    @Test fun `first to last`() {
        assertEquals(listOf(2L, 3L, 4L, 1L), RuleOrder.move(listOf(1L, 2L, 3L, 4L), 1L, 4L))
    }

    @Test fun `last to first`() {
        assertEquals(listOf(4L, 1L, 2L, 3L), RuleOrder.move(listOf(1L, 2L, 3L, 4L), 4L, 1L))
    }

    @Test fun `adjacent rules swap`() {
        assertEquals(listOf(1L, 3L, 2L, 4L), RuleOrder.move(listOf(1L, 2L, 3L, 4L), 2L, 3L))
        assertEquals(listOf(1L, 3L, 2L, 4L), RuleOrder.move(listOf(1L, 2L, 3L, 4L), 3L, 2L))
    }

    @Test fun `a move onto itself or an unknown rule changes nothing`() {
        val order = listOf(1L, 2L, 3L)
        assertEquals(order, RuleOrder.move(order, 2L, 2L))
        assertEquals(order, RuleOrder.move(order, 9L, 2L))
        assertEquals(order, RuleOrder.move(order, 2L, 9L))
    }

    @Test fun `under a filter the shown rules move as dragged and the hidden ones keep their order`() {
        // All: 1 2 3 4 5; shown (say, enabled): 1 3 5. Drag 1 onto 5, then 5 back onto 3.
        val all = listOf(1L, 2L, 3L, 4L, 5L)
        val down = RuleOrder.move(all, 1L, 5L)
        assertEquals(listOf(2L, 3L, 4L, 5L, 1L), down)
        assertEquals(listOf(3L, 5L, 1L), down.filter { it in setOf(1L, 3L, 5L) })
        val up = RuleOrder.move(down, 5L, 3L)
        assertEquals(listOf(2L, 5L, 3L, 4L, 1L), up)
        assertEquals(listOf(5L, 3L, 1L), up.filter { it in setOf(1L, 3L, 5L) })
        assertEquals(listOf(2L, 4L), up.filter { it in setOf(2L, 4L) })
    }
}
