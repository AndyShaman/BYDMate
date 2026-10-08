package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.util.Log
import com.bydmate.app.R
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.util.AppStrings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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

    private fun session(pkg: String, state: Int?, title: String? = null) = mockk<MediaController> {
        every { packageName } returns pkg
        every { playbackState } returns state?.let { s -> mockk<PlaybackState> { every { this@mockk.state } returns s } }
        if (title != null) {
            every { metadata } returns mockk<MediaMetadata> {
                every { getString(MediaMetadata.METADATA_KEY_TITLE) } returns title
            }
        }
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

    @Test fun `only a stopped mediacenter session is no player, not a wake-up of the stock player`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("com.byd.mediacenter", 1)) }
        val r = dispatcher.dispatch(key("pause"), null)
        assertFalse(r.success)
        assertEquals("Нет запущенного плеера", r.reason)
        assertTrue(sent.isEmpty())
    }

    @Test fun `a mediacenter session with state NONE is skipped too`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("com.byd.mediacenter", 0)) }
        val r = dispatcher.dispatch(key("play"), null)
        assertFalse(r.success)
        assertTrue(sent.isEmpty())
    }

    @Test fun `a mediacenter session with no state is skipped too`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("com.byd.mediacenter", null)) }
        val r = dispatcher.dispatch(key("play"), null)
        assertFalse(r.success)
        assertTrue(sent.isEmpty())
    }

    @Test fun `a stopped mediacenter yields to a paused YouTube Music`() = runBlocking {
        dispatcher.activeMediaControllers = {
            listOf(session("com.byd.mediacenter", 1), session("app.morphe.android.apps.youtube.music", 2))
        }
        assertTrue(dispatcher.dispatch(key("play"), null).success)
        assertEquals(listOf("app.morphe.android.apps.youtube.music" to 126), sent)
    }

    @Test fun `a playing mediacenter still gets the key`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("com.byd.mediacenter", 3), session("b.paused", 2)) }
        assertTrue(dispatcher.dispatch(key("pause"), null).success)
        assertEquals(listOf("com.byd.mediacenter" to 127), sent)
    }

    @Test fun `a paused mediacenter still gets the key`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("com.byd.mediacenter", 2)) }
        assertTrue(dispatcher.dispatch(key("play"), null).success)
        assertEquals(listOf("com.byd.mediacenter" to 126), sent)
    }

    @Test fun `a stopped session of another player is kept`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("com.byd.mediacenter", 1), session("b.stopped", 1)) }
        assertTrue(dispatcher.dispatch(key("play"), null).success)
        assertEquals(listOf("b.stopped" to 126), sent)
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

    // Issue #275: replays the «media key» lines of the user's log with their session lists. The
    // automation paused the radio while it played, the radio's session died, and the last play
    // found only the stock player the user had paused by hand: it must be skipped, not resumed.
    @Test fun `issue 275 replay a play skips the paused stock player when the radio we paused is gone`() = runBlocking {
        val lines = requireNotNull(javaClass.classLoader?.getResource("media/issue275-play-resumes-stock-player.txt"))
            .readText().lines().filter { it.contains("I/ActionDispatcher") }
        assertEquals(6, lines.size)
        lines.forEachIndexed { i, line ->
            val payload = line.substringAfter("media key ").substringBefore(" ")
            val logged = line.substringAfter("-> ").substringBefore(" ")
            dispatcher.activeMediaControllers = {
                line.substringAfter("sessions=[").substringBefore("]").split(" ").map {
                    session(it.substringBefore(":"), it.substringAfter(":").toInt())
                }
            }
            sent.clear()
            val r = dispatcher.dispatch(key(payload), null)
            if (i < lines.lastIndex) {
                assertTrue(line.take(18), r.success)
                assertEquals(line.take(18), listOf(logged to ActionDispatcher.mediaKeyCode(payload)), sent)
            } else {
                assertEquals("com.byd.mediacenter", logged)
                assertFalse(line.take(18), r.success)
                assertEquals("Нет запущенного плеера", r.reason)
                assertTrue(sent.isEmpty())
            }
        }
    }

    @Test fun `issue 275 a play while the radio reconnects goes to the radio, then skips the stock player`() = runBlocking {
        mockkStatic(Log::class)
        try {
            val logged = mutableListOf<String>()
            every { Log.i(any(), any<String>()) } answers { logged += secondArg<String>(); 0 }
            every { Log.w(any(), any<String>()) } answers { logged += secondArg<String>(); 0 }
            dispatcher.activeMediaControllers = { listOf(session("com.ilv.vradio", 3), session("com.byd.mediacenter", 2)) }
            assertTrue(dispatcher.dispatch(key("pause"), null).success)
            dispatcher.activeMediaControllers = { listOf(session("com.ilv.vradio", 8), session("com.byd.mediacenter", 2)) }
            assertTrue(dispatcher.dispatch(key("play"), null).success)
            dispatcher.activeMediaControllers = { listOf(session("com.byd.mediacenter", 2)) }
            val r = dispatcher.dispatch(key("play"), null)
            assertFalse(r.success)
            assertEquals("Нет запущенного плеера", r.reason)
            assertEquals(listOf("com.ilv.vradio" to 127, "com.ilv.vradio" to 126), sent)
            assertTrue(logged.toString(), "media key play: stock player paused, last paused com.ilv.vradio gone: skipped" in logged)
        } finally {
            unmockkStatic(Log::class)
        }
    }

    @Test fun `issue 275 a pause to a session that was not playing keeps the memory`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("com.ilv.vradio", 3), session("com.byd.mediacenter", 2)) }
        dispatcher.dispatch(key("pause"), null)
        dispatcher.activeMediaControllers = { listOf(session("com.byd.mediacenter", 2)) }
        assertTrue(dispatcher.dispatch(key("pause"), null).success)
        sent.clear()
        assertFalse(dispatcher.dispatch(key("play"), null).success)
        assertTrue(sent.isEmpty())
    }

    @Test fun `issue 275 a stock player we paused ourselves is resumed`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("com.byd.mediacenter", 3)) }
        assertTrue(dispatcher.dispatch(key("pause"), null).success)
        dispatcher.activeMediaControllers = { listOf(session("com.byd.mediacenter", 2)) }
        assertTrue(dispatcher.dispatch(key("play"), null).success)
        assertEquals(listOf("com.byd.mediacenter" to 127, "com.byd.mediacenter" to 126), sent)
    }

    @Test fun `issue 275 a refused pause to the playing stock player keeps the memory`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("com.ilv.vradio", 3)) }
        assertTrue(dispatcher.dispatch(key("pause"), null).success)
        dispatcher.sendMediaKey = { _, _ -> false }
        dispatcher.activeMediaControllers = { listOf(session("com.byd.mediacenter", 3)) }
        assertFalse(dispatcher.dispatch(key("pause"), null).success)
        dispatcher.sendMediaKey = { controller, keyCode -> sent += controller.packageName to keyCode; true }
        sent.clear()
        dispatcher.activeMediaControllers = { listOf(session("com.byd.mediacenter", 2)) }
        val r = dispatcher.dispatch(key("play"), null)
        assertFalse(r.success)
        assertEquals("Нет запущенного плеера", r.reason)
        assertTrue(sent.isEmpty())
    }

    @Test fun `issue 275 a pause to the playing stock player replaces the radio in memory`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("com.ilv.vradio", 3)) }
        assertTrue(dispatcher.dispatch(key("pause"), null).success)
        dispatcher.activeMediaControllers = { listOf(session("com.byd.mediacenter", 3)) }
        assertTrue(dispatcher.dispatch(key("pause"), null).success)
        dispatcher.activeMediaControllers = { listOf(session("com.byd.mediacenter", 2)) }
        assertTrue(dispatcher.dispatch(key("play"), null).success)
        assertEquals(
            listOf("com.ilv.vradio" to 127, "com.byd.mediacenter" to 127, "com.byd.mediacenter" to 126), sent)
    }

    @Test fun `issue 275 a playing stock player wins over the player we paused`() = runBlocking {
        dispatcher.activeMediaControllers = { listOf(session("com.ilv.vradio", 3)) }
        assertTrue(dispatcher.dispatch(key("pause"), null).success)
        dispatcher.activeMediaControllers = { listOf(session("com.ilv.vradio", 2), session("com.byd.mediacenter", 3)) }
        assertTrue(dispatcher.dispatch(key("play"), null).success)
        assertEquals(listOf("com.ilv.vradio" to 127, "com.byd.mediacenter" to 126), sent)
    }

    @Test fun `the media key log line tells per session whether it carries a title`() = runBlocking {
        mockkStatic(Log::class)
        try {
            val logged = mutableListOf<String>()
            every { Log.i(any(), any<String>()) } answers { logged += secondArg<String>(); 0 }
            val noMetadata = session("c.none", 2)
            every { noMetadata.metadata } returns null
            dispatcher.activeMediaControllers = {
                listOf(session("a.titled", 3, "Song"), session("b.blank", 2, " "), noMetadata, session("d.throws", 2))
            }
            assertTrue(dispatcher.dispatch(key("pause"), null).success)
            assertEquals(
                listOf("media key pause -> a.titled ok=true sessions=[a.titled:3:t1 b.blank:2:t0 c.none:2:t0 d.throws:2:t0]"),
                logged)
        } finally {
            unmockkStatic(Log::class)
        }
    }
}
