package com.bydmate.app.data.trips

import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.remote.diParsData
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PowerStateFallbackTest {
    @Test fun `after 5 null powerState ticks, gear D acts as ignition DRIVE`() = runTest {
        val tripDao = mockk<TripDao>(relaxed = true)
        val ls = mockk<LastStateDao>(relaxed = true)
        val en = mockk<EnergyDataReader> { every { isAvailable() } returns false }
        val rec = TripRecorder(tripDao, ls, en, batteryCapacityKwh = { 72.9 })

        // 5 ticks with powerState null → fallback armed.
        repeat(5) { rec.consume(diParsData(powerState = null, gear = 1, soc = 80, mileage = 100.0)) }
        // Gear D (drive) opens a trip.
        rec.consume(diParsData(powerState = null, gear = 4, soc = 80, mileage = 100.0, speed = 10))
        // Gear P (park) ends it.
        rec.consume(diParsData(powerState = null, gear = 1, soc = 75, mileage = 105.0, speed = 0))

        coVerify(exactly = 1) { tripDao.insert(any()) }
    }
}
