package com.bydmate.app.data.trips

import com.bydmate.app.data.local.EnergyDataDeadDetector
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.LastStateEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.remote.diParsData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Native trips carry the odometer TripRecorder already reads at both ends of the drive. */
class TripRecorderOdometerTest {

    private val tripDao = mockk<TripDao>(relaxed = true)

    private fun recorder(lastState: LastStateDao = mockk(relaxed = true), now: () -> Long) = TripRecorder(
        tripDao, lastState,
        mockk<EnergyDataReader> { every { isAvailable() } returns false },
        mockk<EnergyDataDeadDetector>(relaxed = true),
        batteryCapacityKwh = { 72.9 },
        now = now,
    )

    private fun inserted(): TripEntity {
        val captured = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(captured)) }
        return captured.captured
    }

    @Test fun `close stores the odometer at open and at close`() = runTest {
        val clock = mutableListOf(1_000L, 2_000L)
        val rec = recorder { clock.removeAt(0) }
        rec.consume(diParsData(powerState = 2, soc = 80, mileage = 11042.4))
        rec.consume(diParsData(powerState = 1, soc = 64, mileage = 11092.9))

        val t = inserted()
        assertEquals(11042.4, t.odometerStartKm!!, 1e-9)
        assertEquals(11092.9, t.odometerEndKm!!, 1e-9)
    }

    @Test fun `startup zero is not an odometer`() = runTest {
        val clock = mutableListOf(1_000L, 2_000L)
        val rec = recorder { clock.removeAt(0) }
        rec.consume(diParsData(powerState = 2, soc = 80, mileage = 0.0))
        rec.consume(diParsData(powerState = 1, soc = 79, mileage = 11092.9))

        val t = inserted()
        assertNull(t.odometerStartKm)
        assertEquals(11092.9, t.odometerEndKm!!, 1e-9)
    }

    @Test fun `a scale switch under the open trip trusts neither end`() = runTest {
        val clock = mutableListOf(1_000L, 2_000L)
        val rec = recorder { clock.removeAt(0) }
        rec.consume(diParsData(powerState = 2, soc = 80, mileage = 8647.2))
        rec.consume(diParsData(powerState = 1, soc = 70, mileage = 86472.0))

        val t = inserted()
        assertNull(t.odometerStartKm)
        assertNull(t.odometerEndKm)
    }

    @Test fun `cold-start close stores the odometer from last_state`() = runTest {
        val lastState = mockk<LastStateDao>(relaxed = true) {
            coEvery { getCurrent() } returns LastStateEntity(
                id = 1, ts = 1_000_000L, soc = 70, mileage = 11092.9,
                openTripId = 99L, tripStartTs = 900_000L, tripStartSoc = 80,
                tripStartMileage = 11042.4,
            )
        }
        val rec = recorder(lastState) { 1_000_000L + 10 * 60_000L }
        rec.reconcileColdStart()

        val t = inserted()
        assertEquals(11042.4, t.odometerStartKm!!, 1e-9)
        assertEquals(11092.9, t.odometerEndKm!!, 1e-9)
    }
}
