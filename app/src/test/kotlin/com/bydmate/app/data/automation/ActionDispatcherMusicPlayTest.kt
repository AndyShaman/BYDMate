package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import android.media.session.MediaController
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

// Plain JUnit (no Robolectric): Robolectric shadows android.media.session.MediaController,
// which conflicts with mockk's inline class-retransform ("class redefinition failed:
// attempted to change the class modifiers") when both instrument the same class. The fallback
// (no live session) path is covered in ActionDispatcherLaunchTest, which needs Robolectric for
// real Intent construction instead.
class ActionDispatcherMusicPlayTest {
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

    @Test fun music_play_uses_media_session_when_available() = runBlocking {
        val controls = mockk<MediaController.TransportControls>(relaxed = true)
        val controller = mockk<MediaController> {
            every { packageName } returns "ru.yandex.music"
            every { transportControls } returns controls
        }
        dispatcher.activeMediaControllers = { listOf(controller) }
        val action = ActionDef(command = "", displayName = "music", kind = "yandex_music",
            payload = """{"mode":"play","query":"группа кино"}""")
        val result = dispatcher.dispatch(action, null)
        assertTrue(result.success)
        verify { controls.playFromSearch("группа кино", any()) }
    }
}
