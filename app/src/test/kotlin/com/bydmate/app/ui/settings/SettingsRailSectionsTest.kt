package com.bydmate.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** «Места» moved to the Automation header: the Settings rail keeps nine sections. */
class SettingsRailSectionsTest {

    @Test fun `settings rail has nine sections and no places`() {
        assertEquals(9, SettingsSection.entries.size)
        assertFalse(SettingsSection.entries.any { it.name == "PLACES" })
    }
}
