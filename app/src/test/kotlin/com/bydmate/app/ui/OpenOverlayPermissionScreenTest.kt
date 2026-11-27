package com.bydmate.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.IntentFilter
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OpenOverlayPermissionScreenTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun `returns false without throwing when no activity resolves overlay permission`() {
        shadowOf(app).checkActivities(true)

        assertFalse(app.openOverlayPermissionScreen())
    }

    @Test fun `returns true and starts intent with package data when resolving activity exists`() {
        shadowOf(app).checkActivities(true)
        val component = ComponentName("com.android.settings", "com.android.settings.OverlayPermissionActivity")
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
                addDataScheme("package")
            },
        )

        assertTrue(app.openOverlayPermissionScreen())
        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, started.action)
        assertEquals("package:com.bydmate.app", started.dataString)
    }
}
