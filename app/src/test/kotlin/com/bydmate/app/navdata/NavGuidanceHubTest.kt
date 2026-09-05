package com.bydmate.app.navdata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun `maneuver and its icon clear after the maneuver timeout`() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(
            maneuverGaode = 2, distanceMeters = 250, road = "ул. Ленина",
            maneuverPng = byteArrayOf(5)), nowMs = 1000)
        // Guidance keeps flowing without a maneuver balloon (straight stretch)
        val later = 1000 + NavGuidanceHub.MANEUVER_TIMEOUT_MS + 500
        NavGuidanceHub.update(data(dist = 250, limit = 60), NavGuidanceHub.Source.A11Y, nowMs = later)
        val s = NavGuidanceHub.snapshot(nowMs = later + 1)
        assertTrue(s.active)
        assertEquals(0, s.maneuverGaode)
        assertNull(s.maneuverPng)
        assertEquals(250, s.distanceMeters)
        assertEquals("ул. Ленина", s.road)
        assertEquals(60, s.speedLimit)   // own freshness, untouched by maneuver expiry
    }

    @Test fun `fresh maneuver survives up to the timeout`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 250), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        val s = NavGuidanceHub.snapshot(nowMs = 1000 + NavGuidanceHub.MANEUVER_TIMEOUT_MS)
        assertEquals(2, s.maneuverGaode)
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

    @Test
    fun `rich notification update fills guidance fields`() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(
            maneuverGaode = 2, distanceMeters = 300, road = "улица Ленина",
            etaSeconds = 600, totalDistMeters = 5000, maneuverPng = byteArrayOf(5)), nowMs = 1000)
        val s = NavGuidanceHub.snapshot(nowMs = 1000)
        assertTrue(s.active)
        assertEquals(2, s.maneuverGaode)
        assertEquals(300, s.distanceMeters)
        assertEquals("улица Ленина", s.road)
        assertEquals(600, s.etaSeconds)
        assertEquals(5000, s.totalDistMeters)
        assertArrayEquals(byteArrayOf(5), s.maneuverPng)
        assertEquals(1000L, s.lastUpdateMs)
    }

    @Test
    fun `fresh a11y blocks rich guidance fields but camera and activity apply`() {
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 250, road = "ул. А"),
            NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(
            maneuverGaode = 1, distanceMeters = 900, road = "ул. Б", maneuverPng = byteArrayOf(3),
            cameraAlert = "camera", cameraDistanceMeters = 400, cameraIconPng = byteArrayOf(1)), nowMs = 5000)
        val s = NavGuidanceHub.snapshot(nowMs = 5000)
        assertEquals(2, s.maneuverGaode)
        assertEquals(250, s.distanceMeters)
        assertEquals("ул. А", s.road)
        assertNull(s.maneuverPng)
        assertEquals("camera", s.cameraAlert)
        assertEquals(400, s.cameraDistanceMeters)
        assertArrayEquals(byteArrayOf(1), s.cameraIconPng)
        assertEquals(5000L, s.lastUpdateMs)
    }

    @Test
    fun `stale a11y allows full rich merge`() {
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 250, road = "ул. А"),
            NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(
            maneuverGaode = 1, distanceMeters = 900, road = "ул. Б"), nowMs = 11_500)
        val s = NavGuidanceHub.snapshot(nowMs = 11_500)
        assertEquals(1, s.maneuverGaode)
        assertEquals(900, s.distanceMeters)
        assertEquals("ул. Б", s.road)
    }

    @Test
    fun `rich update within a11y priority still cancels no-guidance streak`() {
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.markNoGuidance(nowMs = 2000)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(road = "ул. Б"), nowMs = 3000)
        // Without the streak reset the 10s no-guidance deadline (2000+10000) would deactivate.
        assertTrue(NavGuidanceHub.snapshot(nowMs = 12_500).active)
    }

    @Test
    fun `camera two-stage merge keeps prev distance and icon, empty alert clears`() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(
            cameraAlert = "camera", cameraDistanceMeters = 400, cameraIconPng = byteArrayOf(1)), nowMs = 1000)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(cameraAlert = "camera"), nowMs = 2000)
        var s = NavGuidanceHub.snapshot(nowMs = 2000)
        assertEquals(400, s.cameraDistanceMeters)
        assertArrayEquals(byteArrayOf(1), s.cameraIconPng)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(road = "x"), nowMs = 3000)
        s = NavGuidanceHub.snapshot(nowMs = 3000)
        assertEquals("", s.cameraAlert)
        assertEquals(0, s.cameraDistanceMeters)
        assertNull(s.cameraIconPng)
    }

    @Test
    fun `extras update with applyCamera false preserves camera`() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(
            cameraAlert = "camera", cameraDistanceMeters = 400, cameraIconPng = byteArrayOf(1)), nowMs = 1000)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(
            road = "ул. В", applyCamera = false), nowMs = 2000)
        val s = NavGuidanceHub.snapshot(nowMs = 2000)
        assertEquals("camera", s.cameraAlert)
        assertEquals(400, s.cameraDistanceMeters)
        assertArrayEquals(byteArrayOf(1), s.cameraIconPng)
        assertEquals("ул. В", s.road)
    }

    @Test
    fun `a11y update does not touch camera`() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(
            cameraAlert = "camera", cameraDistanceMeters = 400), nowMs = 1000)
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 100),
            NavGuidanceHub.Source.A11Y, nowMs = 2000)
        val s = NavGuidanceHub.snapshot(nowMs = 2000)
        assertEquals("camera", s.cameraAlert)
        assertEquals(400, s.cameraDistanceMeters)
        assertEquals(2, s.maneuverGaode)
    }

    @Test
    fun `active timeout expiry clears camera and maneuver png`() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(
            maneuverGaode = 2, road = "x", maneuverPng = byteArrayOf(2),
            cameraAlert = "camera", cameraDistanceMeters = 100, cameraIconPng = byteArrayOf(1)), nowMs = 1000)
        // Snapshot expiry check is strict (> ACTIVE_TIMEOUT_MS), so cross the boundary
        val s = NavGuidanceHub.snapshot(nowMs = 1000 + 90_001)
        assertFalse(s.active)
        assertEquals("", s.cameraAlert)
        assertEquals(0, s.cameraDistanceMeters)
        assertNull(s.cameraIconPng)
        assertNull(s.maneuverPng)
    }

    @Test
    fun `empty rich update on inactive hub is a no-op`() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(), nowMs = 1000)
        val s = NavGuidanceHub.snapshot(nowMs = 1000)
        assertFalse(s.active)
        assertEquals(0L, s.lastUpdateMs)
    }

    @Test
    fun `empty rich update on active hub does not deactivate or reset maneuver`() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(
            maneuverGaode = 2, distanceMeters = 300, road = "ул. А"), nowMs = 1000)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(), nowMs = 2000)
        val s = NavGuidanceHub.snapshot(nowMs = 2000)
        assertTrue(s.active)
        assertEquals(2, s.maneuverGaode)
        assertEquals(300, s.distanceMeters)
        assertEquals("ул. А", s.road)
        assertEquals(1000L, s.lastUpdateMs)
    }

    @Test
    fun `notification grace deactivates only when stale`() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(
            maneuverGaode = 2, road = "x", cameraAlert = "camera"), nowMs = 1000)
        NavGuidanceHub.deactivateFromNotificationGrace(nowMs = 5000) // fresh - no-op
        assertTrue(NavGuidanceHub.snapshot(nowMs = 5000).active)
        NavGuidanceHub.deactivateFromNotificationGrace(nowMs = 1000 + 90_000)
        val s = NavGuidanceHub.snapshot(nowMs = 1000 + 90_000)
        assertFalse(s.active)
        assertEquals("", s.cameraAlert)
    }
}
