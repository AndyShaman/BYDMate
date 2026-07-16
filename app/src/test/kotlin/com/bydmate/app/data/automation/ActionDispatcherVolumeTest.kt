package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

// Plain JUnit + mockk, mirrors ActionDispatcherSentryTest: setMediaVolume touches only
// AudioManager (via context) and the injected AudioCapture duck state.
class ActionDispatcherVolumeTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val notificationManager = mockk<NotificationManager>(relaxed = true)
    private val audioManager = mockk<AudioManager>(relaxed = true)
    private val audioCapture = mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true)
    private val dispatcher: ActionDispatcher

    init {
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 39
        dispatcher = ActionDispatcher(vehicleApi, helper, context,
            dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
            mockk<ClusterVoiceControl>(relaxed = true),
            audioCapture)
    }

    private fun volume(payload: String) =
        ActionDef(command = "media_volume", displayName = "Громкость", kind = "media_volume", payload = payload)

    @Test fun `explicit set delegates to the atomic duck-aware apply`() = runBlocking {
        every { audioCapture.pendingRestoreVolume() } returns 10   // duck active, pre-duck 10
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 1
        assertTrue(dispatcher.dispatch(volume("5"), null).success)
        verify(exactly = 1) { audioCapture.applyExplicitVolume(5) }
        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
    }

    @Test fun `relative step resolves from the pre-duck volume not the duck level`() = runBlocking {
        every { audioCapture.pendingRestoreVolume() } returns 10
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 1  // ducked
        assertTrue(dispatcher.dispatch(volume("+3"), null).success)
        verify(exactly = 1) { audioCapture.applyExplicitVolume(13) }
    }

    @Test fun `without an active duck the current stream volume is the base`() = runBlocking {
        every { audioCapture.pendingRestoreVolume() } returns null
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 10
        assertTrue(dispatcher.dispatch(volume("+3"), null).success)
        verify(exactly = 1) { audioCapture.applyExplicitVolume(13) }
    }
}
