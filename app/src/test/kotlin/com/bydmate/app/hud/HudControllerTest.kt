package com.bydmate.app.hud

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

    // --- #301: the cluster card through the Amap adapter, only without the gateway ---

    private val shadowApp get() = shadowOf(ApplicationProvider.getApplicationContext<Application>())
    private fun hudPrefs() = context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)
    private fun broadcasts(): List<Intent> = shadowApp.broadcastIntents.toList()
    private fun kills() = broadcasts().filter { it.action == HudAmapClusterLoop.ACTION_KILL }

    private fun installPkg(pkg: String) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = pkg })
    }

    private fun guidedRoute() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(maneuverGaode = 2, distanceMeters = 300, road = "Birch Street"))
    }

    @Test fun `a car with the gateway keeps the TYPE 8 path and never gets the cluster loop`() {
        installSomeIp()
        installPkg("com.example.amapservice")
        installPkg("com.byd.amapservice")
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        guidedRoute()
        val c = controller(bridge)
        c.setEnabled(true)
        assertEquals(HudController.Status.ON, c.status.value)
        verify { bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI) }
        assertNull(c.amapCluster)
        val amap = broadcasts().filter { it.action == HudAmapBroadcaster.ACTION }
        assertTrue(amap.isNotEmpty())
        assertTrue(amap.all { it.getIntExtra("TYPE", -99) == 8 })
        assertTrue(kills().isEmpty())
        c.setEnabled(false)
        awaitTrue { c.status.value == HudController.Status.OFF }
        assertTrue(kills().isEmpty())
        assertTrue(broadcasts().none { it.getIntExtra("TYPE", -99) == 0 })
    }

    @Test fun `a car without the gateway and with the adapter runs the cluster path with zero helper calls`() {
        installPkg("com.example.amapservice")
        var bridgeBuilt = false
        val c = controller().apply { bridgeFactory = { _, _ -> bridgeBuilt = true; connectedBridge() } }
        c.setEnabled(true)
        assertEquals(HudController.Status.CLUSTER_ONLY, c.status.value)
        verify { helperClient wasNot Called }
        verify { helperBootstrap wasNot Called }
        assertFalse(bridgeBuilt)
        assertFalse(hudPrefs().getBoolean(HudController.KEY_SUPPORTED, true))
        assertFalse(c.requiresA11y())
        assertFalse(NavA11yFeed.enabled)
        assertEquals(NavGuidanceHub.WAY_AMAP_CLUSTER, NavGuidanceHub.hudWay)
        c.setEnabled(false)
    }

    @Test fun `the switch off with no leftover broadcasts nothing`() {
        installPkg("com.example.amapservice")
        val c = controller()
        c.startIfEnabled()
        assertEquals(HudController.Status.OFF, c.status.value)
        assertTrue(broadcasts().isEmpty())
    }

    @Test fun `switching off mid route is one KILL, switching off with no route is none`() {
        installPkg("com.example.amapservice")
        val idle = controller()
        idle.setEnabled(true)
        idle.setEnabled(false)
        assertEquals(HudController.Status.OFF, idle.status.value)
        assertTrue(broadcasts().isEmpty())

        guidedRoute()
        val c = controller()
        c.setEnabled(true)
        assertEquals(0, broadcasts().first().getIntExtra("TYPE", -99))
        c.setEnabled(false)
        assertEquals(HudController.Status.OFF, c.status.value)
        assertEquals(1, kills().size)
        assertEquals("com.example.amapservice", kills().single().`package`)
    }

    @Test fun `a service stop mid route is one KILL`() {
        installPkg("com.example.amapservice")
        guidedRoute()
        val c = controller()
        c.setEnabled(true)
        c.stop()
        assertEquals(1, kills().size)
    }

    private fun leftoverKey(enabled: Boolean) {
        hudPrefs().edit().putBoolean(HudController.KEY_ENABLED, enabled)
            .putLong(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT, 1L).commit()
    }

    @Test fun `a cluster leftover with no route is one KILL when the cluster path starts`() {
        installPkg("com.example.amapservice")
        leftoverKey(enabled = true)
        val c = controller()
        c.startIfEnabled()
        assertEquals(1, kills().size)
        assertFalse(hudPrefs().contains(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT))
        c.stop()
        assertEquals(1, kills().size)
    }

    @Test fun `a cluster leftover with the switch off is one KILL even while a route is guided`() {
        installPkg("com.example.amapservice")
        guidedRoute()
        leftoverKey(enabled = false)
        controller().startIfEnabled()
        assertEquals(1, kills().size)
        assertEquals("com.example.amapservice", kills().single().`package`)
        assertFalse(hudPrefs().contains(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT))
    }

    @Test fun `a leftover key on a car with the gateway is dropped without a KILL`() {
        installSomeIp()
        installPkg("com.example.amapservice")
        leftoverKey(enabled = false)
        controller().startIfEnabled()
        assertTrue(broadcasts().isEmpty())
        assertFalse(hudPrefs().contains(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT))
    }

    /** The application context with a package manager whose probes answer [gateway] and [adapter]:
     *  null = installed, else the throwable getPackageInfo throws. */
    private fun probing(gateway: Throwable?, adapter: Throwable?): Context {
        val pm = mockk<PackageManager>()
        for ((pkg, t) in listOf("com.ts.car.someip.service" to gateway, "com.example.amapservice" to adapter)) {
            if (t == null) every { pm.getPackageInfo(pkg, any<Int>()) } returns PackageInfo()
            else every { pm.getPackageInfo(pkg, any<Int>()) } throws t
        }
        return object : ContextWrapper(context) {
            override fun getPackageManager(): PackageManager = pm
        }
    }

    @Test fun `a gateway probe that fails for another reason never starts the cluster path`() {
        for (probe in listOf(
            probing(gateway = RuntimeException("package manager died"), adapter = null),
            probing(gateway = PackageManager.NameNotFoundException(), adapter = RuntimeException("package manager died")),
        )) {
            guidedRoute()
            val c = HudController(probe, helperClient, helperBootstrap).apply { scope = CoroutineScope(Dispatchers.Unconfined) }
            c.setEnabled(true)
            assertEquals(HudController.Status.UNSUPPORTED, c.status.value)
            assertNull(c.amapCluster)
            assertTrue(broadcasts().isEmpty())
            c.setEnabled(false)
        }
    }

    @Test fun `a failed re-probe keeps the running cluster loop and its status`() {
        var gatewayProbe: Throwable = PackageManager.NameNotFoundException()
        val pm = mockk<PackageManager>()
        every { pm.getPackageInfo("com.ts.car.someip.service", any<Int>()) } answers { throw gatewayProbe }
        every { pm.getPackageInfo("com.example.amapservice", any<Int>()) } returns PackageInfo()
        val probe = object : ContextWrapper(context) {
            override fun getPackageManager(): PackageManager = pm
        }
        guidedRoute()
        val test = TestScope(StandardTestDispatcher())
        val c = HudController(probe, helperClient, helperBootstrap).apply { scope = test }
        c.setEnabled(true)
        test.testScheduler.runCurrent()
        val loop = c.amapCluster
        assertEquals(HudController.Status.CLUSTER_ONLY, c.status.value)
        gatewayProbe = SecurityException("package manager refused")
        c.startIfEnabled()
        test.testScheduler.runCurrent()
        test.testScheduler.advanceTimeBy(2_500)   // frames at 1 and 2 s
        assertEquals(HudController.Status.CLUSTER_ONLY, c.status.value)
        assertTrue(loop != null && c.amapCluster === loop)
        assertEquals(3, broadcasts().count { it.action == HudAmapBroadcaster.ACTION })
        c.setEnabled(false)
        test.testScheduler.runCurrent()
    }

    @Test fun `the controller's own loop sends one frame a second and one KILL when the route ends`() {
        installPkg("com.example.amapservice")
        guidedRoute()
        val test = TestScope(StandardTestDispatcher())
        val c = HudController(context, helperClient, helperBootstrap).apply { scope = test }
        c.setEnabled(true)
        test.testScheduler.runCurrent()
        assertEquals(HudController.Status.CLUSTER_ONLY, c.status.value)
        test.testScheduler.advanceTimeBy(4_500)   // frames at 0, 1, 2, 3 and 4 s
        NavGuidanceHub.reset()                    // the route ends
        test.testScheduler.advanceTimeBy(5_000)
        val guide = broadcasts().filter { it.action == HudAmapBroadcaster.ACTION }
        assertEquals(5, guide.size)
        assertTrue(guide.all { it.getIntExtra("TYPE", -99) == 0 })
        assertEquals(1, kills().size)
        c.setEnabled(false)
        test.testScheduler.runCurrent()
        assertEquals(1, kills().size)
        assertEquals(HudController.Status.OFF, c.status.value)
    }

    @Test fun `a gateway car without the leftover key broadcasts nothing at start`() {
        installSomeIp()
        installPkg("com.example.amapservice")
        coEvery { helperBootstrap.ensureRunning() } returns true
        controller(connectedBridge()).startIfEnabled()
        assertTrue(broadcasts().isEmpty())
        hudPrefs().edit().putBoolean(HudController.KEY_ENABLED, true).commit()
        val c = controller(connectedBridge())
        c.startIfEnabled()
        assertEquals(HudController.Status.ON, c.status.value)
        assertTrue(broadcasts().isEmpty())
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
