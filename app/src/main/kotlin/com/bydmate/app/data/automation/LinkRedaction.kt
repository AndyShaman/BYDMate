package com.bydmate.app.data.automation

/**
 * Links, phone numbers and coordinates out of text that reaches a log or the diagnostic dump:
 * users post both publicly in GitHub issues, so a URL token, a number or home coordinates must
 * not be in them.
 */
internal object LinkRedaction {

    // A link (scheme://…), a bare navigator, map or phone URI (geo:, yandexnavi:, tel:…) or an
    // intent's data (dat=…).
    private val URI_LIKE = Regex(
        """\bdat=\S+|[A-Za-z][A-Za-z0-9+.\-]*://\S+|""" +
            """\b(?:geo|yandexnavi|yandexmaps|dgis|tel|sms|smsto|mailto|intent|content|file):\S+""",
        RegexOption.IGNORE_CASE,
    )

    // A lat,lon or lon,lat pair: two decimal numbers around a comma.
    private val COORDS = Regex("""-?\d{1,3}\.\d+\s*,\s*-?\d{1,3}\.\d+""")

    // Seven or more digits, up to two spaces, dashes or brackets between them (« (29) »),
    // optional leading +.
    private val PHONE = Regex("""\+?\d(?:[\s()\-]{0,2}\d){6,}""")

    /** [text] with every URI-like run replaced by `<uri>` whole: the journal dump. */
    fun redactAll(text: String): String = URI_LIKE.replace(text, "<uri>")

    /**
     * [text] for a log line: a link keeps its scheme, host and path, its query becomes
     * `?<redacted>` and its fragment goes; coordinates become `<coords>` and phone numbers
     * `<phone>`, inside a link or not.
     */
    fun forLog(text: String): String {
        // Numbers first: a phone split by spaces runs past the end of a tel: link.
        val scrubbed = PHONE.replace(COORDS.replace(text, "<coords>"), "<phone>")
        return URI_LIKE.replace(scrubbed) { m ->
            val prefix = if (m.value.startsWith("dat=", ignoreCase = true)) "dat=" else ""
            prefix + linkForLog(m.value.removePrefix(prefix))
        }
    }

    private fun linkForLog(link: String): String {
        val noFragment = link.substringBefore('#')
        val base = noFragment.substringBefore('?')
        return base + if (noFragment.length > base.length) "?<redacted>" else ""
    }
}
