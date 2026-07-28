package com.bydmate.app.media

/**
 * Validation for the two URL fields of a radio station. Pure Kotlin (no Android types) so the
 * edit dialog can enable/disable "Save" on every keystroke and the rules stay unit-testable.
 */
object RadioUrlValidator {

    private val STREAM_SCHEMES = listOf("http://", "https://", "rtsp://")
    // "bydmate://preset/" is the in-app monogram icon carried by the built-in stations — editing
    // such a station must not flag its icon field as invalid.
    private val ICON_SCHEMES =
        listOf("http://", "https://", "content://", "file://", "bydmate://preset/")

    /** A playable stream URL: an http(s)/rtsp scheme followed by a non-empty host. */
    fun isValidStreamUrl(raw: String): Boolean = hasScheme(raw, STREAM_SCHEMES)

    /** An icon reference: a remote URL or a local content://file:// URI. Blank means "no icon". */
    fun isValidIconUrl(raw: String): Boolean = raw.isBlank() || hasScheme(raw, ICON_SCHEMES)

    private fun hasScheme(raw: String, schemes: List<String>): Boolean {
        val text = raw.trim()
        if (text.contains(' ')) return false
        val scheme = schemes.firstOrNull { text.startsWith(it, ignoreCase = true) } ?: return false
        return text.length > scheme.length
    }
}
