package com.bydmate.app.hud

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.HudNaviReply
import com.bydmate.app.helper.HelperBinderProtocol
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * HUD arming against a fake car: every read answers the car's state, every write lands in it, and
 * [FakeCar.calls] keeps the order of what went out.
 */
@RunWith(RobolectricTestRunner::class)
class HudArmingTest {

    private val prefs: SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("hud_arming_test", Context.MODE_PRIVATE)

    @Before fun clear() {
        prefs.edit().clear().commit()
    }

    /** The car as the daemon sees it. [sdk] null = a daemon too old for the SDK call. */
    private class FakeCar {
        val state = mutableMapOf(
            HudArming.NAVI to 4, HudArming.SCREEN to 1, HudArming.CLUSTER to 0,
            HudArming.CAN_NAVI to 0, HudArming.ISA to 0,
        )
        /** Reads that answer an autoservice error instead of the state. */
        val readErrors = mutableMapOf<Pair<Int, Int>, Int>()
        var sdk: Int? = HelperBinderProtocol.HUD_NAVI_CALLED
        var sdkReturn = 0
        var alive = true
        var writeDelayMs = 0L
        /** Writes that answer this status instead of landing. */
        val writeErrors = mutableMapOf<Pair<Int, Int>, Int>()
        val calls = mutableListOf<String>()

        val helper: HelperClient = mockk(relaxed = true)

        init {
            coEvery { helper.readBatch(any()) } answers {
                firstArg<List<BatchReadItem>>().map { item ->
                    val key = item.dev to item.fid
                    readErrors[key]?.let { it to 0 } ?: (0 to (state[key] ?: 0))
                }
            }
            coEvery { helper.writeStatus(any(), any(), any(), any()) } coAnswers {
                val key = arg<Int>(0) to arg<Int>(1)
                calls += "set ${key.first}/${key.second}=${arg<Int>(2)}"
                if (writeDelayMs > 0) delay(writeDelayMs)
                writeErrors[key] ?: run { state[key] = arg(2); 1 }
            }
            coEvery { helper.hudNaviStatus(any()) } answers {
                val status = firstArg<Int>()
                calls += "sdk $status"
                val outcome = sdk ?: return@answers null
                if (outcome == HelperBinderProtocol.HUD_NAVI_CALLED && sdkReturn >= 0) state[HudArming.NAVI] = status
                HudNaviReply(outcome, sdkReturn)
            }
            coEvery { helper.isAlive() } answers { alive }
        }
    }

    private fun arming(car: FakeCar, lines: MutableList<String> = mutableListOf()) =
        HudArming(car.helper, prefs).apply { log = { lines += it } }

    // --- arm: order and guards ---

    @Test fun `first arm keeps the as-found layout and raises the status through the SDK`() = runTest {
        val car = FakeCar()
        val a = arming(car)
        val r = a.arm()
        assertEquals(listOf("sdk 2", "set 1023/1276174357=3", "set 1014/1083203624=1", "set 1014/1262485592=1"), car.calls)
        assertEquals(1, a.asFound)
        assertTrue(a.armed)
        assertEquals(HudArming.VIA_SDK, r.via)
        assertEquals(
            "via=sdk navi rc=0 screen rc=1 canNavi rc=1 isa rc=1 readback navi=2 screen=3 canNavi=1 isa=1",
            r.describe(),
        )
        assertEquals(1, prefs.getInt(HudArming.KEY_AS_FOUND, -1))
    }

    @Test fun `layout already 3 is not written`() = runTest {
        val car = FakeCar().apply { state[HudArming.SCREEN] = 3 }
        val r = arming(car).arm()
        assertFalse(car.calls.any { it.startsWith("set 1023/") })
        assertTrue(r.describe().contains("screen rc=skipped"))
    }

    @Test fun `cluster fullscreen keeps the layout and the SDK call out`() = runTest {
        val car = FakeCar().apply { state[HudArming.CLUSTER] = 4 }
        val r = arming(car).arm()
        // The SDK call may move the layout itself (Han L), so NAVI_STATUS goes raw here.
        assertEquals(listOf("set 1007/1138753594=2", "set 1014/1083203624=1", "set 1014/1262485592=1"), car.calls)
        assertEquals(HudArming.VIA_FID, r.via)
        assertEquals(
            "via=fid navi rc=1 screen rc=skipped canNavi rc=1 isa rc=1 readback navi=2 screen=1 canNavi=1 isa=1",
            r.describe(),
        )
    }

    @Test fun `cluster fullscreen keeps the SDK call and the layout out of the disarm`() = runTest {
        val car = FakeCar()
        val a = arming(car)
        a.arm()
        car.calls.clear()
        car.state[HudArming.CLUSTER] = 4
        val r = a.disarm()
        assertEquals(
            listOf("set 1007/1138753594=4", "set 1014/1083203624=0", "set 1014/1262485592=0"),
            car.calls,
        )
        assertEquals(HudArming.VIA_FID, r.via)
        // The layout waits too: kept for the next start or route; ok counts the three writes.
        assertTrue(r.describe().startsWith("navi rc=1 screen=1 rc=deferred ok=true "))
        assertEquals(1, prefs.getInt(HudArming.KEY_AS_FOUND, -1))
        assertEquals(3, car.state[HudArming.SCREEN])
    }

    @Test fun `SDK method absent falls back to the raw write`() = runTest {
        val car = FakeCar().apply { sdk = HelperBinderProtocol.HUD_NAVI_ABSENT }
        val r = arming(car).arm()
        assertEquals("sdk 2", car.calls[0])
        assertEquals("set 1007/1138753594=2", car.calls[1])
        assertEquals(HudArming.VIA_FID, r.via)
    }

    @Test fun `SDK refusal and SDK exception fall back to the raw write`() = runTest {
        val refused = FakeCar().apply { sdkReturn = -10011 }
        assertEquals(HudArming.VIA_FID, arming(refused).arm().via)
        assertTrue("set 1007/1138753594=2" in refused.calls)

        val threw = FakeCar().apply { sdk = HelperBinderProtocol.HUD_NAVI_THREW }
        assertEquals(HudArming.VIA_FID, arming(threw).arm().via)
        assertTrue("set 1007/1138753594=2" in threw.calls)
    }

    @Test fun `outdated daemon writes raw and says so once`() = runTest {
        val car = FakeCar().apply { sdk = null }
        val lines = mutableListOf<String>()
        val a = arming(car, lines)
        assertEquals(HudArming.VIA_FID, a.arm().via)
        a.arm()
        assertEquals(2, car.calls.count { it == "set 1007/1138753594=2" })
        assertEquals(1, lines.count { it.contains("helper daemon is outdated") })
    }

    @Test fun `unreachable daemon is not called outdated`() = runTest {
        val car = FakeCar().apply { sdk = null; alive = false }
        val lines = mutableListOf<String>()
        arming(car, lines).arm()
        assertTrue(lines.none { it.contains("outdated") })
    }

    @Test fun `layout that cannot be put back is never written`() = runTest {
        val unreadable = FakeCar().apply { readErrors[HudArming.SCREEN] = -10011 }
        val a = arming(unreadable)
        a.arm()
        assertNull(a.asFound)
        assertFalse(unreadable.calls.any { it.startsWith("set 1023/") })

        val sentinel = FakeCar().apply { state[HudArming.SCREEN] = 65535 }
        arming(sentinel).arm()
        assertFalse(sentinel.calls.any { it.startsWith("set 1023/") })
    }

    @Test fun `layout kept by an unfinished session is the one restored`() = runTest {
        prefs.edit().putInt(HudArming.KEY_AS_FOUND, 1).commit()
        val car = FakeCar().apply { state[HudArming.SCREEN] = 3 }   // our 3 from before the kill
        val a = arming(car)
        a.arm()
        assertEquals(1, a.asFound)
        a.disarm()
        assertEquals(1, car.state[HudArming.SCREEN])
        assertFalse(prefs.contains(HudArming.KEY_AS_FOUND))
    }

    // --- leftover from a killed process ---

    /** What a process killed mid-route leaves: our status, layout and flags up, the layout kept. */
    private fun leftoverCar(): FakeCar {
        prefs.edit().putInt(HudArming.KEY_AS_FOUND, 1).commit()
        return FakeCar().apply {
            state[HudArming.NAVI] = 2; state[HudArming.SCREEN] = 3
            state[HudArming.CAN_NAVI] = 1; state[HudArming.ISA] = 1
        }
    }

    @Test fun `a leftover without a guided route is undone once`() = runTest {
        val car = leftoverCar()
        val lines = mutableListOf<String>()
        val a = arming(car, lines)
        val r = a.disarmLeftover(guided = false)
        assertEquals(
            listOf("sdk 4", "set 1023/1276174357=1", "set 1014/1083203624=0", "set 1014/1262485592=0"),
            car.calls,
        )
        assertTrue(r!!.ok)
        assertTrue(lines.single().startsWith("hud disarm: reason=leftover navi rc=0 screen=1 rc=1 ok=true "))
        assertFalse(prefs.contains(HudArming.KEY_AS_FOUND))
        assertFalse(a.armed)
        assertNull(a.disarmLeftover(guided = false))   // nothing left the second time
        assertEquals(4, car.calls.size)
    }

    @Test fun `a leftover at a fullscreen cluster waits for the next start`() = runTest {
        val car = leftoverCar().apply { state[HudArming.CLUSTER] = 4 }
        val lines = mutableListOf<String>()
        val r = arming(car, lines).disarmLeftover(guided = false)!!
        assertEquals(listOf("set 1007/1138753594=4", "set 1014/1083203624=0", "set 1014/1262485592=0"), car.calls)
        assertTrue(r.ok)
        assertTrue(lines.single().startsWith("hud disarm: reason=leftover navi rc=1 screen=1 rc=deferred ok=true "))
        assertEquals(1, prefs.getInt(HudArming.KEY_AS_FOUND, -1))
        // The next start, the cluster back to normal: the layout goes back and the key goes.
        car.state[HudArming.CLUSTER] = 0
        car.calls.clear()
        arming(car).disarmLeftover(guided = false)
        assertTrue("set 1023/1276174357=1" in car.calls)
        assertFalse(prefs.contains(HudArming.KEY_AS_FOUND))
    }

    @Test fun `a layout deferred at the route's end goes back once the cluster is free`() = runTest {
        val car = FakeCar()
        val lines = mutableListOf<String>()
        var guided = true
        val a = arming(car, lines)
        a.start(backgroundScope) { guided }
        runCurrent()
        car.state[HudArming.CLUSTER] = 4             // rear camera on as the route ends
        guided = false
        advanceTimeBy(1_100); runCurrent()
        assertTrue(lines.last().startsWith("hud disarm: navi rc=1 screen=1 rc=deferred ok=true "))
        assertEquals(1, prefs.getInt(HudArming.KEY_AS_FOUND, -1))
        car.calls.clear()
        val linesBefore = lines.size
        advanceTimeBy(20_000); runCurrent()          // the camera stays: nothing written, nothing said
        assertTrue(car.calls.isEmpty())
        assertEquals(linesBefore, lines.size)
        car.state[HudArming.CLUSTER] = 0             // camera closed, driving on without a route
        advanceTimeBy(5_000); runCurrent()
        assertEquals(
            listOf("sdk 4", "set 1023/1276174357=1", "set 1014/1083203624=0", "set 1014/1262485592=0"),
            car.calls,
        )
        assertTrue(lines.last().startsWith("hud disarm: reason=deferred navi rc=0 screen=1 rc=1 ok=true "))
        assertFalse(prefs.contains(HudArming.KEY_AS_FOUND))
        car.calls.clear()
        advanceTimeBy(30_000); runCurrent()          // done: quiet from here
        assertTrue(car.calls.isEmpty())
        a.stop()
    }

    @Test fun `a deferred retry that fails is not repeated every 5 s`() = runTest {
        val car = leftoverCar().apply { writeErrors[HudArming.SCREEN] = -10011 }
        val lines = mutableListOf<String>()
        val a = arming(car, lines)
        a.start(backgroundScope) { false }
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, lines.count { it.startsWith("hud disarm: reason=deferred ") })
        assertEquals(1, car.calls.count { it == "set 1023/1276174357=1" })
        a.stop()
    }

    @Test fun `a kept layout the HUD check owns is left alone`() = runTest {
        val car = leftoverCar()
        val a = arming(car)
        a.start(backgroundScope, layoutOwned = { false }) { false }
        advanceTimeBy(30_000); runCurrent()
        assertTrue(car.calls.isEmpty())
        assertEquals(1, prefs.getInt(HudArming.KEY_AS_FOUND, -1))
        a.stop()
    }

    @Test fun `no leftover key writes and reads nothing`() = runTest {
        val car = FakeCar()
        assertNull(arming(car).disarmLeftover(guided = false))
        assertTrue(car.calls.isEmpty())
        coVerify(exactly = 0) { car.helper.readBatch(any()) }
    }

    // --- the armed marker: a status raised over an unreadable layout ---

    @Test fun `the armed marker is kept before the first write to the car`() = runTest {
        val car = FakeCar().apply { readErrors[HudArming.SCREEN] = -10011 }
        coEvery { car.helper.hudNaviStatus(any()) } throws IllegalStateException("helper gone")
        assertTrue(runCatching { arming(car).arm() }.isFailure)
        assertTrue(prefs.getBoolean(HudArming.KEY_ARMED, false))
        assertFalse(prefs.contains(HudArming.KEY_AS_FOUND))
    }

    @Test fun `a status raised over an unreadable layout goes back after a kill, the layout untouched`() = runTest {
        val car = FakeCar().apply { readErrors[HudArming.SCREEN] = -10011 }
        arming(car).arm()                            // the process dies here, mid-route
        assertFalse(prefs.contains(HudArming.KEY_AS_FOUND))
        assertEquals(2, car.state[HudArming.NAVI])
        car.calls.clear()
        val lines = mutableListOf<String>()
        val r = arming(car, lines).disarmLeftover(guided = false)   // the next start
        assertEquals(listOf("sdk 4", "set 1014/1083203624=0", "set 1014/1262485592=0"), car.calls)
        assertTrue(r!!.ok)
        assertTrue(lines.single().startsWith("hud disarm: reason=leftover navi rc=0 screen=na rc=skipped ok=true "))
        assertFalse(prefs.contains(HudArming.KEY_ARMED))
        assertNull(arming(car).disarmLeftover(guided = false))   // nothing left the second time
    }

    @Test fun `a clean disarm clears the armed marker, a refused one keeps it`() = runTest {
        val car = FakeCar()
        val a = arming(car)
        a.arm()
        assertTrue(prefs.getBoolean(HudArming.KEY_ARMED, false))
        a.disarm()
        assertFalse(prefs.contains(HudArming.KEY_ARMED))

        a.arm()
        car.writeErrors[HudArming.ISA] = -10011
        assertFalse(a.disarm().ok)
        assertTrue(prefs.getBoolean(HudArming.KEY_ARMED, false))
    }

    @Test fun `a guided route leaves the leftover to its own arm and disarm`() = runTest {
        val car = leftoverCar()
        assertNull(arming(car).disarmLeftover(guided = true))
        assertTrue(car.calls.isEmpty())
        assertEquals(1, prefs.getInt(HudArming.KEY_AS_FOUND, -1))
    }

    // --- re-arm ---

    @Test fun `navi 2 and 621 hold, anything else re-arms`() = runTest {
        val a = arming(FakeCar())
        a.arm()
        fun status(navi: HudArming.FidRead, screen: Int = 3, cluster: Int = 0) =
            HudArming.Status(navi, HudArming.FidRead(0, screen), HudArming.FidRead(0, cluster))
        assertFalse(a.needsRearm(status(HudArming.FidRead(0, 2))))
        assertFalse(a.needsRearm(status(HudArming.FidRead(0, 621))))
        assertTrue(a.needsRearm(status(HudArming.FidRead(0, 0))))
        assertTrue(a.needsRearm(status(HudArming.FidRead(0, 4))))
        assertTrue(a.needsRearm(status(HudArming.FidRead(-10011))))
        assertTrue(a.needsRearm(status(HudArming.FidRead(null))))
        // A layout off 3 re-arms too, but not while the cluster is fullscreen.
        assertTrue(a.needsRearm(status(HudArming.FidRead(0, 2), screen = 1)))
        assertFalse(a.needsRearm(status(HudArming.FidRead(0, 2), screen = 1, cluster = 4)))
    }

    @Test fun `loop arms on guidance and re-checks every 5 s`() = runTest {
        val car = FakeCar()
        val lines = mutableListOf<String>()
        val a = arming(car, lines)
        a.start(backgroundScope) { true }
        runCurrent()
        assertEquals(1, car.calls.count { it == "sdk 2" })
        advanceTimeBy(4_900); runCurrent()
        assertEquals(1, car.calls.count { it == "sdk 2" })   // status held: nothing re-sent
        car.state[HudArming.NAVI] = 0                        // the firmware let it drop
        advanceTimeBy(200); runCurrent()                      // t = 5.1 s: the check ran
        assertEquals(2, car.calls.count { it == "sdk 2" })
        assertTrue(lines.any { it == "hud re-arm: navi=0 screen=3" })
        car.state[HudArming.NAVI] = 0
        advanceTimeBy(5_000); runCurrent()
        car.state[HudArming.NAVI] = 0
        advanceTimeBy(5_000); runCurrent()
        assertEquals(4, car.calls.count { it == "sdk 2" })
        // Identical re-arms collapse into the first line.
        assertEquals(1, lines.count { it.startsWith("hud re-arm:") })
        a.stop()
    }

    @Test fun `nothing is written without guidance`() = runTest {
        val car = FakeCar()
        val a = arming(car)
        a.start(backgroundScope) { false }
        advanceTimeBy(60_000); runCurrent()
        a.stop()
        assertTrue(car.calls.isEmpty())
        coVerify(exactly = 0) { car.helper.readBatch(any()) }
    }

    // --- disarm ---

    @Test fun `disarm closes the status, clears canNavi and isa and puts the layout back`() = runTest {
        val car = FakeCar()
        val a = arming(car)
        a.arm()
        car.calls.clear()
        val r = a.disarm()
        // OpenBYD's stop: the original canNavi and isa cannot be read, 0 is what the car runs without us.
        assertEquals(
            listOf("sdk 4", "set 1023/1276174357=1", "set 1014/1083203624=0", "set 1014/1262485592=0"),
            car.calls,
        )
        assertEquals(
            "navi rc=0 screen=1 rc=1 ok=true canNavi rc=1 isa rc=1 via=sdk readback navi=4 screen=1 canNavi=0 isa=0",
            r.describe(),
        )
        assertEquals(0, car.state[HudArming.CAN_NAVI])
        assertEquals(0, car.state[HudArming.ISA])
    }

    @Test fun `a refused clear makes the disarm not ok`() = runTest {
        val car = FakeCar()
        val a = arming(car)
        a.arm()
        car.writeErrors[HudArming.ISA] = -10011
        val r = a.disarm()
        assertFalse(r.ok)
        assertTrue(r.describe().contains("isa rc=-10011"))
        assertEquals(1, car.state[HudArming.SCREEN])   // the rest still went back
    }

    @Test fun `guidance end closes the status and restores the as-found layout`() = runTest {
        val car = FakeCar()
        val lines = mutableListOf<String>()
        var guided = true
        val a = arming(car, lines)
        a.start(backgroundScope) { guided }
        runCurrent()
        assertEquals(3, car.state[HudArming.SCREEN])
        guided = false
        advanceTimeBy(1_100); runCurrent()
        assertEquals(
            listOf("sdk 4", "set 1023/1276174357=1", "set 1014/1083203624=0", "set 1014/1262485592=0"),
            car.calls.takeLast(4),
        )
        assertEquals(1, car.state[HudArming.SCREEN])
        assertFalse(a.armed)
        assertTrue(lines.any { it.startsWith("hud disarm: navi rc=0 screen=1 rc=1 ok=true canNavi rc=1 isa rc=1 via=sdk") })
        a.stop()
    }

    @Test fun `guidance ending during a re-arm still restores the layout`() = runTest {
        val car = FakeCar()
        var guided = true
        val a = arming(car)
        a.start(backgroundScope) { guided }
        runCurrent()
        car.writeDelayMs = 3_000                    // the re-arm's writes hang on the daemon
        car.state[HudArming.NAVI] = 0
        car.state[HudArming.SCREEN] = 1             // and the firmware put the layout back meanwhile
        advanceTimeBy(5_100); runCurrent()          // re-arm in flight
        guided = false
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, car.state[HudArming.SCREEN])
        assertEquals(4, car.state[HudArming.NAVI])
        assertFalse(a.armed)
        a.stop()
    }

    @Test fun `switching the projection off while armed disarms`() = runTest {
        val car = FakeCar()
        val a = arming(car)
        a.start(backgroundScope) { true }
        runCurrent()
        a.stop()
        assertEquals(4, car.state[HudArming.NAVI])
        assertEquals(1, car.state[HudArming.SCREEN])
        assertFalse(a.armed)
    }

    @Test fun `a failing arm step does not end the loop`() = runTest {
        val car = FakeCar()
        coEvery { car.helper.hudNaviStatus(any()) } throws SecurityException("[setInt] permission deny!")
        val a = arming(car)
        a.start(backgroundScope) { true }
        advanceTimeBy(6_100); runCurrent()
        // Still trying: the first arm threw before any write, the next check arms again.
        coEvery { car.helper.hudNaviStatus(any()) } returns HudNaviReply(HelperBinderProtocol.HUD_NAVI_CALLED, 0)
        advanceTimeBy(6_000); runCurrent()
        assertTrue(car.calls.contains("set 1023/1276174357=3"))
        a.stop()
    }

    // --- the hook ways 2 and 3 clean up in ---

    @Test fun `the hook runs before the route end's disarm, while the status is up`() = runTest {
        val car = FakeCar()
        var guided = true
        val a = arming(car)
        a.beforeDisarm = { car.calls += "hook navi=${car.state[HudArming.NAVI]}" }
        a.start(backgroundScope) { guided }
        runCurrent()
        guided = false
        advanceTimeBy(1_100); runCurrent()
        assertEquals(listOf("hook navi=2", "sdk 4"), car.calls.takeLast(5).take(2))
        a.stop()
        assertEquals(1, car.calls.count { it.startsWith("hook") })
    }

    @Test fun `the hook runs before the disarm of a stop`() = runTest {
        val car = FakeCar()
        val a = arming(car)
        a.beforeDisarm = { car.calls += "hook navi=${car.state[HudArming.NAVI]}" }
        a.start(backgroundScope) { true }
        runCurrent()
        a.stop()
        assertEquals(listOf("hook navi=2", "sdk 4"), car.calls.takeLast(5).take(2))
    }

    @Test fun `the after-arm hook runs once the status is up, and again on the arm after a disarm`() = runTest {
        val car = FakeCar()
        var guided = true
        val a = arming(car)
        a.afterArm = { car.calls += "after navi=${car.state[HudArming.NAVI]}" }
        a.start(backgroundScope) { guided }
        runCurrent()
        assertTrue(car.calls.toString(), car.calls.indexOf("after navi=2") > car.calls.indexOf("sdk 2"))
        guided = false
        advanceTimeBy(1_100); runCurrent()
        assertEquals(1, car.calls.count { it.startsWith("after") })
        guided = true
        advanceTimeBy(1_100); runCurrent()
        assertEquals(2, car.calls.count { it.startsWith("after") })
        a.stop()
    }

    @Test fun `the after-arm hook runs even when the arm throws after the session is armed`() = runTest {
        val car = FakeCar()
        val a = arming(car)
        var hooks = 0
        a.afterArm = { hooks++ }
        coEvery { car.helper.writeStatus(HudArming.CAN_NAVI.first, HudArming.CAN_NAVI.second, any(), any()) } throws
            IllegalStateException("helper gone")
        assertTrue(runCatching { a.arm() }.isFailure)
        assertTrue(a.armed)
        assertEquals(1, hooks)
    }

    @Test fun `a failing hook still disarms`() = runTest {
        val car = FakeCar()
        val a = arming(car)
        a.beforeDisarm = { error("helper gone") }
        a.start(backgroundScope) { true }
        runCurrent()
        a.stop()
        assertEquals(4, car.state[HudArming.NAVI])
        assertFalse(a.armed)
    }
}
