package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSpellingTest {

    private fun fix(text: String) = VoiceSpelling.correct(VoiceNormalizer.tokens(text)).joinToString(" ")

    @Test fun known_asr_slips() {
        assertEquals("включи подогрев руля", fix("включи подогрев роля"))
        assertEquals("руль", fix("роль"))
    }

    @Test fun one_edit_on_long_device_words() {
        assertEquals("включи кондиционер", fix("включи кондиционр"))
        assertEquals("выключи кондиционер", fix("выключи кандиционер"))
        assertEquals("открой багажник", fix("открой багажнек"))
        assertEquals("включи обогрев зеркало", fix("включи обогрев зеркла"))
    }

    @Test fun short_and_known_words_are_left_alone() {
        assertEquals("закрой крышку", fix("закрой крышку"))
        assertEquals("открой окна", fix("открой окна"))
        assertEquals("какая погода", fix("какая погода"))
    }

    // "открыта" is one ending away from "открыть": a state, not a slip; guessing it
    // would unlock the car on "машина открыта".
    @Test fun verb_state_forms_are_not_guessed() {
        assertEquals("машина открыта", fix("машина открыта"))
        assertEquals("окно закрыто", fix("окно закрыто"))
    }

    // Verbs are never fuzzy-matched: "откроем" is one edit from "открыть" and talks about later.
    @Test fun verbs_are_never_guessed() {
        assertEquals("мы откроем багажник завтра", fix("мы откроем багажник завтра"))
        assertEquals("закроем окна", fix("закроем окна"))
        assertEquals("включи кондиционер", fix("включи кондиционр"))
    }

    @Test fun comparatives_are_not_guessed() {
        assertEquals("говори громко", fix("говори громко"))
    }
}
