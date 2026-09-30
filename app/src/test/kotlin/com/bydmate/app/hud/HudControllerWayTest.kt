package com.bydmate.app.hud

import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.HudNaviReply
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.navdata.NavA11yFeed
import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceHub
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * «Способ вывода на стекло»: way 1 is 3.19.0's output untouched; way 2 adds the raised status and
 * the CAN guidance fields; way 3 adds the LAUNCHER_MAP_CN family. Whatever ends a route's output
 * (route end, way change, HUD off) blanks the CAN fields while the status is still up, then stops
 * the family, then disarms; what a process death left is cleaned at the next start.
 */
@RunWith(RobolectricTestRunner::class)
class HudControllerWayTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val helperClient = mockk<HelperClient>(relaxed = true)
    private val helperBootstrap = mockk<HelperBootstrap>(relaxed = true)
    private lateinit var bridge: HudSomeIpBridge
    /** The car's and the gateway's calls on one timeline. */
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before fun reset() {
        NavGuidanceHub.reset()
        NavA11yFeed.enabled = false
        prefs().edit().clear().commit()
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.ts.car.someip.service" })
        coEvery { helperBootstrap.ensureRunning() } returns true
        bridge = mockk(relaxed = true)
        coEvery { bridge.bind() } returns true
        every { bridge.startService(any()) } answers { calls += "start 0x${firstArg<Long>().toString(16)}"; 0 }
        every { bridge.stopService(any()) } answers { calls += "stop 0x${firstArg<Long>().toString(16)}"; 0 }
        every { bridge.fireEvent(any(), any()) } answers {
            if (firstArg<Long>() != HudSomeIpBridge.TOPIC_NAVI) calls += "fire 0x${firstArg<Long>().toString(16)}"
            0
        }
    }

    private fun prefs() = context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)

    private fun controller(): HudController = HudController(context, helperClient, helperBootstrap).apply {
        scope = CoroutineScope(Dispatchers.Unconfined)
        bridgeFactory = { _, _ -> bridge }
    }

    private fun guideRoute() =
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300, road = "Main St"), NavGuidanceHub.Source.A11Y)

    private fun car(): MutableMap<Pair<Int, Int>, Int> {
        val state = Collections.synchronizedMap(mutableMapOf(
            HudArming.NAVI to 4, HudArming.SCREEN to 1, HudArming.CLUSTER to 0,
            HudArming.CAN_NAVI to 0, HudArming.ISA to 0,
        ))
        coEvery { helperClient.readBatch(any()) } answers {
            firstArg<List<BatchReadItem>>().map { 0 to (state[it.dev to it.fid] ?: 0) }
        }
        coEvery { helperClient.writeStatus(any(), any(), any(), any()) } answers {
            calls += "set ${arg<Int>(0)}/${arg<Int>(1)}=${arg<Int>(2)}"
            state[arg<Int>(0) to arg<Int>(1)] = arg(2)
            1
        }
        coEvery { helperClient.writeBufferStatus(any(), any(), any()) } answers {
            calls += "buf ${arg<Int>(1)}=${String(arg<ByteArray>(2), Charsets.UTF_16LE)}"
            0
        }
        coEvery { helperClient.hudNaviStatus(any()) } answers {
            calls += "sdk ${firstArg<Int>()}"
            state[HudArming.NAVI] = firstArg()
            HudNaviReply(HelperBinderProtocol.HUD_NAVI_CALLED, 0)
        }
        return state
    }

    private val can = "set ${HudCanChannel.DEV}/"
    private val canClear = listOf(
        "set 1007/${HudCanChannel.FID_TURN_KIND}=0", "set 1007/${HudCanChannel.FID_GUIDE_INFO_ROAD_AHEAD}=0",
        "set 1007/${HudCanChannel.FID_TURN_DISTANCE_M}=0", "buf ${HudCanChannel.FID_NEXT_PATHNAME}= ",
    )
    private val canShown = "set 1007/${HudCanChannel.FID_TURN_DISTANCE_M}=300"
    private val lmcnStarts = HudLauncherMapCnFrames.SERVICE_IDS.map { "start 0x${it.toString(16)}" }
    private val lmcnStops = HudLauncherMapCnFrames.SERVICE_IDS.map { "stop 0x${it.toString(16)}" }
    private val lmcnOff = listOf("fire 0x4000d000d8001", "fire 0x4000d000d8005", "fire 0x4000e000e8001")

    private fun snapshot(): List<String> = synchronized(calls) { calls.toList() }

    /** The CAN clear sits before the status goes down, and right after the last CAN write. */
    private fun assertClearedBeforeDisarm(calls: List<String>) {
        val down = calls.indexOf("sdk 4")
        assertTrue("status closed: $calls", down >= 0)
        val clearAt = Collections.indexOfSubList(calls, canClear)
        assertTrue("CAN cleared: $calls", clearAt >= 0)
        assertTrue("clear before the disarm: $calls", clearAt < down)
        assertTrue("nothing drawn after the clear: $calls",
            calls.drop(clearAt + canClear.size).none { it.startsWith(can) && !it.endsWith("=0") })
    }

    // --- way 1 ---

    @Test fun `way 1 draws only the frames on the navigation service`() {
        car()
        guideRoute()
        val c = controller()
        c.setEnabled(true)
        awaitTrue { c.status.value == HudController.Status.ON }
        Thread.sleep(1_500)
        c.setEnabled(false)
        awaitTrue { c.status.value == HudController.Status.OFF }
        assertEquals(
            listOf("start 0x${HudSomeIpBridge.SERVICE_ID_NAVI.toString(16)}", "stop 0x${HudSomeIpBridge.SERVICE_ID_NAVI.toString(16)}"),
            snapshot(),
        )
        assertFalse(prefs().contains(HudWayChannels.KEY_CAN_LEFT))
        assertFalse(prefs().contains(HudWayChannels.KEY_LMCN_LEFT))
    }

    // --- way 2 ---

    @Test fun `way 2 writes the CAN fields during a route and blanks them before the status goes down at its end`() {
        car()
        guideRoute()
        val c = controller()
        c.setMode(HudController.MODE_NAVI_STATUS)
        c.setEnabled(true)
        awaitTrue { snapshot().contains(canShown) }
        assertTrue(snapshot().contains("sdk 2"))
        assertTrue(prefs().getBoolean(HudWayChannels.KEY_CAN_LEFT, false))
        NavGuidanceHub.reset()   // the route ends
        awaitTrue { snapshot().contains("sdk 4") }
        Thread.sleep(500)
        assertClearedBeforeDisarm(snapshot())
        assertFalse(prefs().contains(HudWayChannels.KEY_CAN_LEFT))
        assertTrue(snapshot().none { it.startsWith("start 0x") && it != "start 0x${HudSomeIpBridge.SERVICE_ID_NAVI.toString(16)}" })
        c.setEnabled(false)
    }

    @Test fun `switching way 2 to way 1 mid-route blanks the CAN fields, then disarms, then writes no more`() {
        val state = car()
        guideRoute()
        val c = controller()
        c.setMode(HudController.MODE_NAVI_STATUS)
        c.setEnabled(true)
        awaitTrue { snapshot().contains(canShown) }
        c.setMode(HudController.MODE_GLASS_ONLY)
        awaitTrue { !c.armingLive && snapshot().contains("sdk 4") }
        assertClearedBeforeDisarm(snapshot())
        val after = snapshot().size
        Thread.sleep(1_500)
        assertEquals(after, snapshot().size)
        assertEquals(4, state[HudArming.NAVI])
        assertFalse(prefs().contains(HudWayChannels.KEY_CAN_LEFT))
        c.setEnabled(false)
    }

    @Test fun `HUD off in way 2 blanks the CAN fields before the status goes down`() {
        car()
        guideRoute()
        val c = controller()
        c.setMode(HudController.MODE_NAVI_STATUS)
        c.setEnabled(true)
        awaitTrue { snapshot().contains(canShown) }
        c.setEnabled(false)
        awaitTrue { c.status.value == HudController.Status.OFF }
        assertClearedBeforeDisarm(snapshot())
        assertFalse(prefs().contains(HudWayChannels.KEY_CAN_LEFT))
    }

    // --- way 3 ---

    @Test fun `way 3 runs the family during a route and stops it after the CAN clear and before the disarm`() {
        car()
        guideRoute()
        val c = controller()
        c.setMode(HudController.MODE_LMCN)
        c.setEnabled(true)
        awaitTrue { snapshot().count { it == "fire 0x${HudLauncherMapCnFrames.TOPIC_GUIDE_STATE.toString(16)}" } >= 3 }
        assertTrue(snapshot().containsAll(lmcnStarts))
        assertTrue(snapshot().contains(canShown))
        assertTrue(prefs().contains(HudWayChannels.KEY_LMCN_LEFT))
        c.setEnabled(false)
        awaitTrue { c.status.value == HudController.Status.OFF }
        val calls = snapshot()
        assertClearedBeforeDisarm(calls)
        val clearEnd = Collections.indexOfSubList(calls, canClear) + canClear.size
        assertEquals(lmcnOff + lmcnStops, calls.subList(clearEnd, clearEnd + lmcnOff.size + lmcnStops.size))
        assertTrue(calls.indexOf(lmcnStops.last()) < calls.indexOf("sdk 4"))
        assertFalse(prefs().contains(HudWayChannels.KEY_LMCN_LEFT))
    }

    @Test fun `switching way 2 to way 3 mid-route starts the family`() {
        car()
        guideRoute()
        val c = controller()
        c.setMode(HudController.MODE_NAVI_STATUS)
        c.setEnabled(true)
        awaitTrue { snapshot().contains(canShown) }
        c.setMode(HudController.MODE_LMCN)
        awaitTrue { snapshot().containsAll(lmcnStarts) }
        awaitTrue { snapshot().contains("fire 0x${HudLauncherMapCnFrames.TOPIC_GUIDE_STATE.toString(16)}") }
        c.setEnabled(false)
        awaitTrue { c.status.value == HudController.Status.OFF }
        assertTrue(snapshot().containsAll(lmcnStops))
    }

    // --- what a process death left ---

    /** Way 2 or 3 killed mid-route: status and layout up, CAN values on the instrument. */
    private fun leftover(state: MutableMap<Pair<Int, Int>, Int>, enabled: Boolean) {
        state.putAll(mapOf(HudArming.NAVI to 2, HudArming.SCREEN to 3, HudArming.CAN_NAVI to 1, HudArming.ISA to 1))
        prefs().edit().putBoolean(HudController.KEY_ENABLED, enabled).putInt(HudArming.KEY_AS_FOUND, 1)
            .putBoolean(HudWayChannels.KEY_CAN_LEFT, true).commit()
    }

    @Test fun `a crash-left CAN is blanked at start before the leftover disarm`() {
        val state = car().also { leftover(it, enabled = true) }
        prefs().edit().putInt(HudController.KEY_MODE, HudController.MODE_NAVI_STATUS).commit()
        val c = controller()
        c.startIfEnabled()
        awaitTrue { !prefs().contains(HudArming.KEY_AS_FOUND) && !prefs().contains(HudWayChannels.KEY_CAN_LEFT) }
        assertClearedBeforeDisarm(snapshot())
        assertEquals(1, state[HudArming.SCREEN])
        c.setEnabled(false)
    }

    @Test fun `a crash-left CAN is blanked with HUD off too`() {
        car().also { leftover(it, enabled = false) }
        val c = controller()
        c.startIfEnabled()
        awaitTrue { !prefs().contains(HudArming.KEY_AS_FOUND) && !prefs().contains(HudWayChannels.KEY_CAN_LEFT) }
        assertClearedBeforeDisarm(snapshot())
    }

    @Test fun `a crash-left CAN alone is blanked in way 1 and nothing else is written`() {
        car()
        prefs().edit().putBoolean(HudController.KEY_ENABLED, true).putBoolean(HudWayChannels.KEY_CAN_LEFT, true).commit()
        val c = controller()
        c.startIfEnabled()
        awaitTrue { !prefs().contains(HudWayChannels.KEY_CAN_LEFT) }
        Thread.sleep(500)
        assertEquals(canClear, snapshot().filterNot { it.startsWith("start") })
        c.setEnabled(false)
    }

    @Test fun `a crash-left family is stopped on the new binding`() {
        car()
        prefs().edit().putBoolean(HudController.KEY_ENABLED, true).putLong(HudWayChannels.KEY_LMCN_LEFT, 1_234_567_890L).commit()
        val c = controller()
        c.startIfEnabled()
        awaitTrue { !prefs().contains(HudWayChannels.KEY_LMCN_LEFT) }
        assertTrue(snapshot().containsAll(lmcnOff + lmcnStops))
        c.setEnabled(false)
    }

    @Test fun `a refused CAN clear at start is kept for the next start`() {
        val state = car().also { leftover(it, enabled = true) }
        coEvery { helperClient.writeBufferStatus(any(), any(), any()) } returns -1
        val c = controller()
        c.startIfEnabled()
        awaitTrue { !prefs().contains(HudArming.KEY_AS_FOUND) }
        assertTrue(prefs().getBoolean(HudWayChannels.KEY_CAN_LEFT, false))
        assertEquals(4, state[HudArming.NAVI])
        c.setEnabled(false)
    }

    private fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(50)
        }
        assertTrue("timeout: ${snapshot()} prefs=${prefs().all}", cond())
    }
}
