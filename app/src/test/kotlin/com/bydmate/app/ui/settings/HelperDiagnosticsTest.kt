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

    @Test fun `all three sections come back when the daemon answers`() = runTest {
        coEvery { helper.readBatch(SteeringHeatDiagnostics.batchItems()) } returns steering
        val diag = SettingsViewModel.collectHelperDiagnostics(
            backgroundScope, helper, 3_000L, StandardTestDispatcher(testScheduler),
        )
        assertEquals(SettingsViewModel.HelperDiagnostics(true, seats, steering), diag)
    }
}
