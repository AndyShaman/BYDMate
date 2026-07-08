package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceStemmerTest {
    @Test fun strips_russian_noun_endings_to_common_stem() {
        val stem = VoiceStemmer.stem("окно")
        assertEquals(stem, VoiceStemmer.stem("окна"))
        assertEquals(stem, VoiceStemmer.stem("окном"))
        assertEquals(stem, VoiceStemmer.stem("окне"))
    }

    @Test fun keeps_short_tokens_intact() {
        assertEquals("ac", VoiceStemmer.stem("AC"))
        assertEquals("свет", VoiceStemmer.stem("свет"))
    }

    @Test fun strips_english_plural() {
        assertEquals(VoiceStemmer.stem("window"), VoiceStemmer.stem("windows"))
    }

    @Test fun is_idempotent() {
        val once = VoiceStemmer.stem("окнами")
        assertEquals(once, VoiceStemmer.stem(once))
    }
}
