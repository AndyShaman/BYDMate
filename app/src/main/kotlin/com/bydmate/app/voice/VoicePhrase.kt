package com.bydmate.app.voice

import java.util.Collections

/**
 * Normalizes a spoken phrase for collision detection and matching.
 * Lowercase, ё→е, strip non-letter/digit, split, drop filler words, stem each token (same
 * VoiceStemmer the recognizer uses), join with a single space. Pure and deterministic, so
 * inflected variants ("форточка"/"форточки") normalize to the same string.
 */
object VoicePhrase {
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd} ]")
    private val WHITESPACE = Regex("\\s+")

    // Politeness and address words that never change what the driver asked for.
    private val FILLERS = setOf("пожалуйста", "можешь", "мне", "слушай", "эй")

    fun normalize(phrase: String): String = tokens(phrase).joinToString(" ")

    /** The normalized tokens of [phrase], in spoken order. */
    fun tokens(phrase: String): List<String> =
        phrase.lowercase()
            .replace('ё', 'е')
            .replace(NON_WORD, " ")
            .split(WHITESPACE)
            .filter { it.isNotBlank() && it !in FILLERS }
            .map { VoiceStemmer.stem(it) }

    /** True when [phrase] (normalized tokens) occurs in [heard] as a whole-word sequence,
     *  equality included. Token-wise, so «окно» never matches inside «окновать». */
    fun containsSequence(heard: List<String>, phrase: List<String>): Boolean =
        phrase.isNotEmpty() && Collections.indexOfSubList(heard, phrase) >= 0
}
