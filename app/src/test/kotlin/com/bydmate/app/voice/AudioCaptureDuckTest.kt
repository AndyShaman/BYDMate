package com.bydmate.app.voice

import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wave F Task 3: duckMusic() must drop STREAM_MUSIC to near-zero, not 15%. */
class AudioCaptureDuckTest {

    @Test
    fun `duckMusic drops volume to DUCK_VOLUME_INDEX and returns previous volume`() {
        val audioManager = mockk<AudioManager>()
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 20
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 39
        every { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, AudioCapture.DUCK_VOLUME_INDEX, 0) } returns Unit
        val capture = AudioCapture(audioManager)

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
        val capture = AudioCapture(audioManager)

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
        val capture = AudioCapture(audioManager)

        val result = capture.duckMusic()

        assertNull(result)
        verify(exactly = 0) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, any(), any()) }
    }

    @Test
    fun `restoreMusic restores the saved volume`() {
        val audioManager = mockk<AudioManager>()
        every { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 20, 0) } returns Unit
        val capture = AudioCapture(audioManager)

        capture.restoreMusic(20)

        verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 20, 0) }
    }

    @Test
    fun `restoreMusic is a no-op when nothing was ducked`() {
        val audioManager = mockk<AudioManager>()
        val capture = AudioCapture(audioManager)

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
        val capture = AudioCapture(audioManager)

        val first = capture.duckMusic()   // 20 -> 1, returns 20
        val second = capture.duckMusic()  // already at 1 -> null, no setStreamVolume

        assertEquals(20, first)
        assertNull(second)
        verify(exactly = 1) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, AudioCapture.DUCK_VOLUME_INDEX, 0) }
    }
}
