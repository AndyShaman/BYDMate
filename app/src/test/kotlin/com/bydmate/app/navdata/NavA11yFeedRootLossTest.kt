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

    @Test fun `navigator event without reachable window starts no-guidance streak`() {
        NavGuidanceHub.reset()
        val t0 = System.currentTimeMillis()
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 500),
            NavGuidanceHub.Source.A11Y, nowMs = t0)
        NavA11yFeed.enabled = true
        NavA11yFeed.lastProcessMs = 0L
        val service = mockk<SteeringWheelKeyService> {
            every { findNavigatorRoot() } returns null
        }
        val event = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageName = "ru.yandex.yandexnavi"
        }
        NavA11yFeed.onEvent(service, event)   // window gone -> streak must start
        // Still active right away; deactivated once the 10 s deadline passes with
        // NO further a11y events (the window is gone, none will come).
        assertTrue(NavGuidanceHub.snapshot(t0 + 1_000).active)
        assertFalse(NavGuidanceHub.snapshot(
            t0 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS + 5_000).active)
    }
}
