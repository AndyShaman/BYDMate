package com.bydmate.app.data.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class ClampVolumeTest {

    @Test fun `level within range is unchanged`() {
        assertEquals(7, ActionDispatcher.clampVolume(7, 15))
    }

    @Test fun `level above max clamps to max`() {
        assertEquals(15, ActionDispatcher.clampVolume(99, 15))
    }

    @Test fun `negative level clamps to zero`() {
        assertEquals(0, ActionDispatcher.clampVolume(-3, 15))
    }

    @Test fun `zero is a valid level`() {
        assertEquals(0, ActionDispatcher.clampVolume(0, 15))
    }

    @Test fun `negative max degrades to zero`() {
        assertEquals(0, ActionDispatcher.clampVolume(5, -1))
    }
}
