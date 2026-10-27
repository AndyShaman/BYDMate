package com.bydmate.app.voice

import android.content.SharedPreferences
import android.media.AudioManager
import com.bydmate.app.data.vehicle.AutoVoiceReply
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.split.fakeSplitSharedPrefs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AudioCapture while the firmware owns the voice duck (#353): its media duck goes through AutoVoiceDuck.nest. */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioCaptureFirmwareDuckTest {

    private val applied = AutoVoiceReply(HelperBinderProtocol.AUTO_VOICE_APPLIED, "ok")
    private val unsupported = AutoVoiceReply(HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED, "no method")

    /** Stateful STREAM_MUSIC: [volume] follows setStreamVolume, every MUSIC set is logged in [sets]. */
    private class Audio(var playing: Boolean = true, var volume: Int = 20) {
        val sets = mutableListOf<Int>()
        val am = mockk<AudioManager>(relaxed = true).also { m ->
            every { m.isMusicActive } answers { playing }
            every { m.getStreamVolume(AudioManager.STREAM_MUSIC) } answers { volume }
            every { m.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 39
            val level = slot<Int>()
            every { m.setStreamVolume(AudioManager.STREAM_MUSIC, capture(level), any()) } answers {
                volume = level.captured; sets += level.captured
            }
        }
    }

    private fun prefs() = mockk<SharedPreferences>(relaxed = true)

    // The mic cannot open in a JVM test: the session ends right after the duck decision.
    private suspend fun runSession(capture: AudioCapture) {
        runCatching { capture.captureSession { it }.toList() }
    }

    private val duck = AudioCapture.DUCK_VOLUME_INDEX

    @Test fun `firmware duck applied - the capture session leaves STREAM_MUSIC alone`() = runTest {
        val a = Audio()
        val calls = mutableListOf<Boolean>()
        val d = AutoVoiceDuck({ on -> calls += on; applied }, fakeSplitSharedPrefs(mutableMapOf()), scope = duckScope())
        val capture = AudioCapture(a.am, prefs(), d)

        val lease = d.activate(capture::duckMusic, capture::restoreMusic)
        runSession(capture)
        assertTrue(a.sets.isEmpty())
        d.deactivate(lease)
        advanceUntilIdle()

        assertTrue(a.sets.isEmpty())
        assertEquals(listOf(true, false), calls)
    }

    @Test fun `rollback off - nest declines and the capture session ducks and restores itself as before`() = runTest {
        val a = Audio()
        val capture = AudioCapture(a.am, prefs(), AutoVoiceDuck.OFF)

        assertNull(AutoVoiceDuck.OFF.nest(capture::duckMusic, capture::restoreMusic))
        runSession(capture)

        assertEquals(listOf(duck, 20), a.sets)
    }

    @Test fun `cached UNSUPPORTED - the capture session ducks and restores itself as before`() = runTest {
        val a = Audio()
        val calls = mutableListOf<Boolean>()
        val d = AutoVoiceDuck(
            { on -> calls += on; unsupported }, fakeSplitSharedPrefs(mutableMapOf()),
            initialVerdict = HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED, scope = duckScope(),
        )
        val capture = AudioCapture(a.am, prefs(), d)

        runSession(capture)
        advanceUntilIdle()

        assertEquals(listOf(duck, 20), a.sets)
        assertTrue(calls.isEmpty())
    }

    @Test fun `default capture without a duck ducks as before`() = runTest {
        val a = Audio()
        runSession(AudioCapture(a.am, prefs()))
        assertEquals(listOf(duck, 20), a.sets)
    }

    @Test fun `no live session - nest declines and nothing is sent to the daemon`() = runTest {
        val a = Audio()
        val calls = mutableListOf<Boolean>()
        val d = AutoVoiceDuck({ on -> calls += on; applied }, fakeSplitSharedPrefs(mutableMapOf()), scope = duckScope())
        val capture = AudioCapture(a.am, prefs(), d)

        assertTrue(d.mayApply())
        assertNull(d.nest(capture::duckMusic, capture::restoreMusic))
        advanceUntilIdle()

        assertTrue(calls.isEmpty())
        assertTrue(a.sets.isEmpty())
    }

    @Test fun `explicit volume with no live duck is set directly and nothing is pending`() {
        val a = Audio()
        val capture = AudioCapture(a.am, prefs(), AutoVoiceDuck.OFF)

        capture.applyExplicitVolume(12)

        verify(exactly = 1) { a.am.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0) }
        assertNull(capture.pendingRestoreVolume())
    }

    // ---- late APPLIED (activate fell back after 250 ms) ----

    @Test fun `late APPLIED after the capture nested a media duck - both ducks restored once, firmware off at the end`() = runTest {
        val a = Audio()
        val calls = mutableListOf<Boolean>()
        val d = AutoVoiceDuck(
            { on -> calls += on; if (on) { withContext(NonCancellable) { delay(1000) }; applied } else applied },
            fakeSplitSharedPrefs(mutableMapOf()), scope = duckScope(),
        )
        val capture = AudioCapture(a.am, prefs(), d)

        val session = async { d.activate(capture::duckMusic, capture::restoreMusic) }
        advanceTimeBy(260); runCurrent()
        val lease = session.await()
        assertEquals(listOf(duck), a.sets) // the session's media fallback
        assertEquals(duck, a.volume)

        advanceTimeBy(40) // t = 300
        val inner = d.nest(capture::duckMusic, capture::restoreMusic)
        assertNotNull(inner)
        assertEquals(listOf(duck), a.sets) // already low: the nested duck saves nothing

        advanceTimeBy(800); runCurrent() // late APPLIED
        assertTrue(d.isActive())
        assertEquals(listOf(duck, 20), a.sets) // restored once, not twice
        assertEquals(20, a.volume)

        d.deactivate(inner!!)
        d.deactivate(lease)
        advanceUntilIdle()

        assertEquals(listOf(duck, 20), a.sets)
        assertEquals(listOf(true, false), calls)
        assertNull(capture.pendingRestoreVolume())
    }

    @Test fun `late APPLIED - the capture's duck is the only one and still gets undone`() = runTest {
        val a = Audio(playing = false)
        val calls = mutableListOf<Boolean>()
        val d = AutoVoiceDuck(
            { on -> calls += on; if (on) { withContext(NonCancellable) { delay(1000) }; applied } else applied },
            fakeSplitSharedPrefs(mutableMapOf()), scope = duckScope(),
        )
        val capture = AudioCapture(a.am, prefs(), d)

        val session = async { d.activate(capture::duckMusic, capture::restoreMusic) }
        advanceTimeBy(260); runCurrent()
        val lease = session.await()
        assertTrue(a.sets.isEmpty()) // nothing was playing

        a.playing = true
        advanceTimeBy(40)
        val inner = d.nest(capture::duckMusic, capture::restoreMusic)
        assertEquals(listOf(duck), a.sets)

        advanceTimeBy(800); runCurrent()
        assertEquals(listOf(duck, 20), a.sets)
        assertEquals(20, a.volume)

        d.deactivate(inner!!)
        d.deactivate(lease)
        advanceUntilIdle()

        assertEquals(listOf(duck, 20), a.sets)
        assertEquals(listOf(true, false), calls)
    }

    @Test fun `APPLIED before nest - nest ducks nothing`() = runTest {
        val a = Audio()
        val calls = mutableListOf<Boolean>()
        val d = AutoVoiceDuck({ on -> calls += on; applied }, fakeSplitSharedPrefs(mutableMapOf()), scope = duckScope())
        val capture = AudioCapture(a.am, prefs(), d)

        val lease = d.activate(capture::duckMusic, capture::restoreMusic)
        val inner = d.nest(capture::duckMusic, capture::restoreMusic)

        assertNotNull(inner)
        verify(exactly = 0) { a.am.setStreamVolume(AudioManager.STREAM_MUSIC, any(), any()) }
        d.deactivate(inner!!)
        d.deactivate(lease)
        advanceUntilIdle()

        verify(exactly = 0) { a.am.setStreamVolume(AudioManager.STREAM_MUSIC, any(), any()) }
        assertEquals(listOf(true, false), calls)
    }

    @Test fun `off waits for the nested level, a double release changes nothing`() = runTest {
        val a = Audio()
        val calls = mutableListOf<Boolean>()
        val d = AutoVoiceDuck({ on -> calls += on; applied }, fakeSplitSharedPrefs(mutableMapOf()), scope = duckScope())
        val capture = AudioCapture(a.am, prefs(), d)

        val lease = d.activate(capture::duckMusic, capture::restoreMusic)
        val inner = d.nest(capture::duckMusic, capture::restoreMusic)!!
        d.deactivate(lease)
        advanceUntilIdle()
        assertEquals(listOf(true), calls)

        d.deactivate(inner)
        d.deactivate(inner)
        advanceUntilIdle()
        assertEquals(listOf(true, false), calls)
    }

    @Test fun `late APPLIED end to end through AutoVoiceDuck - fallback once, restore once, one off call`() = runTest {
        val a = Audio()
        val calls = mutableListOf<Boolean>()
        val d = AutoVoiceDuck(
            { on -> calls += on; if (on) { withContext(NonCancellable) { delay(3000) }; applied } else applied },
            fakeSplitSharedPrefs(mutableMapOf()), scope = duckScope(),
        )
        val capture = AudioCapture(a.am, prefs(), d)

        val session = async { d.activate(capture::duckMusic, capture::restoreMusic) }
        advanceTimeBy(260); runCurrent()
        val lease = session.await()
        assertEquals(listOf(duck), a.sets)

        advanceTimeBy(2800); runCurrent()
        assertTrue(d.isActive())
        assertEquals(listOf(duck, 20), a.sets)

        d.deactivate(lease)
        advanceUntilIdle()
        assertEquals(listOf(duck, 20), a.sets)
        assertEquals(listOf(true, false), calls)
        assertFalse(d.isActive())
    }
}
