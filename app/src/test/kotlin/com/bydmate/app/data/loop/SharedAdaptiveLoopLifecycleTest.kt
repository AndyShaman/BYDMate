package com.bydmate.app.data.loop

import app.cash.turbine.test
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.diParsData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedAdaptiveLoopLifecycleTest {

    @Test fun `emits ticks from parsReader on the schedule`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = mockk<ParsReader>()
        var n = 0
        coEvery { reader.fetch() } answers {
            n++
            // powerState=0 (OFF) -> IDLE cadence (30s)
            diParsData(soc = 80 - n, mileage = 100.0 + n, voltage12v = 12.5, powerState = 0)
        }
        val lastState = mockk<LastStateDao>(relaxed = true)
        val energy = mockk<EnergyDataReader> { every { isAvailable() } returns true }
        val loop = SharedAdaptiveLoop(reader, lastState, energy, dispatcher)

        val job = loop.start(TestScope(dispatcher))
        try {
            loop.flow.test {
                advanceTimeBy(1)             // first fetch runs immediately
                val t1 = awaitItem()
                assertEquals(79, t1.soc)
                advanceTimeBy(30_000)        // idle cadence
                val t2 = awaitItem()
                assertEquals(78, t2.soc)
            }
        } finally {
            job.cancel()
        }
    }

    @Test fun `start is idempotent`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = mockk<ParsReader>(relaxed = true)
        coEvery { reader.fetch() } returns null
        val lastState = mockk<LastStateDao>(relaxed = true)
        val energy = mockk<EnergyDataReader>(relaxed = true)
        val loop = SharedAdaptiveLoop(reader, lastState, energy, dispatcher)
        val scope = TestScope(dispatcher)
        val j1 = loop.start(scope)
        val j2 = loop.start(scope)
        assertEquals(j1, j2)
        j1.cancel()
    }
}
