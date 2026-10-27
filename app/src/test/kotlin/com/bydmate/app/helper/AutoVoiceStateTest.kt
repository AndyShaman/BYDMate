package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Daemon body of TX_SET_AUTO_VOICE (#353): the reflection call and its three outcomes. */
class AutoVoiceStateTest {

    class RecordingAudio {
        val calls = mutableListOf<Triple<Boolean, Boolean, String>>()
        fun setAutoVoiceActiveState(a: Boolean, b: Boolean, pkg: String) {
            calls += Triple(a, b, pkg)
        }
    }

    @Suppress("UnusedParameter")
    class ThrowingAudio {
        fun setAutoVoiceActiveState(a: Boolean, b: Boolean, pkg: String) {
            error("denied")
        }
    }

    @Suppress("UnusedParameter")
    class OldAudio {
        fun setSomethingElse(a: Boolean) = Unit
    }

    @Test fun `method present - applied with both flags and the stock package`() {
        val audio = RecordingAudio()
        val on = setAutoVoiceActive(true, { null }, { audio })
        val off = setAutoVoiceActive(false, { null }, { audio })

        assertEquals(HelperBinderProtocol.AUTO_VOICE_APPLIED, on.outcome)
        assertEquals(HelperBinderProtocol.AUTO_VOICE_APPLIED, off.outcome)
        assertEquals(
            listOf(Triple(true, true, "com.byd.autovoice"), Triple(false, false, "com.byd.autovoice")),
            audio.calls,
        )
    }

    @Test fun `method absent - unsupported`() {
        val r = setAutoVoiceActive(true, { null }, { OldAudio() })
        assertEquals(HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED, r.outcome)
    }

    @Test fun `method throwing - failed with the real cause in the detail`() {
        val r = setAutoVoiceActive(true, { null }, { ThrowingAudio() })
        assertEquals(HelperBinderProtocol.AUTO_VOICE_FAILED, r.outcome)
        assertTrue(r.detail, r.detail.contains("IllegalStateException: denied"))
    }

    @Test fun `audio service lookup throwing - failed`() {
        val r = setAutoVoiceActive(true, { null }, { error("audio service null") })
        assertEquals(HelperBinderProtocol.AUTO_VOICE_FAILED, r.outcome)
        assertTrue(r.detail, r.detail.contains("audio service null"))
    }

    @Test fun `amp type changes the detail only, never the outcome`() {
        val results = listOf<() -> String?>({ null }, { "3" }, { error("no prop") }).map { amp ->
            val audio = RecordingAudio()
            setAutoVoiceActive(true, amp, { audio }).also { assertEquals(1, audio.calls.size) }
        }
        results.forEach { assertEquals(HelperBinderProtocol.AUTO_VOICE_APPLIED, it.outcome) }
        assertTrue(results[0].detail, results[0].detail.contains("amp=none"))
        assertTrue(results[1].detail, results[1].detail.contains("amp=3"))
        assertTrue(results[2].detail, results[2].detail.contains("amp=none"))
    }
}
