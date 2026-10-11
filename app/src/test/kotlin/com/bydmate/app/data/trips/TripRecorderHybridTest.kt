package com.bydmate.app.data.trips

import com.bydmate.app.data.local.EnergyDataDeadDetector
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.LastStateEntity
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

/** DM-i trips (#184): fuel counters at both edges, no SOC fallback, the hybrid ignition edge. */
class TripRecorderHybridTest {

    private val tripDao = mockk<TripDao>(relaxed = true)
    private val lastState = mockk<LastStateDao>(relaxed = true).also {
        coEvery { it.openTrip(any(), any(), any(), any(), any(), any()) } returns 1
    }
    private var clock = 0L

    private fun recorder(kind: VehicleKind) = TripRecorder(
        tripDao, lastState,
        mockk<EnergyDataReader> { every { isAvailable() } returns false },
        mockk<EnergyDataDeadDetector>(relaxed = true),
        batteryCapacityKwh = { 72.9 },
        now = { clock },
        kind = { kind },
    )

    private fun hybrid(
        powerState: Int? = null,
        mileage: Double? = 1000.0,
        fuel: Double? = null,
        hev: Int? = null,
        rpm: Int? = null,
    ): DiParsData = diParsData(powerState = powerState, mileage = mileage, soc = 60)
        .copy(fuelTotalL = fuel, mileageHevKm = hev, engineRpm = rpm)

    private suspend fun TripRecorder.at(ms: Long, data: DiParsData) {
        clock = ms
        consume(data)
    }

    /** Opens at 0 s, drives to 10 s, parks; the edge drops 90 s after the last odometer move. */
    private suspend fun drive(rec: TripRecorder, start: DiParsData, end: DiParsData): TripEntity {
        rec.at(0, start)
        rec.at(10_000, end)
        rec.at(20_000, end.copy(powerState = 0, engineRpm = 0))
        rec.at(80_000, end.copy(powerState = 0, engineRpm = 0))
        rec.at(120_000, end.copy(powerState = 0, engineRpm = 0))
        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        return trip.captured
    }

    @Test fun `a hybrid trip takes its litres and engine km from the counters at both edges`() = runTest {
        val trip = drive(
            recorder(VehicleKind.HYBRID),
            hybrid(powerState = 2, mileage = 1000.0, fuel = 302.4f.toDouble(), hev = 3812),
            hybrid(powerState = 2, mileage = 1040.0, fuel = 305.2f.toDouble(), hev = 3850),
        )

        assertEquals(2.8, trip.fuelLiters!!, 1e-9)
        assertEquals(38.0, trip.hevKm!!, 1e-9)
        assertEquals(302.4, trip.fuelTotalStart!!, 1e-4)
        assertEquals(305.2, trip.fuelTotalEnd!!, 1e-4)
        assertEquals(3812.0, trip.mileageHevStart!!, 1e-9)
        assertEquals(3850.0, trip.mileageHevEnd!!, 1e-9)
        assertEquals(40.0, trip.distanceKm!!, 1e-9)
        assertNull("no SOC estimate on a hybrid", trip.kwhConsumed)
        assertNull(trip.fuelCost)
        coVerify(exactly = 1) { lastState.setOpenTripCounters(302.4f.toDouble(), 3812.0) }
    }

    @Test fun `a missing end counter leaves the litres and engine km null`() = runTest {
        val trip = drive(
            recorder(VehicleKind.HYBRID),
            hybrid(powerState = 2, mileage = 1000.0, fuel = 302.4, hev = 3812),
            hybrid(powerState = 2, mileage = 1040.0, fuel = null, hev = null),
        )
        assertNull(trip.fuelLiters)
        assertNull(trip.hevKm)
        assertNull(trip.fuelTotalEnd)
        assertNull(trip.mileageHevEnd)
        assertEquals(302.4, trip.fuelTotalStart!!, 1e-9)
    }

    @Test fun `a delta out of range leaves the litres and engine km null`() = runTest {
        val trip = drive(
            recorder(VehicleKind.HYBRID),
            hybrid(powerState = 2, mileage = 1000.0, fuel = 302.4, hev = 3812),
            hybrid(powerState = 2, mileage = 1040.0, fuel = 372.4, hev = 3872),
        )
        assertNull("70 L in one trip is a counter glitch", trip.fuelLiters)
        assertNull("60 engine km on a 40 km trip", trip.hevKm)
    }

    @Test fun `a falling counter leaves the litres null`() = runTest {
        val trip = drive(
            recorder(VehicleKind.HYBRID),
            hybrid(powerState = 2, mileage = 1000.0, fuel = 302.4, hev = 3812),
            hybrid(powerState = 2, mileage = 1040.0, fuel = 300.0, hev = 3800),
        )
        assertNull(trip.fuelLiters)
        assertNull(trip.hevKm)
    }

    @Test fun `engine rpm alone opens a hybrid trip and 90 s of silence closes it`() = runTest {
        val rec = recorder(VehicleKind.HYBRID)
        rec.at(0, hybrid(rpm = 1200, fuel = 302.4, hev = 3812))
        coVerify(exactly = 1) { lastState.openTrip(any(), any(), any(), any(), any(), any()) }

        rec.at(30_000, hybrid(rpm = 900, fuel = 302.6, hev = 3812))   // last live tick
        rec.at(60_000, hybrid(rpm = 0, fuel = 302.6, hev = 3812))
        rec.at(119_999, hybrid(rpm = 0, fuel = 302.6, hev = 3812))
        coVerify(exactly = 0) { tripDao.insert(any()) }

        rec.at(120_000, hybrid(rpm = 0, fuel = 302.6, hev = 3812))
        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(0L, trip.captured.startTs)
        assertEquals("ends at its last turning tick", 30_000L, trip.captured.endTs)
        assertEquals(0.2, trip.captured.fuelLiters!!, 1e-9)
    }

    @Test fun `a hybrid trip ends at its last live tick, not when the silence is confirmed`() = runTest {
        val trip = drive(
            recorder(VehicleKind.HYBRID),
            hybrid(powerState = 2, mileage = 1000.0, fuel = 302.4, hev = 3812),
            hybrid(powerState = 2, mileage = 1040.0, fuel = 305.2, hev = 3850),
        )
        // In drive() the odometer window last saw growth at 20 s; the edge dropped at 120 s.
        assertEquals(20_000L, trip.endTs)
    }

    @Test fun `a resumed hybrid trip that sees no live tick ends at the resume, not at zero`() = runTest {
        coEvery { lastState.getCurrent() } returns LastStateEntity(
            id = 1, ts = 1_000_000L, mileage = 1040.0,
            openTripId = 99L, tripStartTs = 900_000L, tripStartMileage = 1000.0,
        )
        val rec = recorder(VehicleKind.HYBRID)
        clock = 1_060_000L
        rec.reconcileColdStart()
        rec.at(1_070_000L, hybrid(powerState = 0, rpm = 0, mileage = 1040.0))
        rec.at(1_150_000L, hybrid(powerState = 0, rpm = 0, mileage = 1040.0))
        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(1_060_000L, trip.captured.endTs)
    }

    @Test fun `a resumed hybrid trip survives the first silent tick and closes 90 s later`() = runTest {
        coEvery { lastState.getCurrent() } returns LastStateEntity(
            id = 1, ts = 1_000_000L, mileage = 1040.0,
            openTripId = 99L, tripStartTs = 900_000L, tripStartMileage = 1000.0,
        )
        val rec = recorder(VehicleKind.HYBRID)
        clock = 1_030_000L
        rec.reconcileColdStart()

        rec.at(1_032_000L, hybrid(powerState = 0, rpm = 0, mileage = 1040.0))
        rec.at(1_119_999L, hybrid(powerState = 0, rpm = 0, mileage = 1040.0))
        coVerify(exactly = 0) { tripDao.insert(any()) }

        rec.at(1_120_000L, hybrid(powerState = 0, rpm = 0, mileage = 1040.0))
        coVerify(exactly = 1) { tripDao.insert(any()) }
    }

    @Test fun `end counters missing on the confirming tick come from the last running tick`() = runTest {
        val rec = recorder(VehicleKind.HYBRID)
        rec.at(0, hybrid(powerState = 2, mileage = 1000.0, fuel = 302.4, hev = 3812))
        rec.at(10_000, hybrid(powerState = 2, mileage = 1040.0, fuel = 305.2, hev = 3850))
        rec.at(75_000, hybrid(powerState = 0, rpm = 0, mileage = 1040.0, fuel = null, hev = null))
        rec.at(100_000, hybrid(powerState = 0, rpm = 0, mileage = 1040.0, fuel = null, hev = null))
        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(10_000L, trip.captured.endTs)
        assertEquals(2.8, trip.captured.fuelLiters!!, 1e-9)
        assertEquals(38.0, trip.captured.hevKm!!, 1e-9)
        assertEquals(305.2, trip.captured.fuelTotalEnd!!, 1e-9)
    }

    @Test fun `a late OFF tick counter never replaces the counters of the last ON tick`() = runTest {
        val rec = recorder(VehicleKind.HYBRID)
        rec.at(0, hybrid(powerState = 2, mileage = 1000.0, fuel = 302.4, hev = 3812))
        rec.at(10_000, hybrid(powerState = 2, mileage = 1040.0, fuel = 305.2, hev = 3850))
        rec.at(75_000, hybrid(powerState = 0, rpm = 0, mileage = 1040.0, fuel = 305.5, hev = 3851))
        rec.at(100_000, hybrid(powerState = 0, rpm = 0, mileage = 1040.0, fuel = 305.5, hev = 3851))
        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(10_000L, trip.captured.endTs)
        assertEquals(305.2, trip.captured.fuelTotalEnd!!, 1e-9)
        assertEquals(3850.0, trip.captured.mileageHevEnd!!, 1e-9)
        assertEquals(2.8, trip.captured.fuelLiters!!, 1e-9)
        coVerify(exactly = 1) { lastState.setLastOn(10_000L, 305.2, 3850.0, 1040.0) }
        coVerify(exactly = 0) { lastState.setLastOn(any(), 305.5, any(), any()) }
    }

    @Test fun `counters lost while the car still moves leave the trip without litres`() = runTest {
        // The odometer grew after the last readable tick: its counters are no end edge of the trip.
        val rec = recorder(VehicleKind.HYBRID)
        rec.at(0, hybrid(powerState = 2, mileage = 1000.0, fuel = 302.4, hev = 3812))
        rec.at(10_000, hybrid(powerState = 2, mileage = 1040.0, fuel = null, hev = null))
        rec.at(75_000, hybrid(powerState = 0, rpm = 0, mileage = 1040.0, fuel = 305.5, hev = 3851))
        rec.at(100_000, hybrid(powerState = 0, rpm = 0, mileage = 1040.0, fuel = 305.5, hev = 3851))
        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertNull(trip.captured.fuelTotalEnd)
        assertNull(trip.captured.mileageHevEnd)
        assertNull(trip.captured.fuelLiters)
        assertNull(trip.captured.hevKm)
    }

    @Test fun `a trip where no ON tick had counters closes without litres at the last ON tick`() = runTest {
        val rec = recorder(VehicleKind.HYBRID)
        rec.at(0, hybrid(powerState = 2, mileage = 1000.0))
        rec.at(10_000, hybrid(powerState = 2, mileage = 1040.0))
        rec.at(75_000, hybrid(powerState = 0, rpm = 0, mileage = 1040.0, fuel = 305.5, hev = 3851))
        rec.at(100_000, hybrid(powerState = 0, rpm = 0, mileage = 1040.0, fuel = 305.5, hev = 3851))
        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(10_000L, trip.captured.endTs)
        assertNull(trip.captured.fuelTotalEnd)
        assertNull(trip.captured.fuelLiters)
        assertNull(trip.captured.hevKm)
    }

    /** ON 305.2, then an ON tick with both counters null that is ON only by the odometer window. */
    private suspend fun parkWithNullCounters(rec: TripRecorder) {
        rec.at(0, hybrid(powerState = 2, mileage = 1000.0, fuel = 302.4, hev = 3812))
        rec.at(10_000, hybrid(powerState = 2, mileage = 1040.0, fuel = 305.2, hev = 3850))
        rec.at(30_000, hybrid(powerState = 0, rpm = 0, mileage = 1040.0, fuel = null, hev = null))
    }

    @Test fun `an ON tick with both counters null after parking keeps the readable tick, live`() = runTest {
        val rec = recorder(VehicleKind.HYBRID)
        parkWithNullCounters(rec)
        rec.at(75_000, hybrid(powerState = 0, rpm = 0, mileage = 1040.0, fuel = null, hev = null))
        rec.at(120_000, hybrid(powerState = 0, rpm = 0, mileage = 1040.0, fuel = null, hev = null))
        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(10_000L, trip.captured.endTs)
        assertEquals(305.2, trip.captured.fuelTotalEnd!!, 1e-9)
        assertEquals(2.8, trip.captured.fuelLiters!!, 1e-9)
        assertEquals(38.0, trip.captured.hevKm!!, 1e-9)
        coVerify(exactly = 0) { lastState.setLastOn(30_000L, any(), any(), any()) }
    }

    @Test fun `an ON tick with both counters null after parking keeps the readable tick at cold start`() = runTest {
        var written: Triple<Long, Double?, Double?>? = null
        coEvery { lastState.setLastOn(any(), any(), any(), any()) } answers {
            written = Triple(firstArg(), secondArg(), thirdArg())
        }
        parkWithNullCounters(recorder(VehicleKind.HYBRID))   // then the process dies
        val (onTs, onFuel, onHev) = written!!
        assertEquals(10_000L, onTs)

        coEvery { lastState.getCurrent() } returns LastStateEntity(
            id = 1, ts = 30_000L, mileage = 1040.0,
            openTripId = 0L, tripStartTs = 0L, tripStartMileage = 1000.0,
            tripStartFuelTotal = 302.4, tripStartMileageHev = 3812.0,
            onTs = onTs, onFuelTotal = onFuel, onMileageHev = onHev,
        )
        val rec = recorder(VehicleKind.HYBRID)
        clock = 30_000L + 10 * 60_000L
        rec.reconcileColdStart()

        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(10_000L, trip.captured.endTs)
        assertEquals(2.8, trip.captured.fuelLiters!!, 1e-9)
        assertEquals(38.0, trip.captured.hevKm!!, 1e-9)
    }

    @Test fun `ON ticks, a null tick and process death close the trip with the ON values at onTs`() = runTest {
        val live = recorder(VehicleKind.HYBRID)
        live.at(900_000L, hybrid(powerState = 2, mileage = 1000.0, fuel = 302.4, hev = 3812))
        live.at(950_000L, hybrid(powerState = 2, mileage = 1040.0, fuel = 305.2, hev = 3850))
        // The car is switched off: the CAN fields go null, the process dies before the 90 s confirmation.
        // 65 s after the last odometer growth, so the edge sees no sign of running on this tick.
        live.at(1_015_000L, hybrid(powerState = 0, rpm = 0, mileage = 1040.0, fuel = null, hev = null))
        coVerify(exactly = 1) { lastState.setOpenTripCounters(302.4, 3812.0) }
        coVerify(exactly = 1) { lastState.setLastOn(950_000L, 305.2, 3850.0, 1040.0) }
        coVerify(exactly = 0) { lastState.setLastOn(any(), null, any(), any()) }
        coVerify(exactly = 0) { tripDao.insert(any()) }

        coEvery { lastState.getCurrent() } returns LastStateEntity(
            id = 1, ts = 1_015_000L, mileage = 1040.0,
            openTripId = 900_000L, tripStartTs = 900_000L, tripStartMileage = 1000.0,
            tripStartFuelTotal = 302.4, tripStartMileageHev = 3812.0,
            onTs = 950_000L, onFuelTotal = 305.2, onMileageHev = 3850.0,
        )
        val rec = recorder(VehicleKind.HYBRID)
        clock = 1_015_000L + 10 * 60_000L
        rec.reconcileColdStart()

        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(950_000L, trip.captured.endTs)
        assertEquals(2.8, trip.captured.fuelLiters!!, 1e-9)
        assertEquals(38.0, trip.captured.hevKm!!, 1e-9)
        assertEquals(305.2, trip.captured.fuelTotalEnd!!, 1e-9)
        assertEquals(3850.0, trip.captured.mileageHevEnd!!, 1e-9)
    }

    @Test fun `an ON tick older than the trip start is no end edge at cold start`() = runTest {
        coEvery { lastState.getCurrent() } returns LastStateEntity(
            id = 1, ts = 1_000_000L, mileage = 1040.0,
            openTripId = 900_000L, tripStartTs = 900_000L, tripStartMileage = 1000.0,
            tripStartFuelTotal = 302.4, tripStartMileageHev = 3812.0,
            onTs = 800_000L, onFuelTotal = 301.0, onMileageHev = 3800.0,
        )
        val rec = recorder(VehicleKind.HYBRID)
        clock = 1_000_000L + 10 * 60_000L
        rec.reconcileColdStart()

        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(1_000_000L, trip.captured.endTs)
        assertNull(trip.captured.fuelTotalEnd)
        assertNull(trip.captured.fuelLiters)
    }

    @Test fun `an EV trip closed at cold start is the same TripEntity as before`() = runTest {
        for (kind in listOf(VehicleKind.EV, VehicleKind.UNKNOWN)) {
            val dao = mockk<TripDao>(relaxed = true)
            val ls = mockk<LastStateDao>(relaxed = true)
            coEvery { ls.getCurrent() } returns LastStateEntity(
                id = 1, ts = 1_000_000L, soc = 70, mileage = 110.0,
                openTripId = 900_000L, tripStartTs = 900_000L, tripStartSoc = 80, tripStartMileage = 100.0,
                onTs = 950_000L, onFuelTotal = 305.2, onMileageHev = 3850.0,
            )
            val rec = TripRecorder(
                dao, ls,
                mockk<EnergyDataReader> { every { isAvailable() } returns false },
                mockk<EnergyDataDeadDetector>(relaxed = true),
                batteryCapacityKwh = { 72.9 },
                now = { 1_000_000L + 10 * 60_000L },
                kind = { kind },
            )
            rec.reconcileColdStart()

            val kwh = 10 / 100.0 * 72.9
            val expected = TripEntity(
                startTs = 900_000L, endTs = 1_000_000L, distanceKm = 10.0,
                kwhConsumed = kwh, kwhPer100km = kwh / 10.0 * 100.0,
                socStart = 80, socEnd = 70,
                odometerStartKm = 100.0, odometerEndKm = 110.0,
                source = TripSource.NATIVE_POLLING,
            )
            val trip = slot<TripEntity>()
            coVerify(exactly = 1) { dao.insert(capture(trip)) }
            assertEquals("kind=$kind", expected, trip.captured)
        }
    }

    @Test fun `a growing odometer keeps a hybrid trip open with powerState and rpm silent`() = runTest {
        val rec = recorder(VehicleKind.HYBRID)
        rec.at(0, hybrid(powerState = 2, mileage = 1000.0))
        for (i in 1..10) rec.at(i * 50_000L, hybrid(powerState = 0, rpm = 0, mileage = 1000.0 + i))
        coVerify(exactly = 0) { tripDao.insert(any()) }
    }

    /** The pre-change expectation for the first TripRecorderActiveTest case, field by field. */
    @Test fun `an EV trip is the same TripEntity as before`() = runTest {
        for (kind in listOf(VehicleKind.EV, VehicleKind.UNKNOWN)) {
            val dao = mockk<TripDao>(relaxed = true)
            val ls = mockk<LastStateDao>(relaxed = true)
            coEvery { ls.openTrip(any(), any(), any(), any(), any(), any()) } returns 1
            val ticks = mutableListOf(1_000L, 2_000L)
            val rec = TripRecorder(
                dao, ls,
                mockk<EnergyDataReader> { every { isAvailable() } returns false },
                mockk<EnergyDataDeadDetector>(relaxed = true),
                batteryCapacityKwh = { 72.9 },
                now = { ticks.removeAt(0) },
                kind = { kind },
            )
            rec.consume(diParsData(powerState = 1, soc = 80, mileage = 100.0))
            rec.consume(diParsData(powerState = 2, soc = 80, mileage = 100.0))
            rec.consume(diParsData(powerState = 2, soc = 70, mileage = 110.0))
            rec.consume(diParsData(powerState = 1, soc = 70, mileage = 110.0))

            val kwh = 10 / 100.0 * 72.9
            val expected = TripEntity(
                startTs = 1_000L, endTs = 2_000L, distanceKm = 10.0,
                kwhConsumed = kwh, kwhPer100km = kwh / 10.0 * 100.0,
                socStart = 80, socEnd = 70,
                odometerStartKm = 100.0, odometerEndKm = 110.0,
                source = TripSource.NATIVE_POLLING,
            )
            val trip = slot<TripEntity>()
            coVerify(exactly = 1) { dao.insert(capture(trip)) }
            assertEquals("kind=$kind", expected, trip.captured)
            coVerify(exactly = 0) { ls.setOpenTripCounters(any(), any()) }
            coVerify(exactly = 0) { ls.setLastOn(any(), any(), any(), any()) }
        }
    }

    @Test fun `an EV snapshot that happens to carry hybrid fields still writes no fuel columns`() = runTest {
        val rec = recorder(VehicleKind.EV)
        rec.at(1_000, hybrid(powerState = 2, mileage = 100.0, fuel = 302.4, hev = 3812))
        rec.at(2_000, hybrid(powerState = 1, mileage = 110.0, fuel = 305.2, hev = 3820))
        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        coVerify(exactly = 0) { lastState.setLastOn(any(), any(), any(), any()) }
        assertNull(trip.captured.fuelLiters)
        assertNull(trip.captured.fuelTotalStart)
        assertNull(trip.captured.mileageHevEnd)
    }

    @Test fun `a cold-start resume keeps the start counters of the open trip`() = runTest {
        coEvery { lastState.getCurrent() } returns LastStateEntity(
            id = 1, ts = 1_000_000L, mileage = 1030.0,
            openTripId = 99L, tripStartTs = 900_000L, tripStartMileage = 1000.0,
            tripStartFuelTotal = 302.4, tripStartMileageHev = 3812.0,
        )
        val rec = recorder(VehicleKind.HYBRID)
        clock = 1_000_000L + 60_000L
        rec.reconcileColdStart()
        coVerify(exactly = 0) { tripDao.insert(any()) }

        rec.at(1_070_000L, hybrid(powerState = 2, mileage = 1040.0, fuel = 305.2, hev = 3850))
        rec.at(1_170_000L, hybrid(powerState = 0, rpm = 0, mileage = 1040.0, fuel = 305.2, hev = 3850))
        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(2.8, trip.captured.fuelLiters!!, 1e-9)
        assertEquals(38.0, trip.captured.hevKm!!, 1e-9)
    }

    @Test fun `a stale trip finalised at cold start has no end counter and so no litres`() = runTest {
        coEvery { lastState.getCurrent() } returns LastStateEntity(
            id = 1, ts = 1_000_000L, mileage = 1040.0,
            openTripId = 99L, tripStartTs = 900_000L, tripStartMileage = 1000.0,
            tripStartFuelTotal = 302.4, tripStartMileageHev = 3812.0,
        )
        val rec = recorder(VehicleKind.HYBRID)
        clock = 1_000_000L + 10 * 60_000L
        rec.reconcileColdStart()
        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertNull(trip.captured.fuelLiters)
        assertNull(trip.captured.hevKm)
        assertEquals(302.4, trip.captured.fuelTotalStart!!, 1e-9)
        assertNull(trip.captured.fuelTotalEnd)
    }

    // --- Resume restores the saved end edge (round 4) ---

    /** An open trip in last_state, the process died 60 s ago. */
    private fun resumedState(onTs: Long) = LastStateEntity(
        id = 1, ts = 1_000_000L, mileage = 40180.0,
        openTripId = 900_000L, tripStartTs = 900_000L, tripStartMileage = 40140.0,
        tripStartFuelTotal = 302.4, tripStartMileageHev = 3812.0,
        onTs = onTs, onFuelTotal = 305.2, onMileageHev = 3850.0, onMileage = 40180.0,
    )

    /** Ticks with no sign of running and no counters until the 90 s OFF confirmation. */
    private suspend fun silentUntilOff(rec: TripRecorder) {
        for (ms in listOf(1_065_000L, 1_100_000L, 1_150_000L)) {
            rec.at(ms, hybrid(powerState = 0, mileage = 40180.0))
        }
    }

    @Test fun `a resumed trip that sees only silent ticks ends at the saved ON tick with its litres`() = runTest {
        coEvery { lastState.getCurrent() } returns resumedState(onTs = 990_000L)
        val rec = recorder(VehicleKind.HYBRID)
        clock = 1_060_000L
        rec.reconcileColdStart()
        silentUntilOff(rec)

        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(990_000L, trip.captured.endTs)
        assertEquals(305.2, trip.captured.fuelTotalEnd!!, 1e-9)
        assertEquals(305.2 - 302.4, trip.captured.fuelLiters!!, 1e-9)
        assertEquals(38.0, trip.captured.hevKm!!, 1e-9)
    }

    @Test fun `a resumed trip whose saved ON tick predates its start gets no end counters`() = runTest {
        coEvery { lastState.getCurrent() } returns resumedState(onTs = 800_000L)
        val rec = recorder(VehicleKind.HYBRID)
        clock = 1_060_000L
        rec.reconcileColdStart()
        silentUntilOff(rec)

        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertNull(trip.captured.fuelTotalEnd)
        assertNull(trip.captured.fuelLiters)
    }

    @Test fun `a real ON tick after the resume moves the end edge forward`() = runTest {
        coEvery { lastState.getCurrent() } returns resumedState(onTs = 990_000L)
        val rec = recorder(VehicleKind.HYBRID)
        clock = 1_060_000L
        rec.reconcileColdStart()
        rec.at(1_070_000L, hybrid(powerState = 2, mileage = 40185.0, fuel = 306.0, hev = 3852))
        for (ms in listOf(1_100_000L, 1_140_000L, 1_170_000L)) {
            rec.at(ms, hybrid(powerState = 0, mileage = 40185.0))
        }

        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(1_070_000L, trip.captured.endTs)
        assertEquals(306.0, trip.captured.fuelTotalEnd!!, 1e-9)
        assertEquals(306.0 - 302.4, trip.captured.fuelLiters!!, 1e-9)
    }
}
