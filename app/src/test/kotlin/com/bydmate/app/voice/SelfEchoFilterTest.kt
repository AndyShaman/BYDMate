package com.bydmate.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfEchoFilterTest {

    @Test
    fun `exact repeat of recent TTS phrase is echo`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("маршрут проложи")
        assertTrue(filter.isEcho("маршрут проложи"))
    }

    @Test
    fun `exact repeat case and punctuation insensitive`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("Маршрут проложи")
        assertTrue(filter.isEcho("маршрут проложи!"))
    }

    @Test
    fun `exact repeat yo-insensitive`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("Готово, что-нибудь ещё?")
        assertTrue(filter.isEcho("готово что нибудь еще"))
    }

    @Test
    fun `torn fragment with containment above threshold is echo`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("Давай без этой драмы, я просто ЖЭЗ с характером. Маршрут проложи.")
        // spoken words: давай без этой драмы я просто жэз с характером маршрут проложи
        // transcript words: маршрут проложи без этой драмы (5 words)
        // intersection: маршрут проложи без этой драмы (5 words)
        // containment = 5/5 = 1.0 >= 0.75 ✓
        assertTrue(filter.isEcho("маршрут проложи без этой драмы"))
    }

    @Test
    fun `real command not matching spoken text is not echo`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("Готово. Что-нибудь ещё?")
        assertFalse(filter.isEcho("включи кондиционер"))
    }

    @Test
    fun `single word command 5 seconds after playback end is not echo`() {
        var time = 0L
        val filter = SelfEchoFilter(now = { time })
        time = 0L
        filter.noteSpoken("да")
        time = 100L
        filter.onPlaybackEnd()
        // 5 seconds = 5100 ms total, SHORT_ECHO_WINDOW_MS = 1500 ms from playback end
        time = 5100L
        assertFalse(filter.isEcho("да"))
    }

    @Test
    fun `single word with length 4 or more within 1_5s after playback end is echo`() {
        var time = 0L
        val filter = SelfEchoFilter(now = { time })
        time = 0L
        filter.noteSpoken("включи кондиционер")
        time = 100L
        filter.onPlaybackEnd()
        // transcript word "включи" (length 6) is in phrase, 1000ms from playback end (less than 1500ms)
        time = 1100L
        assertTrue(filter.isEcho("включи"))
    }

    @Test
    fun `single word with length 4 or more outside 1_5s window is not echo`() {
        var time = 0L
        val filter = SelfEchoFilter(now = { time })
        time = 0L
        filter.noteSpoken("включи кондиционер")
        time = 100L
        filter.onPlaybackEnd()
        // 2000ms greater than 1500ms window
        time = 2100L
        assertFalse(filter.isEcho("включи"))
    }

    @Test
    fun `single word with length less than 4 is never echo via short window`() {
        var time = 0L
        val filter = SelfEchoFilter(now = { time })
        time = 0L
        filter.noteSpoken("да, хорошо")
        time = 100L
        filter.onPlaybackEnd()
        // "да" is length 2 < 4
        time = 500L
        assertFalse(filter.isEcho("да"))
    }

    @Test
    fun `phrase older than TTL is not echo`() {
        var time = 0L
        val filter = SelfEchoFilter(now = { time })
        time = 0L
        filter.noteSpoken("маршрут проложи")
        // PHRASE_TTL_MS = 20_000
        time = 20001L  // older than TTL
        assertFalse(filter.isEcho("маршрут проложи"))
    }

    @Test
    fun `phrase within TTL is echo`() {
        var time = 0L
        val filter = SelfEchoFilter(now = { time })
        time = 0L
        filter.noteSpoken("маршрут проложи")
        time = 19999L  // still within 20_000 ms TTL
        assertTrue(filter.isEcho("маршрут проложи"))
    }

    @Test
    fun `buffer holds only 6 phrases max`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("phrase1")
        filter.noteSpoken("phrase2")
        filter.noteSpoken("phrase3")
        filter.noteSpoken("phrase4")
        filter.noteSpoken("phrase5")
        filter.noteSpoken("phrase6")
        filter.noteSpoken("phrase7")  // pushes out phrase1

        assertFalse(filter.isEcho("phrase1"))  // no longer in buffer
        assertTrue(filter.isEcho("phrase2"))   // still in buffer
    }

    @Test
    fun `empty transcript after normalization is garbage`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("something")
        assertTrue(filter.isEcho("!!!"))  // only punctuation, empty after normalization
        assertTrue(filter.isEcho("   "))  // only spaces, empty after normalization
        assertTrue(filter.isEcho(""))     // empty
    }

    @Test
    fun `containment calculation with multiset minimum`() {
        val filter = SelfEchoFilter(now = { 0L })
        // words in spoken: открой окно открой (open, window, open)
        filter.noteSpoken("открой окно открой")
        // words in transcript: открой открой открой (open, open, open)
        // intersection multiset: min(2,3) open + min(1,0) окно = 2 opens
        // containment = 2 / 3 = 0.667 < 0.75, NOT echo
        assertFalse(filter.isEcho("открой открой открой"))
    }

    @Test
    fun `containment just meets threshold`() {
        val filter = SelfEchoFilter(now = { 0L })
        // Create phrase where exactly 3 of 4 words match
        // spoken: "раз два три четыре пять" (words: раз два три четыре пять)
        filter.noteSpoken("раз два три четыре пять")
        // transcript: "раз два три" (3 words, all in spoken)
        // containment = 3/3 = 1.0 >= 0.75 ✓
        assertTrue(filter.isEcho("раз два три"))
    }

    @Test
    fun `containment below threshold is not echo`() {
        val filter = SelfEchoFilter(now = { 0L })
        // spoken: "раз два три четыре пять" (5 words)
        filter.noteSpoken("раз два три четыре пять")
        // transcript: "раз два" (2 words, both in spoken)
        // containment = 2/2 = 1.0 >= 0.75, should be echo with 2+ words
        // Let's try: "раз окно" (2 words, 1 in spoken)
        // containment = 1/2 = 0.5 < 0.75
        assertFalse(filter.isEcho("раз окно"))
    }
}
