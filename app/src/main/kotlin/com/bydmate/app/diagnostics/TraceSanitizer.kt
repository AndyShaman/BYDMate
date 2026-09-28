package com.bydmate.app.diagnostics

/**
 * The single gate every trace value passes. The trace keeps the cause of what happened (codes,
 * hosts, HTTP statuses, exception classes, durations, package and window class names), never
 * the data: text anyone said or wrote, coordinates, links with a path or query, phone numbers.
 * Values the gate cannot tell apart from data are replaced, not escaped, so a careless call
 * site loses detail instead of leaking it.
 *
 * Output is plain printable ASCII without spaces, so a value can never break the
 * `key=value` line format or the ring's byte accounting.
 */
internal object TraceSanitizer {

    const val MAX_VALUE_CHARS = 80
    private const val MAX_KEY_CHARS = 24
    private const val CUT_MARK = "~"

    // Every trace value the app legitimately writes is ASCII: Cyrillic or any other script
    // means a transcript, an answer, a place or a contact name.
    private const val TEXT = "<text>"

    private val URL = Regex("""(?i)\b[a-z][a-z0-9+.-]*://(?:[^\s/?#@]*@)?([^\s/?#:]+)(?::\d+)?([/?#]\S*)?""")
    private val HOST_WITH_PATH = Regex("""(?i)\b((?:[a-z0-9-]+\.)+[a-z]{2,})(?::\d+)?[/?]\S*""")
    private val GEO_URI = Regex("""(?i)\bgeo:\S*""")
    private val COORD_PAIR = Regex("""-?\d{1,3}\.\d{3,}\s*[,;]?\s*-?\d{1,3}\.\d{3,}""")
    private val PRECISE_NUMBER = Regex("""-?\d{1,3}\.\d{4,}""")
    private val PHONE_INTL = Regex("""\+\d[\d\s()-]{8,}\d""")
    private val PHONE_RU = Regex("""\b[78]\d{10}\b""")
    private val BLANK = Regex("""[\s\p{Cntrl}]+""")

    /** [value] as it may appear in a trace line. */
    fun value(value: Any): String {
        val raw = when (value) {
            is Int, is Boolean -> return value.toString()
            is Enum<*> -> value.name.lowercase()
            else -> value.toString()
        }
        return cap(clean(raw))
    }

    /** Keys are code literals; anything outside `[a-z0-9_-]` is dropped. */
    fun key(key: String): String =
        key.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
            .take(MAX_KEY_CHARS).ifEmpty { "k" }

    private fun clean(raw: String): String {
        if (raw.any { it.code > ASCII_LAST }) return TEXT
        // Fast path for the common case: a code word has nothing any rule below could match.
        if (raw.all { it.isLetter() || it == '_' || it == '-' }) return raw.ifEmpty { "-" }
        var s = raw
        s = URL.replace(s) { m -> m.groupValues[1] + if (m.groupValues[2].isNotEmpty()) "/..." else "" }
        s = HOST_WITH_PATH.replace(s) { m -> m.groupValues[1] + "/..." }
        s = GEO_URI.replace(s, "geo:...")
        s = COORD_PAIR.replace(s, "<coords>")
        s = PRECISE_NUMBER.replace(s, "<num>")
        s = PHONE_INTL.replace(s, "<phone>")
        s = PHONE_RU.replace(s, "<phone>")
        s = BLANK.replace(s, "_")
        return s.ifEmpty { "-" }
    }

    private fun cap(s: String): String =
        if (s.length <= MAX_VALUE_CHARS) s else s.take(MAX_VALUE_CHARS - CUT_MARK.length) + CUT_MARK

    private const val ASCII_LAST = 0x7E
}
