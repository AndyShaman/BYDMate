package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.cluster.SteeringWheelKeyService
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/** Issue #199 (Han 2024): mid-route the Navigator window keeps reading without its guidance
 *  widgets, and the hub used to end guidance after 10 s of that, 8 times in one drive. Like the
 *  donor, a window without widgets ends nothing: only 90 s without any update does. */
@RunWith(RobolectricTestRunner::class)
class NavGuidanceIssue199Test {

    @Before fun setUp() {
        NavGuidanceHub.reset()
        ShadowLog.clear()
        NavA11yFeed.enabled = true
    }

    @After fun tearDown() {
        NavA11yFeed.enabled = false
        NavGuidanceHub.reset()
    }

    @Test fun `issue 199 reads without guidance widgets mid-route keep the guidance`() {
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 800, road = "Ленина"),
            NavGuidanceHub.Source.A11Y, nowMs = T0)
        for (t in T0 + 5_000..T0 + 50_000 step 5_000) {
            timerRead(navigatorRoot(withGuidance = false), t)
            val s = NavGuidanceHub.snapshot(t)
            assertTrue("inactive at +${t - T0} ms", s.active)
            assertEquals(800, s.distanceMeters)
            assertEquals("Ленина", s.road)
        }
        timerRead(navigatorRoot(withGuidance = true), T0 + 55_000)
        val s = NavGuidanceHub.snapshot(T0 + 55_000)
        assertTrue(s.active)
        assertEquals(300, s.distanceMeters)
        assertTrue(ShadowLog.getLogsForTag("NavGuidanceHub").none { "route ended" in it.msg })
    }

    @Test fun `issue 199 a route without any update ends at 90 s, not earlier`() {
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 800, road = "Ленина"),
            NavGuidanceHub.Source.A11Y, nowMs = T0)
        for (t in T0 + 5_000..T0 + 85_000 step 5_000) timerRead(navigatorRoot(withGuidance = false), t)
        assertTrue(NavGuidanceHub.snapshot(T0 + 89_000).active)
        assertTrue(NavGuidanceHub.snapshot(T0 + NavGuidanceHub.ACTIVE_TIMEOUT_MS).active)
        assertFalse(NavGuidanceHub.snapshot(T0 + NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1).active)
    }

    private fun timerRead(root: AccessibilityNodeInfo, atMs: Long) {
        NavA11yFeed.lastProcessMs = 0L
        NavA11yFeed.onTimer(mockk<SteeringWheelKeyService> { every { findNavigatorRoot() } returns root }, atMs)
    }

    private fun navigatorRoot(withGuidance: Boolean): AccessibilityNodeInfo {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns PKG
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        if (withGuidance) {
            every { root.findAccessibilityNodeInfosByViewId("$PKG:id/text_maneuverballoon_distance") } returns
                listOf(textNode("300"))
            every { root.findAccessibilityNodeInfosByViewId("$PKG:id/text_maneuverballoon_metrics") } returns
                listOf(textNode("м"))
        }
        return root
    }

    private fun textNode(value: String): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text } returns value
        every { node.contentDescription } returns null
        return node
    }

    private companion object {
        const val PKG = "ru.yandex.yandexnavi"
        const val T0 = 1_000_000L
    }
}
