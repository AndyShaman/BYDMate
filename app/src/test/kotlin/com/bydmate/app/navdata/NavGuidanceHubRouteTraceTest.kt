package com.bydmate.app.navdata

import com.bydmate.app.diagnostics.TraceRecorder
import org.junit.After
import org.junit.Assert.assertEquals
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
    }}
