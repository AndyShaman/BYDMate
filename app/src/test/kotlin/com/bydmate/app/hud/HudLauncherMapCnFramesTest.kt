package com.bydmate.app.hud

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LAUNCHER_MAP_CN frames against bytes derived by hand from OpenBYD 2.5's
 * LauncherMapCnStrategy (varint / fixed64 double / length-delimited fields, each event wrapped
 * as field 1), with the route id, counter, clock and position fixed.
 */
class HudLauncherMapCnFramesTest {

    private fun hex(events: List<HudLauncherMapCnFrames.Event>) =
        events.map { "0x${it.topic.toString(16)} ${it.payload.joinToString("") { b -> "%02x".format(b) }}" }

    private val routeId = 1_234_567_890L
    private val nowMs = 1_700_000_000_000L

    @Test fun `update sends the nine events of updateNavigation in order, byte for byte`() {
        val events = HudLauncherMapCnFrames.update(
            iconId = 1, distanceM = 444, remainDistanceM = 15_000, remainTimeS = 900,
            position = HudLauncherMapCnFrames.Position(lat = 53.9, lon = 27.56),
            routeId = routeId, counter = 5, nowMs = nowMs,
        )
        assertEquals(
            listOf(
                "0x4000700078001 0a022065",
                "0x482028202800b 0a0908011002180020bc03",
                "0x4000700078003 0a1a8801987590018407598fc2f5285c8f3b40613333333333f34a40",
                "0x4000c000c8001 0a0608febeb3eb09",
                "0x4000c000c8003 0a0608cd9eefb806",
                "0x4000d000d8001 0a1a08f8c1e6b00d2801610000000000001440699a99999999990140",
                "0x4000d000d8002 0a0608f6d0a99c0e",
                "0x4000d000d8005 0a0808b68ec3960f1801",
                "0x4000e000e8001 0a1108d285d8cc041801210000796090281843",
                "0x4001700178003 0a4d0a1108d285d8cc0410051900007960902818431a3609767746b1df249240115113412724598e40" +
                    "19fcb5a079aa2833c02150a1605890ef573f29692dfdb4bd23f9bf31c789be2520ab6e3f3807",
            ),
            hex(events),
        )
    }

    @Test fun `stop sends the three off events of stopNavigation in order, byte for byte`() {
        assertEquals(
            listOf(
                "0x4000d000d8001 0a1a08f8c1e6b00d2800610000000000000000690000000000000000",
                "0x4000d000d8005 0a0808b68ec3960f1800",
                "0x4000e000e8001 0a1108d285d8cc041800210000796090281843",
            ),
            hex(HudLauncherMapCnFrames.stop(routeId, nowMs)),
        )
    }

    @Test fun `the family has eleven topics on six gateway services, in OpenBYD's order`() {
        assertEquals(11, HudLauncherMapCnFrames.TOPICS.size)
        assertEquals(
            listOf(0xB000700070000L, 0xB820282020000L, 0xB000C000C0000L, 0xB000D000D0000L, 0xB000E000E0000L, 0xB001700170000L),
            HudLauncherMapCnFrames.SERVICE_IDS,
        )
        // Every topic an update or a stop fires is carried by one of those services.
        val fired = HudLauncherMapCnFrames.update(1, 444, 0, 0, HudLauncherMapCnFrames.Position(0.0, 0.0), 1L, 0, 0L) +
            HudLauncherMapCnFrames.stop(1L, 0L)
        assertTrue(fired.all { HudSomeIpBridge.serviceIdFor(it.topic) in HudLauncherMapCnFrames.SERVICE_IDS })
    }

    @Test fun `main action follows mapManeuverToMainAction`() {
        assertEquals(2, HudLauncherMapCnFrames.mainAction(1))   // left
        assertEquals(3, HudLauncherMapCnFrames.mainAction(2))   // right
        assertEquals(4, HudLauncherMapCnFrames.mainAction(4))
        assertEquals(1, HudLauncherMapCnFrames.mainAction(12))  // straight
        assertEquals(0, HudLauncherMapCnFrames.mainAction(13))
    }

    @Test fun `a route id is a random ten-digit number, the injected random decides which`() {
        val a = HudLauncherMapCnFrames.newRouteId(Random(42))
        assertEquals(a, HudLauncherMapCnFrames.newRouteId(Random(42)))
        repeat(1_000) {
            assertTrue(HudLauncherMapCnFrames.newRouteId(Random(it)) in 1_000_000_000L..9_999_999_999L)
        }
    }
}
