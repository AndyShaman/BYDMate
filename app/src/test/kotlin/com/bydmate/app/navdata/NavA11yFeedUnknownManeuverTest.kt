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

/** A route with a distance but maneuver code 0: one line with the raw maneuver node and one id
 *  walk per distinct raw value, sharing the tree walk's floor. */
@Suppress("DEPRECATION")   // recycle() is the pooling contract these tests assert
@RunWith(RobolectricTestRunner::class)
class NavA11yFeedUnknownManeuverTest {

    private val lines = mutableListOf<String>()
    private val realSink = NavA11yFeed.treeDumpSink
    // Every maneuver node handed out by a lookup, so the test can check each one went back.
    private val issued = mutableListOf<AccessibilityNodeInfo>()

    private val unknownLines get() = lines.filter { it.startsWith("nav maneuver unknown") }
    private val zeroWalks get() = lines.filter { it.startsWith("nav tree [gaode=0]:") }

    @Before fun installSink() {
        NavA11yFeed.enabled = false   // a fresh episode
        NavA11yFeed.treeDumpSink = { lines.add(it) }
        NavGuidanceHub.reset()
    }

    @After fun tearDown() {
        NavA11yFeed.treeDumpSink = realSink
        NavA11yFeed.enabled = false
        NavGuidanceHub.reset()
    }

    @Test fun `an unrecognised maneuver logs its raw node once, with one id walk`() {
        deliverPhrase("Turn right")
        assertEquals(
            listOf("nav maneuver unknown [a11y]: found=1 class=android.widget.ImageView desc=\"Turn right\" len=10"),
            unknownLines,
        )
        assertEquals(1, zeroWalks.size)
        assertTrue(zeroWalks.single(), " lane_sign" in zeroWalks.single())
    }

    @Test fun `a steady unknown phrase stays one line`() {
        repeat(4) {
            deliverPhrase("Turn right")
            rewindRateLimit()
        }
        assertEquals(1, unknownLines.size)
        assertEquals(1, zeroWalks.size)
    }

    @Test fun `a blink through a recognised maneuver does not log the phrase again`() {
        deliverPhrase("Turn right")
        rewindRateLimit()
        deliverPhrase("Поверните направо")
        rewindRateLimit()
        deliverPhrase("Turn right")
        assertEquals(1, unknownLines.size)
        assertEquals(1, zeroWalks.size)
    }

    @Test fun `a recognised maneuver logs no unknown line`() {
        deliverPhrase("Поверните направо")
        assertTrue(unknownLines.isEmpty())
        assertTrue(zeroWalks.isEmpty())
    }

    @Test fun `an absent node, a null and an empty description are three values`() {
        deliver { emptyList() }
        rewindRateLimit()
        deliver { listOf(maneuverNode(null)) }
        rewindRateLimit()
        deliver { listOf(maneuverNode("")) }
        assertEquals(
            listOf(
                "nav maneuver unknown [a11y]: found=0",
                "nav maneuver unknown [a11y]: found=1 class=android.widget.ImageView desc=null",
                "nav maneuver unknown [a11y]: found=1 class=android.widget.ImageView desc=\"\" len=0",
            ),
            unknownLines,
        )
        assertEquals(3, zeroWalks.size)
    }

    @Test fun `the line shows the node the parse read`() {
        deliver { listOf(maneuverNode(""), maneuverNode("Keep left", cls = "android.view.View")) }
        assertEquals(
            "nav maneuver unknown [a11y]: found=2 class=android.view.View desc=\"Keep left\" len=9",
            unknownLines.single(),
        )
    }

    @Test fun `a long description is cut to its head`() {
        deliverPhrase("x".repeat(200))
        assertTrue(unknownLines.single(), unknownLines.single().endsWith("desc=\"" + "x".repeat(48) + "\"… len=200"))
    }

    @Test fun `a street after the maneuver words stays out of the line`() {
        deliverPhrase("Turn right onto Baker Street")
        assertEquals(
            "nav maneuver unknown [a11y]: found=1 class=android.widget.ImageView desc=\"Turn right onto Baker\"… len=28",
            unknownLines.single(),
        )
        assertTrue(lines.none { "Street" in it })
    }

    @Test fun `phrases with the same head and length are one value`() {
        deliverPhrase("Turn right onto Baker Street")
        rewindRateLimit()
        deliverPhrase("Turn right onto Baker Avenue")
        assertEquals(1, unknownLines.size)
        assertEquals(1, zeroWalks.size)
    }

    @Test fun `a new value waits for the walk floor`() {
        deliverPhrase("Поверните направо")   // the known maneuver's walk starts the floor
        deliverPhrase("Turn right")          // seconds later
        assertTrue(unknownLines.isEmpty())
        rewindRateLimit()
        deliverPhrase("Turn right")
        assertEquals(1, unknownLines.size)
    }

    @Test fun `nothing without a distance`() {
        deliver(distance = null) { listOf(maneuverNode("Turn right")) }
        assertTrue(lines.isEmpty())
    }

    @Test fun `nothing when the navigator shows no route`() {
        val root = node("root_container", children = listOf(node("lane_sign")))
        every { root.packageName } returns PKG
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        deliverRoot(root)
        assertTrue(lines.isEmpty())
    }

    @Test fun `turning the feed off and on logs a value again`() {
        deliverPhrase("Turn right")
        NavA11yFeed.enabled = false
        deliverPhrase("Turn right")
        assertEquals(2, unknownLines.size)
    }

    @Test fun `every maneuver node looked up goes back to the pool once`() {
        deliver { listOf(maneuverNode("Turn right"), maneuverNode("Keep left")) }
        assertEquals(1, unknownLines.size)
        assertEquals(4, issued.size)   // the parse's lookup and the probe's, two nodes each
        issued.forEach { verify(exactly = 1) { it.recycle() } }
    }

    /** Pretends the walk floor has passed since the last walk. */
    private fun rewindRateLimit() {
        NavA11yFeed.lastDumpMs -= 31_000L
    }

    private fun deliverPhrase(desc: String) = deliver { listOf(maneuverNode(desc)) }

    /** One navigator read; [maneuverNodes] runs on every lookup, as the framework hands out fresh nodes. */
    private fun deliver(distance: String? = "300", maneuverNodes: () -> List<AccessibilityNodeInfo>) {
        val root = node("root_container", children = listOf(node("lane_sign")))
        every { root.packageName } returns PKG
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/image_maneuverballoon_maneuver") } answers
            { maneuverNodes().also { issued.addAll(it) } }
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/text_maneuverballoon_distance") } returns
            listOfNotNull(distance?.let { textNode(it) })
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/text_maneuverballoon_metrics") } returns
            listOf(textNode("м"))
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/text_nextstreet") } returns
            listOf(textNode("Ленина"))
        deliverRoot(root)
    }

    private fun deliverRoot(root: AccessibilityNodeInfo) {
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

    private fun node(id: String?, children: List<AccessibilityNodeInfo> = emptyList()): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.viewIdResourceName } returns id?.let { "$PKG:id/$it" }
        every { node.text } returns null
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

    private fun maneuverNode(desc: String?, cls: String = "android.widget.ImageView"): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.contentDescription } returns desc
        every { node.className } returns cls
        return node
    }

    private companion object {
        const val PKG = "ru.yandex.yandexnavi"
    }
}
