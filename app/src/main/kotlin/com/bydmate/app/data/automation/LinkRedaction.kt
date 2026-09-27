package com.bydmate.app.data.automation

import java.net.URI
import java.net.URISyntaxException

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

    // A lat,lon or lon,lat pair: two decimal numbers around a comma, in any form Kotlin's
    // Double.toString prints them (1.0E-4 included).
    private const val NUMBER = """-?\d{1,3}\.\d+(?:[eE][+-]?\d+)?"""
    private val COORDS = Regex("""$NUMBER\s*,\s*$NUMBER""")

    // A phone number in free text (a call step's name): five or more digits, up to two spaces,
    // dashes or brackets between them (« (29) »), optional leading +. Never applied inside a
    // link, where a long number is an object id, not a phone.
    private val PHONE = Regex("""\+?\d(?:[\s()\-]{0,2}\d){4,}""")

    // The dispatcher's own labels whose whole value is a number or a place.
    private val PHONE_LABELS = setOf("dial")
    private val COORD_LABELS = setOf("navigate", "navigate_show", "navigate_maps", "navigate_maps_show")
    private val LABEL = Regex("""^([a-z_]+):(.*)$""", RegexOption.DOT_MATCHES_ALL)

    private val PHONE_SCHEMES = setOf("tel", "sms", "smsto")

    /** [text] with every URI-like run replaced by `<uri>` whole: the journal dump. */
    fun redactAll(text: String): String = URI_LIKE.replace(text, "<uri>")

    /**
     * [text] for a log line, by structure: a `dial:` label's value is `<phone>`, a coordinate
     * label's (`navigate:` and the like) is `<coords>`, with a trailing ` (name)` reduced to its
     * length only — the name itself can be a place, a phone or even a link, so none of it is
     * logged. A link keeps its scheme, host and port; each path segment survives only if it has
     * no digit and no `%` (an id, a coordinate or a percent-encoded one all become `<n>`), loses
     * its user info and fragment, and its query becomes `?<redacted>`; a `geo:` or `tel:` link
     * keeps only its scheme, a link that does not parse becomes `<uri>`. Outside links,
     * coordinates and phone numbers are masked.
     */
    fun forLog(text: String): String {
        LABEL.matchEntire(text)?.let { m ->
            val (kind, value) = m.destructured
            if (kind in PHONE_LABELS) return "$kind:<phone>"
            if (kind in COORD_LABELS) {
                val nameAt = value.indexOf(" (")
                val suffix = if (nameAt >= 0) " (name len=${value.length - nameAt - 3})" else ""
                return "$kind:<coords>$suffix"
            }
        }
        val out = StringBuilder()
        var last = 0
        for (m in URI_LIKE.findAll(text)) {
            out.append(freeTextForLog(text.substring(last, m.range.first)))
            val prefix = if (m.value.startsWith("dat=", ignoreCase = true)) "dat=" else ""
            out.append(prefix).append(linkForLog(m.value.removePrefix(prefix)))
            last = m.range.last + 1
        }
        out.append(freeTextForLog(text.substring(last)))
        return out.toString()
    }

    private fun freeTextForLog(text: String): String = PHONE.replace(COORDS.replace(text, "<coords>"), "<phone>")

    private fun linkForLog(link: String): String {
        val uri = try {
            URI(link)
        } catch (_: URISyntaxException) {
            return "<uri>"
        }
        val scheme = uri.scheme ?: return "<uri>"
        if (uri.isOpaque) {
            val body = when (scheme.lowercase()) {
                in PHONE_SCHEMES -> "<phone>"
                "geo" -> "<coords>"
                else -> "<redacted>"
            }
            return "$scheme:$body" + if ('?' in uri.rawSchemeSpecificPart) "?<redacted>" else ""
        }
        val authority = uri.rawAuthority?.substringAfterLast('@')?.let { "//$it" } ?: ""
        val path = maskPath(uri.rawPath ?: "")
        return "$scheme:$authority$path" + if (uri.rawQuery != null) "?<redacted>" else ""
    }

    // A path segment with a digit or a `%` can hide an id, a coordinate or a percent-encoded
    // one (e.g. a comma as `%2C`); a plain word like `routeSearch` or `car` has neither.
    private fun maskPath(path: String): String =
        path.split('/').joinToString("/") { seg -> if (seg.any { c -> c.isDigit() || c == '%' }) "<n>" else seg }
}
