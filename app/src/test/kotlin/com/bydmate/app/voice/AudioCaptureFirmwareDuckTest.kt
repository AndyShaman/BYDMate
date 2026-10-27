package com.bydmate.app.voice

import android.content.SharedPreferences
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

/** AudioCapture while the firmware owns the voice duck (#353). */
class AudioCaptureFirmwareDuckTest {

    private fun audioManager() = mockk<AudioManager>(relaxed = true).also {
        every { it.isMusicActive } returns true
        every { it.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 20
        every { it.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 39
    }

    private fun prefs() = mockk<SharedPreferences>(relaxed = true)

    // The mic cannot open in a JVM test: the session ends right after the duck decision.
    private suspend fun runSession(capture: AudioCapture) {
        runCatching { capture.captureSession { it }.toList() }
    }

    @Test fun `firmware duck active - the capture session leaves STREAM_MUSIC alone`() = runTest {
        val am = audioManager()
        runSession(AudioCapture(am, prefs(), firmwareDuckActive = { true }))
        verify(exactly = 0) { am.setStreamVolume(AudioManager.STREAM_MUSIC, any(), any()) }
    }

    @Test fun `firmware duck not active - the capture session ducks as before`() = runTest {
        val am = audioManager()
        runSession(AudioCapture(am, prefs()))
        verify { am.setStreamVolume(AudioManager.STREAM_MUSIC, AudioCapture.DUCK_VOLUME_INDEX, 0) }
        verify { am.setStreamVolume(AudioManager.STREAM_MUSIC, 20, 0) }
    }

    @Test fun `explicit volume with no live duck is set directly and nothing is pending`() {
        val am = audioManager()
        val capture = AudioCapture(am, prefs(), firmwareDuckActive = { true })

        capture.applyExplicitVolume(12)

        verify(exactly = 1) { am.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0) }
        assertNull(capture.pendingRestoreVolume())
    }
}
