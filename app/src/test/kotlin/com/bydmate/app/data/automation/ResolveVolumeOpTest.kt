package com.bydmate.app.data.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveVolumeOpTest {
    @Test fun `absolute level clamps to max`() {
        assertEquals(ActionDispatcher.VolumeOp.SetTo(15), ActionDispatcher.resolveVolumeOp("20", 7, 15))
        assertEquals(ActionDispatcher.VolumeOp.SetTo(10), ActionDispatcher.resolveVolumeOp("10", 7, 15))
    }
    @Test fun `relative steps from current and clamps`() {
        assertEquals(ActionDispatcher.VolumeOp.SetTo(8), ActionDispatcher.resolveVolumeOp("+1", 7, 15))
        assertEquals(ActionDispatcher.VolumeOp.SetTo(6), ActionDispatcher.resolveVolumeOp("-1", 7, 15))
        assertEquals(ActionDispatcher.VolumeOp.SetTo(15), ActionDispatcher.resolveVolumeOp("+9", 14, 15))
        assertEquals(ActionDispatcher.VolumeOp.SetTo(0), ActionDispatcher.resolveVolumeOp("-9", 2, 15))
    }
    @Test fun `mute and unmute`() {
        assertEquals(ActionDispatcher.VolumeOp.Mute, ActionDispatcher.resolveVolumeOp("mute", 7, 15))
        assertEquals(ActionDispatcher.VolumeOp.Unmute, ActionDispatcher.resolveVolumeOp("unmute", 7, 15))
    }
    @Test fun `garbage is invalid`() {
        assertEquals(ActionDispatcher.VolumeOp.Invalid, ActionDispatcher.resolveVolumeOp("loud", 7, 15))
    }
}
