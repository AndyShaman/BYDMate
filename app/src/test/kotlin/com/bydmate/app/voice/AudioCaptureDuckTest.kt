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
}
