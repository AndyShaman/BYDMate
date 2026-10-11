package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #294 and #301: when a held maneuver stops being true. Fixtures are the reads of the two logs
 *  (bydmate_logs_20261009_084313, bydmate_logs_20261009_101708), street names made up. */
class NavGuidanceHubStaleArrowTest {

    @Before fun reset() = NavGuidanceHub.reset()

    /** One accessibility read of the Navigator window as the #294 car gave it: no next-street
     *  widget, so the road is the status panel's current street (a fallback). */
    private fun a11yRead(gaode: Int, dist: Int, atMs: Long) = NavGuidanceHub.update(
        NavGuidance(
            maneuverGaode = gaode, distanceMeters = dist, road = "Maple Avenue",
            maneuverRaw = if (gaode > 0) "desc:key=поверните направо" else "",
            roadIsNextStreet = false,
        ),
        NavGuidanceHub.Source.A11Y, nowMs = atMs,
    )

    @Test fun `a read without a maneuver whose distance jumped up drops the held arrow (#294)`() {
        val t0 = 1_000_000L
        a11yRead(gaode = 2, dist = 20, atMs = t0)            // 08:45:11 right turn, 20 m
        a11yRead(gaode = 2, dist = 10, atMs = t0 + 13_400)   // 08:45:24.9 the last read with the arrow
        // 08:45:25.674: the maneuver image is gone from the window (found=0), 150 m to the U-turn.
        a11yRead(gaode = 0, dist = 150, atMs = t0 + 14_100)
        val s = NavGuidanceHub.snapshot(nowMs = t0 + 14_100)
        assertEquals(150, s.distanceMeters)
        assertEquals("the right turn is behind, its arrow must not go out with the next maneuver's metres",
            0, s.maneuverGaode)
    }

    @Test fun `a source that is only silent does not expire the held arrow (#301)`() {
        val t0 = 1_000_000L
        // 10:19:24 the last notification before a 44 s gap at a standstill: right turn, 50 m.
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(
            maneuverGaode = 2, distanceMeters = 50, etaSeconds = 180, totalDistMeters = 930,
            maneuverRaw = "res:notification_right_sdl"), nowMs = t0)
        // 10:19:54 nothing was read in between; 10:20:08 the same turn comes back at 30 m.
        val s = NavGuidanceHub.snapshot(nowMs = t0 + NavGuidanceHub.MANEUVER_TIMEOUT_MS + 1_000)
        assertTrue(s.active)
        assertEquals("no read said the turn was over", 2, s.maneuverGaode)
    }

    private fun notifRead(gaode: Int, dist: Int, atMs: Long) = NavGuidanceHub.updateFromNotification(
        NavGuidanceHub.RichUpdate(maneuverGaode = gaode, distanceMeters = dist,
            maneuverRaw = if (gaode > 0) "res:notification_right_sdl" else ""),
        nowMs = atMs,
    )

    @Test fun `the notification lane drops the held arrow on its own distance jump too`() {
        val t0 = 1_000_000L
        notifRead(gaode = 2, dist = 100, atMs = t0)
        notifRead(gaode = 0, dist = 200, atMs = t0 + 2_000)   // exactly twice
        val s = NavGuidanceHub.snapshot(nowMs = t0 + 2_000)
        assertEquals(200, s.distanceMeters)
        assertEquals(0, s.maneuverGaode)
    }

    @Test fun `a distance jump read after the timeout leaves the held arrow to the expiry`() {
        val t0 = 1_000_000L
        notifRead(gaode = 2, dist = 20, atMs = t0)
        // Nothing for 42 s, then the next maneuver's metres: the read arms the expiry, the snapshot takes the arrow.
        val t1 = t0 + NavGuidanceHub.MANEUVER_TIMEOUT_MS + 12_000
        notifRead(gaode = 0, dist = 3200, atMs = t1)
        val s = NavGuidanceHub.snapshot(nowMs = t1)
        assertEquals(3200, s.distanceMeters)
        assertEquals(0, s.maneuverGaode)
        val summary = NavGuidanceHub.routeSummary(t1)
        assertTrue(summary, summary.contains("expiries=1") && summary.contains("drops=0"))
    }

    // What the two rules must not break.

    @Test fun `a rounding step above a kilometre keeps the held arrow`() {
        val t0 = 1_000_000L
        a11yRead(gaode = 2, dist = 1100, atMs = t0)
        a11yRead(gaode = 0, dist = 1200, atMs = t0 + 1_000)   // standing at a 100 m rounding boundary
        val s = NavGuidanceHub.snapshot(nowMs = t0 + 1_000)
        assertEquals(1200, s.distanceMeters)
        assertEquals(2, s.maneuverGaode)
    }

    @Test fun `a distance that rose by the margin but did not double keeps the held arrow`() {
        val t0 = 1_000_000L
        a11yRead(gaode = 2, dist = 100, atMs = t0)
        a11yRead(gaode = 0, dist = 150, atMs = t0 + 1_000)
        val s = NavGuidanceHub.snapshot(nowMs = t0 + 1_000)
        assertEquals(150, s.distanceMeters)
        assertEquals(2, s.maneuverGaode)
    }

    @Test fun `a distance jump read by the other source keeps the held arrow`() {
        val t0 = 1_000_000L
        a11yRead(gaode = 2, dist = 20, atMs = t0)
        // Past the a11y priority window, so the notification is merged. 150 m is both 50 m further
        // and more than twice 20 m: only the source differs from a jump that drops the arrow.
        val t1 = t0 + NavGuidanceHub.A11Y_PRIORITY_MS + 1_000
        notifRead(gaode = 0, dist = 150, atMs = t1)
        val s = NavGuidanceHub.snapshot(nowMs = t1)
        assertEquals(150, s.distanceMeters)
        assertEquals(2, s.maneuverGaode)
    }

    @Test fun `a distance that rose by less than the margin keeps the held arrow`() {
        val t0 = 1_000_000L
        // 40 m is four times 10 m, so only the 50 m margin keeps the arrow here.
        a11yRead(gaode = 2, dist = 10, atMs = t0)
        a11yRead(gaode = 0, dist = 40, atMs = t0 + 1_000)
        val s = NavGuidanceHub.snapshot(nowMs = t0 + 1_000)
        assertEquals(40, s.distanceMeters)
        assertEquals(2, s.maneuverGaode)
    }

    @Test fun `a read that raises the distance with its own maneuver shows that maneuver`() {
        val t0 = 1_000_000L
        a11yRead(gaode = 2, dist = 10, atMs = t0)
        a11yRead(gaode = 3, dist = 150, atMs = t0 + 1_000)
        val s = NavGuidanceHub.snapshot(nowMs = t0 + 1_000)
        assertEquals(150, s.distanceMeters)
        assertEquals(3, s.maneuverGaode)
    }

    @Test fun `a read without a maneuver lets the held arrow expire after the timeout`() {
        val t0 = 1_000_000L
        a11yRead(gaode = 2, dist = 300, atMs = t0)
        a11yRead(gaode = 0, dist = 250, atMs = t0 + 5_000)
        assertEquals(2, NavGuidanceHub.snapshot(nowMs = t0 + NavGuidanceHub.MANEUVER_TIMEOUT_MS).maneuverGaode)
        val s = NavGuidanceHub.snapshot(nowMs = t0 + NavGuidanceHub.MANEUVER_TIMEOUT_MS + 1_000)
        assertTrue(s.active)
        assertEquals(0, s.maneuverGaode)
    }

    @Test fun `silence past the active timeout ends guidance and clears the arrow`() {
        val t0 = 1_000_000L
        notifRead(gaode = 2, dist = 50, atMs = t0)
        val s = NavGuidanceHub.snapshot(nowMs = t0 + NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1_000)
        assertFalse(s.active)
        assertEquals(0, s.maneuverGaode)
    }

    @Test fun `a keep-alive read lets the held arrow expire after the timeout`() {
        val t0 = 1_000_000L
        a11yRead(gaode = 2, dist = 300, atMs = t0)
        NavGuidanceHub.keepAlive(nowMs = t0 + 5_000)
        val s = NavGuidanceHub.snapshot(nowMs = t0 + NavGuidanceHub.MANEUVER_TIMEOUT_MS + 1_000)
        assertTrue(s.active)
        assertEquals(0, s.maneuverGaode)
    }

    @Test fun `HUD frames are not a source and do not expire the held arrow`() {
        val t0 = 1_000_000L
        notifRead(gaode = 2, dist = 50, atMs = t0)
        NavGuidanceHub.countFrame(0)
        val s = NavGuidanceHub.snapshot(nowMs = t0 + NavGuidanceHub.MANEUVER_TIMEOUT_MS + 1_000)
        assertEquals(2, s.maneuverGaode)
    }
}
