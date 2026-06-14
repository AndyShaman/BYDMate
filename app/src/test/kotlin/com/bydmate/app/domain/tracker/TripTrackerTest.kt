package com.bydmate.app.domain.tracker

import android.location.Location
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.remote.diParsData
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
}
