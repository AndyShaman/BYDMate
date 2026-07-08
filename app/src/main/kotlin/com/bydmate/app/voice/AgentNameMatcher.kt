package com.bydmate.app.voice

/** Wake-name matching over ASR transcripts. Tokens are normalized (lowercase, ё->е,
 *  letters/digits only); a token matches the name exactly, or within Levenshtein
 *  distance 1 when the normalized name is 4+ chars AND token length differs by at
 *  most 1 (ASR near-misses like "лио" for "лео" come from vowel confusion). */
object AgentNameMatcher {
    fun matches(transcript: String, name: String): Boolean {
        val target = normalize(name)
        if (target.isEmpty()) return false
        return tokens(transcript).any { tokenMatches(it, target) }
    }

    /** Drops a leading name token ("Лео, открой окно" -> "открой окно") so NLU/agent see
     *  the command itself; a transcript that is ONLY the name is returned unchanged. */
    fun stripLeadingName(transcript: String, name: String): String {
        val target = normalize(name)
        if (target.isEmpty()) return transcript
        val raw = transcript.trim().split(Regex("\\s+"))
        if (raw.size < 2 || !tokenMatches(normalize(raw.first()), target)) return transcript
        return raw.drop(1).joinToString(" ")
    }

    private fun normalize(s: String): String =
        s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    private fun tokens(s: String): List<String> =
        s.split(Regex("\\s+")).map(::normalize).filter { it.isNotEmpty() }

    private fun tokenMatches(token: String, target: String): Boolean {
        if (token == target) return true
        if (target.length < 4) return false
        if (kotlin.math.abs(token.length - target.length) > 1) return false
        return levenshtein(token, target) <= 1
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            cur.copyInto(prev)
        }
        return prev[b.length]
    }
}
