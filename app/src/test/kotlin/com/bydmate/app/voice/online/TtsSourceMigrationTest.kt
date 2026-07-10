package com.bydmate.app.voice.online

import com.bydmate.app.di.migrateLegacyTtsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the runtime tts_source migration seam in VoiceModule. */
class TtsSourceMigrationTest {

    // --- legacy "openai" stored value ---

    @Test
    fun `openai stored value returns offline`() {
        val result = migrateLegacyTtsSource("openai") { }
        assertEquals("offline", result)
    }

    @Test
    fun `openai stored value calls persist with offline`() {
        var persisted: String? = null
        migrateLegacyTtsSource("openai") { persisted = it }
        assertEquals("offline", persisted)
    }

    // --- non-legacy values pass through unchanged ---

    @Test
    fun `gemini stored value returns gemini and does not call persist`() {
        var persisted: String? = null
        val result = migrateLegacyTtsSource("gemini") { persisted = it }
        assertEquals("gemini", result)
        assertNull(persisted)
    }

    @Test
    fun `offline stored value returns offline and does not call persist`() {
        var persisted: String? = null
        val result = migrateLegacyTtsSource("offline") { persisted = it }
        assertEquals("offline", result)
        assertNull(persisted)
    }

    @Test
    fun `minimax stored value returns minimax and does not call persist`() {
        var persisted: String? = null
        val result = migrateLegacyTtsSource("minimax") { persisted = it }
        assertEquals("minimax", result)
        assertNull(persisted)
    }
}
