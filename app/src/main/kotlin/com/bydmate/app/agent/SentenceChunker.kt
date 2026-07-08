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

    private companion object {
        private val TERMINATORS = setOf('.', '!', '?', '…')
    }
}
