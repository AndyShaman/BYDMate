package com.bydmate.app.hud

import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.HudNaviReply
import com.bydmate.app.diagnostics.TraceRecorder
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavA11yFeed
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HudControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val helperClient = mockk<HelperClient>(relaxed = true)
    private val helperBootstrap = mockk<HelperBootstrap>(relaxed = true)

    @get:Rule val trace = TraceRecorder()

    private fun controller(bridge: HudSomeIpBridge? = null): HudController =
        HudController(context, helperClient, helperBootstrap).apply {
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            if (bridge != null) bridgeFactory = { _, _ -> bridge }
        }

    private fun installSomeIp() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.ts.car.someip.service" })
    }

    private fun connectedBridge(): HudSomeIpBridge {
        val bridge = mockk<HudSomeIpBridge>(relaxed = true)
        coEvery { bridge.bind() } returns true
        every { bridge.startService(any()) } returns 0
        return bridge
    }

    @Before fun reset() {
        NavGuidanceHub.reset()
        NavA11yFeed.enabled = false
        context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun `unsupported head unit makes zero helper calls and stays inert`() {
        val c = controller()
        c.setEnabled(true)
        assertEquals(HudController.Status.UNSUPPORTED, c.status.value)
        assertFalse(NavA11yFeed.enabled)
        assertFalse(c.requiresA11y())
        assertFalse(context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(HudController.KEY_SUPPORTED, true))
        verify { helperClient wasNot Called }
        verify { helperBootstrap wasNot Called }
    }

    @Test fun `supported path binds starts service and enables feed`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = controller(bridge)
        c.setEnabled(true)
        assertEquals(HudController.Status.ON, c.status.value)
        assertTrue(NavA11yFeed.enabled)
        assertTrue(c.requiresA11y())
        coVerify { helperClient.enableAccessibilityService() }
        verify { bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI) }
        c.setEnabled(false)   // stop the push loop so it does not leak into other tests
    }

    @Test fun `stop sends clear frame before stopService and unbind`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = controller(bridge)
        c.setEnabled(true)
        c.setEnabled(false)
        verifyOrder {
            bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, any())
            bridge.stopService(HudSomeIpBridge.SERVICE_ID_NAVI)
            bridge.unbind()
        }
        assertFalse(NavA11yFeed.enabled)
        assertEquals(HudController.Status.OFF, c.status.value)
    }

    @Test fun `toggle off during slow bind cancels start and unbinds`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = mockk<HudSomeIpBridge>(relaxed = true)
        coEvery { bridge.bind() } coAnswers { delay(30_000); true }
        val c = HudController(context, helperClient, helperBootstrap).apply {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            bridgeFactory = { _, _ -> bridge }
        }
        c.setEnabled(true)
        awaitTrue { c.status.value == HudController.Status.CONNECTING }
        c.setEnabled(false)
        awaitTrue { runCatching { verify { bridge.unbind() } }.isSuccess }
        awaitTrue { c.status.value == HudController.Status.OFF }
    }

    @Test fun `speed sign pref default on and persists`() {
        val c = controller()
        assertTrue(c.isSpeedSignEnabled())
        c.setSpeedSignEnabled(false)
        assertFalse(c.isSpeedSignEnabled())
    }

    @Test fun `requiresA11y false when disabled`() {
        assertFalse(controller().requiresA11y())
    }

    @Test fun `connection lost cleans up so service restart can recover`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        var onLost: () -> Unit = {}
        val c = HudController(context, helperClient, helperBootstrap).apply {
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            bridgeFactory = { _, lost -> onLost = lost; bridge }
        }
        c.setEnabled(true)
        assertEquals(HudController.Status.ON, c.status.value)
        onLost()   // gateway binding died
        awaitTrue { c.status.value == HudController.Status.BIND_FAILED }
        assertFalse(NavA11yFeed.enabled)
        c.startIfEnabled()   // TrackingService restart on next ignition
        awaitTrue { c.status.value == HudController.Status.ON }
        verify(exactly = 2) { bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI) }
        c.setEnabled(false)
    }

    @Test fun `service restart stop then start lands ON with ordered default scope`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = HudController(context, helperClient, helperBootstrap).apply {
            bridgeFactory = { _, _ -> bridge }   // scope left at its ordered default
        }
        c.setEnabled(true)
        awaitTrue { c.status.value == HudController.Status.ON }
        c.stop()             // TrackingService.onDestroy
        c.startIfEnabled()   // new service instance onCreate
        awaitTrue { c.status.value == HudController.Status.ON }
        c.setEnabled(false)
        awaitTrue { c.status.value == HudController.Status.OFF }
    }

    @Test fun `refused arming writes never stop the frames`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        coEvery { helperClient.hudNaviStatus(any()) } throws SecurityException("[setInt] permission deny!")
        coEvery { helperClient.writeStatus(any(), any(), any(), any()) } returns -10011
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300), NavGuidanceHub.Source.A11Y)
        val bridge = connectedBridge()
        val c = controller(bridge)
        c.setMode(HudController.MODE_NAVI_STATUS)   // only mode 2 arms
        c.setEnabled(true)
        awaitTrue { runCatching { coVerify { helperClient.hudNaviStatus(2) } }.isSuccess }
        awaitTrue { runCatching { verify(atLeast = 5) { bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, any()) } }.isSuccess }
        assertEquals(HudController.Status.ON, c.status.value)
        c.setEnabled(false)
    }

    @Test fun `HUD on without a guided route writes and reads nothing in the car`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = controller(bridge)
        c.setEnabled(true)
        assertEquals(HudController.Status.ON, c.status.value)
        Thread.sleep(2_500)   // the arming loop looks at the route three times
        c.setEnabled(false)
        coVerify(exactly = 0) { helperClient.writeStatus(any(), any(), any(), any()) }
        coVerify(exactly = 0) { helperClient.hudNaviStatus(any()) }
        coVerify(exactly = 0) { helperClient.writeBufferStatus(any(), any(), any()) }
        coVerify(exactly = 0) { helperClient.readBatch(any()) }
    }

    /** The car after a process killed mid-route: our values up, the layout kept in prefs, the
     *  HUD switched on. Reads answer the state, writes land in it. */
    private fun leftoverCar(): MutableMap<Pair<Int, Int>, Int> {
        context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(HudController.KEY_ENABLED, true).putInt(HudArming.KEY_AS_FOUND, 1).commit()
        val state = mutableMapOf(
            HudArming.NAVI to 2, HudArming.SCREEN to 3, HudArming.CLUSTER to 0,
            HudArming.CAN_NAVI to 1, HudArming.ISA to 1,
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

    @Test fun `service start with a leftover and no route undoes it once`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val state = leftoverCar()
        val c = controller(connectedBridge())
        c.startIfEnabled()
        assertEquals(HudController.Status.ON, c.status.value)
        coVerify(exactly = 1) { helperClient.hudNaviStatus(4) }
        assertEquals(1, state[HudArming.SCREEN])
        assertEquals(0, state[HudArming.CAN_NAVI])
        assertEquals(0, state[HudArming.ISA])
        assertFalse(context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)
            .contains(HudArming.KEY_AS_FOUND))
        c.setEnabled(false)
        coVerify(exactly = 1) { helperClient.hudNaviStatus(4) }   // nothing armed, nothing more to undo
    }

    @Test fun `a leftover with a guided route is left to the arming loop in mode 2`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        leftoverCar()
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300), NavGuidanceHub.Source.A11Y)
        val c = controller(connectedBridge())
        c.setMode(HudController.MODE_NAVI_STATUS)
        c.startIfEnabled()
        coVerify(exactly = 1) { helperClient.hudNaviStatus(2) }
        coVerify(exactly = 0) { helperClient.hudNaviStatus(4) }
        c.setEnabled(false)
    }

    // --- status lines (trace audit) ---

    @Test fun `a failed gateway start is a status line with its step and rc`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = mockk<HudSomeIpBridge>(relaxed = true)
        coEvery { bridge.bind() } returns true
        every { bridge.startService(any()) } returns -2
        val c = controller(bridge)
        c.setEnabled(true)
        assertEquals(HudController.Status.BIND_FAILED, c.status.value)
        val status = trace.events().filter { it.contains(" status ") }
        assertEquals(2, status.size)
        assertTrue(status[0], status[0].contains("status to=connecting #"))
        assertTrue(status[1], status[1].contains("status to=bind_failed step=start_service rc=-2 #"))
    }

    @Test fun `the output on and off are status lines, the way goes to the route summary`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val c = controller(connectedBridge())
        c.setEnabled(true)
        assertEquals(HudController.MODE_GLASS_ONLY, NavGuidanceHub.hudWay)
        c.setEnabled(false)
        awaitTrue { c.status.value == HudController.Status.OFF }
        assertEquals(0, NavGuidanceHub.hudWay)
        val status = trace.events().filter { it.contains(" status ") }.map { it.substringAfter("status ").substringBefore(" #") }
        assertEquals(listOf("to=connecting", "to=on rc=0", "to=off"), status)
    }

    @Test fun `a service stop with the output already off writes no status line`() {
        controller().stop()
        assertTrue(trace.events().none { it.contains(" status ") })
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
