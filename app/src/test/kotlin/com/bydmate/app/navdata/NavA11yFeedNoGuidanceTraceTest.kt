package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.bydmate.app.cluster.SteeringWheelKeyService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Issue #199: a window read without guidance while a route is guided logs what it saw, once per
 *  streak, with the id walk behind a 60 s floor; the read that brings guidance back says after how
 *  long; a timer read that finds no navigator window says so on the edge. Counts only, no text. */
@Suppress("DEPRECATION")   // recycle() is the pooling contract these tests assert
@RunWith(RobolectricTestRunner::class)
class NavA11yFeedNoGuidanceTraceTest {

    private val lines = mutableListOf<String>()
    private val realSink = NavA11yFeed.traceSink
    // Every node handed out by an id lookup, so the test can check each one went back.
    private val issued = mutableListOf<AccessibilityNodeInfo>()

    private val readLines get() = lines.filter { it.startsWith("no-guidance read:") }
    private val walks get() = lines.filter { it.startsWith("nav tree [no-guidance]:") }
    private val againLines get() = lines.filter { it.startsWith("guidance read again") }
    private val notFoundLines get() = lines.filter { it == "timer read: navigator window not found" }
    private val windowLines get() = lines.filter { it.startsWith("no-guidance window:") }

    @Before fun installSink() {
        NavA11yFeed.enabled = false   // a fresh episode
        NavA11yFeed.traceSink = { lines.add(it) }
        NavGuidanceHub.reset()
    }

    @After fun tearDown() {
        NavA11yFeed.traceSink = realSink
        NavA11yFeed.enabled = false
        NavGuidanceHub.reset()
    }

    @Test fun `a lost guidance logs the window, the counts and one id walk`() {
        armGuidance()
        val root = emptyGuidanceRoot()
        timerRead(root, T0 + 1_000, windows = listOf(emptyGuidanceRoot(), emptyGuidanceRoot(windowId = 9)))
        assertEquals(
            listOf("no-guidance read: src=timer display=? window=7 type=1 active=true focused=false navWindows=2 " +
                "nodes=2 ids[maneuver=1 distance=1 metrics=0 nextstreet=0 status=1 eta=1]"),
            readLines,
        )
        assertEquals(1, walks.size)
        assertTrue(walks.single(), " status_panel_text:t7" in walks.single())
        // No screen text in any new line: the status panel and the ETA carry the route.
        lines.forEach { assertFalse(it, "Маршрут" in it || "12:30" in it) }
        issued.forEach { verify(exactly = 1) { it.recycle() } }
        verify(exactly = 1) { root.recycle() }
    }

    @Test @Config(sdk = [32])
    fun `the display is read where the window info has one`() {
        armGuidance()
        timerRead(emptyGuidanceRoot(displayId = 2), T0 + 1_000)
        assertTrue(readLines.single(), " display=2 window=7 " in readLines.single())
    }

    @Test fun `a root without a window info prints question marks and does not crash`() {
        armGuidance()
        val root = emptyGuidanceRoot()
        every { root.window } returns null
        every { root.windowId } throws IllegalStateException("stale")
        timerRead(root, T0 + 1_000, windows = null)
        assertTrue(readLines.single(),
            readLines.single().startsWith("no-guidance read: src=timer display=? window=? type=? active=? focused=? navWindows=? "))
    }

    @Test fun `a streak logs one line, the read that ends it says after how long`() {
        armGuidance()
        timerRead(emptyGuidanceRoot(), T0 + 1_000)
        timerRead(emptyGuidanceRoot(), T0 + 6_000)
        assertEquals(1, readLines.size)
        timerRead(guidanceRoot(), T0 + 9_500)
        timerRead(guidanceRoot(), T0 + 14_500)
        assertEquals(listOf("guidance read again after 8500 ms (src=timer)"), againLines)
    }

    @Test fun `an event read is labelled as such`() {
        armGuidance(System.currentTimeMillis())
        eventRead(emptyGuidanceRoot())
        eventRead(guidanceRoot())
        assertTrue(readLines.single(), readLines.single().startsWith("no-guidance read: src=event "))
        assertTrue(againLines.single(), Regex("""guidance read again after \d+ ms \(src=event\)""").matches(againLines.single()))
    }

    @Test fun `a new streak logs a line again, its walk waits for 60 s`() {
        armGuidance()
        timerRead(emptyGuidanceRoot(), T0 + 1_000)
        timerRead(guidanceRoot(), T0 + 6_000)
        timerRead(emptyGuidanceRoot(), T0 + 11_000)
        assertEquals(2, readLines.size)
        assertEquals(1, walks.size)
        timerRead(guidanceRoot(), T0 + 16_000)
        timerRead(emptyGuidanceRoot(), T0 + 61_000)
        assertEquals(3, readLines.size)
        assertEquals(2, walks.size)
    }

    @Test fun `no route guided, no line`() {
        NavA11yFeed.enabled = true
        NavA11yFeed.lastProcessMs = 0L
        eventRead(emptyGuidanceRoot())
        assertTrue(lines.isEmpty())
    }

    @Test fun `a missing navigator window on a timer read is said once until a window is found`() {
        armGuidance()
        timerRead(null, T0 + 1_000)
        timerRead(null, T0 + 6_000)
        assertEquals(1, notFoundLines.size)
        timerRead(guidanceRoot(), T0 + 11_000)
        timerRead(null, T0 + 16_000)
        assertEquals(2, notFoundLines.size)
    }

    @Test fun `a traced read leaves the hub as it was`() {
        armGuidance()
        timerRead(emptyGuidanceRoot(), T0 + 1_000)
        val s = NavGuidanceHub.snapshot(T0 + 15_000)
        assertTrue(s.active)
        assertEquals(T0, s.lastUpdateMs)
        assertEquals(500, s.distanceMeters)
    }

    @Test fun `every navigator window gets a line, the one that was read is marked`() {
        armGuidance()
        val read = emptyGuidanceRoot(windowId = 7)
        val windows = listOf(emptyGuidanceRoot(windowId = 7), widgetsRoot(windowId = 9))
        timerRead(read, T0 + 1_000, windows = windows)
        assertTrue(readLines.single(), " navWindows=2 " in readLines.single())
        assertEquals(
            listOf(
                "no-guidance window: display=? window=7 type=1 active=true focused=false read=true " +
                    "ids[maneuver=1 distance=1 metrics=0 nextstreet=0 status=1 eta=1]",
                "no-guidance window: display=? window=9 type=1 active=false focused=false read=false " +
                    "ids[maneuver=1 distance=1 metrics=1 nextstreet=1 status=0 eta=0]",
            ),
            windowLines,
        )
        // The window lines follow the read line, before the id walk.
        assertEquals(readLines.single(), lines[lines.indexOf(windowLines.first()) - 1])
        lines.forEach { assertFalse(it, "Ленина" in it || "Маршрут" in it) }
        windows.forEach { verify(exactly = 1) { it.recycle() } }
        verify(exactly = 1) { read.recycle() }
        issued.forEach { verify(exactly = 1) { it.recycle() } }
    }

    @Test fun `one navigator window, one window line`() {
        armGuidance()
        timerRead(emptyGuidanceRoot(), T0 + 1_000)
        assertEquals(1, windowLines.size)
        assertTrue(windowLines.single(), " read=true " in windowLines.single())
    }

    @Test fun `an unreadable window list writes the read line and no window line`() {
        armGuidance()
        timerRead(emptyGuidanceRoot(), T0 + 1_000, windows = null)
        assertEquals(1, readLines.size)
        assertTrue(readLines.single(), " navWindows=? " in readLines.single())
        assertTrue(windowLines.isEmpty())
    }

    @Test fun `at most 6 window lines, every window recycled`() {
        armGuidance()
        val windows = (1..8).map { emptyGuidanceRoot(windowId = 10 + it) }
        timerRead(emptyGuidanceRoot(), T0 + 1_000, windows = windows)
        assertTrue(readLines.single(), " navWindows=8 " in readLines.single())
        assertEquals(6, windowLines.size)
        windows.forEach { verify(exactly = 1) { it.recycle() } }
    }

    /** Active a11y guidance in the hub (500 m ahead) at [atMs]. */
    private fun armGuidance(atMs: Long = T0) {
        NavA11yFeed.enabled = true
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 500),
            NavGuidanceHub.Source.A11Y, nowMs = atMs)
    }

    /** [windows]: the navigator window roots the service lists, null when the list is unreadable. */
    private fun timerRead(
        root: AccessibilityNodeInfo?,
        atMs: Long,
        windows: List<AccessibilityNodeInfo>? = listOf(emptyGuidanceRoot()),
    ) {
        NavA11yFeed.lastProcessMs = 0L
        NavA11yFeed.onTimer(service(root, windows), atMs)
    }

    private fun eventRead(root: AccessibilityNodeInfo) {
        NavA11yFeed.lastProcessMs = 0L
        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.eventType } returns AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        every { event.packageName } returns PKG
        NavA11yFeed.onEvent(service(root, listOf(emptyGuidanceRoot())), event)
    }

    private fun service(root: AccessibilityNodeInfo?, windows: List<AccessibilityNodeInfo>?) =
        mockk<SteeringWheelKeyService> {
            every { findNavigatorRoot() } returns root
            every { navigatorWindowRoots() } returns windows
        }

    /** A navigator window whose guidance widgets are there but blank: the parse reads no guidance. */
    private fun emptyGuidanceRoot(displayId: Int? = null, windowId: Int = 7): AccessibilityNodeInfo {
        val status = node(id = "status_panel_text", text = "Маршрут")
        val root = node(id = "root_container", children = listOf(status))
        withWindow(root, windowId, active = true, displayId = displayId)
        lookup(root, "image_maneuverballoon_maneuver") { descNode("") }
        lookup(root, "text_maneuverballoon_distance") { textNode("") }
        lookup(root, "status_panel_text") { textNode("Маршрут") }
        lookup(root, "textview_eta_time") { textNode("12:30") }
        return root
    }

    /** A second navigator window that does hold the guidance widgets. */
    private fun widgetsRoot(windowId: Int): AccessibilityNodeInfo {
        val root = node(id = "root_container")
        withWindow(root, windowId, active = false, displayId = null)
        lookup(root, "image_maneuverballoon_maneuver") { descNode("Поверните направо") }
        lookup(root, "text_maneuverballoon_distance") { textNode("300") }
        lookup(root, "text_maneuverballoon_metrics") { textNode("м") }
        lookup(root, "text_nextstreet") { textNode("Ленина") }
        return root
    }

    private fun withWindow(root: AccessibilityNodeInfo, windowId: Int, active: Boolean, displayId: Int?) {
        every { root.packageName } returns PKG
        every { root.windowId } returns windowId
        val window = mockk<AccessibilityWindowInfo>(relaxed = true)
        every { window.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { window.isActive } returns active
        every { window.isFocused } returns false
        // getDisplayId() exists from API 30 on, so it is stubbed only where the test runs there.
        if (displayId != null) every { window.displayId } returns displayId
        every { root.window } returns window
        every { root.findAccessibilityNodeInfosByViewId(any()) } answers { emptyList() }
    }

    private fun guidanceRoot(): AccessibilityNodeInfo {
        val root = node(id = "root_container")
        every { root.packageName } returns PKG
        every { root.findAccessibilityNodeInfosByViewId(any()) } answers { emptyList() }
        lookup(root, "text_maneuverballoon_distance") { textNode("300") }
        lookup(root, "text_maneuverballoon_metrics") { textNode("м") }
        return root
    }

    /** Each lookup of [id] hands out fresh nodes, all recorded in [issued]. */
    private fun lookup(root: AccessibilityNodeInfo, id: String, make: () -> AccessibilityNodeInfo) {
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/$id") } answers { listOf(make().also { issued.add(it) }) }
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
        const val T0 = 1_000_000L
    }
}
