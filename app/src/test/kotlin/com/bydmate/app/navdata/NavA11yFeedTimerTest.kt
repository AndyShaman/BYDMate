package com.bydmate.app.navdata

import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.cluster.SteeringWheelKeyService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/** A standing car changes nothing on the navigator screen, so no a11y events come: the timer
 *  re-reads the window so the maneuver does not expire while the navigator still shows it. */
@RunWith(RobolectricTestRunner::class)
class NavA11yFeedTimerTest {

    @Before fun setUp() {
        NavGuidanceHub.reset()
        ShadowLog.clear()
    }

    @After fun tearDown() {
        NavA11yFeed.enabled = false
        NavA11yFeed.timerService = { SteeringWheelKeyService.instance }
        NavGuidanceHub.reset()
    }

    @Test fun `a timer read feeds the hub exactly like an event read`() {
        val t0 = armedGuidance()
        NavA11yFeed.enabled = true
        NavA11yFeed.lastProcessMs = 0L
        NavA11yFeed.onEvent(service(navigatorRoot(withGuidance = true)), navigatorEvent())
        val byEvent = NavGuidanceHub.snapshot(t0 + 1_000)

        armedGuidance()
        NavA11yFeed.lastProcessMs = 0L
        val now = System.currentTimeMillis()
        NavA11yFeed.onTimer(service(navigatorRoot(withGuidance = true)), now)
        val byTimer = NavGuidanceHub.snapshot(t0 + 1_000)

        assertEquals(300, byTimer.distanceMeters)
        assertEquals("Ленина", byTimer.road)
        assertEquals(byEvent.active, byTimer.active)
        assertEquals(byEvent.maneuverGaode, byTimer.maneuverGaode)
        assertEquals(byEvent.distanceMeters, byTimer.distanceMeters)
        assertEquals(byEvent.road, byTimer.road)
        // The timer shares the event debounce, so the next event does not read again at once.
        assertEquals(now, NavA11yFeed.lastProcessMs)
    }

    @Test fun `a timer read of a navigator without guidance widgets ends guidance`() {
        val t0 = armedGuidance()
        NavA11yFeed.enabled = true
        NavA11yFeed.lastProcessMs = 0L
        NavA11yFeed.onTimer(service(navigatorRoot(withGuidance = false)), t0)
        assertFalse(NavGuidanceHub.snapshot(t0 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS).active)
    }

    @Test fun `an unreachable window on a timer read says nothing`() {
        val t0 = armedGuidance()
        NavA11yFeed.enabled = true
        NavA11yFeed.lastProcessMs = 0L
        NavA11yFeed.onTimer(service(null), t0 + 1_000)
        assertTrue(NavGuidanceHub.snapshot(t0 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS + 5_000).active)
        assertEquals(500, NavGuidanceHub.snapshot(t0 + 1_000).distanceMeters)
    }

    @Test fun `no timer read right after an event read or without a route`() {
        val svc = service(navigatorRoot(withGuidance = true))
        NavA11yFeed.enabled = true
        val t0 = System.currentTimeMillis()
        NavA11yFeed.lastProcessMs = 0L
        NavA11yFeed.onTimer(svc, t0)   // no route guided
        armedGuidance()
        NavA11yFeed.lastProcessMs = t0
        NavA11yFeed.onTimer(svc, t0 + 4_999)   // an event read 5 s ago at most
        verify(exactly = 0) { svc.findNavigatorRoot() }
    }

    @Test fun `the keep-alive line is logged once per quiet episode`() {
        armedGuidance()
        NavA11yFeed.enabled = true
        val svc = service(navigatorRoot(withGuidance = true))
        repeat(3) {
            NavA11yFeed.lastProcessMs = 0L
            NavA11yFeed.onTimer(svc, System.currentTimeMillis())
        }
        assertEquals(1, keepAliveLines())
        // An event read ends the quiet episode; the next timer refresh starts a new one.
        NavA11yFeed.lastProcessMs = 0L
        NavA11yFeed.onEvent(svc, navigatorEvent())
        NavA11yFeed.lastProcessMs = 0L
        NavA11yFeed.onTimer(svc, System.currentTimeMillis())
        assertEquals(2, keepAliveLines())
    }

    @Test fun `the timer ticks every 5 s while the feed is on and stops with it`() {
        armedGuidance()
        val svc = service(navigatorRoot(withGuidance = true))
        NavA11yFeed.timerService = { svc }
        NavA11yFeed.enabled = true
        NavA11yFeed.lastProcessMs = 0L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_999))
        verify(exactly = 0) { svc.findNavigatorRoot() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        verify(exactly = 1) { svc.findNavigatorRoot() }
        NavA11yFeed.lastProcessMs = 0L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000))
        verify(exactly = 2) { svc.findNavigatorRoot() }

        NavA11yFeed.enabled = false
        NavA11yFeed.lastProcessMs = 0L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20_000))
        verify(exactly = 2) { svc.findNavigatorRoot() }
    }

    private fun keepAliveLines() = ShadowLog.getLogsForTag("NavA11yFeed")
        .count { it.msg == "timer re-read keeps maneuver alive" }

    /** Active a11y guidance in the hub (500 m ahead); returns its timestamp. */
    private fun armedGuidance(): Long {
        NavGuidanceHub.reset()
        val t0 = System.currentTimeMillis()
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 500),
            NavGuidanceHub.Source.A11Y, nowMs = t0)
        return t0
    }

    private fun service(windowRoot: AccessibilityNodeInfo?) = mockk<SteeringWheelKeyService> {
        every { findNavigatorRoot() } returns windowRoot
    }

    private fun navigatorEvent(): AccessibilityEvent = mockk<AccessibilityEvent>(relaxed = true).also {
        every { it.eventType } returns AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        every { it.packageName } returns PKG
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
            every { root.findAccessibilityNodeInfosByViewId("$PKG:id/text_nextstreet") } returns
                listOf(textNode("Ленина"))
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
    }
}
