package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import android.media.session.MediaController
import android.media.session.PlaybackState
import com.bydmate.app.R
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.util.AppStrings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Медиа: играть / пауза» (#212, #275): an explicit PLAY 126 / PAUSE 127 to the session
 * KnobPlayPause.pickTarget picks, and a failed step when no player is running.
 * Plain JUnit for the same reason as ActionDispatcherMusicPlayTest (mockk vs Robolectric's
 * MediaController shadow); which key goes to which session is checked at the sender seam.
 */
class ActionDispatcherMediaKeyTest {
    private val context = mockk<Context>(relaxed = true)
    private val appStrings = mockk<AppStrings>(relaxed = true)
    private val sent = mutableListOf<Pair<String, Int>>()
    private val dispatcher: ActionDispatcher

    init {
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        every { appStrings.get(R.string.dispatch_media_no_session) } returns "Нет запущенного плеера"
        every { appStrings.get(R.string.dispatch_media_key_invalid) } returns "Неизвестная команда плеера"
        every { appStrings.get(R.string.dispatch_media_key_failed, *anyVararg()) } answers {
            "Плеер не принял команду: ${(args[1] as Array<*>)[0]}"
        }
        dispatcher = ActionDispatcher(mockk<VehicleApi>(relaxed = true), mockk<HelperClient>(relaxed = true), context,
            dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
            mockk<ClusterVoiceControl>(relaxed = true),
            mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
            mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
            appStrings, dagger.Lazy { mockk(relaxed = true) })
        dispatcher.sendMediaKey = { controller, keyCode -> sent += controller.packageName to keyCode; true }
    }

    private fun session(pkg: String, state: Int?) = mockk<MediaController> {
        every { packageName } returns pkg
        every { playbackState } returns state?.let { s -> mockk<PlaybackState> { every { this@mockk.state } returns s } }
    }

    private fun key(payload: String) = ActionDef("", "Медиа", "media_key", payload)

    @Test fun `play goes to the playing session as PLAY 126`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("a.paused", 2), session("b.playing", 3)) }
        assertTrue(dispatcher.dispatch(key("play"), null).success)
        assertEquals(listOf("b.playing" to 126), sent)
    }

    @Test fun `pause goes to a session with a state as PAUSE 127`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("a.none", 0), session("b.paused", 2)) }
        assertTrue(dispatcher.dispatch(key("pause"), null).success)
        assertEquals(listOf("b.paused" to 127), sent)
    }

    @Test fun `with no state anywhere the first session gets the key`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("a.first", null), session("b.second", 0)) }
        assertTrue(dispatcher.dispatch(key("play"), null).success)
        assertEquals(listOf("a.first" to 126), sent)
    }

    @Test fun `no session fails with the journal line and no fallback`() = runBlocking {
        dispatcher.activeMediaControllers = { emptyList() }
        val r = dispatcher.dispatch(key("pause"), null)
        assertFalse(r.success)
        assertEquals("Нет запущенного плеера", r.reason)
        assertTrue(sent.isEmpty())
        verify(exactly = 0) { context.getSystemService(Context.AUDIO_SERVICE) }
    }

    @Test fun `a session that refuses the key is a failure`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("a.player", 3)) }
        dispatcher.sendMediaKey = { _, _ -> false }
        val r = dispatcher.dispatch(key("pause"), null)
        assertFalse(r.success)
        assertEquals("Плеер не принял команду: a.player", r.reason)
    }

    @Test fun `an unknown key is refused without touching a session`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("a.player", 3)) }
        val r = dispatcher.dispatch(key("toggle"), null)
        assertFalse(r.success)
        assertEquals("Неизвестная команда плеера", r.reason)
        assertTrue(sent.isEmpty())
    }

    @Test fun `the key codes are PLAY 126 and PAUSE 127, never the toggle`() {
        assertEquals(126, ActionDispatcher.mediaKeyCode("play"))
        assertEquals(127, ActionDispatcher.mediaKeyCode("pause"))
        assertNull(ActionDispatcher.mediaKeyCode("play_pause"))
    }

    @Test fun `the default sender goes through the session's transport controls`() = runBlocking {
        val fresh = ActionDispatcher(mockk<VehicleApi>(relaxed = true), mockk<HelperClient>(relaxed = true), context,
            dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
            mockk<ClusterVoiceControl>(relaxed = true),
            mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
            mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
            appStrings, dagger.Lazy { mockk(relaxed = true) })
        val controls = mockk<MediaController.TransportControls>(relaxed = true)
        val player = session("a.player", 3)
        every { player.transportControls } returns controls
        fresh.activeMediaControllers = { listOf(player) }
        assertTrue(fresh.dispatch(key("play"), null).success)
        assertTrue(fresh.dispatch(key("pause"), null).success)
        verifyOrder { controls.play(); controls.pause() }
        verify(exactly = 0) { player.dispatchMediaButtonEvent(any()) }
    }

    @Test fun `a dead session token is a failure, not a crash`() = runBlocking {
        val fresh = ActionDispatcher(mockk<VehicleApi>(relaxed = true), mockk<HelperClient>(relaxed = true), context,
            dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
            mockk<ClusterVoiceControl>(relaxed = true),
            mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
            mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
            appStrings, dagger.Lazy { mockk(relaxed = true) })
        val player = session("a.player", 3)
        every { player.transportControls } throws IllegalStateException("dead")
        fresh.activeMediaControllers = { listOf(player) }
        val r = fresh.dispatch(key("pause"), null)
        assertFalse(r.success)
        assertEquals("Плеер не принял команду: a.player", r.reason)
    }
}
