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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [HudCheck] ending while the instrument cluster is fullscreen (spec: `hud-leftover-any-car`, R3),
 * written from the requirement, not from the implementation: the layout (dev 1023, fid 1276174357)
 * is never written while the cluster (dev 1007, fid 1086337074) reads 4, the check retries every
 * 5 s for up to 60 s, then leaves the kept as-found for the next start.
 */
@RunWith(RobolectricTestRunner::class)
class HudCheckDeferredRestoreSpecTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clear() {
        prefs().edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)

    /** One write the fake car received, with its virtual time. */
    private data class Write(val dev: Int, val fid: Int, val value: Int, val atMs: Long)

    /** A fake car at rest, HUD layout 1; [fullscreen] says whether the cluster reads 4 at a time. */
    private class FakeCar(val now: () -> Long, val fullscreen: (Long) -> Boolean) {
        val state = mutableMapOf(
            HudCheck.HUD_TYPE to 1, HudArming.NAVI to 4, HudArming.SCREEN to 1,
            HudArming.CAN_NAVI to 0, HudArming.ISA to 0,
        )
        val writes = mutableListOf<Write>()
        /** Virtual times of every read that asked for the cluster. */
        val clusterReads = mutableListOf<Long>()
        val helper: HelperClient = mockk(relaxed = true)

        init {
            coEvery { helper.readBatch(any()) } answers {
                firstArg<List<BatchReadItem>>().map { item ->
                    val key = item.dev to item.fid
                    if (key == HudArming.CLUSTER) {
                        clusterReads += now()
                        0 to if (fullscreen(now())) HudArming.CLUSTER_FULLSCREEN else 0
                    } else {
                        0 to (state[key] ?: 0)
                    }
                }
            }
            coEvery { helper.writeStatus(any(), any(), any(), any()) } coAnswers {
                writes += Write(arg(0), arg(1), arg(2), now())
                state[arg<Int>(0) to arg<Int>(1)] = arg(2)
                1
            }
            // No SDK method on this car: NAVI_STATUS always goes out as a raw fid write.
            coEvery { helper.hudNaviStatus(any()) } returns null
        }

        fun layoutWrites() = writes.filter { it.dev == HudArming.SCREEN.first && it.fid == HudArming.SCREEN.second }
    }

    private class Setup(val check: HudCheck, val car: FakeCar, val controller: HudController)

    private fun TestScope.setup(speedAt: (Long) -> Int = { 0 }, fullscreen: (Long) -> Boolean): Setup {
        val car = FakeCar({ testScheduler.currentTime }, fullscreen)
        val bootstrap = mockk<HelperBootstrap>()
        coEvery { bootstrap.ensureRunning() } returns true
        val ctl = HudController(context, car.helper, bootstrap)   // HUD projection off
        val bridge = mockk<HudSomeIpBridge>(relaxed = true).also {
            coEvery { it.bind() } returns true
            every { it.startService(any()) } returns 0
            every { it.fireEvent(any(), any()) } returns 0
        }
        val check = HudCheck(context, car.helper, bootstrap, ctl).apply {
            bridgeFactory = { bridge }
            gatewayPresent = { true }
            guidanceActive = { false }
            speedKmh = { speedAt(testScheduler.currentTime) }
            log = {}
        }
        return Setup(check, car, ctl)
    }

    /** The steps last 3 x 20 s; the restore starts right after them. */
    private val stepsEndMs = 60_000L

    @Test fun `R3 a layout deferred at the end goes back once the cluster leaves fullscreen`() = runTest {
        val leavesAt = stepsEndMs + 17_000
        val s = setup { t -> t in 58_000 until leavesAt }
        s.check.run()
        // The arm wrote 3 in step 2; the only other layout write is the as-found 1, after the
        // cluster left fullscreen and within one 5 s retry of it.
        val back = s.car.layoutWrites().filter { it.value != HudArming.LAYOUT_NAVI }
        assertEquals(1, back.size)
        assertEquals(1, back.single().value)
        assertTrue("layout written at ${back.single().atMs} while the cluster was fullscreen", back.single().atMs >= leavesAt)
        assertTrue("layout written at ${back.single().atMs}, later than one retry", back.single().atMs <= leavesAt + 5_000 + 1_000)
        assertEquals(1, s.car.state[HudArming.SCREEN])
        assertFalse(prefs().contains(HudArming.KEY_AS_FOUND))
        assertEquals(HudCheck.State.Done, s.check.state.value)
        assertFalse(s.controller.armingPaused)
    }

    @Test fun `R3 a cluster still fullscreen after 60 s leaves the kept layout for the next start`() = runTest {
        val s = setup { t -> t >= 58_000 }
        s.check.run()
        val endedAt = testScheduler.currentTime
        // It kept trying for about a minute after the steps, about every 5 s, and then gave up.
        assertTrue("the check ended at $endedAt", endedAt in (stepsEndMs + 55_000)..(stepsEndMs + 66_000))
        val retries = s.car.clusterReads.filter { it > stepsEndMs + 1_000 }
        assertTrue("${retries.size} cluster looks after the steps", retries.size in 10..13)
        assertTrue(s.car.layoutWrites().none { it.value != HudArming.LAYOUT_NAVI })
        assertEquals(HudArming.LAYOUT_NAVI, s.car.state[HudArming.SCREEN])
        assertEquals(1, prefs().getInt(HudArming.KEY_AS_FOUND, -1))
        assertEquals(HudCheck.State.Done, s.check.state.value)
        assertFalse(s.controller.armingPaused)
    }

    @Test fun `R7 a route starting during the layout retry ends it at once and leaves the kept layout`() = runTest {
        val routeAt = stepsEndMs + 12_000
        // The cluster lets go after the route started: a retry still running would write under it.
        val s = setup { t -> t in 58_000 until routeAt + 2_000 }
        s.check.guidanceActive = { testScheduler.currentTime >= routeAt }
        s.check.run()
        val endedAt = testScheduler.currentTime
        assertTrue("the check ended at $endedAt", endedAt in routeAt..(routeAt + 1_500))
        assertTrue(s.car.layoutWrites().none { it.value != HudArming.LAYOUT_NAVI })
        assertEquals(1, prefs().getInt(HudArming.KEY_AS_FOUND, -1))
        assertEquals(HudCheck.State.RouteStarted, s.check.state.value)
        assertFalse(s.controller.armingPaused)
    }

    @Test fun `R3 a check stopped by moving off at a fullscreen cluster retries the layout the same way`() = runTest {
        val s = setup(speedAt = { t -> if (t >= 30_000) 25 else 0 }) { t -> t in 29_000 until 40_000 }
        s.check.run()
        val back = s.car.layoutWrites().filter { it.value != HudArming.LAYOUT_NAVI }
        assertEquals(1, back.size)
        assertTrue(back.single().atMs in 40_000..46_000)
        assertEquals(1, s.car.state[HudArming.SCREEN])
        assertFalse(prefs().contains(HudArming.KEY_AS_FOUND))
        assertEquals(HudCheck.State.Refused(HudCheck.Refusal.MOVING), s.check.state.value)
    }
}
