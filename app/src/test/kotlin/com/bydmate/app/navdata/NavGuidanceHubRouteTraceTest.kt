package com.bydmate.app.navdata

import com.bydmate.app.diagnostics.TraceRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The hub's route lines in the trace: start, end with its reason, one summary of counters. */
class NavGuidanceHubRouteTraceTest {

    @get:Rule val trace = TraceRecorder()

    @Before fun reset() {
        NavGuidanceHub.reset()
        NavGuidanceHub.hudWay = 2
    }

    @After fun after() {
        NavGuidanceHub.reset()
        NavGuidanceHub.hudWay = 0
    }

    private val a11y = NavGuidanceHub.Source.A11Y

    private fun hud() = trace.events().filter { it.startsWith("hud") }.map { it.substringAfter("hud").trim() }

    @Test fun `a route ended by silence is three lines, the summary linked to its end`() {
        NavGuidanceHub.update(NavGuidance(2, 300, "Main St"), a11y, nowMs = 1_000)
        NavGuidanceHub.keepAlive(nowMs = 6_000)
        NavGuidanceHub.countA11yNoGuidance()
        NavGuidanceHub.countFrame(0)
        NavGuidanceHub.countFrame(0)
        NavGuidanceHub.countFrame(-2)
        NavGuidanceHub.update(NavGuidance(0, 200, "Main St"), a11y, nowMs = 10_000)  // distance, no maneuver
        NavGuidanceHub.update(NavGuidance(3, 150, "Main St"), a11y, nowMs = 11_000)
        repeat(20) { NavGuidanceHub.snapshot(nowMs = 12_000L + it) }               // ticks write nothing
        NavGuidanceHub.snapshot(nowMs = 102_000)
        val lines = hud()
        assertEquals(3, lines.size)
        assertEquals("guidance-on src=a11y #1", lines[0])
        assertEquals("guidance-off reason=silence-90s route_s=10 last_a11y_ago_s=91 last_notif_ago_s=na #2", lines[1])
        assertEquals(
            "route-summary way=2 dur_s=10 a11y_guid=3 a11y_noguid=1 kept=1 blind_ms=0 notif_rich=0 notif_ignored=0 " +
                "notif_empty=0 maneuvers=2 unknown=1 expiries=0 drops=0 frames=3 rc={-2:1,0:2} max_gap_s=5 #3 by=#2",
            lines[2],
        )
        assertEquals(2L, NavGuidanceHub.lastOffTraceId)
    }

    @Test fun `notification posts are counted taken, ignored behind a11y, or empty`() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(maneuverGaode = 2, distanceMeters = 300), nowMs = 1_000)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(), nowMs = 2_000)
        NavGuidanceHub.countNotifEmpty()
        NavGuidanceHub.update(NavGuidance(2, 250), a11y, nowMs = 3_000)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(maneuverGaode = 2, distanceMeters = 240), nowMs = 4_000)
        NavGuidanceHub.deactivateFromNotificationGrace(nowMs = 95_000)
        val lines = hud()
        assertEquals("guidance-on src=notification #1", lines[0])
        assertTrue(lines[1], lines[1].startsWith("guidance-off reason=notif-grace route_s=3 last_a11y_ago_s=92 last_notif_ago_s=91 "))
        assertTrue(lines[2], lines[2].contains(" a11y_guid=1 ") && lines[2].contains(" notif_rich=1 notif_ignored=1 notif_empty=2 "))
    }

    @Test fun `a maneuver that expires is one line and counted`() {
        NavGuidanceHub.update(NavGuidance(2, 300), a11y, nowMs = 1_000)
        NavGuidanceHub.keepAlive(nowMs = 20_000)
        NavGuidanceHub.snapshot(nowMs = 31_500)
        NavGuidanceHub.snapshot(nowMs = 32_000)
        assertEquals(listOf("guidance-on src=a11y #1", "maneuver-expired after_s=30 src=a11y #2"), hud())
        assertTrue(NavGuidanceHub.routeSummary(nowMs = 32_000).contains(" expiries=1 "))
    }

    @Test fun `counters reset per route and stay frozen between routes`() {
        NavGuidanceHub.update(NavGuidance(2, 300), a11y, nowMs = 1_000)
        NavGuidanceHub.countFrame(0)
        NavGuidanceHub.snapshot(nowMs = 100_000)
        NavGuidanceHub.countFrame(0)                // no route: not counted
        NavGuidanceHub.countA11yNoGuidance()
        val frozen = NavGuidanceHub.routeSummary(nowMs = 200_000)
        assertTrue(frozen, frozen.contains(" frames=1 ") && frozen.contains(" a11y_noguid=0 ") && frozen.contains(" dur_s=0 "))
        NavGuidanceHub.update(NavGuidance(3, 300), a11y, nowMs = 300_000)
        assertTrue(NavGuidanceHub.routeSummary(nowMs = 305_000).contains(" dur_s=5 a11y_guid=1 "))
        assertTrue(NavGuidanceHub.routeSummary(nowMs = 305_000).contains(" frames=0 "))
    }

    @Test fun `a blind a11y feed counts from the route's start when it was blind before`() {
        NavGuidanceHub.a11yBlind(true, nowMs = 500)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(maneuverGaode = 2, distanceMeters = 300), nowMs = 1_000)
        NavGuidanceHub.a11yBlind(false, nowMs = 4_000)
        NavGuidanceHub.a11yBlind(true, nowMs = 6_000)
        assertTrue(NavGuidanceHub.routeSummary(nowMs = 7_000).contains(" blind_ms=4000 "))
    }

    @Test fun `no route yet says so`() {
        assertEquals("(no route)", NavGuidanceHub.routeSummary())
    }
}
