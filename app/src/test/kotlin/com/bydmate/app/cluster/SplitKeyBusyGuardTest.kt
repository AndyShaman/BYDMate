package com.bydmate.app.cluster

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decision 9: while a key-triggered start/exit runs, further presses are ignored. */
@OptIn(ExperimentalCoroutinesApi::class)
class SplitKeyBusyGuardTest {

    @Test fun `two presses during a slow start run exactly one start`() = runTest {
        val guard = SplitKeyBusyGuard()
        val gate = CompletableDeferred<Unit>()
        var starts = 0
        var exits = 0
        val first = launch { guard.runIfIdle { starts++; gate.await() } }
        advanceUntilIdle()
        val second = guard.runIfIdle { exits++ }
        assertFalse(second)
        gate.complete(Unit)
        first.join()
        assertEquals(1, starts)
        assertEquals(0, exits)
    }

    @Test fun `a press after the run finished runs again`() = runTest {
        val guard = SplitKeyBusyGuard()
        var runs = 0
        assertTrue(guard.runIfIdle { runs++ })
        assertTrue(guard.runIfIdle { runs++ })
        assertEquals(2, runs)
    }

    @Test fun `a failing run releases the guard`() = runTest {
        val guard = SplitKeyBusyGuard()
        try {
            guard.runIfIdle { error("boom") }
        } catch (_: IllegalStateException) {
        }
        assertTrue(guard.runIfIdle { })
    }
}
