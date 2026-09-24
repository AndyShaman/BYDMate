package com.bydmate.app.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLexiconTest {
    @Test fun ru_window_synonyms_present() {
        val words = VoiceLexicon.deviceWords()[DeviceSlot.WINDOW_DRIVER].orEmpty()
        // forms the user gave: окно / стекло / форточка / окошко (driver-qualified handled by qualifiers)
        assertTrue(words.containsAll(listOf("окно", "стекло", "форточка", "окошко")))
    }

    @Test fun ru_open_and_close_are_disjoint() {
        val open = VoiceLexicon.actionWords()[ActionSlot.OPEN].orEmpty().toSet()
        val close = VoiceLexicon.actionWords()[ActionSlot.CLOSE].orEmpty().toSet()
        assertTrue("open/close must not share words", open.intersect(close).isEmpty())
    }

    @Test fun lexicon_is_russian_only() {
        val words = (VoiceLexicon.actionWords().values + VoiceLexicon.deviceWords().values).flatten()
        assertTrue(words.filter { w -> w.any { it in 'a'..'z' } }.toString(), words.none { w -> w.any { it in 'a'..'z' } })
    }

    @Test fun lexicon_words_use_e_not_yo() {
        val words = (VoiceLexicon.actionWords().values + VoiceLexicon.deviceWords().values).flatten()
        assertTrue(words.none { 'ё' in it })
    }
}
