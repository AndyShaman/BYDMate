package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: dispatch()'s catch(e: Exception) used to swallow CancellationException and
 * return DispatchResult(false). After the fix, CancellationException is re-thrown so the
 * voice routing job (which calls dispatch()) can be cancelled by the orb hard-stop without
 * the failure branch of VoiceController.execute() announcing a spurious error.
 */
class ActionDispatcherCancellationTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val notificationManager = mockk<NotificationManager>(relaxed = true)
    private val dispatcher: ActionDispatcher

    init {
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        dispatcher = ActionDispatcher(vehicleApi, helper, context,
            dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) })
    }

    private fun param(command: String) =
        ActionDef(command = command, displayName = command, kind = "param")

    /**
     * Core contract: when the underlying vehicleApi.dispatch() throws CancellationException
     * (e.g. because the routing job was cancelled by the orb hard-stop), ActionDispatcher.dispatch()
     * must rethrow it rather than catching and wrapping it as DispatchResult(false=success). If it
     * were swallowed, VoiceController.execute() would enter the failure branch and call announce()
     * with an error phrase after the session was already torn down.
     */
    @Test fun `dispatch propagates CancellationException thrown by the underlying action`() {
        coEvery { vehicleApi.dispatch(any()) } throws CancellationException("orb-hard-stop")

        var caughtCe = false
        try {
            runBlocking {
                dispatcher.dispatch(param("车窗关闭"), null)
            }
        } catch (e: CancellationException) {
            caughtCe = true
        }

        assertTrue(
            "CancellationException must propagate out of dispatch, not be swallowed as DispatchResult(false)",
            caughtCe
        )
    }

    /** Sanity: non-cancellation exceptions are still caught and returned as DispatchResult(false). */
    @Test fun `dispatch catches non-cancellation exception and returns failure result`() {
        coEvery { vehicleApi.dispatch(any()) } throws RuntimeException("helper-io-error")

        val result = runBlocking {
            dispatcher.dispatch(param("车窗关闭"), null)
        }

        assertTrue("RuntimeException must be caught and returned as DispatchResult(false)", !result.success)
    }
}
