package com.bydmate.app.voice

/**
 * Text-based self-echo filter for voice agent ASR transcripts.
 *
 * Detects and filters out ASR transcripts that are echoes of recently spoken TTS phrases.
 * Root cause: HAL audio position is unreliable on DiLink (APK 341); agent's own speech
 * is occasionally picked up by ASR despite AudioTrack isolation. This text-based filter
 * is industry-standard insurance against self-echo.
 *
 * Algorithm:
 * - Stores recent spoken phrases in a circular buffer (max 6 phrases, 20s TTL each).
 * - Normalizes both transcript and stored phrases (lowercase, remove punctuation, collapse spaces).
 * - For multi-word transcripts (2+ words): checks word containment ratio.
 *   If transcript words are 75%+ contained in any recent phrase → echo.
 * - For single-word transcripts: checks only if word length >= 4 chars and within 1.5s
 *   after playback ends (avoids filtering short driver commands like "да" / "стоп").
 * - Empty transcripts (after normalization) are always treated as garbage echo.
 */
class SelfEchoFilter(private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        private const val MAX_PHRASES = 6
        private const val PHRASE_TTL_MS = 20_000L
        private const val SHORT_ECHO_WINDOW_MS = 1_500L
        private const val CONTAINMENT_THRESHOLD = 0.75
    }

    private data class SpokenPhrase(
        val text: String,
        val normalizedWords: List<String>,
        val spokenAtMs: Long
    )

    private val phraseBuffer = mutableListOf<SpokenPhrase>()
    private var playbackEndMs = 0L

    /**
     * Records a spoken TTS phrase at current time.
     * If buffer is full, oldest phrase is discarded.
     */
    fun noteSpoken(text: String) {
        if (phraseBuffer.size >= MAX_PHRASES) {
            phraseBuffer.removeAt(0)
        }
        val normalizedWords = normalize(text)
        phraseBuffer.add(SpokenPhrase(text, normalizedWords, now()))
    }

    /**
     * Marks the end of TTS playback at current time.
     * Used to enforce SHORT_ECHO_WINDOW_MS for single-word transcripts.
     */
    fun onPlaybackEnd() {
        playbackEndMs = now()
    }

    /**
     * Checks if the given ASR transcript is likely a self-echo.
     *
     * Returns true if:
     * - Transcript is empty after normalization (garbage), OR
     * - Transcript has 2+ words with containment ratio >= 75% in any active phrase, OR
     * - Transcript is a single word with length >= 4 chars, found in an active phrase,
     *   and within 1.5s after playback end.
     *
     * Returns false otherwise (treat as legitimate user command).
     */
    fun isEcho(transcript: String): Boolean {
        val transcriptWords = normalize(transcript)

        // Empty after normalization = garbage
        if (transcriptWords.isEmpty()) {
            return true
        }

        val currentTimeMs = now()

        // Check each active phrase in buffer
        for (phrase in phraseBuffer) {
            // Phrase is active only within TTL
            if (currentTimeMs - phrase.spokenAtMs > PHRASE_TTL_MS) {
                continue
            }

            // Multi-word (2+ words) case: check containment ratio
            if (transcriptWords.size >= 2) {
                val containment = calculateContainment(transcriptWords, phrase.normalizedWords)
                if (containment >= CONTAINMENT_THRESHOLD) {
                    return true
                }
            }

            // Single-word case: check length >= 4 and within SHORT_ECHO_WINDOW
            if (transcriptWords.size == 1) {
                val word = transcriptWords[0]
                if (word.length >= 4 && phrase.normalizedWords.contains(word)) {
                    val timeSincePlaybackEnd = currentTimeMs - playbackEndMs
                    if (timeSincePlaybackEnd < SHORT_ECHO_WINDOW_MS) {
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * Calculates word containment ratio: intersection / transcript word count.
     * Uses multiset semantics (counts minimum occurrence in both lists).
     */
    private fun calculateContainment(transcriptWords: List<String>, phraseWords: List<String>): Double {
        if (transcriptWords.isEmpty()) return 0.0

        // Count word frequencies in phrase
        val phraseFreq = mutableMapOf<String, Int>()
        for (word in phraseWords) {
            phraseFreq[word] = phraseFreq.getOrDefault(word, 0) + 1
        }

        // Count intersection (multiset minimum)
        var intersectionCount = 0
        for (word in transcriptWords) {
            if (phraseFreq.containsKey(word) && phraseFreq[word]!! > 0) {
                intersectionCount++
                phraseFreq[word] = phraseFreq[word]!! - 1
            }
        }

        return intersectionCount.toDouble() / transcriptWords.size
    }

    /**
     * Normalizes text: lowercase, replace ё→е, remove punctuation, collapse spaces, split into words.
     */
    private fun normalize(text: String): List<String> {
        return text
            .lowercase()
            .replace("ё", "е")
            .replace(Regex("[^а-яa-z0-9\\s]"), " ")  // remove punctuation
            .split(Regex("\\s+"))                     // split on whitespace
            .filter { it.isNotEmpty() }               // remove empty tokens
    }
}
