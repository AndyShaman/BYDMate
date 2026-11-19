package com.bydmate.app.data.nativestack

import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.diParsData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #426: a Qin L DM-i has no rear motor, yet «Техника» shows its -36 and 65 degrees. The verdict
 * comes from the axle's own behaviour, never from a temperature value.
 */
class MotorPresenceTest {

    private val qin = "native-stack-fixtures/tech-qinl-rear-absent-20261116.txt"
    private val leopard3 = "native-stack-fixtures/tech-leopard3-awd-drive-20261113.txt"
    private val stepMs = 2_000L

    private fun axles(rpmF: Int?, rpmR: Int?, curF: Float? = 0f, curR: Float? = 0f): DiParsData =
        diParsData().copy(
            motorRpmFront = rpmF, motorRpmRear = rpmR, motorCurrentFront = curF, motorCurrentRear = curR,
        )

    /** Feeds [snapshots] at 2 s steps, cycling the list until [seconds] have passed; returns the last clock. */
    private fun replay(
        presence: MotorPresence,
        snapshots: List<DiParsData>,
        seconds: Int,
        startMs: Long = 0L,
    ): Long {
        var now = startMs
        var i = 0
        while (now - startMs <= seconds * 1_000L) {
            presence.onSnapshot(snapshots[i % snapshots.size], now)
            now += stepMs
            i++
        }
        return now
    }

    /** The Qin drive lines where the front motor really spins (rF >= 300). */
    private suspend fun qinDrive() = HybridTechFixture.snapshots(qin).filter { (it.motorRpmFront ?: 0) >= 300 }

    @Test fun `Qin L drive marks the rear axle absent`() = runTest {
        val presence = MotorPresence()
        replay(presence, qinDrive(), seconds = 24)
        assertTrue(presence.rearAbsent)
        assertFalse(presence.frontAbsent)
    }

    @Test fun `Leopard 3 drive keeps both axles`() = runTest {
        val presence = MotorPresence()
        replay(presence, HybridTechFixture.snapshots(leopard3), seconds = 120)
        assertFalse(presence.rearAbsent)
        assertFalse(presence.frontAbsent)
    }

    @Test fun `a parked car never decides`() = runTest {
        val parked = HybridTechFixture.snapshots(qin).filter { it.motorRpmFront == 0 } +
            HybridTechFixture.snapshots(HybridTechFixture.FIXTURE)
        val presence = MotorPresence()
        replay(presence, parked, seconds = 120)
        assertFalse(presence.rearAbsent)
        assertFalse(presence.frontAbsent)
    }

    /** 441/448: the missing axle answers sentinels, which decode to null, so there is nothing to judge. */
    @Test fun `a missing axle that answers a sentinel never decides`() {
        val presence = MotorPresence()
        replay(presence, listOf(axles(rpmF = 2969, rpmR = null, curR = null)), seconds = 120)
        assertFalse(presence.rearAbsent)
        assertFalse(presence.frontAbsent)
    }

    @Test fun `alive evidence clears the verdict for good`() = runTest {
        val presence = MotorPresence()
        var now = replay(presence, qinDrive(), seconds = 24)
        assertTrue(presence.rearAbsent)

        presence.onSnapshot(axles(rpmF = 5000, rpmR = 1500, curR = 12f), now)
        assertFalse(presence.rearAbsent)

        now = replay(presence, qinDrive(), seconds = 60, startMs = now + stepMs)
        assertFalse(presence.rearAbsent)
    }

    @Test fun `rear rpm outside the idle band counts as alive`() {
        val presence = MotorPresence()
        val now = replay(presence, listOf(axles(rpmF = 5000, rpmR = 0)), seconds = 24)
        assertTrue(presence.rearAbsent)
        presence.onSnapshot(axles(rpmF = 5000, rpmR = 2), now)
        assertFalse(presence.rearAbsent)
    }

    @Test fun `a current of half an ampere counts as alive`() {
        val presence = MotorPresence()
        val now = replay(presence, listOf(axles(rpmF = 5000, rpmR = 0)), seconds = 24)
        assertTrue(presence.rearAbsent)
        presence.onSnapshot(axles(rpmF = 5000, rpmR = 0, curR = 0.5f), now)
        assertFalse(presence.rearAbsent)
    }

    @Test fun `a short dead stretch is not enough`() {
        val presence = MotorPresence()
        val spinning = axles(rpmF = 5000, rpmR = 0)
        presence.onSnapshot(spinning, 0L)
        presence.onSnapshot(spinning, 5_000L)
        assertFalse(presence.rearAbsent)
    }

    @Test fun `twenty seconds of continuous zeros are the threshold`() {
        val presence = MotorPresence()
        val spinning = axles(rpmF = 5000, rpmR = 0)
        for (t in 0L..18_000L step stepMs) presence.onSnapshot(spinning, t)
        assertFalse(presence.rearAbsent)
        presence.onSnapshot(spinning, 20_000L)
        assertTrue(presence.rearAbsent)
    }

    @Test fun `a gap longer than 5 s restarts the count`() {
        val presence = MotorPresence()
        val spinning = axles(rpmF = 5000, rpmR = 0)
        for (t in 0L..10_000L step stepMs) presence.onSnapshot(spinning, t)
        // Six seconds of silence: the stretch before it no longer counts.
        for (t in 16_000L..34_000L step stepMs) presence.onSnapshot(spinning, t)
        assertFalse(presence.rearAbsent)
        presence.onSnapshot(spinning, 36_000L)
        assertTrue(presence.rearAbsent)
    }

    @Test fun `a non-qualifying snapshot restarts the count`() {
        val presence = MotorPresence()
        val spinning = axles(rpmF = 5000, rpmR = 0)
        for (t in 0L..16_000L step stepMs) presence.onSnapshot(spinning, t)
        // The front motor idles for one tick: no verdict can rest on the stretch before it.
        presence.onSnapshot(axles(rpmF = 0, rpmR = 0), 18_000L)
        for (t in 20_000L..36_000L step stepMs) presence.onSnapshot(spinning, t)
        assertFalse(presence.rearAbsent)
        presence.onSnapshot(spinning, 40_000L)
        assertTrue(presence.rearAbsent)
    }

    @Test fun `a front motor that never spins is the mirror case`() {
        val presence = MotorPresence()
        replay(presence, listOf(axles(rpmF = 0, rpmR = 5000)), seconds = 24)
        assertTrue(presence.frontAbsent)
        assertFalse(presence.rearAbsent)
    }
}
