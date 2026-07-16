package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NavGuidanceHubTest {

    @Before fun reset() = NavGuidanceHub.reset()

    private fun data(
        gaode: Int = 0, dist: Int = 0, road: String = "",
        eta: Int = 0, total: Int = 0, limit: Int = 0,
    ) = NavGuidance(gaode, dist, road, eta, total, limit)

    @Test fun `update activates and fills fields`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 250, road = "ул. Ленина", eta = 300, total = 5000, limit = 60),
            NavGuidanceHub.Source.A11Y, nowMs = 1000)
        val s = NavGuidanceHub.snapshot(nowMs = 1000)
        assertTrue(s.active)
        assertEquals(2, s.maneuverGaode)
        assertEquals(1000, s.maneuverGaodeMs)
        assertEquals(250, s.distanceMeters)
        assertEquals("ул. Ленина", s.road)
        assertEquals(300, s.etaSeconds)
        assertEquals(5000, s.totalDistMeters)
        assertEquals(60, s.speedLimit)
        assertEquals(1000, s.lastUpdateMs)
    }

    @Test fun `empty update keeps previous fields but bumps freshness`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 250, road = "ул. Ленина"), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.update(data(), NavGuidanceHub.Source.A11Y, nowMs = 2000)
        val s = NavGuidanceHub.snapshot(nowMs = 2000)
        assertEquals(2, s.maneuverGaode)
        assertEquals(1000, s.maneuverGaodeMs)  // gaode timestamp NOT bumped by empty update
        assertEquals(250, s.distanceMeters)
        assertEquals("ул. Ленина", s.road)
        assertEquals(2000, s.lastUpdateMs)
    }

    @Test fun `sources merge into one snapshot`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 300), NavGuidanceHub.Source.NOTIFICATION, nowMs = 1000)
        NavGuidanceHub.update(data(limit = 60), NavGuidanceHub.Source.A11Y, nowMs = 2000)
        val s = NavGuidanceHub.snapshot(nowMs = 2000)
        assertEquals(2, s.maneuverGaode)
        assertEquals(300, s.distanceMeters)
        assertEquals(60, s.speedLimit)
    }

    @Test fun `snapshot expires after timeout`() {
        NavGuidanceHub.update(data(gaode = 2), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        assertTrue(NavGuidanceHub.snapshot(nowMs = 1000 + NavGuidanceHub.ACTIVE_TIMEOUT_MS).active)
        assertFalse(NavGuidanceHub.snapshot(nowMs = 1001 + NavGuidanceHub.ACTIVE_TIMEOUT_MS).active)
    }

    @Test fun `speed limit clears after its own timeout`() {
        NavGuidanceHub.update(data(gaode = 2, limit = 60), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.update(data(dist = 200), NavGuidanceHub.Source.A11Y,
            nowMs = 1000 + NavGuidanceHub.SPEED_LIMIT_TIMEOUT_MS + 1)
        val s = NavGuidanceHub.snapshot(nowMs = 1000 + NavGuidanceHub.SPEED_LIMIT_TIMEOUT_MS + 1)
        assertTrue(s.active)
        assertEquals(0, s.speedLimit)
    }

    @Test fun `no-guidance streak deactivates after hysteresis`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 250), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.markNoGuidance(nowMs = 2000)   // streak starts
        assertTrue(NavGuidanceHub.snapshot(nowMs = 2000).active)
        NavGuidanceHub.markNoGuidance(nowMs = 2000 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS)
        assertFalse(NavGuidanceHub.snapshot(nowMs = 2000 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS).active)
    }

    @Test fun `guidance update resets no-guidance streak`() {
        NavGuidanceHub.update(data(gaode = 2), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.markNoGuidance(nowMs = 2000)
        NavGuidanceHub.update(data(dist = 100), NavGuidanceHub.Source.A11Y, nowMs = 3000)
        NavGuidanceHub.markNoGuidance(nowMs = 4000)   // new streak, not a continuation
        assertTrue(NavGuidanceHub.snapshot(nowMs = 4000 + 5000).active)
    }

    @Test fun `notification update maps res and distance text`() {
        NavGuidanceHub.updateFromNotification("notification_right_sdl", "300 м", "улица Ленина", nowMs = 1000)
        val s = NavGuidanceHub.snapshot(nowMs = 1000)
        assertTrue(s.active)
        assertEquals(2, s.maneuverGaode)
        assertEquals(300, s.distanceMeters)
        assertEquals("улица Ленина", s.road)
    }

    @Test fun `blank notification update is ignored`() {
        NavGuidanceHub.updateFromNotification(null, null, null, nowMs = 1000)
        assertFalse(NavGuidanceHub.snapshot(nowMs = 1000).active)
    }

    @Test fun `reset clears everything`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 250), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.reset()
        val s = NavGuidanceHub.snapshot(nowMs = 1000)
        assertFalse(s.active)
        assertEquals(0, s.maneuverGaode)
    }

    @Test fun `single no-guidance signal deactivates via snapshot after deadline`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 500), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        NavGuidanceHub.markNoGuidance(nowMs = 2_000)
        assertTrue(NavGuidanceHub.snapshot(nowMs = 5_000).active)      // deadline not reached yet
        assertFalse(NavGuidanceHub.snapshot(nowMs = 12_001).active)    // >=10 s, NO second event
    }

    @Test fun `guidance update cancels pending no-guidance deadline`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 500), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        NavGuidanceHub.markNoGuidance(nowMs = 2_000)
        NavGuidanceHub.update(data(dist = 400), NavGuidanceHub.Source.A11Y, nowMs = 3_000)
        assertTrue(NavGuidanceHub.snapshot(nowMs = 20_000).active)
    }

    @Test fun `notification end deactivates notification-only guidance immediately`() {
        NavGuidanceHub.updateFromNotification("turn_left", "500 м", "ул. Ленина", nowMs = 1_000)
        assertTrue(NavGuidanceHub.snapshot(nowMs = 1_500).active)
        NavGuidanceHub.markNotificationEnded(nowMs = 2_000)
        assertFalse(NavGuidanceHub.snapshot(nowMs = 2_100).active)
    }

    @Test fun `notification end ignored while a11y guidance is fresh`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 300), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        NavGuidanceHub.markNotificationEnded(nowMs = 2_000)
        assertTrue(NavGuidanceHub.snapshot(nowMs = 2_100).active)
    }
}
