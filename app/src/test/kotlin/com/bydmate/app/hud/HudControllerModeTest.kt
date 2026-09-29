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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
 * #266: mode 1 (the default, 3.19.0 behaviour) sends hints to the glass and never raises the
 * car's navigation status; mode 2 raises it during a route as 3.19.1/3.19.2 did.
 */
@RunWith(RobolectricTestRunner::class)
class HudControllerModeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val helperClient = mockk<HelperClient>(relaxed = true)
    private val helperBootstrap = mockk<HelperBootstrap>(relaxed = true)
    private lateinit var bridge: HudSomeIpBridge

    @Before fun reset() {
        NavGuidanceHub.reset()
        NavA11yFeed.enabled = false
        prefs().edit().clear().commit()
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.ts.car.someip.service" })
        coEvery { helperBootstrap.ensureRunning() } returns true
        bridge = mockk(relaxed = true)
        coEvery { bridge.bind() } returns true
        every { bridge.startService(any()) } returns 0
    }

    private fun prefs() = context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)

    private fun controller(): HudController = HudController(context, helperClient, helperBootstrap).apply {
        scope = CoroutineScope(Dispatchers.Unconfined)
        bridgeFactory = { _, _ -> bridge }
    }

    private fun guideRoute() =
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300), NavGuidanceHub.Source.A11Y)

    /** A car answering reads from [state] and landing writes in it; layout 1, nothing raised. */
    private fun car(): MutableMap<Pair<Int, Int>, Int> {
        val state = mutableMapOf(
            HudArming.NAVI to 4, HudArming.SCREEN to 1, HudArming.CLUSTER to 0,
            HudArming.CAN_NAVI to 0, HudArming.ISA to 0,
        )
        coEvery { helperClient.readBatch(any()) } answers {
            firstArg<List<BatchReadItem>>().map { 0 to (state[it.dev to it.fid] ?: 0) }
        }
        coEvery { helperClient.writeStatus(any(), any(), any(), any()) } answers {
            state[arg<Int>(0) to arg<Int>(1)] = arg(2)
            1
        }
        coEvery { helperClient.hudNaviStatus(any()) } answers {
            state[HudArming.NAVI] = firstArg()
            HudNaviReply(HelperBinderProtocol.HUD_NAVI_CALLED, 0)
        }
        return state
    }

    private fun assertNothingWritten() {
        coVerify(exactly = 0) { helperClient.hudNaviStatus(any()) }
        coVerify(exactly = 0) { helperClient.writeStatus(any(), any(), any(), any()) }
        coVerify(exactly = 0) { helperClient.writeBufferStatus(any(), any(), any()) }
    }

    @Test fun `no stored mode is mode 1`() {
        assertEquals(HudController.MODE_GLASS_ONLY, controller().mode())
    }

    @Test fun `mode 1 by default with a guided route never arms and writes nothing in the car`() {
        val state = car()
        guideRoute()
        val c = controller()
        c.setEnabled(true)
        assertEquals(HudController.Status.ON, c.status.value)
        assertFalse(c.armingLive)
        awaitTrue { runCatching { verify(atLeast = 2) { bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, any()) } }.isSuccess }
        Thread.sleep(2_500)   // an arming loop would have armed on its first look
        assertNothingWritten()
        assertEquals(1, state[HudArming.SCREEN])
        c.setEnabled(false)
        assertNothingWritten()
    }

    @Test fun `mode 1 chosen explicitly behaves the same`() {
        car()
        guideRoute()
        val c = controller()
        c.setMode(HudController.MODE_GLASS_ONLY)
        c.setEnabled(true)
        Thread.sleep(1_500)
        assertFalse(c.armingLive)
        assertNothingWritten()
        c.setEnabled(false)
    }

    @Test fun `mode 2 with a guided route arms as before`() {
        val state = car()
        guideRoute()
        val c = controller()
        c.setMode(HudController.MODE_NAVI_STATUS)
        c.setEnabled(true)
        assertTrue(c.armingLive)
        awaitTrue { runCatching { coVerify { helperClient.hudNaviStatus(2) } }.isSuccess }
        assertEquals(3, state[HudArming.SCREEN])
        c.setEnabled(false)
        coVerify { helperClient.hudNaviStatus(4) }
        assertEquals(1, state[HudArming.SCREEN])
    }

    @Test fun `switching from mode 2 to mode 1 during a route disarms and puts the layout back`() {
        val state = car()
        guideRoute()
        val c = controller()
        c.setMode(HudController.MODE_NAVI_STATUS)
        c.setEnabled(true)
        awaitTrue { state[HudArming.SCREEN] == 3 }
        c.setMode(HudController.MODE_GLASS_ONLY)
        awaitTrue { !c.armingLive }
        assertEquals(4, state[HudArming.NAVI])
        assertEquals(1, state[HudArming.SCREEN])
        assertEquals(0, state[HudArming.CAN_NAVI])
        assertEquals(0, state[HudArming.ISA])
        assertFalse(prefs().contains(HudArming.KEY_AS_FOUND))
        assertEquals(HudController.Status.ON, c.status.value)   // the glass keeps its frames
        c.setEnabled(false)
    }

    @Test fun `switching from mode 1 to mode 2 during a route arms`() {
        car()
        guideRoute()
        val c = controller()
        c.setEnabled(true)
        assertFalse(c.armingLive)
        c.setMode(HudController.MODE_NAVI_STATUS)
        assertTrue(c.armingLive)
        awaitTrue { runCatching { coVerify { helperClient.hudNaviStatus(2) } }.isSuccess }
        c.setEnabled(false)
    }

    /** What 3.19.1/3.19.2 left when the process died mid-route: status up, layout 3, 1 kept. */
    private fun leftover(state: MutableMap<Pair<Int, Int>, Int>) {
        state.putAll(mapOf(HudArming.NAVI to 2, HudArming.SCREEN to 3, HudArming.CAN_NAVI to 1, HudArming.ISA to 1))
        prefs().edit().putBoolean(HudController.KEY_ENABLED, true).putInt(HudArming.KEY_AS_FOUND, 1).commit()
    }

    @Test fun `mode 1 puts a leftover back at start`() {
        val state = car().also { leftover(it) }
        val c = controller()
        c.startIfEnabled()
        awaitTrue { !prefs().contains(HudArming.KEY_AS_FOUND) }
        assertEquals(4, state[HudArming.NAVI])
        assertEquals(1, state[HudArming.SCREEN])
        assertFalse(c.armingLive)
        c.setEnabled(false)
    }

    @Test fun `mode 1 puts a leftover back at start even while a route is guided`() {
        val state = car().also { leftover(it) }
        guideRoute()
        val c = controller()
        c.startIfEnabled()
        awaitTrue { !prefs().contains(HudArming.KEY_AS_FOUND) }
        assertEquals(4, state[HudArming.NAVI])
        assertEquals(1, state[HudArming.SCREEN])
        coVerify(exactly = 0) { helperClient.hudNaviStatus(2) }
        c.setEnabled(false)
    }

    private fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(50)
        }
        assertTrue(cond())
    }
}
