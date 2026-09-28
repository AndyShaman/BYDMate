package com.bydmate.app.hud

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [HudArming.start] loop, written from the requirement (spec: `hud-deferred-layout`), not from the
 * implementation: the layout (dev 1023, fid 1276174357) must never be written while the instrument
 * cluster is fullscreen (dev 1007, fid 1086337074 == 4). What the fake car received is asserted by
 * the writes it recorded, not by log text.
 */
@RunWith(RobolectricTestRunner::class)
class HudDeferredLayoutSpecTest {

    private val prefs: SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("hud_deferred_layout_spec_test", Context.MODE_PRIVATE)

    @Before fun clear() {
        prefs.edit().clear().commit()
    }

    /** One write the fake car received. */
    private data class Write(val dev: Int, val fid: Int, val value: Int)

    /** A fake car: reads answer its current state, writes land in it and are kept in order. */
    private class FakeCar(initialScreen: Int = 7, initialCluster: Int = 0) {
        val state = mutableMapOf(
            HudArming.NAVI to 4, HudArming.SCREEN to initialScreen, HudArming.CLUSTER to initialCluster,
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
            // No SDK method on this car: NAVI_STATUS always goes out as a raw fid write.
            coEvery { helper.hudNaviStatus(any()) } returns null
            coEvery { helper.isAlive() } returns true
        }
    }

    private fun arming(car: FakeCar) = HudArming(car.helper, prefs)

    private fun Write.isLayout() = dev == HudArming.SCREEN.first && fid == HudArming.SCREEN.second
    private fun Write.isNavi() = dev == HudArming.NAVI.first && fid == HudArming.NAVI.second

    // --- B1 ---

    @Test fun `B1 guidance ends while the cluster is fullscreen writes navi 4 and the two zeros but not the layout`() = runTest {
        val car = FakeCar(initialScreen = 7, initialCluster = 0)
        val a = arming(car)
        var guided = true
        a.start(backgroundScope) { guided }
        runCurrent()
        assertTrue(car.writes.any { it.isLayout() && it.value == HudArming.LAYOUT_NAVI })   // armed: layout out to 3
        car.state[HudArming.CLUSTER] = HudArming.CLUSTER_FULLSCREEN   // rear camera up as the route ends
        car.writes.clear()
        guided = false
        advanceTimeBy(1_100); runCurrent()
        assertTrue(car.writes.any { it.isNavi() && it.value == HudArming.NAVI_CLOSED })
        assertTrue(car.writes.any { it.dev == HudArming.CAN_NAVI.first && it.fid == HudArming.CAN_NAVI.second && it.value == 0 })
        assertTrue(car.writes.any { it.dev == HudArming.ISA.first && it.fid == HudArming.ISA.second && it.value == 0 })
        assertFalse("the layout must not be written while the cluster is fullscreen", car.writes.any { it.isLayout() })
        assertTrue(prefs.contains(HudArming.KEY_AS_FOUND))
        a.stop()
    }

    // --- B2 ---

    @Test fun `B2 no guidance and a fullscreen cluster writes nothing for at least 20s`() = runTest {
        // Our layout (3) is still up in the car; the original (7) is owed back but the cluster
        // was fullscreen at the last disarm attempt, so the key is still in prefs.
        val car = FakeCar(initialScreen = HudArming.LAYOUT_NAVI, initialCluster = HudArming.CLUSTER_FULLSCREEN)
        prefs.edit().putInt(HudArming.KEY_AS_FOUND, 7).commit()
        val a = arming(car)
        a.start(backgroundScope) { false }
        advanceTimeBy(20_000); runCurrent()
        assertTrue(car.writes.isEmpty())
        a.stop()
    }

    // --- B3 ---

    @Test fun `B3 the cluster leaving fullscreen writes the layout back once and removes the key`() = runTest {
        val car = FakeCar(initialScreen = HudArming.LAYOUT_NAVI, initialCluster = HudArming.CLUSTER_FULLSCREEN)
        prefs.edit().putInt(HudArming.KEY_AS_FOUND, 7).commit()
        val a = arming(car)
        a.start(backgroundScope) { false }
        advanceTimeBy(20_000); runCurrent()
        car.state[HudArming.CLUSTER] = 0   // the rear camera closes
        advanceTimeBy(6_000); runCurrent()   // one more 5 s check tick, with margin for its phase
        val layoutWrites = car.writes.filter { it.isLayout() }
        assertEquals(1, layoutWrites.size)
        assertEquals(7, layoutWrites.single().value)
        assertFalse(prefs.contains(HudArming.KEY_AS_FOUND))
        car.writes.clear()
        advanceTimeBy(20_000); runCurrent()   // the layout is back: quiet from here
        assertTrue(car.writes.isEmpty())
        a.stop()
    }

    // --- B4 ---

    @Test fun `B4 while the HUD check owns the layout the loop never touches the key or the car`() = runTest {
        val car = FakeCar(initialScreen = HudArming.LAYOUT_NAVI, initialCluster = HudArming.CLUSTER_FULLSCREEN)
        prefs.edit().putInt(HudArming.KEY_AS_FOUND, 7).commit()
        val a = arming(car)
        a.start(backgroundScope, layoutOwned = { false }) { false }
        advanceTimeBy(30_000); runCurrent()
        assertTrue(car.writes.isEmpty())
        assertTrue(prefs.contains(HudArming.KEY_AS_FOUND))
        a.stop()
    }

    // --- B5 ---

    @Test fun `B5 no key and no guidance the loop never reads or writes anything`() = runTest {
        val car = FakeCar(initialScreen = 7, initialCluster = 0)
        val a = arming(car)
        a.start(backgroundScope) { false }
        advanceTimeBy(30_000); runCurrent()
        assertTrue(car.writes.isEmpty())
        coVerify(exactly = 0) { car.helper.readBatch(any()) }
        a.stop()
    }
}
