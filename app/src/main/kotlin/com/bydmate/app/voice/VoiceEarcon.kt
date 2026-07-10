package com.bydmate.app.voice

import android.media.AudioManager
import android.media.ToneGenerator

/** Short non-spoken confirmation/failure beeps. TTS responses are Spec 2. */
class VoiceEarcon(private val volume: Int = 70) {
    fun ok() = beep(ToneGenerator.TONE_PROP_ACK, 150)
    fun fail() = beep(ToneGenerator.TONE_PROP_NACK, 250)
    /** Session-stop cue: distinct from ok() so switching the agent on and off are
     *  tellable apart by ear. */
    fun off() = beep(ToneGenerator.TONE_PROP_BEEP, 150)
    private fun beep(tone: Int, ms: Int) {
        runCatching {
            val tg = toneGenerator()
            tg.startTone(tone, ms)
            Thread { Thread.sleep((ms + 50).toLong()); tg.release() }.start()
        }
    }

    // BYD "Voice" stream (17), same route as agent TTS: beeps stay audible while a session
    // ducks STREAM_MUSIC to near-zero -- on the music stream every earcon was swallowed by
    // the session's own duck (field report APK 340). Fall back to the music stream if the
    // firmware rejects the custom stream type.
    private fun toneGenerator(): ToneGenerator =
        runCatching { ToneGenerator(SherpaTtsEngine.BYD_STREAM_BTTS, volume) }
            .getOrElse { ToneGenerator(AudioManager.STREAM_MUSIC, volume) }
}
