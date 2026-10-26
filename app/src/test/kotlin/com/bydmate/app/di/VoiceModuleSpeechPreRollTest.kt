package com.bydmate.app.di

import com.bydmate.app.voice.AsrLoadGuard
import com.bydmate.app.voice.GigaAmModelManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class VoiceModuleSpeechPreRollTest {
    private fun configuredDuration(): Int {
        val engine = VoiceModule.provideContinuousAsr(mockk<GigaAmModelManager>(), mockk<AsrLoadGuard>())
        return engine.javaClass.getDeclaredField("preRollMs").apply { isAccessible = true }.getInt(engine)
    }

    @Test @Config(sdk = [29]) fun `Android 10 production provider enables fixed 400 ms`() {
        assertEquals(400, configuredDuration())
    }

    @Test @Config(sdk = [31, 34]) fun `newer Android production providers preserve zero pre-roll`() {
        assertEquals(0, configuredDuration())
    }
}
