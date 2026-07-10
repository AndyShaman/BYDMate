package com.bydmate.app.voice

import org.junit.Assert.assertEquals
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
}
