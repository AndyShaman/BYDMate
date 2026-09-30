package com.bydmate.app.ui.settings

import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The daemon dump sections share one budget; a section that hangs must not erase the others. */
class HelperDiagnosticsTest {
    private val seats = SeatsDiagnostics.batchItems().map { 0 to 1 }
    private val steering = SteeringHeatDiagnostics.batchItems().map { 0 to 1 }
    private val windows = WindowDiagnostics.batchItems().map { 0 to 2 }
    private val helper = mockk<HelperClient> {
        coEvery { isAlive() } returns true
        coEvery { readBatch(SeatsDiagnostics.batchItems()) } returns seats
    }

    @Test fun `a hanging steering heat batch still yields liveness and seats`() = runTest {
        coEvery { helper.readBatch(SteeringHeatDiagnostics.batchItems()) } coAnswers { awaitCancellation() }
        val diag = SettingsViewModel.collectHelperDiagnostics(
            backgroundScope, helper, 3_000L, StandardTestDispatcher(testScheduler),
        )
        assertEquals(true, diag.alive)
        assertEquals(seats, diag.seats)
        assertNull(diag.steeringHeat)
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test fun `all four sections come back when the daemon answers`() = runTest {
        coEvery { helper.readBatch(SteeringHeatDiagnostics.batchItems()) } returns steering
        coEvery { helper.readBatch(WindowDiagnostics.batchItems()) } returns windows
        val diag = SettingsViewModel.collectHelperDiagnostics(
            backgroundScope, helper, 3_000L, StandardTestDispatcher(testScheduler),
        )
        assertEquals(SettingsViewModel.HelperDiagnostics(true, seats, steering, windows), diag)
    }

    @Test fun `a hanging window batch still yields the other sections`() = runTest {
        coEvery { helper.readBatch(SteeringHeatDiagnostics.batchItems()) } returns steering
        coEvery { helper.readBatch(WindowDiagnostics.batchItems()) } coAnswers { awaitCancellation() }
        val diag = SettingsViewModel.collectHelperDiagnostics(
            backgroundScope, helper, 3_000L, StandardTestDispatcher(testScheduler),
        )
        assertEquals(SettingsViewModel.HelperDiagnostics(true, seats, steering, null), diag)
    }

    @Test fun `window fids are printed with their device and fid`() {
        assertEquals(
            listOf(
                "percentage_control_config[dev=1001 fid=1222639652]=1",
                "control_plan[dev=1001 fid=1222639650]=(status=-10011)",
                "left_front_current_state[dev=1001 fid=1222639653]=0",
            ),
            WindowDiagnostics.format(listOf(0 to 1, -10011 to 0, 0 to 0)),
        )
        assertEquals(listOf("(unavailable)"), WindowDiagnostics.format(null))
    }
}
