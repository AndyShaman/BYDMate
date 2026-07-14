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

    @Test fun `reads speed limit and exit number from navi window`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "ru.yandex.yandexnavi"
        every { root.findAccessibilityNodeInfosByViewId("ru.yandex.yandexnavi:id/text_speedlimit") } returns
            listOf(node("60"))
        every { root.findAccessibilityNodeInfosByViewId("ru.yandex.yandexnavi:id/exit_number_text") } returns
            listOf(node("12"))
        every { root.findAccessibilityNodeInfosByViewId("ru.yandex.yandexnavi:id/text_maneuverballoon_distance") } returns
            listOf(node("250"))
        every { root.findAccessibilityNodeInfosByViewId("ru.yandex.yandexnavi:id/text_maneuverballoon_metrics") } returns
            listOf(node(" м"))
        every { root.findAccessibilityNodeInfosByViewId("ru.yandex.yandexnavi:id/textview_eta_distance") } returns
            listOf(node("28 км"))
        every { root.findAccessibilityNodeInfosByViewId("ru.yandex.yandexnavi:id/textview_eta_time") } returns
            listOf(node("27 мин"))
        every { root.findAccessibilityNodeInfosByViewId("ru.yandex.yandexnavi:id/textview_eta_arrival") } returns
            listOf(node("10:10"))
        every { root.findAccessibilityNodeInfosByViewId("ru.yandex.yandexnavi:id/status_panel_text") } returns
            listOf(node("ул. Качаны"))

        val info = NaviScreenReader.read(root)!!
        assertEquals("60", info.speedLimit)
        assertEquals("12", info.exitNumber)
        assertEquals("250 м", info.maneuverDistance)
        assertEquals("28 км", info.remainingDistance)
        assertEquals("27 мин", info.remainingTime)
        assertEquals("10:10", info.arrivalTime)
        assertEquals("ул. Качаны", info.street)
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

    @Test fun `missing nodes give null fields`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "ru.yandex.yandexnavi"
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()

        val info = NaviScreenReader.read(root)!!
        assertNull(info.speedLimit)
        assertNull(info.exitNumber)
        assertNull(info.maneuverDistance)
        assertNull(info.remainingDistance)
        assertNull(info.remainingTime)
        assertNull(info.arrivalTime)
        assertNull(info.street)
    }
}
