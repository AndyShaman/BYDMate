package com.bydmate.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class BlindSpotOverlayAccessTest {

    @Test fun `overlay access step picks helper, system screen or none`() {
        assertEquals(
            BlindSpotOverlayAccess.Step.NONE,
            BlindSpotOverlayAccess.next(canDraw = true, helperAvailable = false),
        )
        assertEquals(
            BlindSpotOverlayAccess.Step.NONE,
            BlindSpotOverlayAccess.next(canDraw = true, helperAvailable = true),
        )
        assertEquals(
            BlindSpotOverlayAccess.Step.GRANT_VIA_HELPER,
            BlindSpotOverlayAccess.next(canDraw = false, helperAvailable = true),
        )
        assertEquals(
            BlindSpotOverlayAccess.Step.OPEN_SYSTEM_SCREEN,
            BlindSpotOverlayAccess.next(canDraw = false, helperAvailable = false),
        )
    }
}
