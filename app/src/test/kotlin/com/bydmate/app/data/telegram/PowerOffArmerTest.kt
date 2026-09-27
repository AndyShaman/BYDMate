package com.bydmate.app.data.telegram

import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The app half of the power-off report: it only keeps the daemon armed or disarmed. Reporter,
 * daemon client and settings are fakes; the loop runs on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PowerOffArmerTest {

    private val reporter = mockk<TelegramReporter>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val state = PowerOffArmState(helper)
    private lateinit var armer: PowerOffArmer

    private val base = 1_790_000_000_000L
    private val enabled = MutableStateFlow<String?>("true")

    private val report1 = PowerOffReport("p1", "tok", 42L, "t1", "late")
    private val report2 = PowerOffReport("p2", "tok", 42L, "t2", "late")

    @Before fun setUp() {
        armer = PowerOffArmer(reporter, helper, settings, state)
        every { settings.observeString(any()) } returns MutableStateFlow(null)
        every { settings.observeString(SettingsRepository.KEY_TG_REPORT_OFF_ENABLED) } returns enabled
        coEvery { reporter.powerOffReport(any()) } returns null
        coEvery { helper.offReportArm(any(), any(), any(), any(), any()) } returns true
        coEvery { helper.offReportDisarm() } returns true
        coEvery { helper.isAlive() } returns true
    }

    private fun TestScope.start(): Job {
        armer.clock = { base + testScheduler.currentTime }
        armer.bootstrapAttempted()
        val job = launch { armer.run() }
        runCurrent()
        return job
    }

    @Test fun `nothing happens before the helper bootstrap`() = runTest {
        coEvery { reporter.powerOffReport(any()) } returns report1
        val job = launch { armer.run() }
        advanceTimeBy(PowerOffArmer.REFRESH_MS * 3)
        coVerify(exactly = 0) { helper.offReportArm(any(), any(), any(), any(), any()) }
        armer.bootstrapAttempted()
        runCurrent()
        coVerify(exactly = 1) { helper.offReportArm("p1", "tok", 42L, "t1", "late") }
        job.cancel()
    }

    @Test fun `the loop arms on a change, and again once a minute for a restarted daemon`() = runTest {
        var current: PowerOffReport = report1
        coEvery { reporter.powerOffReport(any()) } answers { current }
        val job = start()
        coVerify(exactly = 1) { helper.offReportArm("p1", "tok", 42L, "t1", "late") }
        assertEquals(PowerOffArmState.DAEMON_OK, state.daemon)

        advanceTimeBy(PowerOffArmer.REFRESH_MS * 2 + 1)
        coVerify(exactly = 1) { helper.offReportArm("p1", any(), any(), any(), any()) }

        current = report2
        advanceTimeBy(PowerOffArmer.REFRESH_MS)
        coVerify(exactly = 1) { helper.offReportArm("p2", "tok", 42L, "t2", "late") }

        advanceTimeBy(PowerOffArmer.REARM_MS)
        coVerify(exactly = 2) { helper.offReportArm("p2", any(), any(), any(), any()) }
        job.cancel()
    }

    @Test fun `an arm the daemon did not take is tried again on the next tick`() = runTest {
        coEvery { reporter.powerOffReport(any()) } returns report1
        coEvery { helper.offReportArm(any(), any(), any(), any(), any()) } returns false
        coEvery { helper.isAlive() } returns false
        val job = start()
        assertEquals(PowerOffArmState.DAEMON_UNKNOWN, state.daemon)
        assertEquals(0L, state.armedAtMs)

        coEvery { helper.offReportArm(any(), any(), any(), any(), any()) } returns true
        advanceTimeBy(PowerOffArmer.REFRESH_MS + 1)
        coVerify(exactly = 2) { helper.offReportArm("p1", any(), any(), any(), any()) }
        assertEquals(PowerOffArmState.DAEMON_OK, state.daemon)
        job.cancel()
    }

    @Test fun `an old daemon without the verb is reported outdated`() = runTest {
        coEvery { reporter.powerOffReport(any()) } returns report1
        coEvery { helper.offReportArm(any(), any(), any(), any(), any()) } returns false
        val job = start()
        assertEquals(PowerOffArmState.DAEMON_OUTDATED, state.daemon)
        job.cancel()
    }

    @Test fun `switching the report off disarms at once, retried until the daemon confirms`() = runTest {
        var current: PowerOffReport? = report1
        coEvery { reporter.powerOffReport(any()) } answers { current }
        coEvery { helper.offReportDisarm() } returns false
        val job = start()
        val armedAt = state.armedAtMs
        assertEquals(base, armedAt)

        current = null
        enabled.value = "false"
        runCurrent()
        advanceTimeBy(PowerOffArmer.REFRESH_MS + 1)
        coVerify(exactly = 2) { helper.offReportDisarm() }
        assertEquals("the local state stays until the daemon confirms", armedAt, state.armedAtMs)

        coEvery { helper.offReportDisarm() } returns true
        advanceTimeBy(PowerOffArmer.REFRESH_MS)
        coVerify(exactly = 3) { helper.offReportDisarm() }
        assertEquals(0L, state.armedAtMs)

        advanceTimeBy(PowerOffArmer.REFRESH_MS * 3)
        coVerify(exactly = 3) { helper.offReportDisarm() }
        job.cancel()
    }

    @Test fun `with the report off from the start the first tick disarms once`() = runTest {
        val job = start()
        advanceTimeBy(PowerOffArmer.REFRESH_MS * 3)
        coVerify(exactly = 1) { helper.offReportDisarm() }
        job.cancel()
    }

    @Test fun `the power-off path never puts anything in the outbox`() = runTest {
        var current: PowerOffReport? = report1
        coEvery { reporter.powerOffReport(any()) } answers { current }
        val job = start()
        current = report2
        advanceTimeBy(PowerOffArmer.REARM_MS * 2)
        current = null
        advanceTimeBy(PowerOffArmer.REFRESH_MS * 2)
        coVerify(exactly = 0) { reporter.enqueue(any()) }
        coVerify(exactly = 0) { reporter.drainOutbox(any()) }
        job.cancel()
    }
}
