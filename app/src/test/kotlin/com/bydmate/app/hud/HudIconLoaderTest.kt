package com.bydmate.app.hud

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HudIconLoaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = HudIconLoader.init(context)

    @Test fun `known maneuvers have icons`() {
        for (gaode in listOf(1, 2, 3, 4, 7, 8, 9, 10, 11, 13, 24, 45, 46, 48, 49)) {
            assertNotNull("missing icon for gaode=$gaode", HudIconLoader.iconFor(gaode))
        }
    }

    @Test fun `icons are png files`() {
        val png = HudIconLoader.iconFor(2)!!
        // PNG magic: 89 50 4E 47
        assertEquals(0x89.toByte(), png[0])
        assertEquals(0x50.toByte(), png[1])
        assertTrue(png.size in 100..20_000)
    }

    @Test fun `unknown code returns null`() {
        assertNull(HudIconLoader.iconFor(0))
        assertNull(HudIconLoader.iconFor(999))
    }

    @Test fun `all 48 donor assets load`() {
        assertEquals(48, HudIconLoader.loadedCount())
    }
}
