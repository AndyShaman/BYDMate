package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.cluster.SteeringWheelKeyService
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavA11yFeedRootLossTest {

    @After fun tearDown() {
        NavA11yFeed.enabled = false
        NavGuidanceHub.reset()
    }

    @Test fun `navigator event without reachable window does not end guidance`() {
        NavGuidanceHub.reset()
        val t0 = System.currentTimeMillis()
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 500),
            NavGuidanceHub.Source.A11Y, nowMs = t0)
        deliverUnreachableWindowEvent()
        // An unreachable window says nothing about the route (minimized / covered /
        // private VirtualDisplay), so it must not end guidance.
        assertTrue(NavGuidanceHub.snapshot(t0 + 1_000).active)
        assertTrue(NavGuidanceHub.snapshot(t0 + 15_000).active)
        // A genuinely closed navigator is still caught by source silence.
        assertFalse(NavGuidanceHub.snapshot(
            t0 + NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1_000).active)
    }

    @Test fun `a reachable read without widgets then an unreachable window hold until source silence`() {
        NavGuidanceHub.reset()
        val t0 = System.currentTimeMillis()
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 500),
            NavGuidanceHub.Source.A11Y, nowMs = t0)
        deliverWindowEvent(emptyNavigatorRoot())   // reachable read without widgets
        deliverUnreachableWindowEvent()
        // Neither read ends or refreshes guidance: only source silence does (issue #199).
        assertTrue(NavGuidanceHub.snapshot(t0 + 15_000).active)
        assertEquals(500, NavGuidanceHub.snapshot(t0 + 15_000).distanceMeters)
        assertFalse(NavGuidanceHub.snapshot(
            t0 + NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1_000).active)
    }

    private fun deliverUnreachableWindowEvent() = deliverWindowEvent(null)

    private fun emptyNavigatorRoot(): AccessibilityNodeInfo = mockk<AccessibilityNodeInfo>(relaxed = true).also {
        every { it.packageName } returns "ru.yandex.yandexnavi"
        every { it.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
    }

    private fun deliverWindowEvent(root: AccessibilityNodeInfo?) {
        NavA11yFeed.enabled = true
        NavA11yFeed.lastProcessMs = 0L        // beat the 500 ms debounce
        val service = mockk<SteeringWheelKeyService> {
            every { findNavigatorRoot() } returns root
        }
        val event = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageName = "ru.yandex.yandexnavi"
        }
        NavA11yFeed.onEvent(service, event)
    }
}
