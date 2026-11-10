package com.bydmate.app.voice.online

import com.bydmate.app.di.migrateLegacyTtsSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the runtime tts_source migration seam in VoiceModule. */
class TtsSourceMigrationTest {

    @Test
    fun `openai is migrated to offline and persisted, every other stored value passes through untouched`() {
        // stored value -> (returned value, persisted value or null when persist is not called)
        val cases = listOf(
            Triple("openai", "offline", "offline"),
            Triple("gemini", "gemini", null),
            Triple("offline", "offline", null),
            Triple("minimax", "minimax", null),
        )
        cases.forEach { (stored, returned, persistedValue) ->
            var persisted: String? = null
            val result = migrateLegacyTtsSource(stored) { persisted = it }
            assertEquals("case: $stored returned", returned, result)
            assertEquals("case: $stored persisted", persistedValue, persisted)
        }
    }
}
