package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.repository.SettingsRepository
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

// Plain JUnit + mockk: dispatchHotspot only reads action.payload and calls
// helper.setHotspot — Context is stored but never dereferenced for this kind.
class ActionDispatcherHotspotTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val helper = mockk<HelperClient>()
    private val context = mockk<Context>(relaxed = true)
    private val notificationManager = mockk<NotificationManager>(relaxed = true)
    private val dispatcher: ActionDispatcher

    init {
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        dispatcher = ActionDispatcher(
            vehicleApi, settingsRepository, helper, context,
            dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
            mockk<ClusterVoiceControl>(relaxed = true),
            mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
        )
    }

    private fun hotspot(payload: String) =
        ActionDef(command = "hotspot", displayName = "Точка доступа Wi-Fi", kind = "hotspot", payload = payload)

    @Test fun `hotspot enable calls setHotspot true`() = runBlocking {
        coEvery { helper.setHotspot(true) } returns true
        assertTrue(dispatcher.dispatch(hotspot("1"), null).success)
        coVerify(exactly = 1) { helper.setHotspot(true) }
    }

    @Test fun `hotspot disable calls setHotspot false`() = runBlocking {
        coEvery { helper.setHotspot(false) } returns true
        assertTrue(dispatcher.dispatch(hotspot("0"), null).success)
        coVerify(exactly = 1) { helper.setHotspot(false) }
    }

    @Test fun `hotspot daemon failure returns failure result`() = runBlocking {
        coEvery { helper.setHotspot(true) } returns false
        val result = dispatcher.dispatch(hotspot("1"), null)
        assertFalse(result.success)
        coVerify(exactly = 1) { helper.setHotspot(true) }
    }

    @Test fun `hotspot with bad payload fails without daemon call`() = runBlocking {
        assertFalse(dispatcher.dispatch(hotspot("x"), null).success)
        coVerify(exactly = 0) { helper.setHotspot(any()) }
    }
}
