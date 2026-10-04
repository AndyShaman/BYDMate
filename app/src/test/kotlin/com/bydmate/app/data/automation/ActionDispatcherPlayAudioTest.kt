package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import com.bydmate.app.R
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.util.AppStrings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "play_audio": a picked URI reaches the player seam and a started clip is a success; a missing
 * URI never touches the player and is a failed step. The real MediaPlayer is behind the
 * [ActionDispatcher.playAudioFile] seam, so this is plain JUnit (no Robolectric audio shadow).
 */
class ActionDispatcherPlayAudioTest {
    private val context = mockk<Context>(relaxed = true)
    private val appStrings = mockk<AppStrings>(relaxed = true)
    private var played = 0
    private val dispatcher: ActionDispatcher

    init {
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        every { appStrings.get(R.string.dispatch_play_audio_failed) } returns "Не удалось воспроизвести аудио"
        dispatcher = ActionDispatcher(
            mockk<VehicleApi>(relaxed = true), mockk<HelperClient>(relaxed = true), context,
            dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
            mockk<ClusterVoiceControl>(relaxed = true),
            mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
            mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
            appStrings, dagger.Lazy { mockk(relaxed = true) },
        )
        dispatcher.playAudioFile = { played++; true }
    }

    private fun action(uri: String) =
        ActionDef("", "Звук", "play_audio", """{"uri":"$uri","name":"chime.mp3"}""")

    @Test fun `a picked file is played and the step succeeds`() = runBlocking {
        assertTrue(dispatcher.dispatch(action("content://audio/1"), null).success)
        assertEquals(1, played)
    }

    @Test fun `an empty uri never touches the player and fails`() = runBlocking {
        val r = dispatcher.dispatch(action(""), null)
        assertFalse(r.success)
        assertEquals("Не удалось воспроизвести аудио", r.reason)
        assertEquals(0, played)
    }

    @Test fun `a player that refused to start is a failure`() = runBlocking {
        dispatcher.playAudioFile = { false }
        val r = dispatcher.dispatch(action("content://audio/2"), null)
        assertFalse(r.success)
        assertEquals("Не удалось воспроизвести аудио", r.reason)
    }
}
