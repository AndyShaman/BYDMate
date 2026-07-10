package com.bydmate.app.data.trips

import com.bydmate.app.data.local.EnergyDataDeadDetector
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.remote.diParsData
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TripRecorderDeadEnergydataTest {

    private fun recorder(
        isAvailable: Boolean,
        isDead: Boolean,
        detector: EnergyDataDeadDetector = mockk(relaxed = true) {
            every { isDead() } returns isDead
        },
    ): Triple<TripRecorder, TripDao, EnergyDataDeadDetector> {
        val tripDao = mockk<TripDao>(relaxed = true)
        val lastState = mockk<LastStateDao>(relaxed = true)
        val energy = mockk<EnergyDataReader> { every { isAvailable() } returns isAvailable }
        val clock = mutableListOf(1_000L, 2_000L, 3_000L, 4_000L)
        val rec = TripRecorder(
            tripDao, lastState, energy, detector,
            batteryCapacityKwh = { 72.9 },
            now = { clock.removeAt(0) },
        )
        return Triple(rec, tripDao, detector)
    }

    // Matrix case 3 at recorder level: dead verdict overrides a readable leftover DB.
    @Test
    fun `dead verdict makes recorder active despite readable energydata`() = runTest {
        val (rec, tripDao, _) = recorder(isAvailable = true, isDead = true)
        rec.consume(diParsData(powerState = 2, soc = 80, mileage = 1000.0))
        rec.consume(diParsData(powerState = 0, soc = 78, mileage = 1010.0))
        val inserted = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(inserted)) }
        assertEquals(10.0, inserted.captured.distanceKm!!, 0.001)
    }

    // Matrix cases 1/2 at recorder level: alive verdict keeps the passive gate intact.
    @Test
    fun `alive verdict keeps recorder passive when energydata is readable`() = runTest {
        val (rec, tripDao, _) = recorder(isAvailable = true, isDead = false)
        rec.consume(diParsData(powerState = 2, soc = 80, mileage = 1000.0))
        rec.consume(diParsData(powerState = 0, soc = 78, mileage = 1010.0))
        coVerify(exactly = 0) { tripDao.insert(any()) }
    }

    @Test
    fun `passive recorder still feeds every tick into the detector`() = runTest {
        val detector = mockk<EnergyDataDeadDetector>(relaxed = true) { every { isDead() } returns false }
        val (rec, _, _) = recorder(isAvailable = true, isDead = false, detector = detector)
        rec.consume(diParsData(powerState = 2, soc = 80, mileage = 1000.0))
        rec.consume(diParsData(powerState = 0, soc = 78, mileage = 1010.0))
        verify(exactly = 1) { detector.onTick(true, 1000.0) }
        verify(exactly = 1) { detector.onTick(false, 1010.0) }
    }
}
