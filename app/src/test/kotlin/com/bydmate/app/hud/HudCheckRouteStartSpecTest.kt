package com.bydmate.app.hud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [HudCheck] against a route that starts while it runs, written from the requirement
 * (spec: `hud-check-route-start`), not from the implementation. Assertions look at what the fake
 * car and the fake gateway received (writes, frames), not at log wording.
 */
@RunWith(RobolectricTestRunner::class)
class HudCheckRouteStartSpecTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clear() {
        context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)

    /** One write the fake car received. */
    private data class Write(val dev: Int, val fid: Int, val value: Int)

    /** A fake car at rest: W-HUD present, no navigation, cluster not fullscreen. */
    private class FakeCar {
        val state = mutableMapOf(
            HudCheck.HUD_TYPE to 1, HudArming.NAVI to 4, HudArming.SCREEN to 1, HudArming.CLUSTER to 0,
            HudArming.CAN_NAVI to 0, HudArming.ISA to 0,
        )
        val writes = mutableListOf<Write>()
        val helper: HelperClient = mockk(relaxed = true)

        init {
            coEvery { helper.readBatch(any()) } answers {
                firstArg<List<BatchReadItem>>().map { item -> 0 to (state[item.dev to item.fid] ?: 0) }
            }
            coEvery { helper.writeStatus(any(), any(), any(), any()) } coAnswers {
                val dev = arg<Int>(0)
                val fid = arg<Int>(1)
                val value = arg<Int>(2)
                writes += Write(dev, fid, value)
                state[dev to fid] = value
                1
            }
            coEvery { helper.writeBufferStatus(any(), any(), any()) } coAnswers {
                writes += Write(arg<Int>(0), arg<Int>(1), 0)
                0
            }
            // No SDK method on this car: NAVI_STATUS always goes out as a raw fid write.
            coEvery { helper.hudNaviStatus(any()) } returns null
        }
    }

    private fun gateway(): HudSomeIpBridge = mockk<HudSomeIpBridge>(relaxed = true).also {
        coEvery { it.bind() } returns true
        every { it.startService(any()) } returns 0
        every { it.fireEvent(any(), any()) } returns 0
    }

    private class Setup(val check: HudCheck, val car: FakeCar, val bridge: HudSomeIpBridge, val controller: HudController)

    /** [controller] null = no HUD projection running (a mock stands in for the real product loop). */
    private fun setup(car: FakeCar = FakeCar(), controller: HudController? = null): Setup {
        val bootstrap = mockk<HelperBootstrap>()
        coEvery { bootstrap.ensureRunning() } returns true
        val ctl = controller ?: HudController(context, car.helper, bootstrap)
        val bridge = gateway()
        val check = HudCheck(context, car.helper, bootstrap, ctl).apply {
            bridgeFactory = { bridge }
            gatewayPresent = { true }
            speedKmh = { 0 }
            log = {}
        }
        return Setup(check, car, bridge, ctl)
    }

    /** A live HUD projection: its arming loop is running, it shares its gateway with the check. */
    private class LiveProduct {
        val bridge: HudSomeIpBridge = mockk(relaxed = true)
        val controller: HudController = mockk(relaxed = true)

        init {
            every { bridge.fireEvent(any(), any()) } returns 0
            every { controller.boundBridge } returns bridge
            every { controller.armingLive } returns true
        }
    }

    /** Guidance turns active once virtual time reaches [ms]. */
    private fun TestScope.guidanceStartingAt(ms: Long, s: Setup) {
        s.check.guidanceActive = { testScheduler.currentTime >= ms }
    }

    // --- A1: the check stops sending test frames once guidance is seen ---

    @Test fun `A1 no test frame goes out once guidance starts in step 1, at most one already in flight`() = runTest {
        val s = setup()
        val times = mutableListOf<Long>()
        every { s.bridge.fireEvent(any(), any()) } answers { times += testScheduler.currentTime; 0 }
        guidanceStartingAt(3_000, s)
        s.check.run()
        assertTrue(times.all { it < 3_000 } || times.count { it >= 3_000 } <= 1)
    }

    @Test fun `A1 no test frame goes out once guidance starts in step 2, at most one already in flight`() = runTest {
        val s = setup()
        val times = mutableListOf<Long>()
        every { s.bridge.fireEvent(any(), any()) } answers { times += testScheduler.currentTime; 0 }
        guidanceStartingAt(25_000, s)
        s.check.run()
        assertTrue(times.count { it >= 25_000 } <= 1)
    }

    // --- A2: a distinct outcome, neither Done nor a Refused ---

    @Test fun `A2 a route mid-check ends in a distinct RouteStarted state`() = runTest {
        val s = setup()
        guidanceStartingAt(25_000, s)
        s.check.run()
        assertEquals(HudCheck.State.RouteStarted, s.check.state.value)
        assertNotEquals(HudCheck.State.Done, s.check.state.value)
        assertTrue(HudCheck.State.Refused::class.java.isInstance(s.check.state.value).not())
    }

    // --- A3: CAN fields the check wrote are cleared when a route starts in step 3 ---

    @Test fun `A3 a route starting in step 3 clears the CAN fields the check had written`() = runTest {
        val s = setup()
        guidanceStartingAt(45_000, s)
        s.check.run()
        // The CAN clear is the LAST word on these fids: icon back to TURN_NONE, distance to -1
        // (the display step itself wrote a turn icon and a real distance first).
        assertEquals(HudCanChannel.TURN_NONE, s.car.writes.last { it.dev == HudCanChannel.DEV && it.fid == HudCanChannel.FID_TURN_KIND }.value)
        assertEquals(HudCanChannel.DISTANCE_NONE, s.car.writes.last { it.dev == HudCanChannel.DEV && it.fid == HudCanChannel.FID_TURN_DISTANCE_M }.value)
    }

    // --- A4: with the product's arming alive, the check leaves navi/1014/layout to it ---

    private fun assertNothingClosedOrCleared(s: Setup) {
        assertFalse("navi must not be closed by the check", s.car.writes.any { it.dev == HudArming.NAVI.first && it.fid == HudArming.NAVI.second && it.value == HudArming.NAVI_CLOSED })
        assertFalse("canNavi must not be zeroed by the check", s.car.writes.any { it.dev == HudArming.CAN_NAVI.first && it.fid == HudArming.CAN_NAVI.second && it.value == 0 })
        assertFalse("isa must not be zeroed by the check", s.car.writes.any { it.dev == HudArming.ISA.first && it.fid == HudArming.ISA.second && it.value == 0 })
        assertFalse("the layout must not be written back by the check", s.car.writes.any { it.dev == HudArming.SCREEN.first && it.fid == HudArming.SCREEN.second && it.value != HudArming.LAYOUT_NAVI })
    }

    @Test fun `A4 guidance in step 1 with the product live leaves navi, 1014 and the layout untouched`() = runTest {
        val product = LiveProduct()
        val s = setup(controller = product.controller)
        guidanceStartingAt(3_000, s)
        s.check.run()
        assertNothingClosedOrCleared(s)
        assertEquals(HudCheck.State.RouteStarted, s.check.state.value)
    }

    @Test fun `A4 guidance in step 2 with the product live leaves navi, 1014 and the layout untouched, key kept`() = runTest {
        val product = LiveProduct()
        val s = setup(controller = product.controller)
        guidanceStartingAt(25_000, s)
        s.check.run()
        assertNothingClosedOrCleared(s)
        // The check had armed (and saved the as-found layout) before the route came: the projection
        // now owns that session, so the saved key is left for its own eventual disarm.
        assertTrue(prefs().contains(HudArming.KEY_AS_FOUND))
    }

    @Test fun `A4 guidance in step 3 with the product live still clears CAN but leaves navi, 1014 and layout`() = runTest {
        val product = LiveProduct()
        val s = setup(controller = product.controller)
        guidanceStartingAt(45_000, s)
        s.check.run()
        assertNothingClosedOrCleared(s)
        assertTrue(s.car.writes.any {
            it.dev == HudCanChannel.DEV && it.fid == HudCanChannel.FID_TURN_DISTANCE_M && it.value == HudCanChannel.DISTANCE_NONE
        })
    }

    // --- A5: without a running product, the check undoes everything itself ---

    @Test fun `A5 guidance in step 2 without the product running the check fully undoes its own writes`() = runTest {
        val s = setup()   // no HUD projection: nobody else would ever close what the check opened
        guidanceStartingAt(25_000, s)
        s.check.run()
        assertTrue(s.car.writes.any { it.dev == HudArming.NAVI.first && it.fid == HudArming.NAVI.second && it.value == HudArming.NAVI_CLOSED })
        assertTrue(s.car.writes.any { it.dev == HudArming.CAN_NAVI.first && it.fid == HudArming.CAN_NAVI.second && it.value == 0 })
        assertTrue(s.car.writes.any { it.dev == HudArming.ISA.first && it.fid == HudArming.ISA.second && it.value == 0 })
        assertEquals(1, s.car.state[HudArming.SCREEN])   // the layout as found before the check ever ran
        assertFalse(prefs().contains(HudArming.KEY_AS_FOUND))
    }
}
