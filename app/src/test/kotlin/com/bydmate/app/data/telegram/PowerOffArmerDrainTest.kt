package com.bydmate.app.data.telegram

import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A1 (#413): each refresh tick asks the reporter to drain what was held for the daemon's reports. */
@OptIn(ExperimentalCoroutinesApi::class)
class PowerOffArmerDrainTest {

    private val reporter = mockk<TelegramReporter>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val state = PowerOffArmState(helper)

    @Test fun `A1 every refresh tick drains the outbox after arming`() = runTest {
        val armer = PowerOffArmer(reporter, helper, settings, state)
        every { settings.observeString(any()) } returns MutableStateFlow(null)
        coEvery { reporter.powerOffReport(any()) } returns PowerOffReport("p1", "tok", 42L, "t1", "late")
        coEvery { helper.offReportArm(any(), any(), any(), any(), any()) } returns true
        armer.bootstrapAttempted()
        val job = launch { armer.run() }
        runCurrent()
        coVerify(exactly = 1) { reporter.drainOutbox(any()) }
        advanceTimeBy(PowerOffArmer.REFRESH_MS + 1)
        coVerify(exactly = 2) { reporter.drainOutbox(any()) }
        job.cancel()
    }
}
