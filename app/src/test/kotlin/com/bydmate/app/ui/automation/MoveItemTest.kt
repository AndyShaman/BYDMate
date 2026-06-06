package com.bydmate.app.ui.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MoveItemTest {

    @Test fun `move up swaps with previous`() {
        assertEquals(listOf("b", "a", "c"), listOf("a", "b", "c").moveItem(1, up = true))
    }

    @Test fun `move down swaps with next`() {
        assertEquals(listOf("a", "c", "b"), listOf("a", "b", "c").moveItem(1, up = false))
    }

    @Test fun `move up at first index is a no-op`() {
        val list = listOf("a", "b", "c")
        assertSame(list, list.moveItem(0, up = true))
    }

    @Test fun `move down at last index is a no-op`() {
        val list = listOf("a", "b", "c")
        assertSame(list, list.moveItem(2, up = false))
    }

    @Test fun `out of range index is a no-op`() {
        val list = listOf("a", "b", "c")
        assertSame(list, list.moveItem(5, up = true))
        assertSame(list, list.moveItem(-1, up = false))
    }
}
