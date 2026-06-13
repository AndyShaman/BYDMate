package com.bydmate.app.data.trips

import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.LastStateEntity
import com.bydmate.app.data.local.entity.TripEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ColdStartReconciliationTest {

    private fun energyAvailable(b: Boolean) =
        mockk<EnergyDataReader> { every { isAvailable() } returns b }

    @Test fun `gap less than 5 minutes resumes the open trip`() = runTest {
        val tripDao = mockk<TripDao>(relaxed = true)
        val lastState = mockk<LastStateDao>(relaxed = true) {
            coEvery { getCurrent() } returns LastStateEntity(
                id = 1, ts = 1_000_000L, soc = 70, mileage = 110.0,
                openTripId = 99L, tripStartTs = 900_000L, tripStartSoc = 80,
                tripStartMileage = 100.0
            )
        }
        val rec = TripRecorder(
            tripDao, lastState, energyAvailable(false),
            batteryCapacityKwh = { 72.9 },
            now = { 1_000_000L + 4 * 60_000L }   // 4 min later
        )
        rec.reconcileColdStart()
        coVerify(exactly = 0) { tripDao.insert(any()) }
    }

    @Test fun `gap 5+ minutes finalises stale trip`() = runTest {
        val tripDao = mockk<TripDao>(relaxed = true)
        val lastState = mockk<LastStateDao>(relaxed = true) {
            coEvery { getCurrent() } returns LastStateEntity(
                id = 1, ts = 1_000_000L, soc = 70, mileage = 110.0,
                openTripId = 99L, tripStartTs = 900_000L, tripStartSoc = 80,
                tripStartMileage = 100.0
            )
        }
        val rec = TripRecorder(
            tripDao, lastState, energyAvailable(false),
            batteryCapacityKwh = { 72.9 },
            now = { 1_000_000L + 10 * 60_000L }
        )
        rec.reconcileColdStart()
        val cap = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(cap)) }
        assertEquals(900_000L, cap.captured.startTs)
        assertEquals(1_000_000L, cap.captured.endTs)
        assertEquals(80, cap.captured.socStart)
        assertEquals(70, cap.captured.socEnd)
        assertEquals(10.0, cap.captured.distanceKm!!, 0.001)
        assertEquals(72.9 * 0.10, cap.captured.kwhConsumed!!, 0.001)
        assertEquals(72.9, cap.captured.kwhPer100km!!, 0.001)  // 7.29 kWh / 10 km * 100
        coVerify { lastState.clearOpenTrip() }
    }

    @Test fun `stale trip consumption uses totalElec delta when available`() = runTest {
        val tripDao = mockk<TripDao>(relaxed = true)
        val lastState = mockk<LastStateDao>(relaxed = true) {
            coEvery { getCurrent() } returns LastStateEntity(
                id = 1, ts = 1_000_000L, soc = 70, mileage = 110.0,
                totalElec = 500.8,
                openTripId = 99L, tripStartTs = 900_000L, tripStartSoc = 70,
                tripStartMileage = 100.0, tripStartTotalElec = 499.5
            )
        }
        val rec = TripRecorder(
            tripDao, lastState, energyAvailable(false),
            batteryCapacityKwh = { 72.9 },
            now = { 1_000_000L + 10 * 60_000L }
        )
        rec.reconcileColdStart()
        val cap = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(cap)) }
        assertEquals(1.3, cap.captured.kwhConsumed!!, 0.001)   // SOC flat: SOC path would yield null
        assertEquals(13.0, cap.captured.kwhPer100km!!, 0.001)  // 1.3 kWh / 10 km * 100
    }

    @Test fun `no last_state row starts fresh`() = runTest {
        val tripDao = mockk<TripDao>(relaxed = true)
        val lastState = mockk<LastStateDao>(relaxed = true) { coEvery { getCurrent() } returns null }
        val rec = TripRecorder(tripDao, lastState, energyAvailable(false), batteryCapacityKwh = { 72.9 })
        rec.reconcileColdStart()
        coVerify(exactly = 0) { tripDao.insert(any()) }
    }

    @Test fun `passive mode (energydata available) never inserts even when openTripId set`() = runTest {
        val tripDao = mockk<TripDao>(relaxed = true)
        val lastState = mockk<LastStateDao>(relaxed = true) {
            coEvery { getCurrent() } returns LastStateEntity(
                id = 1, ts = 1_000_000L, openTripId = 99L,
                tripStartTs = 900_000L, tripStartSoc = 80, tripStartMileage = 100.0
            )
        }
        val rec = TripRecorder(
            tripDao, lastState, energyAvailable(true),
            batteryCapacityKwh = { 72.9 },
            now = { 1_000_000L + 10 * 60_000L }
        )
        rec.reconcileColdStart()
        coVerify(exactly = 0) { tripDao.insert(any()) }
        coVerify { lastState.clearOpenTrip() }
    }
}
