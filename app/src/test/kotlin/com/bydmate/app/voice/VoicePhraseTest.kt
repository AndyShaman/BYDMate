package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePhraseTest {
    // Collisions are as exact as matching: another inflection is another phrase.
    @Test fun `inflections normalize apart`() {
        assertNotEquals(VoicePhrase.normalize("форточка"), VoicePhrase.normalize("форточки"))
        assertNotEquals(VoicePhrase.normalize("открой окно"), VoicePhrase.normalize("открой окна"))
    }
    @Test fun `strips punctuation and case and collapses spaces`() {
        assertEquals("поехали домой", VoicePhrase.normalize("  Поехали, домой!  "))
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
