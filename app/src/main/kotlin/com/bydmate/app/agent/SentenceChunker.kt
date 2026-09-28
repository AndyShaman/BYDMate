package com.bydmate.app.agent

/**
 * Incremental sentence segmenter for streamed LLM text. Feed deltas as they arrive;
 * complete sentences are returned as soon as their boundary is visible. A boundary is a
 * run of terminators ([.!?…]) followed by whitespace, or a newline. Decimals ("22.5")
 * never split because the dot is not followed by whitespace. A terminator at the very
 * end of the buffer waits for the next delta to decide. flush() returns the trimmed
 * unterminated tail (or null) and resets the buffer.
 */
class SentenceChunker {

    private val buf = StringBuilder()

    fun feed(delta: String): List<String> {
        buf.append(delta)
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            if (c == '\n') {
                emit(out, start, i)
                start = i + 1
            } else if (c in TERMINATORS) {
                var j = i
                while (j + 1 < buf.length && buf[j + 1] in TERMINATORS) j++
                if (j + 1 < buf.length && buf[j + 1].isWhitespace()) {
                    emit(out, start, j + 1)
                    start = j + 1
                }
                i = j
            } else if (isSoftBoundary(c, i, start)) {
                emit(out, start, i + 1)
                start = i + 1
            } else if (isMaxLengthBoundary(c, i, start)) {
                // Some models return long list-like answers with no sentence-ending dot until
                // the very end. Do not make offline TTS synthesize the entire answer before
                // playback starts: cut at a safe whitespace boundary.
                emit(out, start, i)
                start = i + 1
            }
            i++
        }
        if (start > 0) buf.delete(0, start)
        return out
    }

    fun flush(): String? {
        val tail = buf.toString().trim()
        buf.setLength(0)
        return tail.ifEmpty { null }
    }

    private fun emit(out: MutableList<String>, start: Int, endExclusive: Int) {
        val s = buf.substring(start, endExclusive).trim()
        if (s.isNotEmpty()) out += s
    }

    private fun isSoftBoundary(c: Char, index: Int, start: Int): Boolean {
        if (c !in SOFT_TERMINATORS) return false
        if (index - start + 1 < MIN_SOFT_CHUNK) return false
        return index + 1 < buf.length && buf[index + 1].isWhitespace()
    }

    private fun isMaxLengthBoundary(c: Char, index: Int, start: Int): Boolean =
        index - start + 1 >= MAX_CHUNK && c.isWhitespace()

    private companion object {
        private val TERMINATORS = setOf('.', '!', '?', '…')
        private val SOFT_TERMINATORS = setOf(';', ':')
        private const val MIN_SOFT_CHUNK = 24
        private const val MAX_CHUNK = 80
    }
}
