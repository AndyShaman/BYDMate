package com.bydmate.app.hud

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.diagnostics.TraceRecorder
import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HudAmapClusterLoopTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<Application>())
    private val prefs = context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)
    private var clock = 1_000_000L

    @get:Rule val trace = TraceRecorder()

    @Before fun reset() {
        NavGuidanceHub.reset()
        prefs.edit().clear().commit()
        shadowApp.clearBroadcastIntents()
    }

    private fun loop() = HudAmapClusterLoop(context, prefs, nowMsProvider = { clock })

    private fun route(gaode: Int = 2, dist: Int = 500, road: String = "Lenin Street", eta: Int = 1200, total: Int = 15000) {
        NavGuidanceHub.updateFromNotification(
            NavGuidanceHub.RichUpdate(maneuverGaode = gaode, distanceMeters = dist, road = road,
                etaSeconds = eta, totalDistMeters = total), clock)
    }

    private fun sent(): List<Intent> = shadowApp.broadcastIntents

    @Test fun `one tick of a guided route is one TYPE 0 broadcast to the adapter only`() {
        route(gaode = 2, dist = 500, road = "Ленина", eta = 1200, total = 15000)
        loop().tick()
        val i = sent().single()
        assertEquals("com.example.amapservice", i.`package`)
        assertEquals("AUTONAVI_STANDARD_BROADCAST_SEND", i.action)
        assertEquals(10001, i.getIntExtra("KEY_TYPE", -99))
        assertEquals(0, i.getIntExtra("TYPE", -99))
        assertFalse(i.getBooleanExtra("IS_BYD_MAP", true))
        assertFalse(i.getBooleanExtra("IS_BYD_BAIDU_MAP", true))
        assertEquals(3, i.getIntExtra("NEW_ICON", -99))
        assertFalse(i.hasExtra("ROUNG_ABOUT_NUM"))
        assertEquals(500, i.getIntExtra("SEG_REMAIN_DIS", -99))
        assertEquals("Ленина", i.getStringExtra("NEXT_ROAD_NAME"))
        assertEquals(15000, i.getIntExtra("ROUTE_REMAIN_DIS", -99))
        assertEquals(1200, i.getIntExtra("ROUTE_REMAIN_TIME", -99))
        assertEquals("20分", i.getStringExtra("ROUTE_REMAIN_TIME_AUTO"))
    }

    @Test fun `NEW_ICON follows the Gaode table and 0 stays 0`() {
        fun icon(gaode: Int): Intent =
            HudAmapClusterLoop.buildGuideIntent(NavGuidanceHub.Snapshot(active = true, maneuverGaode = gaode, distanceMeters = 300))
        assertEquals(2, icon(1).getIntExtra("NEW_ICON", -99))
        assertEquals(3, icon(2).getIntExtra("NEW_ICON", -99))
        assertEquals(11, icon(25).getIntExtra("NEW_ICON", -99))
        assertEquals(1, icon(25).getIntExtra("ROUNG_ABOUT_NUM", -99))
        assertEquals(0, icon(0).getIntExtra("NEW_ICON", -99))
        assertFalse(icon(0).hasExtra("ROUNG_ABOUT_NUM"))
    }

    @Test fun `remaining time is written in the adapter's hours and minutes`() {
        assertEquals("1时5分", HudAmapClusterLoop.remainTimeAuto(3900))
        assertEquals("20分", HudAmapClusterLoop.remainTimeAuto(1200))
        val unknown = HudAmapClusterLoop.buildGuideIntent(NavGuidanceHub.Snapshot(active = true, distanceMeters = 300))
        assertEquals(-1, unknown.getIntExtra("ROUTE_REMAIN_TIME", -99))
        assertEquals(-1, unknown.getIntExtra("ROUTE_REMAIN_DIS", -99))
        assertNull(unknown.getStringExtra("ROUTE_REMAIN_TIME_AUTO"))
    }

    @Test fun `a distance below the floor is lifted like the gateway path`() {
        val i = HudAmapClusterLoop.buildGuideIntent(NavGuidanceHub.Snapshot(active = true, maneuverGaode = 2, distanceMeters = 5))
        assertEquals(HudProtobufBuilder.MIN_DISTANCE_METERS, i.getIntExtra("SEG_REMAIN_DIS", -99))
    }

    @Test fun `the route end is one KILL to the adapter, the leftover key goes with it`() {
        val l = loop()
        route()
        l.tick()
        assertTrue(prefs.contains(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT))
        clock += NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1_000
        l.tick()
        repeat(3) { clock += 1_000; l.tick() }
        assertEquals(2, sent().size)
        val kill = sent()[1]
        assertEquals("byd.intent.action.KILL_BydAutoMap", kill.action)
        assertEquals("com.example.amapservice", kill.`package`)
        assertFalse(prefs.contains(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT))
        assertEquals(1L, l.framesSent)
        assertEquals(1L, l.killsSent)
        val lines = trace.events().filter { it.contains("amap-cluster") }
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].contains("amap-cluster state=on"))
        assertTrue(lines[1], lines[1].contains("amap-cluster state=off sent=1"))
    }

    @Test fun `stop mid route sends one KILL, stop with nothing sent sends none`() {
        val idle = loop()
        idle.stop()
        assertTrue(sent().isEmpty())
        val l = loop()
        route()
        l.tick()
        l.stop()
        l.stop()
        assertEquals(listOf("AUTONAVI_STANDARD_BROADCAST_SEND", "byd.intent.action.KILL_BydAutoMap"), sent().map { it.action })
    }

    /** A context whose sendBroadcast throws for the actions in [failOnce], once each. */
    private fun flakyContext(vararg failOnce: String): Context {
        val pending = failOnce.toMutableList()
        return object : ContextWrapper(context) {
            override fun sendBroadcast(intent: Intent) {
                if (pending.remove(intent.action)) throw SecurityException("broadcast refused")
                super.sendBroadcast(intent)
            }
        }
    }

    @Test fun `a first frame that fails to go out leaves no leftover key`() {
        val l = HudAmapClusterLoop(flakyContext(HudAmapBroadcaster.ACTION), prefs, nowMsProvider = { clock })
        route()
        runCatching { l.tick() }
        assertTrue(sent().isEmpty())
        assertFalse(prefs.contains(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT))
    }

    @Test fun `a failed KILL is retried on the next tick and the key stays until it goes`() {
        val l = HudAmapClusterLoop(flakyContext(HudAmapClusterLoop.ACTION_KILL), prefs, nowMsProvider = { clock })
        route()
        l.tick()
        clock += NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1_000
        l.tick()   // the KILL throws
        assertTrue(prefs.contains(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT))
        clock += 1_000
        l.tick()
        repeat(3) { clock += 1_000; l.tick() }
        assertEquals(listOf(HudAmapBroadcaster.ACTION, HudAmapClusterLoop.ACTION_KILL), sent().map { it.action })
        assertEquals(1L, l.killsSent)
        assertFalse(prefs.contains(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT))
    }

    @Test fun `two routes in a row are one KILL each`() {
        val l = loop()
        repeat(2) {
            route()
            l.tick()
            clock += 1_000
            route()
            l.tick()
            clock += NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1_000
            l.tick()
            clock += 1_000
            l.tick()
        }
        val guide = HudAmapBroadcaster.ACTION
        val kill = HudAmapClusterLoop.ACTION_KILL
        assertEquals(listOf(guide, guide, kill, guide, guide, kill), sent().map { it.action })
    }

    @Test fun `guide and KILL carry the broadcaster's receiver flags`() {
        val flags = 0x11000000
        route()
        loop().apply { tick(); stop() }
        assertEquals(2, sent().size)
        sent().forEach { assertEquals(it.action, flags, it.flags and flags) }
    }

    @Test fun `a leftover with no route is one KILL and the key goes`() {
        prefs.edit().putLong(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT, 1L).commit()
        assertTrue(HudAmapClusterLoop.killLeftover(context, prefs))
        assertFalse(HudAmapClusterLoop.killLeftover(context, prefs))
        assertEquals("byd.intent.action.KILL_BydAutoMap", sent().single().action)
        assertFalse(prefs.contains(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT))
    }
}
