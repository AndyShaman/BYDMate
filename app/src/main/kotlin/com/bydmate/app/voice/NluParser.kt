package com.bydmate.app.voice

import android.util.Log

sealed interface ParseResult {
    /** One utterance can run several dispatchable commands (both seats, the windows "кроме"
     *  one, "A и B"). Single-command callers keep using [command]. */
    data class Command(val commands: List<String>) : ParseResult {
        constructor(command: String) : this(listOf(command))
        val command: String? get() = commands.singleOrNull()
    }
    data class RelativeTemp(val sign: Int) : ParseResult
    data class Volume(val payload: String) : ParseResult
    data object Unrecognized : ParseResult
}

/**
 * Offline Russian voice commands. A phrase runs only when it equals, word for word after
 * normalization, a phrase of the built-in dictionary ([VoiceDictionary]). "A и B" / "A а также B"
 * runs when every part is a dictionary command of its own; anything else is
 * [ParseResult.Unrecognized] and goes to the agent. Deterministic and pure.
 */
object NluParser {

    private const val TAG = "NluParser"

    // A corrupt resource must never surface as internal_error: a failed load is logged once
    // here (by "by lazy") and every phrase after it goes to the agent instead, same as one the
    // dictionary itself does not recognize.
    private val dictionary: Result<VoiceDictionary> by lazy {
        runCatching { VoiceDictionary.load() }.onFailure { Log.e(TAG, "dictionary failed to load", it) }
    }

    /** Load the dictionary ahead of the first command so it never waits on the parse. */
    fun warmUp() {
        dictionary
    }

    fun parse(text: String): ParseResult = parse(text, dictionary)

    /** [dict] is the loaded (or failed) dictionary, exposed here so a failed load can be
     *  exercised without pointing [NluParser] at a broken resource. */
    internal fun parse(text: String, dict: Result<VoiceDictionary>): ParseResult {
        val dictionary = dict.getOrNull() ?: return ParseResult.Unrecognized
        val words = VoiceDictionary.words(text)
        if (words.isEmpty()) return ParseResult.Unrecognized
        return dictionary.match(words) ?: compound(words, dictionary) ?: ParseResult.Unrecognized
    }

    /** Every part between «и» / «а также» as its commands, or null when some part is not a
     *  command of its own (a step, the volume and a verbless phrase never are). */
    private fun compound(words: List<String>, dictionary: VoiceDictionary): ParseResult? {
        val parts = split(words)
        if (parts.size < 2) return null
        val commands = parts.map { part ->
            (dictionary.match(part, inCompound = true) as? ParseResult.Command)?.commands ?: return null
        }
        return ParseResult.Command(commands.flatten())
    }

    private fun split(words: List<String>): List<List<String>> {
        val parts = mutableListOf(mutableListOf<String>())
        var i = 0
        while (i < words.size) {
            val also = words[i] == "а" && words.getOrNull(i + 1) == "также"
            if (words[i] == "и" || also) parts.add(mutableListOf()) else parts.last().add(words[i])
            i += if (also) 2 else 1
        }
        return parts
    }
}
