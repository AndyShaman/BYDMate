package com.bydmate.app.voice

import com.bydmate.app.voice.VoiceNormalizer.Extreme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceNormalizerTest {

    private fun m(text: String) = VoiceNormalizer.measure(VoiceNormalizer.tokens(text))

    @Test fun tokens_fold_yo_split_hyphens_and_percent() {
        assertEquals(listOf("открой", "окно", "чуть", "чуть"), VoiceNormalizer.tokens("Открой окно чуть-чуть!"))
        assertEquals(listOf("все", "стекла"), VoiceNormalizer.tokens("всё стёкла"))
        assertEquals(listOf("на", "50", "процентов"), VoiceNormalizer.tokens("на 50%"))
        assertEquals(listOf("открой", "пол", "окна"), VoiceNormalizer.tokens("открой полокна"))
        assertEquals(listOf("полностью"), VoiceNormalizer.tokens("полностью"))
    }

    @Test fun number_words_compose_tens_and_units() {
        assertEquals(listOf(0), m("ноль").numbers)
        assertEquals(listOf(7), m("семь").numbers)
        assertEquals(listOf(15), m("пятнадцать").numbers)
        assertEquals(listOf(24), m("двадцать четыре").numbers)
        assertEquals(listOf(50), m("пятьдесят").numbers)
        assertEquals(listOf(99), m("девяносто девять").numbers)
        assertEquals(listOf(100), m("сто").numbers)
        assertEquals(listOf(50), m("до пятидесяти").numbers)
        assertEquals(listOf(22), m("22").numbers)
    }

    @Test fun numbers_are_whole_tokens_not_substrings() {
        // "проветри" contains "три", "пятьдесят" contains "пять": neither is a number here.
        assertEquals(emptyList<Int>(), m("проветри окно").numbers)
        assertEquals(listOf(50), m("громкость пятьдесят").numbers)
    }

    @Test fun number_is_a_share_after_na_do_or_before_percent() {
        assertTrue(m("открой окно на пятьдесят").numberIsShare)
        assertTrue(m("окно пятьдесят процентов").numberIsShare)
        assertTrue(m("до двадцати процентов").numberIsShare)
        assertFalse(m("открой два окна").numberIsShare)
    }

    @Test fun fractions() {
        for (t in listOf("наполовину", "на половину", "до половины", "на половинку", "пол окна", "окно на пол")) {
            assertEquals(t, 50, m(t).share)
        }
        assertEquals(33, m("на треть").share)
        assertEquals(25, m("на четверть").share)
        assertEquals(75, m("на три четверти").share)
        assertEquals(emptyList<Int>(), m("на три четверти").numbers)
    }

    @Test fun vent_and_full_words() {
        for (t in listOf("чуть чуть", "немного", "слегка", "на щелочку", "щелку")) {
            assertEquals(t, VoiceNormalizer.VENT_SHARE, m(t).share)
        }
        for (t in listOf("полностью", "целиком", "до конца", "на полную")) {
            assertEquals(t, VoiceNormalizer.FULL_SHARE, m(t).share)
        }
        assertTrue(m("приоткрой окно").softVent)
        assertNull(m("приоткрой окно").share)
    }

    @Test fun conflicting_shares_are_flagged() {
        assertTrue(m("полностью наполовину").shareConflict)
        assertNull(m("полностью наполовину").share)
    }

    @Test fun ordinals_and_extremes() {
        assertEquals(3, m("на третий уровень").ordinal)
        assertEquals(2, m("второй").ordinal)
        assertEquals(5, m("пятый").ordinal)
        assertEquals(Extreme.MAX, m("на максимум").extreme)
        assertEquals(Extreme.MIN, m("минимум").extreme)
    }

    // Units ("сантиметров") are simply words no reader explains: the parser does not read them.
    @Test fun unreadable_measures_are_flagged() {
        assertFalse(5 in m("на пять сантиметров").words)
        assertTrue(m("опусти до низа").unexplained)
        assertTrue(m("открой пол").unexplained)
        assertFalse(m("до конца").unexplained)
        assertFalse(m("до половины").unexplained)
        assertFalse(m("до пятидесяти процентов").unexplained)
        assertFalse(m("смотри").unexplained)
    }

    @Test fun measure_words_are_the_ones_the_readers_explain() {
        assertEquals(setOf(2, 3, 4), m("открой на двадцать пять процентов").words)
        assertEquals(setOf(1, 2), m("окно до конца").words)
        assertEquals(setOf(1, 2), m("на три четверти").words)
        assertEquals(emptySet<Int>(), m("на палец").words)
        assertEquals(emptySet<Int>(), m("на процентов").words)
    }
}
