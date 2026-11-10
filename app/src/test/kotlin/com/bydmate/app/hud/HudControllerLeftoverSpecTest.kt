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
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [HudController.startIfEnabled] against a layout the HUD check left kept (spec:
 * `hud-leftover-any-car`, R1/R2/R4), written from the requirement, not from the implementation.
 * What the fake car received is asserted by its state and the calls it saw.
 */
@RunWith(RobolectricTestRunner::class)
class HudControllerLeftoverSpecTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val helperClient = mockk<HelperClient>(relaxed = true)
    private val helperBootstrap = mockk<HelperBootstrap>(relaxed = true)
    private val time = HudVirtualTime()

    @Before fun reset() {
        NavGuidanceHub.reset()
        NavA11yFeed.enabled = false
        prefs().edit().clear().commit()
    }

    @After fun stopLoops() {
        time.close()
        NavGuidanceHub.reset()
        NavGuidanceHub.hudWay = 0
        NavA11yFeed.enabled = false
    }

    private fun prefs() = context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)

    private fun controller(): HudController = HudController(context, helperClient, helperBootstrap).apply {
        scope = time.scope
        bridgeFactory = { _, _ ->
            mockk<HudSomeIpBridge>(relaxed = true).also {
                coEvery { it.bind() } returns true
                every { it.startService(any()) } returns 0
            }
        }
    }

    private fun installSomeIp() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.ts.car.someip.service" })
    }

    /** The car a killed HUD check left: our status, layout 3 and flags up, the as-found 1 kept. */
    private fun leftoverCar(hudOn: Boolean): MutableMap<Pair<Int, Int>, Int> {
        prefs().edit().putBoolean(HudController.KEY_ENABLED, hudOn).putInt(HudArming.KEY_AS_FOUND, 1).commit()
        coEvery { helperBootstrap.ensureRunning() } returns true
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

    private fun assertPutBack(state: Map<Pair<Int, Int>, Int>, label: String) {
        awaitTrue("case: $label") { !prefs().contains(HudArming.KEY_AS_FOUND) }
        assertEquals("case: $label", 1, state[HudArming.SCREEN])
        assertEquals("case: $label", 4, state[HudArming.NAVI])
        assertEquals("case: $label", 0, state[HudArming.CAN_NAVI])
        assertEquals("case: $label", 0, state[HudArming.ISA])
    }

    private fun assertLeftAlone(state: Map<Pair<Int, Int>, Int>) {
        time.advance(1_000)
        coVerify(exactly = 0) { helperClient.writeStatus(any(), any(), any(), any()) }
        coVerify(exactly = 0) { helperClient.hudNaviStatus(any()) }
        assertEquals(3, state[HudArming.SCREEN])
        assertEquals(1, prefs().getInt(HudArming.KEY_AS_FOUND, -1))
    }

    // --- R1: a kept layout goes back at service start on any car ---

    /** (label, gateway installed, HUD switched on, the status it comes up in; null = not asserted).
     *  The cases without the gateway come first: an installed package stays for the test. */
    @Test fun `R1 a kept layout goes back at service start with the HUD off or no gateway`() {
        val cases = listOf(
            Triple("HUD off and no gateway", false to false, null),
            Triple("no SOME IP gateway", false to true, HudController.Status.UNSUPPORTED),
            Triple("HUD switched off", true to false, HudController.Status.OFF),
        )
        cases.forEach { (label, setup, status) ->
            val (gateway, hudOn) = setup
            prefs().edit().clear().commit()
            if (gateway) installSomeIp()
            val state = leftoverCar(hudOn)
            val c = controller()
            c.startIfEnabled()
            assertPutBack(state, label)
            if (status != null) assertEquals("case: $label", status, c.status.value)
        }
    }

    @Test fun `R1 a kept layout the running HUD check owns is left alone`() {
        val state = leftoverCar(hudOn = false)
        val c = controller()
        c.armingPaused = true
        c.startIfEnabled()
        assertLeftAlone(state)
    }

    @Test fun `R1 a kept layout is left alone while a route is guided`() {
        val state = leftoverCar(hudOn = false)
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300), NavGuidanceHub.Source.A11Y)
        controller().startIfEnabled()
        assertLeftAlone(state)
    }

    // --- R5: a status raised over a layout that could not be read goes back too ---

    /** The car a session killed mid-route left when the layout was unreadable at arm time: our
     *  status and flags up, the layout never written, only the armed marker kept. */
    private fun armedOnlyCar(hudOn: Boolean): MutableMap<Pair<Int, Int>, Int> {
        val state = leftoverCar(hudOn)
        prefs().edit().remove(HudArming.KEY_AS_FOUND).putBoolean(HudArming.KEY_ARMED, true).commit()
        state[HudArming.SCREEN] = 1
        return state
    }

    private fun assertStatusPutBack(state: Map<Pair<Int, Int>, Int>) {
        awaitTrue { !prefs().contains(HudArming.KEY_ARMED) }
        assertEquals(4, state[HudArming.NAVI])
        assertEquals(0, state[HudArming.CAN_NAVI])
        assertEquals(0, state[HudArming.ISA])
        coVerify(exactly = 0) { helperClient.writeStatus(HudArming.SCREEN.first, HudArming.SCREEN.second, any(), any()) }
    }

    @Test fun `R5 HUD switched off, a status left up without a kept layout goes back at service start`() {
        installSomeIp()
        val state = armedOnlyCar(hudOn = false)
        controller().startIfEnabled()
        assertStatusPutBack(state)
    }

    @Test fun `R5 HUD on with the gateway, a status left up without a kept layout goes back once`() {
        installSomeIp()
        val state = armedOnlyCar(hudOn = true)
        val c = controller()
        c.startIfEnabled()
        assertStatusPutBack(state)
        awaitTrue { c.status.value == HudController.Status.ON }
        coVerify(exactly = 1) { helperClient.hudNaviStatus(4) }
        c.setEnabled(false)
    }

    // --- R2: no kept layout, no side effects on a car where the HUD is off or unsupported ---

    /** The case without the gateway comes first: an installed package stays for the test. */
    @Test fun `R2 HUD off or no gateway and nothing kept, service start touches nothing`() {
        val cases = listOf(
            Triple("no gateway", false to true, HudController.Status.UNSUPPORTED),
            Triple("HUD off", true to false, HudController.Status.OFF),
        )
        cases.forEach { (label, setup, status) ->
            val (gateway, hudOn) = setup
            prefs().edit().clear().putBoolean(HudController.KEY_ENABLED, hudOn).commit()
            if (gateway) installSomeIp()
            val c = controller()
            c.startIfEnabled()
            time.advance(1_000)
            assertEquals("case: $label", status, c.status.value)
            verify { helperClient wasNot Called }
            verify { helperBootstrap wasNot Called }
        }
    }

    private fun awaitTrue(message: String? = null, cond: () -> Boolean) =
        time.awaitTrue(message = { message }, cond = cond)
}
