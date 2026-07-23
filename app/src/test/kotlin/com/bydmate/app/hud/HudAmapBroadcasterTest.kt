package com.bydmate.app.hud

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HudAmapBroadcasterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<Application>())

    @Before fun clearBroadcasts() {
        shadowApp.clearBroadcastIntents()
    }

    private fun snapshot(
        maneuverGaode: Int = 2,
        distanceMeters: Int = 100,
        road: String = "Test",
        etaSeconds: Int = 300,
        totalDistMeters: Int = 5000,
    ) = NavGuidanceHub.Snapshot(
        active = true,
        maneuverGaode = maneuverGaode,
        distanceMeters = distanceMeters,
        road = road,
        etaSeconds = etaSeconds,
        totalDistMeters = totalDistMeters,
    )

    private fun installAmapService() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.byd.amapservice" })
    }

    @Test
    fun no_receiver_package_means_zero_broadcasts() {
        val b = HudAmapBroadcaster(context)
        b.onSnapshot(snapshot(maneuverGaode = 2))
        assertFalse(b.capable)
        assertEquals(0, shadowApp.broadcastIntents.size)
        assertEquals(0L, b.framesSent)
    }

    @Test
    fun guide_frame_carries_donor_extras_to_three_targets() {
        installAmapService()
        val b = HudAmapBroadcaster(context)
        b.onSnapshot(snapshot(
            maneuverGaode = 2, distanceMeters = 500, road = "Ленина",
            etaSeconds = 1200, totalDistMeters = 15000))
        val sent = shadowApp.broadcastIntents
        assertEquals(3, sent.size)
        // Three targets: com.byd.amapservice, com.example.amapservice, implicit (null)
        assertEquals(
            setOf("com.byd.amapservice", "com.example.amapservice", null),
            sent.map { it.`package` }.toSet())
        val i = sent[0]
        assertEquals("AUTONAVI_STANDARD_BROADCAST_SEND", i.action)
        // Core type/state extras (guide frame)
        assertEquals(10001, i.getIntExtra("KEY_TYPE", -99))
        assertEquals(8, i.getIntExtra("TYPE", -99))
        assertEquals(8, i.getIntExtra("EXTRA_STATE", -99))
        assertEquals(0, i.getIntExtra("EXTRA_IS_FOREGROUND", -99))
        assertTrue(i.getBooleanExtra("IS_BYD_MAP", false))
        assertFalse(i.getBooleanExtra("IS_BYD_BAIDU_MAP", true))
        // Icon: gaode=2 (RIGHT) -> Amap icon 4
        assertEquals(4, i.getIntExtra("NEW_ICON", -99))
        // Distance/road
        assertEquals(500, i.getIntExtra("SEG_REMAIN_DIS", -99))
        assertEquals("Ленина", i.getStringExtra("NEXT_ROAD_NAME"))
        assertEquals(15000, i.getIntExtra("ROUTE_REMAIN_DIS", -99))
        assertEquals(1200, i.getIntExtra("ROUTE_REMAIN_TIME", -99))
        // Auto-formatted distance strings (donor :237-249)
        assertEquals("500 m", i.getStringExtra("SEG_REMAIN_DIS_AUTO"))
        assertEquals("15.0 km", i.getStringExtra("ROUTE_REMAIN_DIS_AUTO"))
        // Auto-formatted time (1200 s = 20 min, donor :252-261)
        assertEquals("20 min", i.getStringExtra("ROUTE_REMAIN_TIME_AUTO"))
        assertEquals("20 min", i.getStringExtra("ROUTE_REMAIN_TIME_STRING"))
        // Flags: 0x11000000 = FLAG_RECEIVER_FOREGROUND | FLAG_INCLUDE_STOPPED_PACKAGES
        assertEquals(0x11000000, i.flags and 0x11000000)
        assertEquals(1L, b.framesSent)
    }

    @Test
    fun snapshot_null_after_guiding_sends_stop_frame_once() {
        installAmapService()
        val b = HudAmapBroadcaster(context)
        b.onSnapshot(snapshot(maneuverGaode = 2))
        shadowApp.clearBroadcastIntents()
        b.onSnapshot(null)
        b.onSnapshot(null)                               // duplicate null - no second stop
        val stops = shadowApp.broadcastIntents
        assertEquals(3, stops.size)                      // one stop x 3 targets
        val s = stops[0]
        assertEquals("AUTONAVI_STANDARD_BROADCAST_SEND", s.action)
        assertEquals(10001, s.getIntExtra("KEY_TYPE", -99))
        assertEquals(9, s.getIntExtra("TYPE", -99))
        assertEquals(1, s.getIntExtra("EXTRA_STATE", -99))
        assertEquals(1, s.getIntExtra("EXTRA_IS_FOREGROUND", -99))
        assertTrue(s.getBooleanExtra("IS_BYD_MAP", false))
        assertFalse(s.getBooleanExtra("IS_BYD_BAIDU_MAP", true))
        assertEquals(-1, s.getIntExtra("NEW_ICON", -99))
        assertEquals(-1, s.getIntExtra("SEG_REMAIN_DIS", -99))
        assertEquals("", s.getStringExtra("NEXT_ROAD_NAME"))
        assertEquals(-1, s.getIntExtra("ROUTE_REMAIN_DIS", -99))
        assertEquals(-1, s.getIntExtra("ROUTE_REMAIN_TIME", -99))
        assertEquals(0x11000000, s.flags and 0x11000000)
        assertEquals(1L, b.stopsSent)
    }

    @Test
    fun on_stop_sends_stop_frame_when_guiding() {
        installAmapService()
        val b = HudAmapBroadcaster(context)
        b.onSnapshot(snapshot(maneuverGaode = 2))
        shadowApp.clearBroadcastIntents()
        b.onStop()
        assertEquals(3, shadowApp.broadcastIntents.size)
        assertEquals(9, shadowApp.broadcastIntents[0].getIntExtra("TYPE", -99))
        assertEquals(1L, b.stopsSent)
    }

    @Test
    fun on_stop_noop_when_not_guiding() {
        installAmapService()
        val b = HudAmapBroadcaster(context)
        b.onStop()
        assertEquals(0, shadowApp.broadcastIntents.size)
        assertEquals(0L, b.stopsSent)
    }

    @Test
    fun gaode_to_amap_icon_complete_mapping() {
        assertEquals(6, HudAmapBroadcaster.gaodeToAmapIcon(1))    // LEFT
        assertEquals(4, HudAmapBroadcaster.gaodeToAmapIcon(2))    // RIGHT
        assertEquals(7, HudAmapBroadcaster.gaodeToAmapIcon(3))    // SLIGHT_LEFT
        assertEquals(7, HudAmapBroadcaster.gaodeToAmapIcon(4))    // SLIGHT_LEFT_ALT
        assertEquals(3, HudAmapBroadcaster.gaodeToAmapIcon(5))    // SLIGHT_RIGHT
        assertEquals(3, HudAmapBroadcaster.gaodeToAmapIcon(6))    // SLIGHT_RIGHT_ALT
        assertEquals(6, HudAmapBroadcaster.gaodeToAmapIcon(7))    // SHARP_LEFT (treated as LEFT)
        assertEquals(4, HudAmapBroadcaster.gaodeToAmapIcon(8))    // SHARP_RIGHT (treated as RIGHT)
        assertEquals(5, HudAmapBroadcaster.gaodeToAmapIcon(9))    // U_TURN_LEFT
        assertEquals(5, HudAmapBroadcaster.gaodeToAmapIcon(10))   // U_TURN_RIGHT
        assertEquals(2, HudAmapBroadcaster.gaodeToAmapIcon(11))   // STRAIGHT_SOLID
        assertEquals(2, HudAmapBroadcaster.gaodeToAmapIcon(12))   // STRAIGHT_DOTTED
        assertEquals(2, HudAmapBroadcaster.gaodeToAmapIcon(13))   // DETOUR_RIGHT (default)
        assertEquals(2, HudAmapBroadcaster.gaodeToAmapIcon(14))   // DETOUR_LEFT (default)
        assertEquals(8, HudAmapBroadcaster.gaodeToAmapIcon(15))   // ROUNDABOUT family
        assertEquals(8, HudAmapBroadcaster.gaodeToAmapIcon(16))
        assertEquals(8, HudAmapBroadcaster.gaodeToAmapIcon(17))
        assertEquals(8, HudAmapBroadcaster.gaodeToAmapIcon(18))
        assertEquals(8, HudAmapBroadcaster.gaodeToAmapIcon(19))
        assertEquals(8, HudAmapBroadcaster.gaodeToAmapIcon(20))
        assertEquals(2, HudAmapBroadcaster.gaodeToAmapIcon(21))   // default (not in switch)
        assertEquals(2, HudAmapBroadcaster.gaodeToAmapIcon(23))   // default
        assertEquals(2, HudAmapBroadcaster.gaodeToAmapIcon(24))   // default (second switch covers 15-20 only)
        assertEquals(2, HudAmapBroadcaster.gaodeToAmapIcon(44))   // default
        assertEquals(2, HudAmapBroadcaster.gaodeToAmapIcon(47))   // TOLLBOOTH (default)
        assertEquals(12, HudAmapBroadcaster.gaodeToAmapIcon(48))  // DESTINATION
        assertEquals(2, HudAmapBroadcaster.gaodeToAmapIcon(49))   // TUNNEL (default)
        assertEquals(2, HudAmapBroadcaster.gaodeToAmapIcon(0))    // unknown (default)
    }

    @Test
    fun guide_time_formatting_hours_and_minutes() {
        // 3660 seconds = 1h 1m
        installAmapService()
        val b = HudAmapBroadcaster(context)
        b.onSnapshot(snapshot(etaSeconds = 3660, totalDistMeters = 5000))
        val i = shadowApp.broadcastIntents[0]
        assertEquals("1h 1m", i.getStringExtra("ROUTE_REMAIN_TIME_AUTO"))
        assertEquals("1h 1m", i.getStringExtra("ROUTE_REMAIN_TIME_STRING"))
    }

    @Test
    fun guide_distance_auto_km_format() {
        // 2500 m >= 1000 -> "2.5 km"
        installAmapService()
        val b = HudAmapBroadcaster(context)
        b.onSnapshot(snapshot(distanceMeters = 2500, totalDistMeters = 100000))
        val i = shadowApp.broadcastIntents[0]
        assertEquals("2.5 km", i.getStringExtra("SEG_REMAIN_DIS_AUTO"))
        assertEquals("100.0 km", i.getStringExtra("ROUTE_REMAIN_DIS_AUTO"))
    }

    @Test
    fun broadcast_exception_does_not_escape_and_keeps_state_consistent() {
        installAmapService()
        // installAmapService installs into the base context's PackageManager;
        // ContextWrapper delegates getPackageManager() to it, so capable resolves true.
        val throwing = object : android.content.ContextWrapper(context) {
            override fun sendBroadcast(intent: android.content.Intent) = throw RuntimeException("boom")
        }
        val b = HudAmapBroadcaster(throwing)
        b.onSnapshot(snapshot(maneuverGaode = 2))   // must not throw
        assertEquals(0L, b.framesSent)              // failed frame is not counted
        b.onSnapshot(null)                          // stop path must not throw either
        b.onStop()                                  // and neither must the loop-stop path
        assertEquals(0L, b.stopsSent)
    }
}
