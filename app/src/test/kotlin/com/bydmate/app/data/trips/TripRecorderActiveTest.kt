package com.bydmate.app.data.trips

import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.remote.diParsData
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripRecorderActiveTest {

    private fun setup(): Triple<TripRecorder, TripDao, LastStateDao> {
        val tripDao = mockk<TripDao>(relaxed = true)
        val lastState = mockk<LastStateDao>(relaxed = true)
        val energy = mockk<EnergyDataReader> { every { isAvailable() } returns false }
        val clock = mutableListOf(1_000L, 2_000L, 3_000L, 4_000L)
        val recorder = TripRecorder(
            tripDao, lastState, energy,
            batteryCapacityKwh = { 72.9 },
            now = { clock.removeAt(0) },
        )
        return Triple(recorder, tripDao, lastState)
    }

    @Test fun `ACC then DRIVE opens trip, DRIVE to ACC closes with one row + writes last_state`() = runTest {
        val (rec, tripDao, lastState) = setup()
        rec.consume(diParsData(powerState = 1, soc = 80, mileage = 100.0))  // ACC
        rec.consume(diParsData(powerState = 2, soc = 80, mileage = 100.0))  // DRIVE -> open(now=1000)
        rec.consume(diParsData(powerState = 2, soc = 70, mileage = 110.0))  // driving (no insert)
        rec.consume(diParsData(powerState = 1, soc = 70, mileage = 110.0))  // ACC -> close(now=2000)

        val captured = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(captured)) }
        val t = captured.captured
        assertEquals(1_000L, t.startTs)
        assertEquals(2_000L, t.endTs)
        assertEquals(80, t.socStart)
        assertEquals(70, t.socEnd)
        assertEquals(10.0, t.distanceKm!!, 0.001)
        assertEquals(72.9 * 0.10, t.kwhConsumed!!, 0.001)
        assertEquals(TripSource.NATIVE_POLLING, t.source)
        coVerify(exactly = 1) { lastState.openTrip(startTs = 1_000L, startSoc = 80, startMileage = 100.0, now = 1_000L) }
        coVerify(exactly = 1) { lastState.clearOpenTrip() }
    }

    @Test fun `consecutive DRIVE ticks do not double-open`() = runTest {
        val (rec, tripDao, lastState) = setup()
        rec.consume(diParsData(powerState = 2, soc = 80, mileage = 100.0))
        rec.consume(diParsData(powerState = 2, soc = 79, mileage = 101.0))
        rec.consume(diParsData(powerState = 2, soc = 78, mileage = 102.0))
        rec.consume(diParsData(powerState = 1, soc = 78, mileage = 102.0))
        coVerify(exactly = 1) { tripDao.insert(any()) }
        coVerify(exactly = 1) { lastState.openTrip(any(), any(), any(), any()) }
    }

    @Test fun `DRIVE to OFF also closes (powerState 2 to 0)`() = runTest {
        val (rec, tripDao, lastState) = setup()
        rec.consume(diParsData(powerState = 2, soc = 80, mileage = 100.0))  // DRIVE -> open
        rec.consume(diParsData(powerState = 0, soc = 75, mileage = 105.0))  // OFF -> close
        coVerify(exactly = 1) { tripDao.insert(any()) }
        coVerify(exactly = 1) { lastState.clearOpenTrip() }
    }
}
