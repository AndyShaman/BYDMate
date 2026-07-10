package com.bydmate.app.ui.settings

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnabledSettingNameTest {
    @Test
    fun `maps all known states`() {
        assertEquals("DEFAULT (enabled)", enabledSettingName(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
        assertEquals("ENABLED", enabledSettingName(PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
        assertEquals("DISABLED", enabledSettingName(PackageManager.COMPONENT_ENABLED_STATE_DISABLED))
        assertEquals("DISABLED_USER", enabledSettingName(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER))
        assertEquals("DISABLED_UNTIL_USED", enabledSettingName(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED))
    }

    @Test
    fun `unknown state carries the raw value`() {
        assertTrue(enabledSettingName(42).contains("42"))
    }
}
