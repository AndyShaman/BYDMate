package com.bydmate.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetButtonIconsTest {

    @Test
    fun `catalog ids are unique`() {
        val ids = WidgetButtonIcons.CATALOG.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `catalog ids are non-blank`() {
        assertTrue(WidgetButtonIcons.CATALOG.all { it.id.isNotBlank() })
    }

    @Test
    fun `every entry carries a label resource`() {
        assertTrue(WidgetButtonIcons.CATALOG.all { it.labelRes != 0 })
    }

    @Test
    fun `find resolves every catalog id`() {
        WidgetButtonIcons.CATALOG.forEach { entry ->
            assertEquals(entry, WidgetButtonIcons.find(entry.id))
        }
    }

    @Test
    fun `find returns null for unknown and absent ids`() {
        assertNull(WidgetButtonIcons.find(null))
        assertNull(WidgetButtonIcons.find(""))
        assertNull(WidgetButtonIcons.find("no_such_icon"))
    }

    @Test
    fun `catalog is not empty`() {
        assertNotNull(WidgetButtonIcons.CATALOG.firstOrNull())
    }
}
