package com.bydmate.app.voice

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

    private val dictionary: VoiceDictionary by lazy { VoiceDictionary.load() }

    fun parse(text: String): ParseResult {
        val words = VoiceDictionary.words(text)
        if (words.isEmpty()) return ParseResult.Unrecognized
        return dictionary.match(words) ?: compound(words) ?: ParseResult.Unrecognized
    }

    /** Every part between «и» / «а также» as its commands, or null when some part is not a
     *  command of its own (a step, the volume and a verbless phrase never are). */
    private fun compound(words: List<String>): ParseResult? {
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
