package com.bydmate.app.navdata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** #325: on a gateway-less car (Han 2022, Navigator 24.3.1) the a11y read finds the maneuver node
 *  without a description, and a fresh a11y read made the hub ignore the notification's arrow too.
 *  Fixtures: navdata/issue325-logs.txt, lines of the field logs. */
class NavGuidanceIssue325Test {

    @Before fun reset() {
        NavGuidanceHub.reset()
        NavGuidanceHub.setGatewaylessClusterPath(false)
    }

    @After fun clean() = NavGuidanceHub.setGatewaylessClusterPath(false)

    private val log: List<String> = requireNotNull(javaClass.classLoader?.getResource("navdata/issue325-logs.txt"))
        .readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }

    /** The rich notification of the log line holding [marker], as the hub gets it. */
    private fun richOf(marker: String): NavGuidanceHub.RichUpdate {
        val line = log.single { marker in it }
        val man = Regex("""man=(\d+)""").find(line)!!.groupValues[1].toInt()
        val dist = Regex("""dist=(\d+)""").find(line)!!.groupValues[1].toInt()
        return NavGuidanceHub.RichUpdate(maneuverGaode = man, distanceMeters = dist,
            maneuverRaw = if (man > 0) "res:notification_right_sdl" else "")
    }

    /** The Han read of l2.txt:2715: distance and ETA nodes, the maneuver node without a description. */
    private fun hanRead(dist: Int, atMs: Long) = NavGuidanceHub.update(
        NavGuidance(distanceMeters = dist, etaSeconds = 960, totalDistMeters = 8000,
            roadIsNextStreet = false, maneuverUndescribed = true),
        NavGuidanceHub.Source.A11Y, nowMs = atMs,
    )

    @Test fun `gateway-less, a11y fresh and the node undescribed - only the notification arrow is taken (#325)`() {
        NavGuidanceHub.setGatewaylessClusterPath(true)
        val t0 = 1_000_000L
        hanRead(dist = 12, atMs = t0)
        NavGuidanceHub.updateFromNotification(richOf("man=2 dist=10"), nowMs = t0 + 3_000)   // l2.txt:2732
        val s = NavGuidanceHub.snapshot(nowMs = t0 + 3_000)
        assertEquals("the notification's arrow reaches the hub", 2, s.maneuverGaode)
        assertEquals("distance stays the a11y one", 12, s.distanceMeters)
        assertEquals(960, s.etaSeconds)
        assertEquals(8000, s.totalDistMeters)
    }

    @Test fun `gateway path - the notification is ignored while a11y is fresh`() {
        NavGuidanceHub.setGatewaylessClusterPath(false)
        val t0 = 1_000_000L
        hanRead(dist = 12, atMs = t0)
        NavGuidanceHub.updateFromNotification(richOf("man=2 dist=10"), nowMs = t0 + 3_000)
        val s = NavGuidanceHub.snapshot(nowMs = t0 + 3_000)
        assertEquals(0, s.maneuverGaode)
        assertEquals(12, s.distanceMeters)
    }

    @Test fun `notification-only flow with a11y not fresh is unchanged (4_0_1 logs)`() {
        NavGuidanceHub.setGatewaylessClusterPath(true)
        val t0 = 1_000_000L
        NavGuidanceHub.updateFromNotification(richOf("man=13 dist=780"), nowMs = t0)         // :2785
        var s = NavGuidanceHub.snapshot(nowMs = t0)
        assertEquals(13, s.maneuverGaode)
        assertEquals(780, s.distanceMeters)
        NavGuidanceHub.updateFromNotification(richOf("man=2 dist=2540"), nowMs = t0 + 20_000)  // :3246
        s = NavGuidanceHub.snapshot(nowMs = t0 + 20_000)
        assertEquals(2, s.maneuverGaode)
        assertEquals(2540, s.distanceMeters)
        NavGuidanceHub.updateFromNotification(richOf("man=2 dist=120"), nowMs = t0 + 40_000)   // :3429
        s = NavGuidanceHub.snapshot(nowMs = t0 + 40_000)
        assertEquals(2, s.maneuverGaode)
        assertEquals(120, s.distanceMeters)
    }

    @Test fun `a11y with the node absent keeps ignoring notifications (294)`() {
        NavGuidanceHub.setGatewaylessClusterPath(true)
        val t0 = 1_000_000L
        // found=0: the node is not in the tree, so the read is not "undescribed".
        NavGuidanceHub.update(NavGuidance(distanceMeters = 100, roadIsNextStreet = false),
            NavGuidanceHub.Source.A11Y, nowMs = t0)
        NavGuidanceHub.updateFromNotification(richOf("man=48 dist=50"), nowMs = t0 + 2_000)
        val s = NavGuidanceHub.snapshot(nowMs = t0 + 2_000)
        assertEquals(0, s.maneuverGaode)
        assertEquals(100, s.distanceMeters)
    }

    @Test fun `a11y with a description keeps ignoring a notification arrow (Seal 367)`() {
        NavGuidanceHub.setGatewaylessClusterPath(true)
        val t0 = 1_000_000L
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 100, roadIsNextStreet = false,
            maneuverRaw = "desc:key=поверните направо"), NavGuidanceHub.Source.A11Y, nowMs = t0)
        NavGuidanceHub.updateFromNotification(richOf("man=4 dist=100"), nowMs = t0 + 2_000)
        val s = NavGuidanceHub.snapshot(nowMs = t0 + 2_000)
        assertEquals(2, s.maneuverGaode)
        assertEquals("a11y", s.maneuverSource)
    }

    @Test fun `the merged arrow expires 30 s after the notification, a11y reads giving no maneuver (#325)`() {
        NavGuidanceHub.setGatewaylessClusterPath(true)
        val t0 = 1_000_000L
        hanRead(dist = 12, atMs = t0)
        NavGuidanceHub.updateFromNotification(richOf("man=2 dist=10"), nowMs = t0 + 1_000)
        var t = t0 + 1_000
        while (t < t0 + 1_000 + 31_000) {
            t += 2_000
            hanRead(dist = 12, atMs = t)
        }
        assertEquals("the arrow was merged first", 2, NavGuidanceHub.snapshot(nowMs = t0 + 1_000 + 29_000).maneuverGaode)
        assertEquals(0, NavGuidanceHub.snapshot(nowMs = t0 + 1_000 + 31_000).maneuverGaode)
    }

    @Test fun `the merged arrow goes on an a11y distance jump 10 m to 800 m (#325)`() {
        NavGuidanceHub.setGatewaylessClusterPath(true)
        val t0 = 1_000_000L
        hanRead(dist = 10, atMs = t0)
        NavGuidanceHub.updateFromNotification(richOf("man=2 dist=10"), nowMs = t0 + 1_000)
        assertEquals("the arrow was merged first", 2, NavGuidanceHub.snapshot(nowMs = t0 + 1_000).maneuverGaode)
        hanRead(dist = 800, atMs = t0 + 4_000)
        val s = NavGuidanceHub.snapshot(nowMs = t0 + 4_000)
        assertEquals(800, s.distanceMeters)
        assertEquals(0, s.maneuverGaode)
    }
}
