package com.bydmate.app.ui.widget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suppression state holder: hiding the widget while one of our own overlays covers it
 * (blind-spot camera PiP) must not touch the widget preference, and releasing it must not
 * force the widget back on by itself.
 */
class WidgetSuppressionTest {

    @After fun clear() {
        WidgetController.suppressReasons.value = emptySet()
    }

    @Test fun `suppress and release toggle the reason set`() {
        WidgetController.setSuppressed("blind-spot camera", true)
        assertTrue(WidgetController.suppressReasons.value.contains("blind-spot camera"))

        WidgetController.setSuppressed("blind-spot camera", false)
        assertTrue(WidgetController.suppressReasons.value.isEmpty())
    }

    @Test fun `repeated suppress does not stack`() {
        WidgetController.setSuppressed("blind-spot camera", true)
        WidgetController.setSuppressed("blind-spot camera", true)
        assertEquals(1, WidgetController.suppressReasons.value.size)

        WidgetController.setSuppressed("blind-spot camera", false)
        assertTrue(WidgetController.suppressReasons.value.isEmpty())
    }

    @Test fun `one reason released leaves another one holding`() {
        WidgetController.setSuppressed("blind-spot camera", true)
        WidgetController.setSuppressed("other", true)
        WidgetController.setSuppressed("other", false)
        assertEquals(setOf("blind-spot camera"), WidgetController.suppressReasons.value)
    }

    @Test fun `releasing an unknown reason changes nothing`() {
        WidgetController.setSuppressed("other", false)
        assertTrue(WidgetController.suppressReasons.value.isEmpty())
    }

    @Test fun `suppression does not change the preference-driven decision`() {
        WidgetController.setSuppressed("blind-spot camera", true)
        assertFalse(WidgetController.shouldHideOverlay(
            cameraActive = false, youtubeForeground = false, hideOnYoutube = false,
            foregroundPkg = null, hideInApps = emptySet()))
    }
}
