package com.bydmate.app.hud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [HudCheck] against a car that moves off while the check runs, written from the requirement
 * (spec: `hud-check-moving`, R5/R6), not from the implementation. Assertions look at what the fake
 * car and the fake gateway received, plus the promised log line and the final state.
 */
@RunWith(RobolectricTestRunner::class)
class HudCheckMovingSpecTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clear() {
        context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)

    /** One write the fake car received. */
    private data class Write(val dev: Int, val fid: Int, val value: Int)

    /** A fake car at rest: W-HUD present, HUD layout 1, no navigation, cluster not fullscreen. */
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

    private class Setup(val check: HudCheck, val car: FakeCar, val bridge: HudSomeIpBridge, val controller: HudController) {
        val lines = mutableListOf<String>()
        /** Virtual times of test frames (clear frames excluded). */
        val testFrames = mutableListOf<Long>()
        /** Virtual times the check asked for the speed. */
        val speedReads = mutableListOf<Long>()
    }

    /** [speedAt] gives the speed at a virtual time; [controller] null = no HUD projection running. */
    private fun TestScope.setup(controller: HudController? = null, speedAt: (Long) -> Int?): Setup {
        val car = FakeCar()
        val bootstrap = mockk<HelperBootstrap>()
        coEvery { bootstrap.ensureRunning() } returns true
        val ctl = controller ?: HudController(context, car.helper, bootstrap)
        val bridge = mockk<HudSomeIpBridge>(relaxed = true).also {
            coEvery { it.bind() } returns true
            every { it.startService(any()) } returns 0
        }
        val s = Setup(HudCheck(context, car.helper, bootstrap, ctl), car, bridge, ctl)
        val clear = HudProtobufBuilder.buildClearFrame(0)
        every { bridge.fireEvent(any(), any()) } answers {
            if (!secondArg<ByteArray>().contentEquals(clear)) s.testFrames += testScheduler.currentTime
            0
        }
        s.check.apply {
            bridgeFactory = { bridge }
            gatewayPresent = { true }
            guidanceActive = { false }
            speedKmh = {
                s.speedReads += testScheduler.currentTime
                speedAt(testScheduler.currentTime)
            }
            log = { s.lines += it }
        }
        return s
    }

    /** A live HUD projection: its arming loop runs, it lends its gateway to the check. */
    private class LiveProduct {
        val bridge: HudSomeIpBridge = mockk(relaxed = true)
        val controller: HudController = mockk(relaxed = true)

        init {
            every { bridge.fireEvent(any(), any()) } returns 0
            every { controller.boundBridge } returns bridge
            every { controller.armingLive } returns true
        }
    }

    private fun Setup.wrote(fid: Pair<Int, Int>, value: Int) =
        car.writes.any { it.dev == fid.first && it.fid == fid.second && it.value == value }

    // --- R5: the speed is looked at about once a second through the steps ---

    @Test fun `R5 the speed is read about once a second while the check runs`() = runTest {
        val s = setup { 0 }
        s.check.run()
        assertEquals(HudCheck.State.Done, s.check.state.value)
        // The steps last about 80 s: one look before they start, then about one a second.
        assertTrue("only ${s.speedReads.size} speed reads", s.speedReads.size >= 45)
        val gaps = s.speedReads.zipWithNext { a, b -> b - a }
        assertTrue("a gap of ${gaps.max()} ms without a speed read", gaps.all { it <= 2_000 })
        assertTrue("the speed read faster than about once a second", gaps.all { it >= 900 })
    }

    // --- R5: moving off stops the check and puts everything back ---

    @Test fun `R5 moving off in step 2 stops the test frames and puts the car back`() = runTest {
        val s = setup { t -> if (t >= 25_000) 30 else 0 }
        s.check.run()
        assertTrue("a test frame went out after the car moved off",
            s.testFrames.none { it > 25_000 + 2_000 })
        assertTrue(s.lines.contains("hudprobe: aborted reason=moving step=2"))
        // The same restore as a normal end: status closed, layout as found, canNavi and isa cleared.
        assertTrue(s.wrote(HudArming.NAVI, HudArming.NAVI_CLOSED))
        assertEquals(1, s.car.state[HudArming.SCREEN])
        assertEquals(0, s.car.state[HudArming.CAN_NAVI])
        assertEquals(0, s.car.state[HudArming.ISA])
        assertFalse(prefs().contains(HudArming.KEY_AS_FOUND))
        verify { s.bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, match { it.contentEquals(HudProtobufBuilder.buildClearFrame(0)) }) }
        verify { s.bridge.stopService(HudSomeIpBridge.SERVICE_ID_NAVI) }
        verify { s.bridge.unbind() }
        assertEquals(HudCheck.State.Refused(HudCheck.Refusal.MOVING), s.check.state.value)
        assertFalse(s.controller.armingPaused)
    }

    @Test fun `R5 an unknown speed in step 1 stops the check the same way`() = runTest {
        val s = setup { t -> if (t >= 5_000) null else 0 }
        s.check.run()
        assertTrue(s.testFrames.none { it > 5_000 + 2_000 })
        assertTrue(s.lines.contains("hudprobe: aborted reason=moving step=1"))
        assertTrue("nothing was armed in step 1, nothing to write back", s.car.writes.isEmpty())
        assertEquals(HudCheck.State.Refused(HudCheck.Refusal.MOVING), s.check.state.value)
        assertFalse(s.controller.armingPaused)
    }

    @Test fun `R5 moving off in step 3 blanks the CAN fields and puts the car back`() = runTest {
        val s = setup { t -> if (t >= 45_000) 12 else 0 }
        s.check.run()
        assertTrue(s.lines.contains("hudprobe: aborted reason=moving step=3"))
        val canTurn = s.car.writes.filter { it.dev == HudCanChannel.DEV && it.fid == HudCanChannel.FID_TURN_KIND }
        val canDist = s.car.writes.filter { it.dev == HudCanChannel.DEV && it.fid == HudCanChannel.FID_TURN_DISTANCE_M }
        assertEquals(HudCanChannel.TURN_NONE, canTurn.last().value)
        assertEquals(HudCanChannel.DISTANCE_NONE, canDist.last().value)
        // No CAN re-send after the car moved off: the step-3 re-sends are 5 s apart, the look 1 s.
        assertEquals(1, canTurn.count { it.value == HudCanChannel.TURN_LEFT })
        assertTrue(s.wrote(HudArming.NAVI, HudArming.NAVI_CLOSED))
        assertEquals(1, s.car.state[HudArming.SCREEN])
        assertEquals(HudCheck.State.Refused(HudCheck.Refusal.MOVING), s.check.state.value)
    }

    // --- R6: a route that starts keeps its outcome, also when the car moves off with it ---

    @Test fun `R6 a route and moving off in the same second end as RouteStarted, handed to the projection`() = runTest {
        val product = LiveProduct()
        val s = setup(controller = product.controller) { t -> if (t >= 25_000) 30 else 0 }
        s.check.guidanceActive = { testScheduler.currentTime >= 25_000 }
        s.check.run()
        assertEquals(HudCheck.State.RouteStarted, s.check.state.value)
        assertTrue(s.lines.contains("hudprobe: aborted reason=guidance step=2"))
        assertTrue(s.lines.none { it.startsWith("hudprobe: aborted reason=moving") })
        // The route's hand-over: the status and the layout stay up for the projection.
        assertFalse(s.wrote(HudArming.NAVI, HudArming.NAVI_CLOSED))
        assertTrue(prefs().contains(HudArming.KEY_AS_FOUND))
    }

    @Test fun `R6 a route alone during the check still ends as RouteStarted`() = runTest {
        val s = setup { 0 }
        s.check.guidanceActive = { testScheduler.currentTime >= 25_000 }
        s.check.run()
        assertEquals(HudCheck.State.RouteStarted, s.check.state.value)
        assertTrue(s.lines.contains("hudprobe: aborted reason=guidance step=2"))
    }
}
