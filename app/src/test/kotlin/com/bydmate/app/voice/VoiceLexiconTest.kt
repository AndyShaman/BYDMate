package com.bydmate.app.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLexiconTest {
    @Test fun ru_window_synonyms_present() {
        val words = VoiceLexicon.deviceWords(VoiceLang.RU)[DeviceSlot.WINDOW_DRIVER].orEmpty()
        // forms the user gave: окно / стекло / форточка (driver-qualified handled by qualifiers)
        assertTrue(words.any { it.contains("окн") || it.contains("стекл") || it.contains("форточк") })
    }

    @Test fun ru_open_and_close_are_disjoint() {
        val open = VoiceLexicon.actionWords(VoiceLang.RU)[ActionSlot.OPEN].orEmpty().toSet()
        val close = VoiceLexicon.actionWords(VoiceLang.RU)[ActionSlot.CLOSE].orEmpty().toSet()
        assertTrue("open/close must not share words", open.intersect(close).isEmpty())
    }

    @Test fun number_words_map_ru() {
        assertTrue(VoiceLexicon.numberWords(VoiceLang.RU)["двадцать"] == 20)
    }

    @Test fun vocabulary_is_nonempty_and_flat() {
        val vocab = VoiceLexicon.vocabulary(VoiceLang.RU)
        assertTrue(vocab.size > 30)
        assertTrue("vocabulary words are single tokens", vocab.all { !it.contains(' ') })
    }

    @Test fun low_number_words_ru() {
        val n = VoiceLexicon.numberWords(VoiceLang.RU)
        assertTrue(n["ноль"] == 0)
        assertTrue(n["один"] == 1)
        assertTrue(n["десять"] == 10)
        assertTrue(n["пятнадцать"] == 15)
    }

    @Test fun low_number_words_en() {
        val n = VoiceLexicon.numberWords(VoiceLang.EN)
        assertTrue(n["zero"] == 0)
        assertTrue(n["one"] == 1)
        assertTrue(n["ten"] == 10)
    }
}
