package com.bydmate.app.data.trips

import com.bydmate.app.data.local.dao.CounterSnapshotDao
import com.bydmate.app.data.local.entity.LastStateEntity
import com.bydmate.app.data.local.entity.CounterSnapshotEntity
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.diParsData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CounterSnapshotWriterTest {

    private val dao = mockk<CounterSnapshotDao>(relaxed = true)
    private val writer = CounterSnapshotWriter(dao)

    private fun tick(powerState: Int?, mileage: Double, fuel: Double, rpm: Int? = 0): DiParsData =
        diParsData(powerState = powerState, mileage = mileage)
            .copy(fuelTotalL = fuel, mileageHevKm = 3812, mileageEvKm = 9240, engineRpm = rpm)

    private fun written(): List<CounterSnapshotEntity> {
        val rows = mutableListOf<CounterSnapshotEntity>()
        coVerify(atLeast = 0) { dao.insert(capture(rows)) }
        return rows
    }

    @Test fun `an EV or UNKNOWN car writes and prunes nothing`() = runTest {
        for (kind in listOf(VehicleKind.EV, VehicleKind.UNKNOWN)) {
            writer.onTick(tick(2, 1000.0, 302.4, rpm = 1500), kind, 0L)
        }
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.deleteOlderThan(any()) }
    }

    @Test fun `on edge, periodic rows, and an off row stamped at the last running tick`() = runTest {
        val h = VehicleKind.HYBRID
        writer.onTick(tick(2, 1000.0, 302.4), h, 1_000_000L)                       // on
        writer.onTick(tick(2, 1005.0, 302.8), h, 1_000_000L + 5 * 60_000L)          // nothing yet
        writer.onTick(tick(2, 1010.0, 303.1), h, 1_000_000L + 10 * 60_000L)         // periodic
        writer.onTick(tick(0, 1012.0, 303.3), h, 1_000_000L + 11 * 60_000L)         // still moving
        val lastRun = 1_000_000L + 11 * 60_000L
        writer.onTick(tick(0, 1012.0, 303.3), h, lastRun + 60_000L)                 // silent, still on
        writer.onTick(tick(0, 1012.0, 303.3), h, lastRun + 120_000L)                // off

        val rows = written()
        assertEquals(
            listOf(
                CounterSnapshotEntity.REASON_IGNITION_ON,
                CounterSnapshotEntity.REASON_PERIODIC,
                CounterSnapshotEntity.REASON_IGNITION_OFF,
            ),
            rows.map { it.reason },
        )
        assertEquals(1_000_000L, rows[0].ts)
        assertEquals(302.4, rows[0].fuelTotal!!, 1e-9)
        assertEquals(3812.0, rows[0].mileageHev!!, 1e-9)
        assertEquals(9240.0, rows[0].mileageEv!!, 1e-9)
        assertEquals(1000.0, rows[0].odometer!!, 1e-9)
        assertEquals(lastRun, rows[2].ts)
        assertEquals(303.3, rows[2].fuelTotal!!, 1e-9)
        coVerify(exactly = 1) { dao.deleteOlderThan(1_000_000L - CounterSnapshotWriter.RETENTION_MS) }
    }

    // The process died 30 s after the last ON tick; the row lands at that tick.
    private val lastState = LastStateEntity(
        id = 1, ts = 5_030_000L, mileage = 1040.0,
        onTs = 5_000_000L, onFuelTotal = 305.2, onMileageHev = 3850.0,
    )

    @Test fun `a cold start writes one row from last_state, and none twice`() = runTest {
        val rows = mutableListOf<CounterSnapshotEntity>()
        coEvery { dao.nearest(any(), any()) } answers {
            val ts = firstArg<Long>()
            val window = secondArg<Long>()
            rows.lastOrNull { kotlin.math.abs(it.ts - ts) <= window }
        }
        coEvery { dao.insert(any()) } answers { rows.add(firstArg()); 1L }

        writer.onColdStart(lastState, VehicleKind.HYBRID)
        writer.onColdStart(lastState, VehicleKind.HYBRID)

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(CounterSnapshotEntity.REASON_COLD_START, row.reason)
        assertEquals(5_000_000L, row.ts)
        assertEquals(305.2, row.fuelTotal!!, 1e-9)
        assertEquals(3850.0, row.mileageHev!!, 1e-9)
        assertEquals(1040.0, row.odometer!!, 1e-9)
        coVerify { dao.nearest(5_000_000L, 90_000L) }
    }

    @Test fun `the service and the import both run the cold start, one row results`() = runTest {
        val rows = mutableListOf<CounterSnapshotEntity>()
        coEvery { dao.nearest(any(), any()) } answers {
            rows.lastOrNull { kotlin.math.abs(it.ts - firstArg<Long>()) <= secondArg<Long>() }
        }
        coEvery { dao.insert(any()) } answers { rows.add(firstArg()); 1L }

        writer.onColdStart(lastState, VehicleKind.HYBRID)                          // TrackingService.startPolling
        CounterSnapshotWriter(dao).onColdStart(lastState.copy(ts = 5_090_000L), VehicleKind.HYBRID)  // runSync

        assertEquals(1, rows.size)
        assertEquals(5_000_000L, rows.single().ts)
    }

    @Test fun `a cold start next to an existing snapshot writes nothing`() = runTest {
        coEvery { dao.nearest(5_000_000L, 90_000L) } returns CounterSnapshotEntity(
            ts = 5_000_000L - 89_000L, fuelTotal = 305.2, mileageHev = 3850.0, mileageEv = null,
            odometer = 1040.0, reason = CounterSnapshotEntity.REASON_IGNITION_OFF,
        )
        writer.onColdStart(lastState, VehicleKind.HYBRID)
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test fun `a cold start with the engine km but no fuel counter writes the engine km alone`() = runTest {
        coEvery { dao.nearest(any(), any()) } returns null
        writer.onColdStart(lastState.copy(onFuelTotal = null), VehicleKind.HYBRID)
        val row = written().single()
        assertEquals(CounterSnapshotEntity.REASON_COLD_START, row.reason)
        assertEquals(5_000_000L, row.ts)
        assertNull(row.fuelTotal)
        assertEquals(3850.0, row.mileageHev!!, 1e-9)
    }

    @Test fun `a cold start without an ON tick stamps the row at the last snapshot`() = runTest {
        coEvery { dao.nearest(any(), any()) } returns null
        writer.onColdStart(lastState.copy(onTs = null), VehicleKind.HYBRID)
        assertEquals(5_030_000L, written().single().ts)
    }

    @Test fun `a cold start without any counter, or on an EV, writes nothing`() = runTest {
        coEvery { dao.nearest(any(), any()) } returns null
        writer.onColdStart(lastState.copy(onFuelTotal = null, onMileageHev = null), VehicleKind.HYBRID)
        writer.onColdStart(null, VehicleKind.HYBRID)
        writer.onColdStart(lastState, VehicleKind.EV)
        writer.onColdStart(lastState, VehicleKind.UNKNOWN)
        coVerify(exactly = 0) { dao.insert(any()) }
    }
}
