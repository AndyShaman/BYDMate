package com.bydmate.app.media

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NaviScreenReaderTest {

    private fun node(text: String?): AccessibilityNodeInfo = mockk(relaxed = true) {
        every { this@mockk.text } returns text
    }

    @Test fun `null root returns null`() {
        assertNull(NaviScreenReader.read(null))
    }

    @Test fun `foreign package returns null`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.bydmate.app"
        assertNull(NaviScreenReader.read(root))
    }

    @Test fun `maneuver distance without metrics node`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "ru.yandex.yandexnavi"
        // any() catches all ids (including metrics → emptyList); specific matcher below overrides
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("ru.yandex.yandexnavi:id/text_maneuverballoon_distance") } returns
            listOf(node("250"))

        val info = NaviScreenReader.read(root)!!
        assertEquals("250", info.maneuverDistance)
    }
}
