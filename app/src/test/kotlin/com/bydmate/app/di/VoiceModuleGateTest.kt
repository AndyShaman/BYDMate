package com.bydmate.app.di

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Test

class VoiceModuleGateTest {

    /** Empty prefs file: every read returns the default the caller passes. */
    private fun emptyPrefsContext(): Context {
        val prefs = mockk<SharedPreferences> {
            every { getBoolean(any(), any()) } answers { secondArg() }
        }
        return mockk { every { getSharedPreferences("voice", Context.MODE_PRIVATE) } returns prefs }
    }

    @Test fun `close after command is off on a fresh install`() {
        assertFalse(VoiceModule.provideVoiceGate(emptyPrefsContext()).closeAfterCommand())
    }
}
