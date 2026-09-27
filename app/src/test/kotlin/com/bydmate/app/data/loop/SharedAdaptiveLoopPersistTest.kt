package com.bydmate.app.data.loop

import app.cash.turbine.test
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.entity.LastStateEntity
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.diParsData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B1: persistSnapshot must skip the Room upsert when nothing tracked changed
 * and the heartbeat is still fresh. See .superpowers/sdd/task-5-brief.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedAdaptiveLoopPersistTest {

    /** Mutable holder standing in for the single last_state row so a test can
     *  mutate it between ticks (mirrors TripRecorder's external UPDATE calls,
     *  which bypass upsert()). */
    private class RowBox(var value: LastStateEntity? = null)

    /** MockK LastStateDao whose getCurrent()/writeSnapshot() are backed by [box], so
     *  getCurrent() always reflects the latest write (or an external mutation of
     *  [box]) -- unlike a plain relaxed mock. writeSnapshot() mirrors the real DAO
     *  method: it only ever changes the loop-owned columns, any open-trip columns
     *  already on the row (e.g. set by a concurrent TripRecorder UPDATE) pass through
     *  untouched -- that is the property this fix relies on. */
    private fun statefulDao(box: RowBox): Pair<LastStateDao, MutableList<LastStateEntity>> {
        val dao = mockk<LastStateDao>()
        val recorded = mutableListOf<LastStateEntity>()
        coEvery { dao.getCurrent() } answers { box.value }
        coEvery {
            dao.writeSnapshot(any(), any(), any(), any(), any(), any())
        } answers {
            val next = (box.value ?: LastStateEntity(id = 1, ts = firstArg())).copy(
                ts = firstArg<Long>(),
                soc = secondArg<Int?>(),
                mileage = thirdArg<Double?>(),
                totalElec = arg<Double?>(3),
                ignition = arg<Int?>(4),
                energydataAvailable = arg<Int>(5),
            )
            box.value = next
            recorded.add(next)
        }
        return dao to recorded
    }

    @Test fun `identical ticks inside heartbeat window write once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = mockk<ParsReader>()
        coEvery { reader.fetch() } returns
            diParsData(soc = 80, mileage = 100.0, totalElecConsumption = 500.0, powerState = 0)
        val (dao, recorded) = statefulDao(RowBox())
        val energy = mockk<EnergyDataReader> { every { isAvailable() } returns true }
        val loop = SharedAdaptiveLoop(reader, dao, energy, dispatcher)

        val job = loop.start(TestScope(dispatcher))
        try {
            loop.flow.test {
                advanceTimeBy(1)
                awaitItem()             // tick 1: prev == null -> write
                advanceTimeBy(30_000)   // IDLE cadence; heartbeat still fresh
                awaitItem()             // tick 2: identical data -> no write
            }
        } finally {
            job.cancel()
        }
        assertEquals(1, recorded.size)
    }

    @Test fun `changed soc writes again and re-reads the dao's current row`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = mockk<ParsReader>()
        var tick = 0
        coEvery { reader.fetch() } answers {
            tick++
            diParsData(
                soc = if (tick == 1) 80 else 79,
                mileage = 100.0, totalElecConsumption = 500.0, powerState = 0,
            )
        }
        val box = RowBox()
        val (dao, recorded) = statefulDao(box)
        val energy = mockk<EnergyDataReader> { every { isAvailable() } returns true }
        val loop = SharedAdaptiveLoop(reader, dao, energy, dispatcher)

        val job = loop.start(TestScope(dispatcher))
        try {
            loop.flow.test {
                advanceTimeBy(1)
                awaitItem()   // tick 1: prev == null -> write, openTripId still null
                // Simulate TripRecorder opening a trip between loop ticks (raw
                // UPDATE, not upsert()) -- persistSnapshot must re-read this.
                box.value = box.value!!.copy(openTripId = 42L, tripStartExteriorTemp = 10)
                advanceTimeBy(30_000)
                awaitItem()   // tick 2: soc changed -> write, must carry openTripId=42
            }
        } finally {
            job.cancel()
        }
        assertEquals(2, recorded.size)
        assertEquals(42L, recorded[1].openTripId)
        assertEquals(10, recorded[1].tripStartExteriorTemp)  // issue #250
    }

    @Test fun `stale heartbeat forces a write even with identical data`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = mockk<ParsReader>()
        coEvery { reader.fetch() } returns
            diParsData(soc = 80, mileage = 100.0, totalElecConsumption = 500.0, powerState = 0)
        val staleTs = System.currentTimeMillis() - SharedAdaptiveLoop.HEARTBEAT_MS - 1_000L
        val box = RowBox(
            LastStateEntity(
                id = 1, ts = staleTs, soc = 80, mileage = 100.0, totalElec = 500.0, ignition = 0,
            )
        )
        val (dao, recorded) = statefulDao(box)
        val energy = mockk<EnergyDataReader> { every { isAvailable() } returns true }
        val loop = SharedAdaptiveLoop(reader, dao, energy, dispatcher)

        val job = loop.start(TestScope(dispatcher))
        try {
            loop.flow.test {
                advanceTimeBy(1)
                awaitItem()   // identical data, but prev.ts is stale -> heartbeat write
            }
        } finally {
            job.cancel()
        }
        assertEquals(1, recorded.size)
    }

    @Test fun `energyDataReader isAvailable is probed only on writing ticks`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = mockk<ParsReader>()
        coEvery { reader.fetch() } returns
            diParsData(soc = 80, mileage = 100.0, totalElecConsumption = 500.0, powerState = 0)
        val (dao, _) = statefulDao(RowBox())
        val energy = mockk<EnergyDataReader> { every { isAvailable() } returns true }
        val loop = SharedAdaptiveLoop(reader, dao, energy, dispatcher)

        val job = loop.start(TestScope(dispatcher))
        try {
            loop.flow.test {
                advanceTimeBy(1)
                awaitItem()             // tick 1: prev == null -> write -> probes isAvailable
                advanceTimeBy(30_000)   // heartbeat still fresh
                awaitItem()             // tick 2: identical data -> no write -> no probe
            }
        } finally {
            job.cancel()
        }
        verify(exactly = 1) { energy.isAvailable() }
    }

    /** P3: an exception thrown by LastStateDao.writeSnapshot (e.g. SQLiteException) must NOT
     *  crash the polling loop. The loop emits to flow consumers BEFORE calling persistSnapshot,
     *  so the consumer already received the data; losing the LastState write is acceptable. */
    @Test fun `writeSnapshot exception does not crash the loop`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var fetchCount = 0
        val reader = mockk<ParsReader>()
        coEvery { reader.fetch() } answers {
            fetchCount++
            diParsData(soc = 80, mileage = 100.0, totalElecConsumption = 500.0, powerState = 0)
        }
        val dao = mockk<LastStateDao>()
        val box = RowBox()
        var firstWrite = true
        coEvery { dao.getCurrent() } answers { box.value }
        coEvery {
            dao.writeSnapshot(any(), any(), any(), any(), any(), any())
        } answers {
            if (firstWrite) {
                firstWrite = false
                throw RuntimeException("SQLite: disk I/O error")
            }
            box.value = (box.value ?: LastStateEntity(id = 1, ts = firstArg())).copy(
                ts = firstArg<Long>(), soc = secondArg<Int?>(), mileage = thirdArg<Double?>(),
                totalElec = arg<Double?>(3), ignition = arg<Int?>(4), energydataAvailable = arg<Int>(5),
            )
        }
        val energy = mockk<EnergyDataReader> { every { isAvailable() } returns true }
        val loop = SharedAdaptiveLoop(reader, dao, energy, dispatcher)

        val job = loop.start(TestScope(dispatcher))
        try {
            loop.flow.test {
                advanceTimeBy(1)
                awaitItem()           // tick 1: writeSnapshot throws; loop must survive
                advanceTimeBy(30_000) // IDLE cadence
                awaitItem()           // tick 2: fetch runs, writeSnapshot succeeds
            }
        } finally {
            job.cancel()
        }
        // Both fetch calls completed: loop survived the first writeSnapshot exception
        assert(fetchCount >= 2) { "fetchCount=$fetchCount: loop crashed on writeSnapshot exception" }
    }

    /**
     * Reproduces the trip-open/snapshot race (regression): TripRecorder's openTrip() UPDATE
     * can land in the instant between persistSnapshot's getCurrent() read and its write. A
     * whole-row REPLACE built from that stale read (the old upsert() path) silently undid the
     * just-opened trip; writeSnapshot() must leave the open-trip columns exactly as the
     * concurrent UPDATE left them, no matter when it lands relative to the read.
     */
    @Test fun `a trip opened between the read and the write is not clobbered`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = mockk<ParsReader>()
        coEvery { reader.fetch() } returns
            diParsData(soc = 79, mileage = 101.0, totalElecConsumption = 500.5, powerState = 2)
        val box = RowBox(
            LastStateEntity(id = 1, ts = 1_000L, soc = 80, mileage = 100.0, totalElec = 500.0, ignition = 0)
        )
        var raceTriggered = false
        val dao = mockk<LastStateDao>()
        coEvery { dao.getCurrent() } answers {
            val readRow = box.value
            // The race: TripRecorder's openTrip() lands right after this read returns,
            // before persistSnapshot's own write (below) runs.
            if (!raceTriggered) {
                raceTriggered = true
                box.value = box.value!!.copy(
                    openTripId = 42L, tripStartTs = 42L, tripStartSoc = 80,
                    tripStartMileage = 100.0, tripStartExteriorTemp = 10,
                )
            }
            readRow
        }
        // Old whole-row REPLACE semantics -- kept stubbed so this test fails the same way
        // against the pre-fix persistSnapshot(), which called upsert() with prev's (stale,
        // pre-race) trip fields copied in.
        coEvery { dao.upsert(any()) } answers { box.value = firstArg() }
        coEvery {
            dao.writeSnapshot(any(), any(), any(), any(), any(), any())
        } answers {
            val current = box.value!!
            box.value = current.copy(
                ts = firstArg<Long>(), soc = secondArg<Int?>(), mileage = thirdArg<Double?>(),
                totalElec = arg<Double?>(3), ignition = arg<Int?>(4), energydataAvailable = arg<Int>(5),
            )
        }
        val energy = mockk<EnergyDataReader> { every { isAvailable() } returns true }
        val loop = SharedAdaptiveLoop(reader, dao, energy, dispatcher)

        val job = loop.start(TestScope(dispatcher))
        try {
            loop.flow.test {
                advanceTimeBy(1)
                awaitItem()
            }
        } finally {
            job.cancel()
        }

        val row = box.value!!
        assertEquals(42L, row.openTripId)
        assertEquals(42L, row.tripStartTs)
        assertEquals(10, row.tripStartExteriorTemp)
        assertEquals(79, row.soc) // the snapshot itself was still written
        coVerify(exactly = 0) { dao.upsert(any()) } // the loop must not use the whole-row path
    }
}
