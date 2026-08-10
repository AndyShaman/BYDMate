package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.cluster.SteeringWheelKeyService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Window enumeration blind (Navigator on a PRIVATE VirtualDisplay, issue #134):
 *  guidance is read from the event's own source node instead. */
@Suppress("DEPRECATION")   // recycle() is the pooling contract these tests assert
@RunWith(RobolectricTestRunner::class)
class NavA11yFeedSourceFallbackTest {

    @After fun tearDown() {
        NavA11yFeed.enabled = false
        NavGuidanceHub.reset()
    }

    @Test fun `guidance is read from the event source when the window is unreachable`() {
        NavGuidanceHub.reset()
        val t0 = System.currentTimeMillis()
        val root = navigatorRoot(PKG, withGuidance = true)
        val mid = childWithParent(root)
        val source = childWithParent(mid)
        deliver(sourceNode = source)
        val s = NavGuidanceHub.snapshot(t0 + 1_000)
        assertTrue(s.active)
        assertEquals(300, s.distanceMeters)
        assertEquals("Ленина", s.road)
        // Every node the climb touched goes back to the finite framework pool exactly once.
        verify(exactly = 1) { source.recycle() }
        verify(exactly = 1) { mid.recycle() }
        verify(exactly = 1) { root.recycle() }
    }

    @Test fun `source root without guidance widgets does not end guidance`() {
        val t0 = armedGuidance()
        deliver(sourceNode = descendantOf(navigatorRoot(PKG, withGuidance = false)))
        // The source root may be a sub-window (balloon, dialog) — missing widgets there
        // say nothing about the route, so the 10 s no-guidance deadline must NOT arm.
        assertTrue(NavGuidanceHub.snapshot(t0 + 1_000).active)
        assertTrue(NavGuidanceHub.snapshot(
            t0 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS + 5_000).active)
        // A genuinely closed navigator is still caught by source silence.
        assertFalse(NavGuidanceHub.snapshot(
            t0 + NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1_000).active)
    }

    @Test fun `null event source leaves the hub untouched`() {
        val t0 = armedGuidance()
        deliver(sourceNode = null)
        assertGuidanceUndisturbed(t0)
    }

    @Test fun `throwing event source leaves the hub untouched`() {
        val t0 = armedGuidance()
        deliver(sourceThrows = true)
        assertGuidanceUndisturbed(t0)
    }

    @Test fun `foreign package root leaves the hub untouched`() {
        val t0 = armedGuidance()
        deliver(sourceNode = descendantOf(navigatorRoot("com.android.launcher", withGuidance = true)))
        assertGuidanceUndisturbed(t0)
    }

    @Test fun `guidance is still read after a long parent chain within the hop limit`() {
        NavGuidanceHub.reset()
        val t0 = System.currentTimeMillis()
        deliver(sourceNode = chainBelow(navigatorRoot(PKG, withGuidance = true), hops = 20))
        val s = NavGuidanceHub.snapshot(t0 + 1_000)
        assertTrue(s.active)
        assertEquals(300, s.distanceMeters)
    }

    @Test fun `parent chain deeper than the hop limit gives up without touching the hub`() {
        val t0 = armedGuidance()
        // 70 > MAX_PARENT_HOPS: the climb stops on an intermediate node, which reads as
        // NotNavigator — the guard against a cyclic chain must not fabricate a root.
        deliver(sourceNode = chainBelow(navigatorRoot(PKG, withGuidance = true), hops = 70))
        assertGuidanceUndisturbed(t0)
    }

    @Test fun `reachable window path never queries the event source`() {
        NavGuidanceHub.reset()
        val t0 = System.currentTimeMillis()
        val event = deliver(windowRoot = navigatorRoot(PKG, withGuidance = true))
        assertEquals(300, NavGuidanceHub.snapshot(t0 + 1_000).distanceMeters)
        verify(exactly = 0) { event.source }
    }

    /** Active a11y guidance in the hub; returns its timestamp. */
    private fun armedGuidance(): Long {
        NavGuidanceHub.reset()
        val t0 = System.currentTimeMillis()
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 500),
            NavGuidanceHub.Source.A11Y, nowMs = t0)
        return t0
    }

    private fun assertGuidanceUndisturbed(t0: Long) {
        assertTrue(NavGuidanceHub.snapshot(
            t0 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS + 5_000).active)
        assertEquals(500, NavGuidanceHub.snapshot(t0 + 1_000).distanceMeters)
    }

    private fun deliver(
        sourceNode: AccessibilityNodeInfo? = null,
        sourceThrows: Boolean = false,
        windowRoot: AccessibilityNodeInfo? = null,
    ): AccessibilityEvent {
        NavA11yFeed.enabled = true
        NavA11yFeed.lastProcessMs = 0L        // beat the 500 ms debounce
        val service = mockk<SteeringWheelKeyService> {
            every { findNavigatorRoot() } returns windowRoot
        }
        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        every { event.packageName } returns PKG
        if (sourceThrows) every { event.source } throws RuntimeException("stale event")
        else every { event.source } returns sourceNode
        NavA11yFeed.onEvent(service, event)
        return event
    }

    private fun navigatorRoot(pkg: String, withGuidance: Boolean): AccessibilityNodeInfo {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns pkg
        every { root.parent } returns null
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        if (withGuidance) {
            every { root.findAccessibilityNodeInfosByViewId("$pkg:id/text_maneuverballoon_distance") } returns
                listOf(textNode("300"))
            every { root.findAccessibilityNodeInfosByViewId("$pkg:id/text_maneuverballoon_metrics") } returns
                listOf(textNode("м"))
            every { root.findAccessibilityNodeInfosByViewId("$pkg:id/text_nextstreet") } returns
                listOf(textNode("Ленина"))
        }
        return root
    }

    /** Source node two hops below [root], as an event from a widget would be. */
    private fun descendantOf(root: AccessibilityNodeInfo): AccessibilityNodeInfo =
        chainBelow(root, hops = 2)

    private fun chainBelow(root: AccessibilityNodeInfo, hops: Int): AccessibilityNodeInfo {
        var node = root
        repeat(hops) { node = childWithParent(node) }
        return node
    }

    private fun childWithParent(parentNode: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.parent } returns parentNode
        every { node.packageName } returns null   // only a real root carries the package
        return node
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
