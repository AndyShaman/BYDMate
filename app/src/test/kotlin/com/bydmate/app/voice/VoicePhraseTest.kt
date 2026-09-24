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

    @Test fun `exactness compares words without stemming`() {
        assertFalse(VoicePhrase.isExact("открой окно", "открой окна"))
        assertTrue(VoicePhrase.isExact("слушай открой окно пожалуйста", "открой окно"))
        assertTrue(VoicePhrase.isExact("Открой, ёлку!", "открой елку"))
        assertFalse(VoicePhrase.isExact("пожалуйста", "пожалуйста"))
    }

    @Test fun `a phrase inside a longer utterance is not exact`() {
        assertFalse(VoicePhrase.isExact("открой окно в машине", "окно в"))
        assertFalse(VoicePhrase.isExact("окновать", "окно"))
    }
}
