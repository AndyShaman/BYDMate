package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.cluster.SteeringWheelKeyService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lane-widget research probe: one bounded listing of the Navigator's view ids per maneuver. */
@Suppress("DEPRECATION")   // recycle() is the pooling contract these tests assert
@RunWith(RobolectricTestRunner::class)
class NavA11yFeedTreeDumpTest {

    private val dumps = mutableListOf<String>()
    private val realSink = NavA11yFeed.treeDumpSink

    @Before fun installSink() {
        NavA11yFeed.enabled = false   // a fresh episode: the dump guard starts empty
        NavA11yFeed.treeDumpSink = { dumps.add(it) }
        NavGuidanceHub.reset()
    }

    @After fun tearDown() {
        NavA11yFeed.treeDumpSink = realSink
        NavA11yFeed.enabled = false
        NavGuidanceHub.reset()
    }

    @Test fun `dump lists view ids with text lengths`() {
        val lane = node("lane_sign", text = "abc")
        val root = navigatorRoot("Поверните направо", listOf(lane, node(id = null)))
        deliver(root)
        assertEquals(1, dumps.size)
        assertTrue(dumps.single(), dumps.single().startsWith("nav tree [gaode=2]:"))
        assertTrue(dumps.single(), " lane_sign:t3" in dumps.single())
        assertTrue(dumps.single(), " root_container" in dumps.single())
        // Children come from getChild(), so the walk returns them to the framework pool;
        // the root belongs to onEvent, which recycles it exactly once itself.
        verify(exactly = 1) { lane.recycle() }
        verify(exactly = 1) { root.recycle() }
    }

    @Test fun `dump fires once per maneuver`() {
        deliverManeuver("Поверните направо")
        deliverManeuver("Поверните направо")
        assertEquals(1, dumps.size)
        rewindRateLimit()
        deliverManeuver("Поверните налево")
        assertEquals(2, dumps.size)
        assertTrue(dumps[1], dumps[1].startsWith("nav tree [gaode=1]:"))
    }

    @Test fun `a blinked-out maneuver does not cost a second walk`() {
        deliverManeuver("Поверните направо")
        deliverManeuver(null)               // description gone, distance still on screen
        rewindRateLimit()
        deliverManeuver("Поверните направо")
        assertEquals(1, dumps.size)
    }

    @Test fun `a new maneuver within the interval waits for it`() {
        deliverManeuver("Поверните направо")
        deliverManeuver("Плавно налево")    // seconds later: rate limited
        assertEquals(1, dumps.size)
        rewindRateLimit()
        deliverManeuver("Плавно налево")
        assertEquals(2, dumps.size)
        assertTrue(dumps[1], dumps[1].startsWith("nav tree [gaode=3]:"))
    }

    @Test fun `a throwing child neither breaks the dump nor leaks its siblings`() {
        val first = node("lane_sign", text = "ab")
        val last = node("eta_panel")
        val root = navigatorRoot("Поверните направо", listOf(first, node(null), last))
        every { root.getChild(1) } throws RuntimeException("stale node")
        deliver(root)
        assertEquals(1, dumps.size)
        assertTrue(dumps.single(), " lane_sign:t2" in dumps.single())
        assertTrue(dumps.single(), " eta_panel" in dumps.single())
        verify(exactly = 1) { first.recycle() }
        verify(exactly = 1) { last.recycle() }
    }

    @Test fun `walk stops at the node budget`() {
        val children = (1..400).map { node("n$it") }
        deliver(navigatorRoot("Поверните направо", children))
        val ids = dumps.single().substringAfter("]:").trim().split(" ")
        assertEquals(300, ids.size)   // the root plus 299 children, then the budget is spent
        assertTrue(dumps.single().length <= 3500)
    }

    /** Pretends the rate-limit interval has passed since the last dump. */
    private fun rewindRateLimit() {
        NavA11yFeed.lastDumpMs -= 31_000L
    }

    private fun deliverManeuver(maneuverDesc: String?) {
        deliver(navigatorRoot(maneuverDesc, listOf(node("lane_sign"))))
    }

    private fun deliver(root: AccessibilityNodeInfo) {
        NavA11yFeed.enabled = true
        NavA11yFeed.lastProcessMs = 0L        // beat the 500 ms debounce
        val service = mockk<SteeringWheelKeyService> {
            every { findNavigatorRoot() } returns root
        }
        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        every { event.packageName } returns PKG
        NavA11yFeed.onEvent(service, event)
    }

    private fun navigatorRoot(
        maneuverDesc: String?,
        children: List<AccessibilityNodeInfo>,
    ): AccessibilityNodeInfo {
        val root = node("root_container", children = children)
        every { root.packageName } returns PKG
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/image_maneuverballoon_maneuver") } returns
            maneuverDesc?.let { listOf(descNode(it)) }.orEmpty()
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/text_maneuverballoon_distance") } returns
            listOf(textNode("300"))
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/text_maneuverballoon_metrics") } returns
            listOf(textNode("м"))
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/text_nextstreet") } returns
            listOf(textNode("Ленина"))
        return root
    }

    private fun node(
        id: String?,
        text: String? = null,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.viewIdResourceName } returns id?.let { "$PKG:id/$it" }
        every { node.text } returns text
        every { node.childCount } returns children.size
        children.forEachIndexed { i, child -> every { node.getChild(i) } returns child }
        return node
    }

    private fun textNode(value: String): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text } returns value
        every { node.contentDescription } returns null
        return node
    }

    private fun descNode(value: String): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.contentDescription } returns value
        return node
    }

    private companion object {
        const val PKG = "ru.yandex.yandexnavi"
    }
}
