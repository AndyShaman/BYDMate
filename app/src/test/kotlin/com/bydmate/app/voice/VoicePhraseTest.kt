package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePhraseTest {
    @Test fun `inflections normalize equal`() {
        assertEquals(VoicePhrase.normalize("форточка"), VoicePhrase.normalize("форточки"))
    }
    @Test fun `strips punctuation and case and collapses spaces`() {
        // VoiceStemmer.stem("поехали") -> "поехал" (strips "и")
        // VoiceStemmer.stem("домой")   -> "дом"    (strips "ой")
        assertEquals("поехал дом", VoicePhrase.normalize("  Поехали, домой!  "))
    }
    @Test fun `blank yields blank`() {
        assertEquals("", VoicePhrase.normalize("   "))
    }

    @Test fun `yo and fillers normalize away`() {
        assertEquals(VoicePhrase.normalize("елка"), VoicePhrase.normalize("Эй, ёлка, пожалуйста"))
    }

    @Test fun `containsSequence matches equal and contained whole words only`() {
        val heard = VoicePhrase.tokens("открой окно в машине")
        assertTrue(VoicePhrase.containsSequence(heard, VoicePhrase.tokens("открой окно в машине")))
        assertTrue(VoicePhrase.containsSequence(heard, VoicePhrase.tokens("окно в")))
        assertFalse(VoicePhrase.containsSequence(heard, VoicePhrase.tokens("в окно")))
        assertFalse(VoicePhrase.containsSequence(VoicePhrase.tokens("окновать"), VoicePhrase.tokens("окно")))
        assertFalse(VoicePhrase.containsSequence(heard, emptyList()))
    }
}
