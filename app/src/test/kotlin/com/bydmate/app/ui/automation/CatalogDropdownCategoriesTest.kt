package com.bydmate.app.ui.automation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The catalog opens collapsed except where the driver already is — otherwise the list of
 * commands is a page they have to scroll through to find anything.
 */
class CatalogDropdownCategoriesTest {

    private val items = listOf("Окно водителя", "Окно пассажира", "Климат авто", "Багажник")
    private val categories = listOf("Окна", "Окна", "Климат", "Кузов")

    @Test
    fun `only the category holding the current pick starts open`() {
        assertEquals(setOf("Климат"), expandedCategoriesFor("Климат авто", items, categories))
        assertEquals(setOf("Окна"), expandedCategoriesFor("Окно пассажира", items, categories))
    }

    @Test
    fun `a selection outside the catalog opens nothing`() {
        assertEquals(emptySet<String>(), expandedCategoriesFor("", items, categories))
        assertEquals(emptySet<String>(), expandedCategoriesFor("发送CAN 123", items, categories))
    }

    @Test
    fun `an empty catalog opens nothing`() {
        assertEquals(emptySet<String>(), expandedCategoriesFor("Багажник", emptyList(), emptyList()))
    }
}
