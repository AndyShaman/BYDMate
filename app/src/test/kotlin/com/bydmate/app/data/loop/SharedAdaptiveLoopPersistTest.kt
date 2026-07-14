package com.bydmate.app.data.loop

import app.cash.turbine.test
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.entity.LastStateEntity
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.diParsData
import io.mockk.coEvery
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

    /** MockK LastStateDao whose getCurrent()/upsert() are backed by [box], so
     *  getCurrent() always reflects the latest upsert (or an external
     *  mutation of [box]) -- unlike a plain relaxed mock. */
    private fun statefulDao(box: RowBox): Pair<LastStateDao, MutableList<LastStateEntity>> {
        val dao = mockk<LastStateDao>()
        val recorded = mutableListOf<LastStateEntity>()
        coEvery { dao.getCurrent() } answers { box.value }
        coEvery { dao.upsert(any()) } answers {
            box.value = firstArg()
            recorded.add(firstArg())
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
                box.value = box.value!!.copy(openTripId = 42L)
                advanceTimeBy(30_000)
                awaitItem()   // tick 2: soc changed -> write, must carry openTripId=42
            }
        } finally {
            job.cancel()
        }
        assertEquals(2, recorded.size)
        assertEquals(42L, recorded[1].openTripId)
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

    /** P3: an exception thrown by LastStateDao.upsert (e.g. SQLiteException) must NOT crash the
     *  polling loop. The loop emits to flow consumers BEFORE calling persistSnapshot, so the
     *  consumer already received the data; losing the LastState write is acceptable. */
    @Test fun `upsert exception does not crash the loop`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var fetchCount = 0
        val reader = mockk<ParsReader>()
        coEvery { reader.fetch() } answers {
            fetchCount++
            diParsData(soc = 80, mileage = 100.0, totalElecConsumption = 500.0, powerState = 0)
        }
        val dao = mockk<LastStateDao>()
        val box = RowBox()
        var firstUpsert = true
        coEvery { dao.getCurrent() } answers { box.value }
        coEvery { dao.upsert(any()) } answers {
            if (firstUpsert) {
                firstUpsert = false
                throw RuntimeException("SQLite: disk I/O error")
            }
            box.value = firstArg()
        }
        val energy = mockk<EnergyDataReader> { every { isAvailable() } returns true }
        val loop = SharedAdaptiveLoop(reader, dao, energy, dispatcher)

        val job = loop.start(TestScope(dispatcher))
        try {
            loop.flow.test {
                advanceTimeBy(1)
                awaitItem()           // tick 1: upsert throws; loop must survive
                advanceTimeBy(30_000) // IDLE cadence
                awaitItem()           // tick 2: fetch runs, upsert succeeds
            }
        } finally {
            job.cancel()
        }
        // Both fetch calls completed: loop survived the first upsert exception
        assert(fetchCount >= 2) { "fetchCount=$fetchCount: loop crashed on upsert exception" }
    }
}
