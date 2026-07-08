package com.bydmate.app.voice

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Dictionary-based Russian stress marking for offline voices: VITS_MULTI needs '+' before the
 *  stressed vowel (the model reads '+' as a stress marker, tokens.txt id 40), while Supertonic
 *  is char-based and needs the stressed vowel uppercased because '+' and U+0301 are spoken
 *  incorrectly by that model family.
 *
 *  Backed by stress.tsv shipped inside the voice archive -- format "wordform<TAB>N", lowercase,
 *  N = 1-based index of the stressed vowel, bytewise-sorted, UTF-8, ~529k lines / ~10 MB. Loaded
 *  as one ByteArray (whole file) + IntArray of line-start offsets and looked up via binary
 *  search over the raw UTF-8 bytes -- no HashMap, no per-entry String allocation; the file is
 *  too large for that to be free.
 *
 *  [preload] loads the dictionary on a background thread it owns, so [mark] can be called from
 *  the tts worker thread on the very first reply without paying the load latency there. [mark]
 *  only ever reads the (volatile) loaded snapshot: it returns the text unchanged until loading
 *  finishes, and forever if the file is missing or unreadable (fail-soft, like the rest of the
 *  TTS tract). */
class RuStressMarker(private val dictFile: () -> File?) {

    enum class Style { PLUS, UPPERCASE }

    @Volatile private var dictionary: Dictionary? = null
    @Volatile private var loadAttempted = false
    private val loadStarted = AtomicBoolean(false)

    /** Starts the background load once after success; failed/missing loads reset the gate so a
     *  later self-heal dictionary download can call [preload] again and become effective. */
    fun preload() {
        if (!loadStarted.compareAndSet(false, true)) return
        Thread({ load() }, "stress-dict-loader").apply { isDaemon = true }.start()
    }

    private fun load() {
        try {
            val file = dictFile() ?: return
            val bytes = file.readBytes()
            dictionary = Dictionary(bytes, lineStarts(bytes))
        } catch (t: Throwable) {
            Log.w(TAG, "failed to load stress dictionary", t)
        } finally {
            if (dictionary == null) loadStarted.set(false)
            loadAttempted = true
        }
    }

    /** Test seam: polls whether the background load has produced a usable dictionary. */
    internal fun isLoadedForTest(): Boolean = dictionary != null

    /** Test seam: polls whether the background load has finished (successfully or not),
     *  distinct from [isLoadedForTest] so a missing-file scenario can be waited on too. */
    internal fun loadAttemptedForTest(): Boolean = loadAttempted

    fun mark(text: String, style: Style = Style.PLUS): String {
        val dict = dictionary ?: return text
        val out = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            if (isWordChar(text[i])) {
                var j = i + 1
                while (j < text.length && isWordChar(text[j])) j++
                out.append(markWord(dict, text.substring(i, j), style))
                i = j
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private fun markWord(dict: Dictionary, word: String, style: Style): String {
        if (word.any { it == '+' || it == 'ё' || it == 'Ё' }) return word
        val vowelPositions = word.indices.filter { isVowel(word[it]) }
        if (vowelPositions.size <= 1) return word
        val n = find(dict.bytes, dict.lineStarts, word.lowercase())
        if (n < 1 || n > vowelPositions.size) return word
        val at = vowelPositions[n - 1]
        return when (style) {
            Style.PLUS -> word.substring(0, at) + "+" + word.substring(at)
            Style.UPPERCASE -> {
                val stressed = word[at]
                if (stressed.isUpperCase()) word
                else word.substring(0, at) + stressed.uppercaseChar() + word.substring(at + 1)
            }
        }
    }

    private class Dictionary(val bytes: ByteArray, val lineStarts: IntArray)

    companion object {
        private const val TAG = "RuStressMarker"
        private const val TAB: Byte = '\t'.code.toByte()
        private const val NEWLINE: Byte = '\n'.code.toByte()
        private const val VOWELS = "аеиоуыэюя"

        private fun isWordChar(c: Char) = c == '+' || c == 'ё' || c == 'Ё' || c in 'а'..'я' || c in 'А'..'Я'

        private fun isVowel(c: Char) = c.lowercaseChar() in VOWELS

        /** Line-start byte offsets: 0, plus the byte right after every '\n' that is not the
         *  file's trailing newline (no phantom empty final line). */
        internal fun lineStarts(bytes: ByteArray): IntArray {
            if (bytes.isEmpty()) return IntArray(0)
            val starts = mutableListOf(0)
            for (i in bytes.indices) {
                if (bytes[i] == NEWLINE && i + 1 < bytes.size) starts.add(i + 1)
            }
            return starts.toIntArray()
        }

        /** Binary search over [lineStarts] for the line whose key (bytes up to the first tab)
         *  equals [key]; returns its N value, or -1 if not found. */
        internal fun find(bytes: ByteArray, lineStarts: IntArray, key: String): Int {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            var lo = 0
            var hi = lineStarts.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val cmp = compareKey(bytes, lineStarts[mid], keyBytes)
                when {
                    cmp < 0 -> lo = mid + 1
                    cmp > 0 -> hi = mid - 1
                    else -> return parseValue(bytes, lineStarts[mid])
                }
            }
            return -1
        }

        /** Compares the dictionary line starting at [lineStart] (its key, up to the first tab)
         *  against [key] byte by byte -- no substring/String allocation per comparison. */
        private fun compareKey(bytes: ByteArray, lineStart: Int, key: ByteArray): Int {
            var i = lineStart
            var j = 0
            while (i < bytes.size && bytes[i] != TAB && j < key.size) {
                val diff = (bytes[i].toInt() and 0xFF) - (key[j].toInt() and 0xFF)
                if (diff != 0) return diff
                i++; j++
            }
            val lineKeyEnded = i >= bytes.size || bytes[i] == TAB
            val keyEnded = j >= key.size
            return when {
                lineKeyEnded && keyEnded -> 0
                lineKeyEnded -> -1
                else -> 1
            }
        }

        /** Parses the ASCII digits after the tab on the line starting at [lineStart]. */
        private fun parseValue(bytes: ByteArray, lineStart: Int): Int {
            var i = lineStart
            while (i < bytes.size && bytes[i] != TAB) i++
            i++
            var n = 0
            while (i < bytes.size && bytes[i] != NEWLINE) {
                val d = bytes[i] - '0'.code.toByte()
                if (d in 0..9) n = n * 10 + d
                i++
            }
            return n
        }
    }
}
