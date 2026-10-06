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
 *  walk per distinct raw value, sharing the tree walk's floor. German stands for a language no
 *  dictionary reads (English is read since the competitors' dictionaries). */
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
        deliverPhrase("Rechts abbiegen")
        assertEquals(
            listOf("nav maneuver unknown [a11y]: found=1 class=android.widget.ImageView desc=* len=15$NODE"),
            unknownLines,
        )
        assertEquals(1, zeroWalks.size)
        assertTrue(zeroWalks.single(), " lane_sign" in zeroWalks.single())
    }

    @Test fun `a steady unknown phrase stays one line`() {
        repeat(4) {
            deliverPhrase("Rechts abbiegen")
            rewindRateLimit()
        }
        assertEquals(1, unknownLines.size)
        assertEquals(1, zeroWalks.size)
    }

    @Test fun `a blink through a recognised maneuver does not log the phrase again`() {
        deliverPhrase("Rechts abbiegen")
        rewindRateLimit()
        deliverPhrase("Поверните направо")
        rewindRateLimit()
        deliverPhrase("Rechts abbiegen")
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
                "nav maneuver unknown [a11y]: found=1 class=android.widget.ImageView desc=null$NODE",
                "nav maneuver unknown [a11y]: found=1 class=android.widget.ImageView desc=* len=0$NODE",
            ),
            unknownLines,
        )
        assertEquals(3, zeroWalks.size)
    }

    @Test fun `the line shows the node the parse read`() {
        deliver { listOf(maneuverNode(""), maneuverNode("Links halten", cls = "android.view.View")) }
        assertEquals(
            "nav maneuver unknown [a11y]: found=2 class=android.view.View desc=* len=12" +
                " node{id=image_maneuverballoon_maneuver cls=View order=0 sel=false chk=false extras=[]} children=0",
            unknownLines.single(),
        )
    }

    @Test fun `a street after the maneuver words stays out of the line`() {
        deliverPhrase("Rechts abbiegen auf Baker Strasse")
        assertEquals(
            "nav maneuver unknown [a11y]: found=1 class=android.widget.ImageView desc=* len=33$NODE",
            unknownLines.single(),
        )
        assertTrue(lines.none { "Baker" in it || "Strasse" in it })
    }

    @Test fun `the maneuver node's children are listed, a street masked, a maneuver word kept`() {
        val street = propertyNode("$PKG:id/text_street", "android.widget.TextView")
        every { street.text } returns "Baker Strasse"
        val arrow = propertyNode(null, "android.widget.ImageView")
        every { arrow.contentDescription } returns "налево"
        every { arrow.isSelected } returns true
        deliver { listOf(maneuverNode(null, children = listOf(street, arrow))) }
        assertEquals(
            "nav maneuver unknown [a11y]: found=1 class=android.widget.ImageView desc=null" +
                " node{id=image_maneuverballoon_maneuver cls=ImageView order=0 sel=false chk=false extras=[]}" +
                " children=2 [0]{id=text_street cls=TextView text=* len=13 order=0 sel=false chk=false extras=[]}" +
                " [1]{id=? cls=ImageView desc=\"налево\" len=6 order=0 sel=true chk=false extras=[]}",
            unknownLines.single(),
        )
        assertTrue(lines.none { "Strasse" in it })
        verify(exactly = 1) { street.recycle() }
        verify(exactly = 1) { arrow.recycle() }
    }

    @Test fun `phrases with the same mask and length are one value`() {
        deliverPhrase("Rechts abbiegen auf Baker Strasse")
        rewindRateLimit()
        deliverPhrase("Rechts abbiegen auf Baker Avenida")
        assertEquals(1, unknownLines.size)
        assertEquals(1, zeroWalks.size)
    }

    @Test fun `a new value waits for the walk floor`() {
        deliverPhrase("Поверните направо")   // the known maneuver's walk starts the floor
        deliverPhrase("Rechts abbiegen")          // seconds later
        assertTrue(unknownLines.isEmpty())
        rewindRateLimit()
        deliverPhrase("Rechts abbiegen")
        assertEquals(1, unknownLines.size)
    }

    @Test fun `nothing without a distance`() {
        deliver(distance = null) { listOf(maneuverNode("Rechts abbiegen")) }
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
        deliverPhrase("Rechts abbiegen")
        NavA11yFeed.enabled = false
        deliverPhrase("Rechts abbiegen")
        assertEquals(2, unknownLines.size)
    }

    @Test fun `every maneuver node looked up goes back to the pool once`() {
        deliver { listOf(maneuverNode("Rechts abbiegen"), maneuverNode("Links halten")) }
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

    private fun maneuverNode(
        desc: String?,
        cls: String = "android.widget.ImageView",
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val node = propertyNode("$PKG:id/image_maneuverballoon_maneuver", cls)
        every { node.contentDescription } returns desc
        every { node.childCount } returns children.size
        children.forEachIndexed { i, child -> every { node.getChild(i) } returns child }
        return node
    }

    /** A node whose a11y properties read as absent unless a test sets them. */
    private fun propertyNode(id: String?, cls: String): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.viewIdResourceName } returns id
        every { node.className } returns cls
        every { node.text } returns null
        every { node.contentDescription } returns null
        every { node.tooltipText } returns null
        every { node.hintText } returns null
        every { node.paneTitle } returns null
        every { node.extras } returns null
        return node
    }

    private companion object {
        const val PKG = "ru.yandex.yandexnavi"
        const val NODE = " node{id=image_maneuverballoon_maneuver cls=ImageView order=0 sel=false chk=false extras=[]} children=0"
    }
}
