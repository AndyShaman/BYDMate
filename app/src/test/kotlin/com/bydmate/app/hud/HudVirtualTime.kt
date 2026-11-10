package com.bydmate.app.hud

import java.util.Collections
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertTrue

/** The controller scope of the HUD tests on virtual time: eager like Dispatchers.Unconfined, but a
 *  delay resumes only when the test moves the clock. The loops never end, so never advanceUntilIdle;
 *  [close] after each test stops them and fails on an error any of its coroutines threw. */
internal class HudVirtualTime {
    val scheduler = TestCoroutineScheduler()
    private val errors: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    val scope = CoroutineScope(
        SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, e -> errors += e },
    )

    fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    /** Moves the clock in small steps until [cond] holds, at most [timeoutMs] of virtual time. */
    fun awaitTrue(timeoutMs: Long = 5_000, message: () -> String? = { null }, cond: () -> Boolean) {
        var waited = 0L
        while (!cond() && waited < timeoutMs) {
            advance(STEP_MS)
            waited += STEP_MS
        }
        assertTrue(message(), cond())
    }

    fun close() {
        scope.cancel()
        assertTrue(errors.toString(), errors.isEmpty())
    }

    private companion object {
        const val STEP_MS = 50L
    }
}
