package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceNormalizerTest {

    private fun d(text: String) = VoiceNormalizer.digits(VoiceNormalizer.tokens(text))

    @Test fun tokens_fold_yo_split_hyphens_and_percent() {
        assertEquals(listOf("открой", "окно", "чуть", "чуть"), VoiceNormalizer.tokens("Открой окно чуть-чуть!"))
        assertEquals(listOf("все", "стекла"), VoiceNormalizer.tokens("всё стёкла"))
        assertEquals(listOf("на", "50", "процентов"), VoiceNormalizer.tokens("на 50%"))
        assertEquals(listOf("открой", "пол", "окна"), VoiceNormalizer.tokens("открой полокна"))
        assertEquals(listOf("полностью"), VoiceNormalizer.tokens("полностью"))
    }

    @Test fun number_words_become_digits_tens_and_units_composed() {
        assertEquals(listOf("0"), d("ноль"))
        assertEquals(listOf("7"), d("семь"))
        assertEquals(listOf("15"), d("пятнадцать"))
        assertEquals(listOf("24"), d("двадцать четыре"))
        assertEquals(listOf("50"), d("пятьдесят"))
        assertEquals(listOf("99"), d("девяносто девять"))
        assertEquals(listOf("100"), d("сто"))
        assertEquals(listOf("до", "50"), d("до пятидесяти"))
        assertEquals(listOf("22"), d("22"))
        assertEquals(listOf("на", "3", "четверти"), d("на три четверти"))
        assertEquals(listOf("20", "0"), d("двадцать ноль"))
    }

    @Test fun other_words_pass_unchanged() {
        assertEquals(listOf("проветри", "окно"), d("проветри окно"))
        assertEquals(listOf("третий", "уровень"), d("третий уровень"))
        assertEquals(listOf("громкость", "50"), d("громкость пятьдесят"))
    }

    @Test fun number_reads_short_digit_tokens_only() {
        assertEquals(50, VoiceNormalizer.number("50"))
        assertNull(VoiceNormalizer.number("пятьдесят"))
        assertNull(VoiceNormalizer.number("1000"))
        assertNull(VoiceNormalizer.number("5a"))
    }
}
