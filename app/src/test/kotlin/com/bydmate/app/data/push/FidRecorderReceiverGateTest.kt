package com.bydmate.app.data.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The receiver is exported, so the build gate is the only thing keeping a public APK quiet. */
class FidRecorderReceiverGateTest {

    @Test fun `a public release build ignores the broadcast`() {
        assertFalse(fidRecorderEnabled("3.15.5", debug = false))
    }

    @Test fun `test builds and local debug builds answer it`() {
        assertTrue(fidRecorderEnabled("3.15.5-test", debug = false))
        assertTrue(fidRecorderEnabled("3.15.5", debug = true))
    }
}
