package com.bydmate.app.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSettingWhitelistTest {

    @Test
    fun `sentry switch and freeform flag are allowed with 0 or 1`() {
        assertTrue(globalSettingAllowed("sentrymode_enabled_switch", 0))
        assertTrue(globalSettingAllowed("sentrymode_enabled_switch", 1))
        assertTrue(globalSettingAllowed("enable_freeform_support", 0))
        assertTrue(globalSettingAllowed("enable_freeform_support", 1))
    }

    @Test
    fun `other keys and out-of-range values are rejected`() {
        assertFalse(globalSettingAllowed("adb_enabled", 1))
        assertFalse(globalSettingAllowed("", 1))
        assertFalse(globalSettingAllowed("enable_freeform_support", 2))
        assertFalse(globalSettingAllowed("sentrymode_enabled_switch", -1))
    }
}
