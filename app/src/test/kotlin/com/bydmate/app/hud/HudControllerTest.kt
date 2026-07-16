package com.bydmate.app.hud

import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HudControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val helperClient = mockk<HelperClient>(relaxed = true)
    private val helperBootstrap = mockk<HelperBootstrap>(relaxed = true)

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

    private fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(50)
        }
        assertTrue(cond())
    }
}
