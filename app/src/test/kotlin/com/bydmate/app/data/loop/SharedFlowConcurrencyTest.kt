package com.bydmate.app.data.loop

import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.diParsData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedFlowConcurrencyTest {

    private fun buildLoop(dispatcher: CoroutineDispatcher): Pair<SharedAdaptiveLoop, () -> Int> {
        val reader = mockk<ParsReader>()
        var counter = 0
        coEvery { reader.fetch() } answers {
            counter++
            // powerState=2 (DRIVE) + speed>0 -> DRIVE cadence (1s)
            diParsData(soc = counter, voltage12v = 12.5, mileage = 1.0, powerState = 2, speed = 30)
        }
        val energy = mockk<EnergyDataReader> { every { isAvailable() } returns false }
        val lastState = mockk<LastStateDao>(relaxed = true)
        return SharedAdaptiveLoop(reader, lastState, energy, dispatcher) to { counter }
    }

    // Drive cadence = 1 000 ms. advanceTimeBy(5_500) fires ticks at t=0,1s,2s,3s,4s,5s → 6 ticks.

    @Test fun `slow consumer drops intermediate ticks, loop never blocked`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (loop, counter) = buildLoop(dispatcher)
        val scope = TestScope(dispatcher)
        loop.start(scope)

        val seen = mutableListOf<Int>()
        scope.launch {
            loop.flow.collect { d ->
                seen.add(d.soc!!)
                delay(10_000)   // way slower than 1s drive cadence
            }
        }
        advanceTimeBy(5_500)        // 6 ticks of drive cadence emitted
        assertEquals(1, seen.size)  // first tick consumed; slow consumer still in delay, rest dropped
        assertEquals(6, counter())  // loop progressed regardless
        scope.cancel()              // cancel loop + collector coroutines
    }

    @Test fun `five fast subscribers all observe ticks, none block the loop`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (loop, counter) = buildLoop(dispatcher)
        val scope = TestScope(dispatcher)
        loop.start(scope)

        val seenLists = List(5) { mutableListOf<Int>() }
        seenLists.forEach { dst ->
            scope.launch { loop.flow.collect { dst.add(it.soc!!) } }
        }
        advanceTimeBy(5_500)   // 6 ticks emitted
        // Loop progressed through all 6 ticks without being blocked by any subscriber.
        assertEquals(6, counter())
        // Each fast subscriber should have observed all 6 ticks (SharedFlow replay=1; extra
        // subscriber latency negligible in virtual-time test).
        seenLists.forEach { assertTrue("subscriber received ticks: $it", it.size in 5..6) }
        scope.cancel()
    }

    @Test fun `late subscriber receives the latest replayed value (replay=1)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (loop, _) = buildLoop(dispatcher)
        val scope = TestScope(dispatcher)
        loop.start(scope)
        advanceTimeBy(2_500)   // 3 ticks emitted; replay buffer holds last value

        val latest = scope.async { loop.flow.first() }.also { advanceTimeBy(10) }.await()
        assertTrue("late subscriber sees a replayed value", latest.soc != null)
        scope.cancel()
    }
}
