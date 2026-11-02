package com.bydmate.app.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #379: the daemon's Settings.Global write whitelist accepts auto_time_zone as 0/1 only. */
class TimezoneGlobalSettingWhitelistTest {

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
