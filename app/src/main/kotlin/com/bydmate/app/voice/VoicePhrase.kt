package com.bydmate.app.voice

/**
 * Normalizes a spoken phrase for collision detection and matching.
 * Lowercase, ё→е, strip non-letter/digit, split, drop filler words, join with a single space.
 * No stemming: «открой окно» and «открой окна» are different phrases, for matching and for
 * collisions alike. Pure and deterministic.
 */
object VoicePhrase {
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd} ]")
    private val WHITESPACE = Regex("\\s+")

    // Politeness and address words that never change what the driver asked for.
    internal val FILLERS = setOf("пожалуйста", "можешь", "мне", "слушай", "эй")

    /** The key two phrases collide on: equal exactly when [isExact] holds between them. */
    fun normalize(phrase: String): String = words(phrase).joinToString(" ")

    /** The words of [phrase] as spoken, without stemming: lowercase, ё→е, no punctuation, no
     *  fillers. Matching and collisions are judged on these, so «открой окна» is not «открой окно». */
    fun words(phrase: String): List<String> =
        phrase.lowercase()
            .replace('ё', 'е')
            .replace(NON_WORD, " ")
            .split(WHITESPACE)
            .filter { it.isNotBlank() && it !in FILLERS }

    /** [phrase] is the whole of [heard], word for word after normalization. */
    fun isExact(heard: String, phrase: String): Boolean {
        val words = words(phrase)
        return words.isNotEmpty() && words == words(heard)
    }
}
