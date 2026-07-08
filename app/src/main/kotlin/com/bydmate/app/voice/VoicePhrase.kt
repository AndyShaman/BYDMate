package com.bydmate.app.voice

/**
 * Normalizes a spoken phrase for collision detection and matching.
 * Lowercase, strip non-letter/digit, split, stem each token (same VoiceStemmer the
 * recognizer uses), join with a single space. Pure and deterministic, so inflected
 * variants ("форточка"/"форточки") normalize to the same string.
 */
object VoicePhrase {
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd} ]")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(phrase: String): String =
        phrase.lowercase()
            .replace(NON_WORD, " ")
            .split(WHITESPACE)
            .filter { it.isNotBlank() }
            .joinToString(" ") { VoiceStemmer.stem(it) }
}
