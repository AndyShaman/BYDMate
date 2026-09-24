package com.bydmate.app.ui.settings

import com.bydmate.app.ui.widget.LeftTapMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #188: the left-tap mode chip must stay usable when the split feature is off but SPLIT is
 * still the saved mode, so the user has a way out of it instead of being stuck.
 */
class LeftTapModeChipTest {
    @Test
    fun `chip enabled with split feature on regardless of mode`() {
        assertTrue(leftTapModeChipEnabled(widgetEnabled = true, splitFeatureEnabled = true, mode = LeftTapMode.APP))
        assertTrue(leftTapModeChipEnabled(widgetEnabled = true, splitFeatureEnabled = true, mode = LeftTapMode.SPLIT))
    }

    @Test
    fun `chip stays enabled with split feature off if saved mode is SPLIT`() {
        assertTrue(leftTapModeChipEnabled(widgetEnabled = true, splitFeatureEnabled = false, mode = LeftTapMode.SPLIT))
    }

    @Test
    fun `chip disabled with split feature off and saved mode is APP`() {
        assertFalse(leftTapModeChipEnabled(widgetEnabled = true, splitFeatureEnabled = false, mode = LeftTapMode.APP))
    }

    @Test
    fun `chip disabled whenever the widget itself is off`() {
        assertFalse(leftTapModeChipEnabled(widgetEnabled = false, splitFeatureEnabled = true, mode = LeftTapMode.SPLIT))
    }

    @Test
    fun `APP selection always accepted`() {
        assertTrue(leftTapModeSelectable(idx = 0, splitFeatureEnabled = true))
        assertTrue(leftTapModeSelectable(idx = 0, splitFeatureEnabled = false))
    }

    @Test
    fun `SPLIT selection accepted only when split feature is on`() {
        assertTrue(leftTapModeSelectable(idx = 1, splitFeatureEnabled = true))
        assertFalse(leftTapModeSelectable(idx = 1, splitFeatureEnabled = false))
    }
}
