package com.bydmate.app.hud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.HudNaviReply
import com.bydmate.app.diagnostics.TraceRecorder
import com.bydmate.app.helper.HelperBinderProtocol
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The HUD check against a fake car and a fake gateway, on virtual time: step order and markers,
 * the log lines the check promises, the restore after a throw or a cancel, and the refusals.
 */
@RunWith(RobolectricTestRunner::class)
class HudCheckTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val trace = TraceRecorder()

    @Before fun clear() {
        context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** The car at rest with a W-HUD, HUD layout 1, no navigation. */
    private class FakeCar {
        val state = mutableMapOf(
            HudCheck.HUD_TYPE to 1, HudArming.NAVI to 4, HudArming.SCREEN to 1, HudArming.CLUSTER to 0,
            HudArming.CAN_NAVI to 0, HudArming.ISA to 0,
        )
        val calls = mutableListOf<String>()
        var bufferFailsOn: String? = null
        val helper: HelperClient = mockk(relaxed = true)

        init {
            coEvery { helper.readBatch(any()) } answers {
                firstArg<List<BatchReadItem>>().map { 0 to (state[it.dev to it.fid] ?: 0) }
            }
            // yield() like the real client's IO hop: a cancelled caller cannot write.
            coEvery { helper.writeStatus(any(), any(), any(), any()) } coAnswers {
                yield()
                calls += "set ${arg<Int>(0)}/${arg<Int>(1)}=${arg<Int>(2)}"
                state[arg<Int>(0) to arg<Int>(1)] = arg(2)
                1
            }
            coEvery { helper.writeBufferStatus(any(), any(), any()) } answers {
                val text = String(arg<ByteArray>(2), Charsets.UTF_16LE)
                calls += "buf ${arg<Int>(0)}/${arg<Int>(1)}=$text"
                check(text != bufferFailsOn) { "buffer write failed" }
                0
            }
            coEvery { helper.hudNaviStatus(any()) } coAnswers {
                yield()
                val status = firstArg<Int>()
                if (status != HelperBinderProtocol.HUD_NAVI_PROBE) {
                    calls += "sdk $status"
                    state[HudArming.NAVI] = status
                }
                HudNaviReply(HelperBinderProtocol.HUD_NAVI_CALLED, 0)
            }
        }
    }

    private fun gateway(): HudSomeIpBridge = mockk<HudSomeIpBridge>(relaxed = true).also {
        coEvery { it.bind() } returns true
        every { it.startService(any()) } returns 0
        every { it.fireEvent(any(), any()) } returns 0
    }

    private class Setup(
        val check: HudCheck,
        val car: FakeCar,
        val bridge: HudSomeIpBridge,
        val controller: HudController,
        val lines: MutableList<String>,
    )

    private fun setup(
        car: FakeCar = FakeCar(),
        controller: HudController? = null,
        guided: Boolean = false,
        speed: Int? = 0,
        gatewayPresent: Boolean = true,
    ): Setup {
        val bootstrap = mockk<HelperBootstrap>()
        coEvery { bootstrap.ensureRunning() } returns true
        val ctl = controller ?: HudController(context, car.helper, bootstrap)
        val bridge = gateway()
        val lines = mutableListOf<String>()
        val check = HudCheck(context, car.helper, bootstrap, ctl).apply {
            bridgeFactory = { bridge }
            this.gatewayPresent = { gatewayPresent }
            guidanceActive = { guided }
            speedKmh = { speed }
            fingerprint = "BYD/test:12"
            log = { lines += it }
        }
        return Setup(check, car, bridge, ctl, lines)
    }

    private fun Setup.probeLines() = lines.filter { it.startsWith("hudprobe:") }

    // --- a full run ---

    @Test fun `three steps draw their markers in order and the restore puts the car back`() = runTest {
        val s = setup()
        s.check.run()
        val probe = s.probeLines()
        assertEquals(
            "hudprobe: census fw=BYD/test:12 gateway=present sdk_navi=present hudType(951058453@1023)=1 " +
                "navi(1138753594@1007)=4 screen(1276174357@1023)=1 canNavi(1083203624@1014)=0 " +
                "isa(1262485592@1014)=0 cluster(1086337074@1007)=0",
            probe[0],
        )
        // 20 s of frames every 300 ms: 67 fires.
        assertEquals("hudprobe: step=1 chan=someip-ui7 armed=false start_rc=0 fire_rc={0:67} marker=111m", probe[1])
        assertEquals(
            "hudprobe: arm via=sdk navi rc=0 screen rc=1 canNavi rc=1 isa rc=1 readback navi=2 screen=3 canNavi=1 isa=1",
            probe[2],
        )
        assertEquals("hudprobe: step=2 chan=someip-ui7 armed=true start_rc=0 fire_rc={0:67} marker=222m", probe[3])
        assertEquals(
            "hudprobe: step=3 chan=can icon st=1/1 dist st=1 road st=0 readback icon=1 dist=333 marker=333m",
            probe[4],
        )
        assertEquals("hudprobe: can clear icon st=1/1 dist st=1 road st=0 readback icon=0 dist=-1", probe[5])
        assertEquals("hudprobe: restore navi rc=0 screen=1 rc=1 ok=true canNavi rc=1 isa rc=1 via=sdk readback navi=4 screen=1 canNavi=0 isa=0", probe[6])
        assertEquals(7, probe.size)

        // Step 1 wrote nothing; step 2 armed; step 3 wrote the CAN fields; the restore blanked
        // them, closed the status, put the layout back and cleared canNavi and isa.
        assertEquals(
            listOf("sdk 2", "set 1023/1276174357=3", "set 1014/1083203624=1", "set 1014/1262485592=1",
                "set 1007/1139806224=1", "set 1007/1139806256=1", "set 1007/1139806232=333", "buf 1007/1140461576=BYDMATE 3"),
            s.car.calls.take(8),
        )
        assertEquals(
            listOf("set 1007/1139806224=0", "set 1007/1139806256=0", "set 1007/1139806232=-1", "buf 1007/1140461576=",
                "sdk 4", "set 1023/1276174357=1", "set 1014/1083203624=0", "set 1014/1262485592=0"),
            s.car.calls.takeLast(8),
        )
        assertEquals(1, s.car.state[HudArming.SCREEN])
        assertEquals(4, s.car.state[HudArming.NAVI])
        verifyOrder {
            s.bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI)
            s.bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, any())
            s.bridge.stopService(HudSomeIpBridge.SERVICE_ID_NAVI)
            s.bridge.unbind()
        }
        assertEquals(HudCheck.State.Done, s.check.state.value)
        assertFalse(s.controller.armingPaused)
    }

    @Test fun `the SOME IP steps carry their own distance and road`() = runTest {
        val frames = mutableListOf<ByteArray>()
        val s = setup()
        every { s.bridge.fireEvent(any(), capture(frames)) } returns 0
        s.check.run()
        val step1 = HudProtobufBuilder.buildFrameSafe(2, 111, "BYDMATE 1", null, 0, 0, HudIconLoader.iconFor(2), null)
        val step2 = HudProtobufBuilder.buildFrameSafe(2, 222, "BYDMATE 2", null, 0, 0, HudIconLoader.iconFor(2), null)
        assertTrue(frames.take(67).all { it.contentEquals(step1) })
        assertTrue(frames.drop(67).take(67).all { it.contentEquals(step2) })
        // One clear frame before the CAN step, one in the restore.
        assertEquals(67 + 67 + 2, frames.size)
    }

    @Test fun `no gateway still runs the CAN step`() = runTest {
        val s = setup(gatewayPresent = false)
        s.check.run()
        val probe = s.probeLines()
        assertTrue(probe[0].contains("gateway=absent"))
        assertEquals("hudprobe: step=1 chan=someip-ui7 armed=false start_rc=absent fire_rc={} marker=111m", probe[1])
        assertTrue(probe[4].startsWith("hudprobe: step=3 chan=can "))
        verify(exactly = 0) { s.bridge.fireEvent(any(), any()) }
    }

    @Test fun `a bound projection lends its gateway and keeps its service`() = runTest {
        val car = FakeCar()
        val productBridge = gateway()
        val controller = mockk<HudController>(relaxed = true)
        every { controller.boundBridge } returns productBridge
        val s = setup(car = car, controller = controller)
        s.check.run()
        assertTrue(s.probeLines()[1].contains("start_rc=shared"))
        verify(atLeast = 1) { productBridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, any()) }
        verify(exactly = 0) { productBridge.stopService(any()) }
        verify(exactly = 0) { productBridge.unbind() }
        verify(exactly = 0) { s.bridge.startService(any()) }
        // The product's arming stands aside for the check and comes back after it.
        verifyOrder {
            controller.armingPaused = true
            controller.armingPaused = false
        }
    }

    // --- the restore always runs ---

    @Test fun `a step that throws still restores`() = runTest {
        val car = FakeCar().apply { bufferFailsOn = "BYDMATE 3" }
        val s = setup(car = car)
        s.check.run()
        val probe = s.probeLines()
        assertTrue(probe.contains("hudprobe: aborted IllegalStateException"))
        assertEquals("hudprobe: restore navi rc=0 screen=1 rc=1 ok=true canNavi rc=1 isa rc=1 via=sdk readback navi=4 screen=1 canNavi=0 isa=0", probe.last())
        assertEquals(1, car.state[HudArming.SCREEN])
        assertEquals(4, car.state[HudArming.NAVI])
        assertEquals(0, car.state[HudArming.CAN_NAVI])
        assertEquals(0, car.state[HudArming.ISA])
        verify { s.bridge.unbind() }
        assertEquals(HudCheck.State.Done, s.check.state.value)
        assertFalse(s.controller.armingPaused)
    }

    @Test fun `a cancelled check still restores`() = runTest {
        val s = setup()
        val job = launch { s.check.run() }
        advanceTimeBy(30_000)   // inside step 2, armed
        assertEquals(HudCheck.State.Step(2), s.check.state.value)
        job.cancel()
        job.join()
        assertEquals("hudprobe: restore navi rc=0 screen=1 rc=1 ok=true canNavi rc=1 isa rc=1 via=sdk readback navi=4 screen=1 canNavi=0 isa=0", s.probeLines().last())
        assertEquals(1, s.car.state[HudArming.SCREEN])
        verify { s.bridge.stopService(HudSomeIpBridge.SERVICE_ID_NAVI) }
        verify { s.bridge.unbind() }
        assertEquals(HudCheck.State.Done, s.check.state.value)
        assertFalse(s.controller.armingPaused)
    }

    @Test fun `cancelled before arming restores nothing it did not write`() = runTest {
        val s = setup()
        val job = launch { s.check.run() }
        advanceTimeBy(5_000)   // inside step 1
        job.cancel()
        job.join()
        assertEquals("hudprobe: restore navi rc=skipped screen=na rc=skipped ok=true", s.probeLines().last())
        assertTrue(s.car.calls.isEmpty())
    }

    private fun canClearEvents() = trace.events().filter { it.contains("probe-can-clear") }

    @Test fun `the restore traces the statuses of the CAN clear`() = runTest {
        val s = setup()
        s.check.run()
        val events = canClearEvents()
        assertEquals(1, events.size)
        val line = events.single()
        listOf("icon=1", "ahead=1", "dist=1", "road=0", "rb-icon=", "rb-dist=").forEach { assertTrue("$it in $line", line.contains(it)) }
        assertFalse(line.contains("ok=false"))
    }

    @Test fun `a CAN clear that throws is traced and the restore still completes`() = runTest {
        val car = FakeCar().apply { bufferFailsOn = "" }
        val s = setup(car = car)
        s.check.run()
        val line = canClearEvents().single()
        assertTrue(line, line.contains("error=IllegalStateException") && line.contains("ok=false"))
        assertTrue(s.probeLines().contains("hudprobe: can clear failed IllegalStateException"))
        assertEquals(4, car.state[HudArming.NAVI])
        assertEquals(HudCheck.State.Done, s.check.state.value)
    }

    @Test fun `a check that never reached step 3 traces no CAN clear`() = runTest {
        val s = setup()
        val job = launch { s.check.run() }
        advanceTimeBy(5_000)   // inside step 1
        job.cancel()
        job.join()
        assertTrue(canClearEvents().isEmpty())
    }

    // --- a route that starts during the check ---

    /** A projection running with its arming: the check shares its gateway. */
    private class LiveProduct {
        val bridge: HudSomeIpBridge = mockk(relaxed = true)
        val frames = mutableListOf<ByteArray>()
        val controller: HudController = mockk(relaxed = true)

        init {
            every { bridge.fireEvent(any(), capture(frames)) } returns 0
            every { controller.boundBridge } returns bridge
            every { controller.armingLive } returns true
        }

        fun clearFrames() = frames.count { it.contentEquals(HudProtobufBuilder.buildClearFrame(0)) }
    }

    private fun TestScope.routeStartsAt(ms: Long, s: Setup) {
        s.check.guidanceActive = { testScheduler.currentTime >= ms }
    }

    /** What every hand-over leaves: no restore clear frame, no disarm, the pause lifted. */
    private fun assertHandedOver(s: Setup, product: LiveProduct, step: Int) {
        val probe = s.probeLines()
        assertTrue(probe.contains("hudprobe: aborted reason=guidance step=$step"))
        assertTrue(s.car.calls.none { it == "sdk 4" || (it.startsWith("set 1023/") && !it.endsWith("=3")) })
        assertTrue(s.car.calls.none { it == "set 1014/1083203624=0" || it == "set 1014/1262485592=0" })
        assertEquals(HudCheck.State.RouteStarted, s.check.state.value)
        verifyOrder {
            product.controller.armingPaused = true
            product.controller.armingPaused = false
        }
    }

    @Test fun `a route starting in step 1 ends the check and leaves nothing to undo`() = runTest {
        val product = LiveProduct()
        val s = setup(controller = product.controller)
        routeStartsAt(5_000, s)
        s.check.run()
        assertHandedOver(s, product, step = 1)
        assertEquals(0, product.clearFrames())
        assertTrue(product.frames.size <= 5_000 / 300 + 1)   // no test frame after the route came
        assertTrue(s.car.calls.isEmpty())
        assertFalse(prefs().contains(HudArming.KEY_AS_FOUND))
    }

    @Test fun `a route starting in step 2 leaves the armed status and the kept layout to the projection`() = runTest {
        val product = LiveProduct()
        val s = setup(controller = product.controller)
        routeStartsAt(25_000, s)
        s.check.run()
        assertHandedOver(s, product, step = 2)
        assertEquals(0, product.clearFrames())
        assertEquals("hudprobe: restore handover armed=true asfound=1", s.probeLines().last())
        assertEquals(1, prefs().getInt(HudArming.KEY_AS_FOUND, -1))
        assertEquals(3, s.car.state[HudArming.SCREEN])
        assertEquals(2, s.car.state[HudArming.NAVI])
    }

    @Test fun `a route starting in step 3 blanks the CAN fields and nothing else`() = runTest {
        val product = LiveProduct()
        val s = setup(controller = product.controller)
        routeStartsAt(45_000, s)
        s.check.run()
        assertHandedOver(s, product, step = 3)
        assertEquals(1, product.clearFrames())   // the one before the CAN step, none in the restore
        assertEquals(
            listOf("set 1007/1139806224=0", "set 1007/1139806256=0", "set 1007/1139806232=-1", "buf 1007/1140461576="),
            s.car.calls.takeLast(4),
        )
        assertEquals(1, prefs().getInt(HudArming.KEY_AS_FOUND, -1))
    }

    @Test fun `without a running projection a route start still gets the full restore`() = runTest {
        val s = setup()   // HUD projection off: nobody else would close the status
        routeStartsAt(25_000, s)
        s.check.run()
        assertTrue(s.probeLines().contains("hudprobe: aborted reason=guidance step=2"))
        assertEquals(
            listOf("sdk 4", "set 1023/1276174357=1", "set 1014/1083203624=0", "set 1014/1262485592=0"),
            s.car.calls.takeLast(4),
        )
        verify { s.bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, match { it.contentEquals(HudProtobufBuilder.buildClearFrame(0)) }) }
        verify { s.bridge.unbind() }
        assertEquals(HudCheck.State.RouteStarted, s.check.state.value)
        assertFalse(s.controller.armingPaused)
    }

    private fun prefs() = context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)

    // --- refusals ---

    @Test fun `refuses during real guidance`() = runTest {
        val s = setup(guided = true)
        s.check.run()
        assertEquals(HudCheck.State.Refused(HudCheck.Refusal.GUIDANCE), s.check.state.value)
        assertEquals(listOf("hudprobe: refused reason=guidance"), s.probeLines())
        assertNothingTouched(s)
    }

    @Test fun `refuses above 5 km per hour`() = runTest {
        val s = setup(speed = 6)
        s.check.run()
        assertEquals(HudCheck.State.Refused(HudCheck.Refusal.MOVING), s.check.state.value)
        assertEquals(listOf("hudprobe: refused reason=moving"), s.probeLines())
        assertNothingTouched(s)
    }

    @Test fun `5 km per hour is still standing`() = runTest {
        val s = setup(speed = 5)
        s.check.run()
        assertEquals(HudCheck.State.Done, s.check.state.value)
    }

    @Test fun `refuses when the speed is unknown`() = runTest {
        val s = setup(speed = null)
        s.check.run()
        assertEquals(HudCheck.State.Refused(HudCheck.Refusal.NO_LINK), s.check.state.value)
        assertEquals(listOf("hudprobe: refused reason=no_link"), s.probeLines())
        assertNothingTouched(s)
    }

    private fun assertNothingTouched(s: Setup) {
        coVerify(exactly = 0) { s.car.helper.writeStatus(any(), any(), any(), any()) }
        coVerify(exactly = 0) { s.car.helper.hudNaviStatus(any()) }
        coVerify(exactly = 0) { s.car.helper.writeBufferStatus(any(), any(), any()) }
        verify(exactly = 0) { s.bridge.fireEvent(any(), any()) }
        assertFalse(s.controller.armingPaused)
    }
}
