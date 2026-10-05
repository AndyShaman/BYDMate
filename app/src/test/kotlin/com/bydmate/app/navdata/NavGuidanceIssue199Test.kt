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

    @Test fun `issue 199 guidance widgets with empty text keep the route alive, fields untouched`() {
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 800, road = "Ленина"),
            NavGuidanceHub.Source.A11Y, nowMs = T0)
        for (t in T0 + 5_000..T0 + 120_000 step 5_000) timerRead(blankWidgetsRoot(), t)
        val s = NavGuidanceHub.snapshot(T0 + 120_000)
        assertTrue(s.active)
        assertEquals(T0 + 120_000, s.lastUpdateMs)
        assertEquals(800, s.distanceMeters)
        assertEquals("Ленина", s.road)
        assertEquals(T0, s.maneuverGaodeMs)
        // The arrow keeps its own 30 s freshness: an empty read does not refresh it.
        assertEquals(0, s.maneuverGaode)
        // Once such reads stop, the route expires 90 s after the last one.
        assertFalse(NavGuidanceHub.snapshot(T0 + 120_000 + NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1).active)
    }

    @Test fun `issue 199 guidance widgets with empty text do not start a route`() {
        val event = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageName = PKG
        }
        NavA11yFeed.lastProcessMs = 0L
        NavA11yFeed.onEvent(mockk<SteeringWheelKeyService>(relaxed = true) {
            every { findNavigatorRoot() } returns blankWidgetsRoot()
        }, event)
        val s = NavGuidanceHub.snapshot()
        assertFalse(s.active)
        assertEquals(0L, s.lastUpdateMs)
    }

    @Test fun `issue 199 fixture - the balloon gone before the turn leaves no arrow at the turn`() {
        val replay = Replay.load()
        val s = drive(replay, gapWindow = { gapRoot() })
        // The log: the arrow expired at 15:11:59 and went out as 0 while the driver signalled right.
        assertEquals(0, replay.frameGaodeBeforeTurn)
        assertEquals(0, s.maneuverGaode)
        assertTrue(s.active)
    }

    @Test fun `issue 199 fixture - the second layout of the navigator keeps the arrow at the turn`() {
        val replay = Replay.load()
        val s = drive(replay, gapWindow = {
            gapRoot().also { secondLayout(it, desc = "Поверните направо", distance = "150", unit = "м") }
        })
        assertEquals(NavManeuverCodes.GAODE_RIGHT, s.maneuverGaode)
        assertEquals(150, s.distanceMeters)
    }

    @Test fun `issue 199 the balloon wins over the second layout when both are on screen`() {
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 1, distanceMeters = 900), NavGuidanceHub.Source.A11Y, nowMs = T0)
        val root = balloonRoot(desc = "Поверните налево", distance = "400", unit = "м")
        secondLayout(root, desc = "Поверните направо", distance = "2", unit = "км")
        timerRead(root, T0 + 5_000)
        val s = NavGuidanceHub.snapshot(T0 + 5_000)
        assertEquals(NavManeuverCodes.GAODE_LEFT, s.maneuverGaode)
        assertEquals(400, s.distanceMeters)
    }

    @Test fun `issue 199 the upcoming maneuver alone is not read as the next one`() {
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 1, distanceMeters = 900), NavGuidanceHub.Source.A11Y, nowMs = T0)
        val root = gapRoot()
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/next_upcoming_maneuver") } answers
            { listOf(descNode("Поверните направо")) }
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/next_upcoming_maneuver_distance") } answers
            { listOf(textNode("300 м")) }
        timerRead(root, T0 + 5_000)
        val s = NavGuidanceHub.snapshot(T0 + 5_000)
        assertEquals(NavManeuverCodes.GAODE_LEFT, s.maneuverGaode)
        assertEquals(900, s.distanceMeters)
        assertEquals(T0, s.maneuverGaodeMs)
    }

    @Test fun `issue 199 second layout widgets with empty text keep the route alive`() {
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 800), NavGuidanceHub.Source.A11Y, nowMs = T0)
        val root = gapRoot().also { secondLayout(it, desc = "", distance = "", unit = "") }
        timerRead(root, T0 + 60_000)
        val s = NavGuidanceHub.snapshot(T0 + 60_000)
        assertEquals(T0 + 60_000, s.lastUpdateMs)
        assertEquals(800, s.distanceMeters)
    }

    /** Feeds the fixture's drive: its balloon read, the balloon window every 5 s up to the gap, then
     *  [gapWindow] every 5 s up to the turn signal; returns the hub at the signal. */
    private fun drive(r: Replay, gapWindow: () -> AccessibilityNodeInfo): NavGuidanceHub.Snapshot {
        assertEquals(NavManeuverCodes.GAODE_RIGHT, r.readGaode)
        NavGuidanceHub.update(NavGuidance(maneuverGaode = r.readGaode, distanceMeters = r.readDistance),
            NavGuidanceHub.Source.A11Y, nowMs = r.readMs)
        var t = r.readMs + 5_000
        while (t < r.gapMs) {
            timerRead(balloonRoot("Поверните направо", "${r.readDistance}", "м"), t)
            t += 5_000
        }
        t = r.gapMs
        while (t <= r.turnMs) {
            timerRead(gapWindow(), t)
            t += 5_000
        }
        return NavGuidanceHub.snapshot(r.turnMs)
    }

    /** The fixture's drive: the balloon read (maneuver and distance from the frame that followed it),
     *  the first read without it, the right-turn signal and the frame that was out at that moment. */
    private class Replay(
        val readMs: Long,
        val readGaode: Int,
        val readDistance: Int,
        val gapMs: Long,
        val turnMs: Long,
        val frameGaodeBeforeTurn: Int,
    ) {
        companion object {
            private val TIME = Regex("""^\d\d-\d\d (\d\d):(\d\d):(\d\d)\.(\d{3}) """)
            private val FRAME = Regex("""frame gaode=(\d+) dist=(\d+)m""")

            fun load(): Replay {
                val lines = requireNotNull(Replay::class.java.classLoader?.getResource("navdata/issue199-han-gap-at-turn.txt"))
                    .readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
                fun ms(line: String): Long {
                    val g = requireNotNull(TIME.find(line)).groupValues.drop(1).map { it.toLong() }
                    return ((g[0] * 60 + g[1]) * 60 + g[2]) * 1000 + g[3]
                }
                val read = lines.indexOfFirst { "guidance read again" in it }
                val readFrame = requireNotNull(FRAME.find(lines.drop(read).first { "HudPushLoop" in it }))
                val gap = lines.first { "no-guidance read" in it }
                // The window the log saw: none of the balloon ids, the status panel and the eta.
                assertTrue(gap.endsWith("ids[maneuver=0 distance=0 metrics=0 nextstreet=0 status=1 eta=1]"))
                val turn = lines.indexOfFirst { "turn from=off to=right" in it }
                val frameBeforeTurn = lines.take(turn).last { "HudPushLoop" in it }
                return Replay(
                    readMs = ms(lines[read]),
                    readGaode = readFrame.groupValues[1].toInt(),
                    readDistance = readFrame.groupValues[2].toInt(),
                    gapMs = ms(gap),
                    turnMs = ms(lines[turn]),
                    frameGaodeBeforeTurn = requireNotNull(FRAME.find(frameBeforeTurn)).groupValues[1].toInt(),
                )
            }
        }
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

    /** The maneuver balloon is in the tree, its icon and distance carry no text (the Han log). */
    private fun blankWidgetsRoot(): AccessibilityNodeInfo {
        val root = navigatorRoot(withGuidance = false)
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/image_maneuverballoon_maneuver") } answers
            { listOf(mockk<AccessibilityNodeInfo>(relaxed = true) { every { contentDescription } returns "" }) }
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/text_maneuverballoon_distance") } answers
            { listOf(textNode("")) }
        return root
    }

    /** The classic maneuver balloon with text. */
    private fun balloonRoot(desc: String, distance: String, unit: String): AccessibilityNodeInfo {
        val root = navigatorRoot(withGuidance = false)
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/image_maneuverballoon_maneuver") } answers
            { listOf(descNode(desc)) }
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/text_maneuverballoon_distance") } answers
            { listOf(textNode(distance)) }
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/text_maneuverballoon_metrics") } answers
            { listOf(textNode(unit)) }
        return root
    }

    /** The window of the log's gap: no balloon ids, the status panel and the eta are there. */
    private fun gapRoot(): AccessibilityNodeInfo {
        val root = navigatorRoot(withGuidance = false)
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/status_panel_text") } answers
            { listOf(textNode("M1")) }
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/textview_eta_time") } answers
            { listOf(textNode("12 мин")) }
        return root
    }

    /** The Navigator's second guidance layout on [root]. */
    private fun secondLayout(root: AccessibilityNodeInfo, desc: String, distance: String, unit: String) {
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/next_maneuver_image") } answers
            { listOf(descNode(desc)) }
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/next_maneuver_distance_value") } answers
            { listOf(textNode(distance)) }
        every { root.findAccessibilityNodeInfosByViewId("$PKG:id/next_maneuver_distance_unit") } answers
            { listOf(textNode(unit)) }
    }

    private fun descNode(value: String): AccessibilityNodeInfo =
        mockk<AccessibilityNodeInfo>(relaxed = true) { every { contentDescription } returns value }

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
