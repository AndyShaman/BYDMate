package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityEvent
import com.bydmate.app.cluster.SteeringWheelKeyService
import io.mockk.every
import io.mockk.mockk
import org.junit.After
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
        // private VirtualDisplay), so the 10 s no-guidance deadline must NOT arm.
        assertTrue(NavGuidanceHub.snapshot(t0 + 1_000).active)
        assertTrue(NavGuidanceHub.snapshot(
            t0 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS + 5_000).active)
        // A genuinely closed navigator is still caught by source silence.
        assertFalse(NavGuidanceHub.snapshot(
            t0 + NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1_000).active)
    }

    @Test fun `unreachable window neither arms nor cancels an armed streak`() {
        NavGuidanceHub.reset()
        val t0 = System.currentTimeMillis()
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 500),
            NavGuidanceHub.Source.A11Y, nowMs = t0)
        NavGuidanceHub.markNoGuidance(t0)     // reachable read without widgets -> streak armed
        deliverUnreachableWindowEvent()
        // The reader-side deadline (Codex audit fix 2) still fires for the armed streak.
        assertFalse(NavGuidanceHub.snapshot(
            t0 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS + 1_000).active)
    }

    private fun deliverUnreachableWindowEvent() {
        NavA11yFeed.enabled = true
        NavA11yFeed.lastProcessMs = 0L        // beat the 500 ms debounce
        val service = mockk<SteeringWheelKeyService> {
            every { findNavigatorRoot() } returns null
        }
        val event = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageName = "ru.yandex.yandexnavi"
        }
        NavA11yFeed.onEvent(service, event)
    }
}
