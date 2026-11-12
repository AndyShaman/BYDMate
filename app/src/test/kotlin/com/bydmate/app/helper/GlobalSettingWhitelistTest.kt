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

    // #379: auto_time_zone is accepted as 0/1 only.
    @Test fun `auto_time_zone is allowed with 0 or 1`() {
        assertTrue(globalSettingAllowed("auto_time_zone", 0))
        assertTrue(globalSettingAllowed("auto_time_zone", 1))
    }

    @Test fun `auto_time_zone refuses other values`() {
        assertFalse(globalSettingAllowed("auto_time_zone", 2))
        assertFalse(globalSettingAllowed("auto_time_zone", -1))
    }

    @Test fun `auto_time_zone can be read back`() {
        assertTrue(globalSettingReadable("auto_time_zone"))
    }
}
