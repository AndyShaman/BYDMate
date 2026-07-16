package com.bydmate.app.voice

import android.content.SharedPreferences
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wave F Task 3: duckMusic() must drop STREAM_MUSIC to near-zero, not 15%. */
class AudioCaptureDuckTest {

    private fun prefsMock(pending: Int = -1): Pair<SharedPreferences, SharedPreferences.Editor> {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putInt(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        val prefs = mockk<SharedPreferences>()
        every { prefs.getInt(AudioCapture.KEY_PRE_DUCK_VOLUME, -1) } returns pending
        every { prefs.edit() } returns editor
        return prefs to editor
    }

    @Test
    fun `duckMusic drops volume to DUCK_VOLUME_INDEX and returns previous volume`() {
        val audioManager = mockk<AudioManager>()
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 20
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 39
        every { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, AudioCapture.DUCK_VOLUME_INDEX, 0) } returns Unit
        val capture = AudioCapture(audioManager, prefsMock().first)

        val result = capture.duckMusic()

        assertEquals(20, result)
        verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, AudioCapture.DUCK_VOLUME_INDEX, 0) }
    }

    @Test
    fun `duckMusic is a no-op when volume is already at the duck target`() {
        val audioManager = mockk<AudioManager>()
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 1
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 39
        val capture = AudioCapture(audioManager, prefsMock().first)

        val result = capture.duckMusic()

        assertNull(result)
        verify(exactly = 0) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, any(), any()) }
    }

    @Test
    fun `duckMusic is a no-op when volume is already zero`() {
        val audioManager = mockk<AudioManager>()
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 0
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 39
        val capture = AudioCapture(audioManager, prefsMock().first)

        val result = capture.duckMusic()

        assertNull(result)
        verify(exactly = 0) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, any(), any()) }
    }

    @Test
    fun `restoreMusic restores the saved volume`() {
        val audioManager = mockk<AudioManager>()
        every { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 20, 0) } returns Unit
        val capture = AudioCapture(audioManager, prefsMock().first)

        capture.restoreMusic(20)

        verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 20, 0) }
    }

    @Test
    fun `restoreMusic is a no-op when nothing was ducked`() {
        val audioManager = mockk<AudioManager>()
        val capture = AudioCapture(audioManager, prefsMock().first)

        capture.restoreMusic(null)

        verify(exactly = 0) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, any(), any()) }
    }

    @Test
    fun duckMusic_second_call_returns_null_when_already_ducked() {
        val audioManager = mockk<AudioManager>()
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 20 andThen 1
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 39
        every { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, AudioCapture.DUCK_VOLUME_INDEX, 0) } returns Unit
        val capture = AudioCapture(audioManager, prefsMock().first)

        val first = capture.duckMusic()   // 20 -> 1, returns 20
        val second = capture.duckMusic()  // already at 1 -> null, no setStreamVolume

        assertEquals(20, first)
        assertNull(second)
        verify(exactly = 1) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, AudioCapture.DUCK_VOLUME_INDEX, 0) }
    }

    @Test
    fun `duckMusic persists the pre-duck volume`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 12
        val (prefs, editor) = prefsMock()
        AudioCapture(audioManager, prefs).duckMusic()
        verify(exactly = 1) { editor.putInt(AudioCapture.KEY_PRE_DUCK_VOLUME, 12) }
    }

    @Test
    fun `restoreMusic clears the persisted value`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        val (prefs, editor) = prefsMock()
        AudioCapture(audioManager, prefs).restoreMusic(12)
        verify(exactly = 1) { editor.remove(AudioCapture.KEY_PRE_DUCK_VOLUME) }
        verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0) }
    }

    @Test
    fun `restoreStuckDuck restores persisted volume when still ducked`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 1
        val (prefs, editor) = prefsMock(pending = 12)
        AudioCapture(audioManager, prefs).restoreStuckDuck()
        verify(exactly = 1) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0) }
        verify(exactly = 1) { editor.remove(AudioCapture.KEY_PRE_DUCK_VOLUME) }
    }

    @Test
    fun `restoreStuckDuck keeps a user-raised volume but clears the marker`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 8
        val (prefs, editor) = prefsMock(pending = 12)
        AudioCapture(audioManager, prefs).restoreStuckDuck()
        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
        verify(exactly = 1) { editor.remove(AudioCapture.KEY_PRE_DUCK_VOLUME) }
    }

    @Test
    fun `restoreStuckDuck is a no-op without a persisted value`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        val (prefs, _) = prefsMock(pending = -1)
        AudioCapture(audioManager, prefs).restoreStuckDuck()
        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun `restoreStuckDuck skips while a live session in this process owns the duck`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 12
        val (prefs, editor) = prefsMock(pending = 12)
        val capture = AudioCapture(audioManager, prefs)
        capture.duckMusic()
        capture.restoreStuckDuck()
        // Only duckMusic's own lowering happened; no restore to 12, marker untouched.
        verify(exactly = 0) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0) }
        verify(exactly = 0) { editor.remove(AudioCapture.KEY_PRE_DUCK_VOLUME) }
    }

    @Test
    fun `restoreMusic re-arms restoreStuckDuck for the next process-death marker`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 12 andThen 1
        val (prefs, editor) = prefsMock(pending = 12)
        val capture = AudioCapture(audioManager, prefs)
        capture.duckMusic()
        capture.restoreMusic(12)
        capture.restoreStuckDuck()
        // Once restoreMusic released the duck, a persisted marker is trusted again.
        verify(exactly = 2) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0) }
        verify(exactly = 2) { editor.remove(AudioCapture.KEY_PRE_DUCK_VOLUME) }
    }

    @Test fun `explicit volume set during duck survives session restore`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 10
        val capture = AudioCapture(audioManager, prefsMock().first)
        val saved = capture.duckMusic()          // 10 -> 1
        capture.applyExplicitVolume(5)           // agent executed an explicit "volume 5"
        capture.restoreMusic(saved)              // session teardown
        // apply sets 5 once, the teardown restore returns to 5 once = 2 calls with index 5
        verify(exactly = 2) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0) }
        verify(exactly = 0) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0) }
    }

    @Test fun `apply without an active duck sets volume but does not register a restore target`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 10
        val capture = AudioCapture(audioManager, prefsMock().first)
        capture.applyExplicitVolume(5)
        val saved = capture.duckMusic()
        capture.restoreMusic(saved)
        verify(exactly = 1) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0) }   // the apply itself
        verify(exactly = 1) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0) }  // restore = pre-duck 10, not 5
    }

    @Test fun `pendingRestoreVolume visible only while a duck is active`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 10
        val capture = AudioCapture(audioManager, prefsMock().first)
        assertNull(capture.pendingRestoreVolume())
        val saved = capture.duckMusic()
        assertEquals(10, capture.pendingRestoreVolume())
        capture.applyExplicitVolume(5)
        assertEquals(5, capture.pendingRestoreVolume())
        capture.restoreMusic(saved)
        assertNull(capture.pendingRestoreVolume())
    }

    @Test fun `apply refreshes the persisted stuck-duck marker`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 10
        val (prefs, editor) = prefsMock()
        val capture = AudioCapture(audioManager, prefs)
        capture.duckMusic()
        capture.applyExplicitVolume(5)
        verify(exactly = 1) { editor.putInt(AudioCapture.KEY_PRE_DUCK_VOLUME, 5) }
    }

    @Test fun `mid-session set survives a nested listen-window duck and the outer restore`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        var vol = 10
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } answers { vol }
        every { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, any(), 0) } answers { vol = secondArg<Int>() }
        val capture = AudioCapture(audioManager, prefsMock().first)
        val session = capture.duckMusic()      // session-level early duck: 10 -> 1
        capture.applyExplicitVolume(13)        // agent command mid-session
        val window = capture.duckMusic()       // next listen window ducks again: 13 -> 1
        capture.restoreMusic(window)           // window closes -> back to 13
        capture.restoreMusic(session)          // session teardown must keep the user's 13
        assertEquals(13, vol)
    }

    @Test fun `set landing after the teardown restore still wins`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        var vol = 10
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } answers { vol }
        every { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, any(), 0) } answers { vol = secondArg<Int>() }
        val capture = AudioCapture(audioManager, prefsMock().first)
        val saved = capture.duckMusic()
        capture.restoreMusic(saved)            // teardown won the race and restored 10 first
        capture.applyExplicitVolume(5)         // the command's set lands after -> must stick
        assertEquals(5, vol)
    }

    @Test fun `marker survives when the physical restore fails`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 10
        val (prefs, editor) = prefsMock()
        val capture = AudioCapture(audioManager, prefs)
        val saved = capture.duckMusic()
        every { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0) } throws SecurityException("boom")
        capture.restoreMusic(saved)   // physical restore fails -> the marker must stay for restoreStuckDuck()
        verify(exactly = 0) { editor.remove(AudioCapture.KEY_PRE_DUCK_VOLUME) }
    }

    @Test fun `set between a window restore and the session teardown still wins`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        var vol = 10
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } answers { vol }
        every { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, any(), 0) } answers { vol = secondArg<Int>() }
        val capture = AudioCapture(audioManager, prefsMock().first)
        val session = capture.duckMusic()      // session-level duck: 10 -> 1
        capture.applyExplicitVolume(13)        // turn 1 command
        val window = capture.duckMusic()       // turn 2 listen window: 13 -> 1
        capture.restoreMusic(window)           // window closes -> 13; the SESSION duck still owns
        assertEquals(13, capture.pendingRestoreVolume())  // ownership must survive the inner restore
        capture.applyExplicitVolume(5)         // turn 2 command, after the window restore
        capture.restoreMusic(session)          // session teardown must keep the user's 5
        assertEquals(5, vol)
    }
}
