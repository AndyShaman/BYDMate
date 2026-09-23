package com.bydmate.app.data.autoservice

import com.bydmate.app.data.vehicle.HelperBootstrap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdbVerdictMonitorTest {

    private val restoreState = MutableStateFlow<AdbRestoreState>(AdbRestoreState.Disabled)
    // Defaults describe a fresh install without ADB: every input says "nothing works".
    private val bootstrap: HelperBootstrap = mockk(relaxed = true) {
        coEvery { isHealthy() } returns false
        every { daemonEverAlive() } returns false
        coEvery { ensureRunning() } returns false
    }
    private val adb: AdbOnDeviceClient = mockk(relaxed = true) {
        coEvery { isConnected() } returns false
        every { lastConnectFailure() } returns AdbConnectFailure.UNREACHABLE
    }
    private val restore: AdbRestoreManager = mockk(relaxed = true) {
        every { state } returns restoreState
    }
    private val system: AdbRestoreSystem = mockk(relaxed = true) {
        every { readAdbWifiEnabled() } returns 0
    }

    private fun TestScope.monitor(scope: CoroutineScope = backgroundScope) =
        AdbVerdictMonitor(bootstrap, adb, restore, system, scope)

    @Test
    fun `verdict stays null before the 15 s gate and publishes after`() = runTest {
        val m = monitor()
        m.onServiceStarted()

        advanceTimeBy(AdbVerdictMonitor.GATE_MS - 1)
        runCurrent()
        assertNull(m.verdict.value)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(AdbVerdict.NOT_ENABLED, m.verdict.value)
    }

    @Test
    fun `restore state change triggers a recompute`() = runTest {
        val m = monitor()
        m.onServiceStarted()
        advanceTimeBy(AdbVerdictMonitor.GATE_MS + 1)
        runCurrent()
        assertEquals(AdbVerdict.NOT_ENABLED, m.verdict.value)

        restoreState.value = AdbRestoreState.NeedsDialog
        runCurrent()
        assertEquals(AdbVerdict.NO_ACCESS, m.verdict.value)

        restoreState.value = AdbRestoreState.Connecting
        runCurrent()
        assertNull(m.verdict.value)
    }

    @Test
    fun `recheck flips checking, runs the attempt then ensureRunning once, publishes before the gate`() = runTest {
        val attemptGate = CompletableDeferred<Unit>()
        coEvery { restore.attemptIfNeeded(any()) } coAnswers { attemptGate.await() }
        val m = monitor()
        m.onServiceStarted()

        m.recheck("dashboard")
        runCurrent()
        assertTrue(m.checking.value)
        assertNull(m.verdict.value)

        attemptGate.complete(Unit)
        runCurrent()
        assertFalse(m.checking.value)
        assertEquals(AdbVerdict.NOT_ENABLED, m.verdict.value)
        coVerifyOrder {
            restore.attemptIfNeeded("dashboard")
            bootstrap.ensureRunning()
        }
        coVerify(exactly = 1) { bootstrap.ensureRunning() }
    }

    @Test
    fun `second recheck while one runs is ignored`() = runTest {
        val attemptGate = CompletableDeferred<Unit>()
        coEvery { restore.attemptIfNeeded(any()) } coAnswers { attemptGate.await() }
        val m = monitor()

        m.recheck("dashboard")
        runCurrent()
        m.recheck("settings")
        attemptGate.complete(Unit)
        runCurrent()

        coVerify(exactly = 1) { restore.attemptIfNeeded(any()) }
        coVerify(exactly = 0) { restore.attemptIfNeeded("settings") }
        coVerify(exactly = 1) { bootstrap.ensureRunning() }
    }

    @Test
    fun `a throwing isHealthy still yields a verdict from safe defaults`() = runTest {
        coEvery { bootstrap.isHealthy() } throws IllegalStateException("binder died")
        val m = monitor()
        m.onServiceStarted()
        advanceTimeBy(AdbVerdictMonitor.GATE_MS + 1)
        runCurrent()

        // healthy=false, connected=false: a wrong default of true would give OK / HELPER_DOWN.
        assertEquals(AdbVerdict.NOT_ENABLED, m.verdict.value)
    }

    @Test
    fun `recheck finishing after onServiceStopped publishes nothing`() = runTest {
        val attemptGate = CompletableDeferred<Unit>()
        coEvery { restore.attemptIfNeeded(any()) } coAnswers { attemptGate.await() }
        val m = monitor()
        m.onServiceStarted()

        m.recheck("dashboard")
        runCurrent()
        m.onServiceStopped()
        attemptGate.complete(Unit)
        runCurrent()

        assertNull(m.verdict.value)
        assertFalse(m.checking.value)
    }

    @Test
    fun `recheck resets checking when ensureRunning throws`() = runTest {
        coEvery { bootstrap.ensureRunning() } throws IllegalStateException("spawn exploded")
        val m = monitor()
        m.onServiceStarted()

        m.recheck("dashboard")
        runCurrent()

        assertFalse(m.checking.value)
        assertEquals(AdbVerdict.NOT_ENABLED, m.verdict.value)
    }

    @Test
    fun `a repeated onServiceStarted keeps one collector and restarts the gate`() = runTest {
        val m = monitor()
        m.onServiceStarted()
        advanceTimeBy(5_000)
        m.onServiceStarted()
        runCurrent()
        assertEquals(1, restoreState.subscriptionCount.value)

        // The first gate would have opened at 15 s; only the second one (at 20 s) may.
        advanceTimeBy(AdbVerdictMonitor.GATE_MS - 4_000)
        runCurrent()
        assertNull(m.verdict.value)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(AdbVerdict.NOT_ENABLED, m.verdict.value)
    }

    @Test
    fun `onServiceStopped resets to null and stops the restore collector`() = runTest {
        val m = monitor()
        m.onServiceStarted()
        advanceTimeBy(AdbVerdictMonitor.GATE_MS + 1)
        runCurrent()
        assertEquals(1, restoreState.subscriptionCount.value)
        assertEquals(AdbVerdict.NOT_ENABLED, m.verdict.value)

        m.onServiceStopped()
        runCurrent()
        assertNull(m.verdict.value)
        assertEquals(0, restoreState.subscriptionCount.value)

        restoreState.value = AdbRestoreState.NeedsDialog
        runCurrent()
        assertNull(m.verdict.value)
    }

    @Test
    fun `enableRestoreAndRecheck switches the toggle on before the attempt`() = runTest {
        val m = monitor()

        m.enableRestoreAndRecheck()
        runCurrent()

        coVerifyOrder {
            restore.setEnabled(true)
            restore.attemptIfNeeded("verdict_dialog")
        }
    }
}
