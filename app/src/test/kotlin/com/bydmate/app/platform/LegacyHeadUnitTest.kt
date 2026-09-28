package com.bydmate.app.platform

import com.bydmate.app.diagnostics.TraceRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LegacyHeadUnitTest {

    @get:Rule val trace = TraceRecorder()

    @Test fun `android 10 and older are legacy head units`() {
        assertTrue(LegacyHeadUnit.isAndroid10(29))
        assertTrue(LegacyHeadUnit.isAndroid10(28))
    }

    @Test fun `android 12 head units are not legacy`() {
        assertFalse(LegacyHeadUnit.isAndroid10(31))
        assertFalse(LegacyHeadUnit.isAndroid10(32))
    }

    @Config(sdk = [29])
    @Test fun `the predicate reads the running sdk - android 10`() {
        assertTrue(LegacyHeadUnit.isAndroid10)
    }

    @Config(sdk = [32])
    @Test fun `the predicate reads the running sdk - android 12`() {
        assertFalse(LegacyHeadUnit.isAndroid10)
    }

    @Config(sdk = [29])
    @Test fun `the service start names the android version and the legacy branch - android 10`() {
        LegacyHeadUnit.noteServiceStart()

        assertEquals(listOf("app    head-unit android=10 sdk=29 legacy=true #1"), trace.events())
    }

    @Config(sdk = [32])
    @Test fun `the service start names the android version and the legacy branch - android 12`() {
        LegacyHeadUnit.noteServiceStart()

        assertEquals(listOf("app    head-unit android=12 sdk=32 legacy=false #1"), trace.events())
    }
}
