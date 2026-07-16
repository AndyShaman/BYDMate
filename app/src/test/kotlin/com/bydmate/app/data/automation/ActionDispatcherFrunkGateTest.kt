package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Gate behavior for the front trunk (frunk). Frunk is a powered external panel:
// opening must FAIL SAFE — blocked unless we can confirm speed == 0. Missing
// telemetry (null DiParsData / null speed) must block, not allow. Close is ungated.
class ActionDispatcherFrunkGateTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val notificationManager = mockk<NotificationManager>(relaxed = true)
    private val dispatcher: ActionDispatcher

    init {
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        dispatcher = ActionDispatcher(vehicleApi, helper, context,
            dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
            mockk<ClusterVoiceControl>(relaxed = true),
            mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true))
    }

    private fun param(command: String) =
        ActionDef(command = command, displayName = command, kind = "param")

    private fun dataAt(speed: Int?) = mockk<DiParsData> { every { this@mockk.speed } returns speed }

    @Test fun `frunk open with null telemetry is blocked (fail-safe)`() = runBlocking {
        assertFalse(dispatcher.dispatch(param("前备箱打开"), null).success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `frunk open with unknown speed is blocked`() = runBlocking {
        assertFalse(dispatcher.dispatch(param("前备箱打开"), dataAt(null)).success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `frunk open while moving is blocked`() = runBlocking {
        assertFalse(dispatcher.dispatch(param("前备箱打开"), dataAt(50)).success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `frunk open at standstill is allowed`() = runBlocking {
        coEvery { vehicleApi.dispatch("前备箱打开") } returns Result.success(Unit)
        assertTrue(dispatcher.dispatch(param("前备箱打开"), dataAt(0)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("前备箱打开") }
    }

    @Test fun `frunk close is ungated even with null telemetry`() = runBlocking {
        coEvery { vehicleApi.dispatch("前备箱关闭") } returns Result.success(Unit)
        assertTrue(dispatcher.dispatch(param("前备箱关闭"), null).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("前备箱关闭") }
    }
}
