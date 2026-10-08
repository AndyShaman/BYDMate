package com.bydmate.app.hud

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.navdata.NavGuidanceHub
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #301 regression: the Song Plus head unit from its dump (no SOME/IP gateway, the HUD switch on,
 *  com.example.amapservice installed) and a notification-only route shaped like its log. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HudAmapClusterRouteFixtureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<Application>())
    private val prefs = context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)

    private class Row(val gaode: Int, val dist: Int, val road: String, val total: Int, val eta: Int)

    @Before fun reset() {
        NavGuidanceHub.reset()
        prefs.edit().clear().commit()
        shadowApp.clearBroadcastIntents()
    }

    @Test fun `a gateway-less head unit sends one TYPE 0 per route read and one KILL at the end`() {
        val lines = requireNotNull(javaClass.classLoader?.getResource("hud/amap-cluster-route-301.txt")).readText().lines()
        val hud = lines.dropWhile { it != "--- hud ---" }.takeWhile { it != "--- route ---" }
        val rows = lines.dropWhile { it != "--- route ---" }.drop(1)
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals("END", rows.last())
        val route = rows.dropLast(1).map { it.split("\t") }
            .map { Row(it[0].toInt(), it[1].toInt(), it[2], it[3].toInt(), it[4].toInt()) }

        // The head unit: installed packages ("pkg ...: <state>", "absent" = not installed), the
        // gateway and the switch as the dump shows them.
        val pm = shadowOf(context.packageManager)
        hud.mapNotNull { Regex("^pkg (\\S+): (.+)$").find(it)?.destructured }
            .filter { (_, state) -> state != "absent" }
            .forEach { (pkg, _) -> pm.installPackage(PackageInfo().apply { packageName = pkg }) }
        if (hud.contains("someip_gateway: present")) {
            pm.installPackage(PackageInfo().apply { packageName = "com.ts.car.someip.service" })
        }
        prefs.edit().putBoolean(HudController.KEY_ENABLED, hud.contains("enabled: true")).commit()

        val c = HudController(context, mockk<HelperClient>(relaxed = true), mockk<HelperBootstrap>(relaxed = true))
            .apply { scope = CoroutineScope(Dispatchers.Unconfined) }
        c.startIfEnabled()
        val status = c.status.value
        // The replay below drives its own loop on the test clock.
        c.stop()
        shadowApp.clearBroadcastIntents()

        var clock = 1_000_000L
        val loop = HudAmapClusterLoop(context, prefs, nowMsProvider = { clock })
        val heldIcons = mutableListOf<Int>()
        route.forEach { r ->
            NavGuidanceHub.updateFromNotification(
                NavGuidanceHub.RichUpdate(maneuverGaode = r.gaode, distanceMeters = r.dist, road = r.road,
                    etaSeconds = r.eta, totalDistMeters = r.total), clock)
            loop.tick()
            val held = NavGuidanceHub.snapshot(clock).maneuverGaode
            heldIcons += if (held == 0) 0 else HudAmapBroadcaster.gaodeToAmapIcon(held)
            clock += 14_000
        }
        val guide = shadowApp.broadcastIntents.toList()
        // END: silence until the hub ends the route, then a few more ticks.
        clock += NavGuidanceHub.ACTIVE_TIMEOUT_MS
        repeat(4) { loop.tick(); clock += 1_000 }
        val after = shadowApp.broadcastIntents.drop(guide.size)

        assertEquals("one TYPE 0 per route read", route.size, guide.size)
        guide.forEach {
            assertEquals("com.example.amapservice", it.`package`)
            assertEquals("AUTONAVI_STANDARD_BROADCAST_SEND", it.action)
            assertEquals(0, it.getIntExtra("TYPE", -99))
        }
        // A read without a maneuver of its own sends NEW_ICON 0 once the hub holds none.
        assertEquals(heldIcons, guide.map { it.getIntExtra("NEW_ICON", -99) })
        assertTrue(heldIcons.count { it == 0 } > route.size / 2)
        val summary = NavGuidanceHub.routeSummary(clock)
        assertTrue(summary, summary.contains("expiries=3"))
        assertEquals("2070 m, 8000 m, 900 s", guide.last().let {
            "${it.getIntExtra("SEG_REMAIN_DIS", -1)} m, ${it.getIntExtra("ROUTE_REMAIN_DIS", -1)} m, " +
                "${it.getIntExtra("ROUTE_REMAIN_TIME", -1)} s"
        })
        assertEquals(listOf("byd.intent.action.KILL_BydAutoMap"), after.map { it.action })
        assertEquals("com.example.amapservice", after.single().`package`)
        assertEquals(HudController.Status.CLUSTER_ONLY, status)
    }
}
