package com.bydmate.app.domain.tracker

import android.location.Location
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.remote.diParsData
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripTrackerTest {

    private val dao = mockk<TripPointDao>(relaxed = true)

    // Controllable clock — START_DELAY_MS (5 s) must elapse between the arm tick
    // and the confirm tick for the IDLE→DRIVING transition.
    private var nowMs = 1_000L
    private fun tracker() = TripTracker(dao, clock = { nowMs })

    @After
    fun tearDown() = unmockkAll()

    /** A moving GPS fix: hasSpeed()=true, speed in m/s. */
    private fun movingLocation(speedMs: Float) = mockk<Location>(relaxed = true) {
        every { hasSpeed() } returns true
        every { speed } returns speedMs
        every { latitude } returns 53.7655
        every { longitude } returns 27.6642
    }

    /** A GPS fix with no speed (e.g. stale getLastKnownLocation). */
    private fun noSpeedLocation() = mockk<Location>(relaxed = true) {
        every { hasSpeed() } returns false
        every { speed } returns 0f
        every { latitude } returns 53.7655
        every { longitude } returns 27.6642
    }

    @Test
    fun `falls back to GPS speed when autoservice speed is null (DiLink4)`() = runTest {
        // DiLink 4: autoservice speed fid returns sentinel → data.speed == null.
        // GPS still reports motion, so the tracker must start collecting.
        val tracker = tracker()
        val data = diParsData(speed = null)
        val loc = movingLocation(15f) // ≈ 54 km/h

        nowMs = 1_000L
        tracker.onData(data, loc) // arm
        nowMs = 7_000L
        tracker.onData(data, loc) // +6 s ≥ START_DELAY → DRIVING

        assertEquals(TripState.DRIVING, tracker.state.value)
    }

    @Test
    fun `applies m_s to km_h conversion on GPS speed fallback`() = runTest {
        // 1.2 m/s → 4 km/h (above SPEED_THRESHOLD) only WITH the *3.6 conversion;
        // the raw m/s value (1) would stay below threshold and never start. Guards
        // against the conversion silently being dropped.
        val tracker = tracker()
        val data = diParsData(speed = null)
        val loc = movingLocation(1.2f)

        nowMs = 1_000L
        tracker.onData(data, loc) // arm
        nowMs = 7_000L
        tracker.onData(data, loc) // +6 s ≥ START_DELAY → DRIVING (only if converted)

        assertEquals(TripState.DRIVING, tracker.state.value)
    }

    @Test
    fun `stays idle when both autoservice and GPS speed are unavailable`() = runTest {
        val tracker = tracker()
        val data = diParsData(speed = null)
        val loc = noSpeedLocation()

        nowMs = 1_000L
        tracker.onData(data, loc)
        nowMs = 7_000L
        tracker.onData(data, loc)

        assertEquals(TripState.IDLE, tracker.state.value)
    }

    @Test
    fun `uses autoservice speed when present, ignoring GPS (DiLink5 unchanged)`() = runTest {
        // Regression guard: where data.speed is valid (Leopard 3) the GPS
        // fallback must not change behaviour — even with a null location.
        val tracker = tracker()
        val data = diParsData(speed = 54)

        nowMs = 1_000L
        tracker.onData(data, null) // arm
        nowMs = 7_000L
        tracker.onData(data, null) // DRIVING

        assertEquals(TripState.DRIVING, tracker.state.value)
    }

    @Test
    fun `a drive that starts without a fix keeps every point from the first fix on (#361)`() = runTest {
        // Underground garage: the car moves (autoservice speed is readable) but there is no fix.
        val tracker = tracker()
        val data = diParsData(speed = 20)
        nowMs = 1_000L
        tracker.onData(data, null) // arm
        nowMs = 7_000L
        tracker.onData(data, null) // DRIVING, nothing to record yet
        assertEquals(TripState.DRIVING, tracker.state.value)

        // Out on the street: fixes arrive and every one of them is kept.
        val fix = movingLocation(5f)
        repeat(30) {
            nowMs += 1_000L
            tracker.onData(data, fix)
        }

        val saved = slot<List<TripPointEntity>>()
        coVerify(exactly = 1) { dao.insertAll(capture(saved)) }
        assertEquals(30, saved.captured.size)
        assertTrue(saved.captured.all { it.timestamp > 7_000L })
    }

    @Test
    fun `a recorded point carries the battery power of its tick`() = runTest {
        val tracker = tracker()
        val data = diParsData(speed = 20)
        nowMs = 1_000L
        tracker.onData(data, null) // arm
        nowMs = 7_000L
        tracker.onData(data, null) // DRIVING

        val fix = movingLocation(5f)
        repeat(30) { i ->
            nowMs += 1_000L
            val w = if (i % 2 == 0) 12_500.0 else -8_000.0
            tracker.onData(data.copy(batteryPowerW = w), fix)
        }

        val saved = slot<List<TripPointEntity>>()
        coVerify(exactly = 1) { dao.insertAll(capture(saved)) }
        assertEquals(30, saved.captured.size)
        assertEquals(12_500.0, saved.captured[0].batteryPowerW!!, 0.0)
        assertEquals(-8_000.0, saved.captured[1].batteryPowerW!!, 0.0)
    }

    @Test
    fun `a recorded point has no battery power when the reading is unavailable`() = runTest {
        val tracker = tracker()
        val data = diParsData(speed = 20) // batteryPowerW stays null
        nowMs = 1_000L
        tracker.onData(data, null) // arm
        nowMs = 7_000L
        tracker.onData(data, null) // DRIVING

        val fix = movingLocation(5f)
        repeat(30) {
            nowMs += 1_000L
            tracker.onData(data, fix)
        }

        val saved = slot<List<TripPointEntity>>()
        coVerify(exactly = 1) { dao.insertAll(capture(saved)) }
        assertTrue(saved.captured.isNotEmpty())
        assertTrue(saved.captured.all { it.batteryPowerW == null })
    }

    /**
     * Drives a tracker to DRIVING with ticks 1 s apart (arm at 1 000 ms, DRIVING at 6 000 ms),
     * all at 3 600 W, then returns 30 recorded points whose power comes from [powerAt] (tick index);
     * the tick at [pauseBeforeTick] comes 15 s after the previous one. Counter after the tick at time t is (t - 1 000) / 1 000 Wh while the power stays 3 600 W.
     */
    private suspend fun driveWithPower(
        tracker: TripTracker,
        pauseBeforeTick: Int? = null,
        powerAt: (Int) -> Double? = { 3_600.0 },
    ): List<TripPointEntity> {
        val data = diParsData(speed = 20).copy(batteryPowerW = 3_600.0)
        nowMs = 1_000L
        tracker.onData(data, null) // arm
        repeat(5) {
            nowMs += 1_000L
            tracker.onData(data, null) // the last one is the DRIVING tick
        }
        val fix = movingLocation(5f)
        repeat(30) { i ->
            nowMs += if (i == pauseBeforeTick) 15_000L else 1_000L
            tracker.onData(data.copy(batteryPowerW = powerAt(i)), fix)
        }
        val saved = slot<List<TripPointEntity>>()
        coVerify(exactly = 1) { dao.insertAll(capture(saved)) }
        assertEquals(30, saved.captured.size)
        return saved.captured
    }

    @Test
    fun `the battery counter grows by one Wh per second of 3600 W`() = runTest {
        val runId = nowMs // the tracker below is created at this clock value
        val points = driveWithPower(tracker())
        points.forEach {
            assertEquals((it.timestamp - 1_000L) / 1_000.0, it.batteryEnergyWh!!, 1e-9)
            assertEquals(runId, it.energyRun)
        }
        points.zipWithNext().forEach { (a, b) ->
            assertEquals(1.0, b.batteryEnergyWh!! - a.batteryEnergyWh!!, 1e-9)
        }
    }

    @Test
    fun `a pause over ten seconds adds nothing to the battery counter`() = runTest {
        val points = driveWithPower(tracker(), pauseBeforeTick = 10)
        assertEquals(points[9].batteryEnergyWh!!, points[10].batteryEnergyWh!!, 1e-9)
        assertEquals(1.0, points[11].batteryEnergyWh!! - points[10].batteryEnergyWh!!, 1e-9)
        assertEquals(1.0, points[9].batteryEnergyWh!! - points[8].batteryEnergyWh!!, 1e-9)
    }

    @Test
    fun `regen takes the battery counter down`() = runTest {
        // tick 10 draws -7 200 W (the interval before it still used +3 600 W), tick 11 is back to
        // +3 600 W and the interval 10 -> 11 is one second at -7 200 W = -2 Wh
        val points = driveWithPower(tracker()) { i -> if (i == 10) -7_200.0 else 3_600.0 }
        assertEquals(1.0, points[10].batteryEnergyWh!! - points[9].batteryEnergyWh!!, 1e-9)
        assertEquals(-2.0, points[11].batteryEnergyWh!! - points[10].batteryEnergyWh!!, 1e-9)
    }

    // A break in the integration must survive thinning: the points on both sides of it belong to
    // different runs, so TripScrub.energyKwh across the break is unknown instead of too small.
    @Test
    fun `a pause over ten seconds starts a new energy run`() = runTest {
        val points = driveWithPower(tracker(), pauseBeforeTick = 10)
        assertNotEquals(points[9].energyRun, points[10].energyRun)
        assertEquals(points[10].energyRun, points[11].energyRun)
        assertEquals(points[0].energyRun, points[9].energyRun)
    }

    @Test
    fun `a tick without battery power starts a new energy run`() = runTest {
        val points = driveWithPower(tracker()) { i -> if (i == 10) null else 3_600.0 }
        assertNull(points[10].energyRun)
        assertNotEquals(points[9].energyRun, points[11].energyRun)
        assertEquals(points[11].energyRun, points[12].energyRun)
    }
}
