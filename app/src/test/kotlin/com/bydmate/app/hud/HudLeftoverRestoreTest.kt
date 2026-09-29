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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stored leftover (status raised by 3.19.1/3.19.2 mode 2, layout as-found kept) while no arming
 * loop runs: the controller puts it back, and finishes a put-back the fullscreen cluster postponed.
 */
@RunWith(RobolectricTestRunner::class)
class HudLeftoverRestoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val helperClient = mockk<HelperClient>(relaxed = true)
    private val helperBootstrap = mockk<HelperBootstrap>(relaxed = true)
    private val bindGate = CompletableDeferred<Unit>()
    private lateinit var bridge: HudSomeIpBridge
    /** Every write the car received, (dev, fid) to value; SDK NAVI_STATUS calls count as NAVI. */
    private val writes = Collections.synchronizedList(mutableListOf<Pair<Pair<Int, Int>, Int>>())
    private lateinit var state: MutableMap<Pair<Int, Int>, Int>
    private val reads = AtomicInteger()

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
        state = Collections.synchronizedMap(mutableMapOf(
            HudArming.NAVI to 4, HudArming.SCREEN to 1, HudArming.CLUSTER to 0,
            HudArming.CAN_NAVI to 0, HudArming.ISA to 0,
        ))
        coEvery { helperClient.readBatch(any()) } answers {
            reads.incrementAndGet()
            firstArg<List<BatchReadItem>>().map { 0 to (state[it.dev to it.fid] ?: 0) }
        }
        coEvery { helperClient.writeStatus(any(), any(), any(), any()) } answers {
            val key = arg<Int>(0) to arg<Int>(1)
            state[key] = arg(2)
            writes.add(key to arg(2))
            1
        }
        coEvery { helperClient.hudNaviStatus(any()) } answers {
            state[HudArming.NAVI] = firstArg()
            writes.add(HudArming.NAVI to firstArg())
            HudNaviReply(HelperBinderProtocol.HUD_NAVI_CALLED, 0)
        }
    }

    private fun prefs() = context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)

    private fun controller(): HudController = HudController(context, helperClient, helperBootstrap).apply {
        scope = CoroutineScope(Dispatchers.Unconfined)
        bridgeFactory = { _, _ -> bridge }
        leftoverRetryMs = RETRY_MS
    }

    private fun guideRoute() =
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300), NavGuidanceHub.Source.A11Y)

    /** What a process killed mid-route in mode 2 left: status up, layout 3, as-found 1 kept. */
    private fun leftover() {
        state.putAll(mapOf(HudArming.NAVI to 2, HudArming.SCREEN to 3, HudArming.CAN_NAVI to 1, HudArming.ISA to 1))
        prefs().edit().putInt(HudArming.KEY_AS_FOUND, 1).commit()
    }

    private fun hudOn(mode: Int) {
        prefs().edit().putBoolean(HudController.KEY_ENABLED, true).putInt(HudController.KEY_MODE, mode).commit()
    }

    private fun layoutWrites() = synchronized(writes) { writes.count { it.first == HudArming.SCREEN } }

    @Test fun `a mode 1 put-back postponed by a fullscreen cluster finishes once the cluster is free`() {
        leftover()
        hudOn(HudController.MODE_GLASS_ONLY)
        state[HudArming.CLUSTER] = HudArming.CLUSTER_FULLSCREEN
        val c = controller()
        c.startIfEnabled()
        Thread.sleep(RETRY_MS * 4)
        assertEquals(0, layoutWrites())
        assertEquals(3, state[HudArming.SCREEN])
        assertEquals(4, state[HudArming.NAVI])   // the status is lowered at once, only the layout waits
        state[HudArming.CLUSTER] = 0
        awaitTrue { state[HudArming.SCREEN] == 1 }
        awaitTrue { !prefs().contains(HudArming.KEY_AS_FOUND) }
        val after = writes.size
        Thread.sleep(RETRY_MS * 4)
        assertEquals(after, writes.size)
        c.setEnabled(false)
    }

    @Test fun `switching 2 to 1 at a fullscreen cluster during a route finishes the layout once the cluster is free`() {
        hudOn(HudController.MODE_NAVI_STATUS)
        guideRoute()
        val c = controller()
        c.startIfEnabled()
        awaitTrue { state[HudArming.SCREEN] == 3 }
        state[HudArming.CLUSTER] = HudArming.CLUSTER_FULLSCREEN
        c.setMode(HudController.MODE_GLASS_ONLY)
        awaitTrue { state[HudArming.NAVI] == 4 }
        Thread.sleep(RETRY_MS * 4)
        assertEquals(3, state[HudArming.SCREEN])
        state[HudArming.CLUSTER] = 0
        awaitTrue { state[HudArming.SCREEN] == 1 }
        awaitTrue { !prefs().contains(HudArming.KEY_AS_FOUND) }
        c.setEnabled(false)
    }

    @Test fun `a switch to mode 1 while the bind waits puts a leftover back`() {
        leftover()
        hudOn(HudController.MODE_NAVI_STATUS)
        guideRoute()
        coEvery { bridge.bind() } coAnswers { bindGate.await(); true }
        val c = controller()
        c.startIfEnabled()
        Thread.sleep(RETRY_MS * 2)
        assertEquals(2, state[HudArming.NAVI])   // mode 2 with a route: left to the arming loop
        c.setMode(HudController.MODE_GLASS_ONLY)
        bindGate.complete(Unit)
        awaitTrue { c.status.value == HudController.Status.ON }
        awaitTrue { state[HudArming.SCREEN] == 1 && state[HudArming.NAVI] == 4 }
        awaitTrue { !prefs().contains(HudArming.KEY_AS_FOUND) }
        coVerify(exactly = 0) { helperClient.hudNaviStatus(2) }
        c.setEnabled(false)
    }

    @Test fun `mode 1 with nothing stored writes nothing over several retry periods`() {
        hudOn(HudController.MODE_GLASS_ONLY)
        state[HudArming.CLUSTER] = HudArming.CLUSTER_FULLSCREEN
        guideRoute()
        val c = controller()
        c.startIfEnabled()
        Thread.sleep(RETRY_MS * 6)
        c.setMode(HudController.MODE_GLASS_ONLY)
        Thread.sleep(RETRY_MS * 4)
        c.setEnabled(false)
        assertTrue(writes.isEmpty())
        coVerify(exactly = 0) { helperClient.writeStatus(any(), any(), any(), any()) }
        coVerify(exactly = 0) { helperClient.hudNaviStatus(any()) }
    }

    @Test fun `mode 2 with the loop running leaves a leftover to the loop and writes nothing of its own`() {
        leftover()
        hudOn(HudController.MODE_NAVI_STATUS)
        guideRoute()
        val c = controller()
        c.startIfEnabled()
        awaitTrue { c.armingLive }
        Thread.sleep(RETRY_MS * 6)
        // The route holds the status: nothing lowered it, the loop owns the kept as-found.
        coVerify(exactly = 0) { helperClient.hudNaviStatus(4) }
        assertTrue(synchronized(writes) { writes.none { it == HudArming.NAVI to 4 || it == HudArming.SCREEN to 1 } })
        assertEquals(1, prefs().getInt(HudArming.KEY_AS_FOUND, -1))
        c.setEnabled(false)
    }

    /** Mode 2, a guided route armed through a controller whose binding-lost callback the test holds. */
    private fun armedWithLostHook(): Pair<HudController, () -> Unit> {
        hudOn(HudController.MODE_NAVI_STATUS)
        guideRoute()
        var onLost: () -> Unit = {}
        val c = controller().apply { bridgeFactory = { _, lost -> onLost = lost; bridge } }
        c.startIfEnabled()
        awaitTrue { state[HudArming.SCREEN] == 3 && c.armingLive }
        return c to onLost
    }

    @Test fun `a binding-lost callback queued behind a stop leaves nothing running after it`() {
        val (c, onLost) = armedWithLostHook()
        state[HudArming.CLUSTER] = HudArming.CLUSTER_FULLSCREEN   // the stop's put-back is deferred
        val gate = CompletableDeferred<Unit>()
        coEvery { helperClient.writeStatus(HudArming.NAVI.first, HudArming.NAVI.second, 4, any()) } coAnswers {
            gate.await()
            state[HudArming.NAVI] = 4
            1
        }
        c.stop()          // suspended inside the disarm, holding the controller's lock
        onLost()          // waits for that lock
        NavGuidanceHub.reset()   // route over: a stray put-back job would poll the car now
        gate.complete(Unit)
        awaitTrue { c.status.value != HudController.Status.ON }
        Thread.sleep(RETRY_MS * 2)
        val readsAfter = reads.get()
        val writesAfter = writes.size
        Thread.sleep(RETRY_MS * 6)
        assertEquals(readsAfter, reads.get())
        assertEquals(writesAfter, writes.size)
        assertEquals(1, prefs().getInt(HudArming.KEY_AS_FOUND, -1))   // left for the next start
    }

    @Test fun `mode 2 with a route, a binding lost at a fullscreen cluster still gets its layout back`() {
        val (c, onLost) = armedWithLostHook()
        state[HudArming.CLUSTER] = HudArming.CLUSTER_FULLSCREEN
        onLost()
        awaitTrue { c.status.value == HudController.Status.BIND_FAILED }
        Thread.sleep(RETRY_MS * 3)
        assertEquals(3, state[HudArming.SCREEN])
        state[HudArming.CLUSTER] = 0
        awaitTrue(timeoutMs = RETRY_MS * 20) { state[HudArming.SCREEN] == 1 }
        awaitTrue { !prefs().contains(HudArming.KEY_AS_FOUND) }
        c.setEnabled(false)
    }

    @Test fun `mode 2 with a route and a failed bind puts a leftover back`() {
        leftover()
        hudOn(HudController.MODE_NAVI_STATUS)
        guideRoute()
        coEvery { bridge.bind() } returns false
        val c = controller()
        c.startIfEnabled()
        awaitTrue { c.status.value == HudController.Status.BIND_FAILED }
        awaitTrue(timeoutMs = RETRY_MS * 20) { state[HudArming.SCREEN] == 1 && state[HudArming.NAVI] == 4 }
        awaitTrue { !prefs().contains(HudArming.KEY_AS_FOUND) }
        c.setEnabled(false)
    }

    private fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        assertTrue(cond())
    }

    private companion object {
        const val RETRY_MS = 100L
    }
}
