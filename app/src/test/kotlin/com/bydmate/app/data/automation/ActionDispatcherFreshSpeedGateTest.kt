package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.vehicle.VehicleApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A speed-gated opening is decided on the speed read right before the step, never on the
 * snapshot the caller hands in (a rule's is from the moment it fired) and never on a poll: a
 * failed direct read is refused as speed unknown. Runs the real dispatcher gate.
 */
class ActionDispatcherFreshSpeedGateTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true) {
        coEvery { dispatch(any()) } returns Result.success(Unit)
    }
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
    }
    private val dispatcher = ActionDispatcher(vehicleApi, mockk(relaxed = true), context,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
        mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
        mockk<com.bydmate.app.util.AppStrings>(relaxed = true), dagger.Lazy { mockk(relaxed = true) },
    )

    private fun param(command: String) = ActionDef(command = command, displayName = command, kind = "param")

    @Test fun `a stale zero snapshot does not open a window on a moving car`() = runBlocking {
        dispatcher.readSpeedNow = { 130 }

        assertFalse(dispatcher.dispatch(param(WINDOW_OPEN), diParsData(speed = 0)).success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `a stale zero snapshot does not open the frunk or unlock on a moving car`() = runBlocking {
        dispatcher.readSpeedNow = { 5 }
        assertFalse(dispatcher.dispatch(param("前备箱打开"), diParsData(speed = 0)).success)

        dispatcher.readSpeedNow = { 40 }
        assertFalse(dispatcher.dispatch(param("车门解锁"), diParsData(speed = 0)).success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `a fresh zero opens even when the step came with no data`() = runBlocking {
        dispatcher.readSpeedNow = { 0 }

        assertTrue(dispatcher.dispatch(param(WINDOW_OPEN), null).success)
        assertTrue(dispatcher.dispatch(param("前备箱打开"), diParsData(speed = 60)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch(WINDOW_OPEN) }
    }

    @Test fun `a failed direct read is refused even with a zero poll`() = runBlocking {
        dispatcher.liveSnapshot = { diParsData(speed = 0) }

        dispatcher.readSpeedNow = { null }
        assertFalse(dispatcher.dispatch(param(WINDOW_OPEN), diParsData(speed = 0)).success)
        dispatcher.readSpeedNow = { error("binder died") }
        assertFalse(dispatcher.dispatch(param("前备箱打开"), diParsData(speed = 0)).success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `the direct read is rounded up`() = runBlocking {
        coEvery { vehicleApi.readSpeed() } returns 120.2f

        assertFalse(dispatcher.dispatch(param(WINDOW_OPEN), diParsData(speed = 0)).success)
        coEvery { vehicleApi.readSpeed() } returns 120.0f
        assertTrue(dispatcher.dispatch(param(WINDOW_OPEN), diParsData(speed = 0)).success)
    }

    @Test fun `0,8 km per h is moving for the frunk and 120,8 is over the window limit`() = runBlocking {
        coEvery { vehicleApi.readSpeed() } returns 0.8f
        assertFalse(dispatcher.dispatch(param("前备箱打开"), diParsData(speed = 0)).success)

        coEvery { vehicleApi.readSpeed() } returns 120.8f
        assertFalse(dispatcher.dispatch(param(WINDOW_OPEN), diParsData(speed = 0)).success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `a closing step reads no speed`() = runBlocking {
        dispatcher.readSpeedNow = { error("must not read") }

        assertTrue(dispatcher.dispatch(param("主驾打开0"), null).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("主驾打开0") }
    }

    private companion object {
        const val WINDOW_OPEN = "主驾打开100"
    }
}
