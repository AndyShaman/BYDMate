package com.bydmate.app.data.trips

import com.bydmate.app.data.local.EnergyDataDeadDetector
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.remote.DiParsData
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

/** A hybrid trip keeps its kWh when the closing tick has no BMS counter (the last ON tick's counter is used). */
class TripRecorderHybridElecEdgeTest {

    private val tripDao = mockk<TripDao>(relaxed = true)
    private val lastState = mockk<LastStateDao>(relaxed = true).also {
        coEvery { it.openTrip(any(), any(), any(), any(), any(), any()) } returns 1
    }
    private var clock = 0L
    private var energyDataAvailable = false

    private fun recorder(kind: VehicleKind) = TripRecorder(
        tripDao, lastState,
        mockk<EnergyDataReader> { every { isAvailable() } answers { energyDataAvailable } },
        mockk<EnergyDataDeadDetector>(relaxed = true),
        batteryCapacityKwh = { 72.9 },
        now = { clock },
        kind = { kind },
    )

    private fun tick(
        powerState: Int?,
        mileage: Double,
        soc: Int,
        elec: Double?,
        rpm: Int? = null,
    ): DiParsData = diParsData(powerState = powerState, mileage = mileage, soc = soc, totalElecConsumption = elec)
        .copy(engineRpm = rpm)

    private suspend fun TripRecorder.at(ms: Long, data: DiParsData) {
        clock = ms
        consume(data)
    }

    /**
     * Opens at 0 s (counter 100.0), last ON tick at 10 s (counter 101.5), then the car is parked:
     * the confirming tick comes at 120 s carrying [closingElec] and [closingSoc].
     */
    private suspend fun driveAndPark(
        rec: TripRecorder, startSoc: Int, closingSoc: Int, closingElec: Double?,
    ): TripEntity {
        rec.at(0, tick(powerState = 2, mileage = 1000.0, soc = startSoc, elec = 100.0))
        rec.at(10_000, tick(powerState = 2, mileage = 1040.0, soc = startSoc, elec = 101.5))
        rec.at(20_000, tick(powerState = 0, mileage = 1040.0, soc = closingSoc, elec = null, rpm = 0))
        rec.at(80_000, tick(powerState = 0, mileage = 1040.0, soc = closingSoc, elec = null, rpm = 0))
        rec.at(120_000, tick(powerState = 0, mileage = 1040.0, soc = closingSoc, elec = closingElec, rpm = 0))
        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        return trip.captured
    }

    @Test fun `a hybrid trip takes its kWh from the last ON tick when the closing tick has no counter`() = runTest {
        val trip = driveAndPark(recorder(VehicleKind.HYBRID), startSoc = 60, closingSoc = 60, closingElec = null)
        assertEquals(1.5, trip.kwhConsumed!!, 1e-9)
    }

    @Test fun `a hybrid trip prefers the closing tick counter when it is present`() = runTest {
        val trip = driveAndPark(recorder(VehicleKind.HYBRID), startSoc = 60, closingSoc = 60, closingElec = 101.7)
        assertEquals(1.7, trip.kwhConsumed!!, 1e-9)
    }

    @Test fun `an EV trip without a closing counter still takes the SOC estimate`() = runTest {
        val trip = driveAndPark(recorder(VehicleKind.EV), startSoc = 80, closingSoc = 78, closingElec = null)
        assertEquals(2 / 100.0 * 72.9, trip.kwhConsumed!!, 1e-9)
    }

    @Test fun `a hybrid trip gets no kWh from a counter seen only before the trip started`() = runTest {
        val rec = recorder(VehicleKind.HYBRID)
        // Passive tick (energydata is live): the counter is seen but no trip opens.
        energyDataAvailable = true
        rec.at(0, tick(powerState = 2, mileage = 1000.0, soc = 60, elec = 100.0))
        energyDataAvailable = false
        // The trip opens here and no tick of it carries a counter.
        rec.at(10_000, tick(powerState = 2, mileage = 1000.0, soc = 60, elec = null))
        rec.at(20_000, tick(powerState = 0, mileage = 1000.0, soc = 60, elec = null, rpm = 0))
        rec.at(60_000, tick(powerState = 0, mileage = 1000.0, soc = 60, elec = null, rpm = 0))
        rec.at(100_000, tick(powerState = 0, mileage = 1000.0, soc = 60, elec = null, rpm = 0))
        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertNull(trip.captured.kwhConsumed)
    }
}
