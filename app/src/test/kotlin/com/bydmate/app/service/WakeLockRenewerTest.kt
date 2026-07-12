package com.bydmate.app.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakeLockRenewerTest {

    @Test
    fun `acquires immediately and renews on every interval until stopped`() = runTest {
        var acquires = 0
        val renewer = WakeLockRenewer(backgroundScope, { acquires++ }, renewIntervalMs = 1_000)
        renewer.start()
        runCurrent()
        assertEquals(1, acquires)
        advanceTimeBy(3_500); runCurrent()
        assertEquals(4, acquires)
        renewer.stop()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(4, acquires)
    }

    @Test
    fun `start is idempotent while running`() = runTest {
        var acquires = 0
        val renewer = WakeLockRenewer(backgroundScope, { acquires++ }, renewIntervalMs = 1_000)
        renewer.start()
        renewer.start()
        runCurrent()
        assertEquals(1, acquires)
    }

    @Test
    fun `renew interval leaves margin against the lock timeout`() {
        assertEquals(true, WakeLockRenewer.RENEW_INTERVAL_MS < WakeLockRenewer.TIMEOUT_MS)
    }
}
