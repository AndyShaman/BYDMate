package com.bydmate.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

// Pure JVM test of the no-running-instance path. Touching the companion does NOT
// instantiate the Android Service; the bridge returns 0 synchronously when no
// instance is registered.
class TrackingServiceButtonBridgeTest {

    @Test fun `fireAutomationButton with no running service reports zero matches`() {
        var reported = -1
        TrackingService.fireAutomationButton(3) { matched -> reported = matched }
        assertEquals(0, reported)
    }
}
