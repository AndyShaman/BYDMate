package com.bydmate.app.navdata

/** Snapshot of Navigator guidance in numeric form. 0 / "" = unknown. */
data class NavGuidance(
    val maneuverGaode: Int = 0,
    val distanceMeters: Int = 0,
    val road: String = "",
    val etaSeconds: Int = 0,
    val totalDistMeters: Int = 0,
    val speedLimit: Int = 0,
    /** What the maneuver code was read from, for the field journal (#294). */
    val maneuverRaw: String = "",
    /** False when [road] is a fallback (the current street), not the street after the maneuver:
     *  such a road never drops a held maneuver (#294). */
    val roadIsNextStreet: Boolean = true,
)

/** Pure parsers: raw Navigator widget strings -> NavGuidance. Shared by the a11y
 *  extractor and the notification channel; every field is optional and soft-fails. */
object NavGuidanceParser {

    private val DIST_KM = Regex("""(\d+(?:[.,]\d+)?)\s*(км|km)(?!\S)""", RegexOption.IGNORE_CASE)
    private val DIST_M = Regex("""(\d+)\s*(м|m)(?!\S)""", RegexOption.IGNORE_CASE)
    private val ETA_HR_MIN = Regex("""(\d+)\s*ч\s*(\d+)\s*мин""", RegexOption.IGNORE_CASE)
    private val ETA_MIN = Regex("""(\d+)\s*мин""", RegexOption.IGNORE_CASE)

    data class RawFields(
        val maneuverDesc: String?,   // image_maneuverballoon_maneuver contentDescription
        val exitNumber: String?,     // exit_number_text
        val distance: String?,       // text_maneuverballoon_distance
        val distanceUnit: String?,   // text_maneuverballoon_metrics
        val nextStreet: String?,     // text_nextstreet
        val statusPanel: String?,    // status_panel_text
        val etaTime: String?,        // textview_eta_time (desc preferred over text)
        val etaDistance: String?,    // textview_eta_distance
        val speedLimit: String?,     // text_speedlimit
    )

    /** Null when no guidance widget is visible (donor's hasManeuverBalloon gate). */
    fun parse(raw: RawFields): NavGuidance? {
        val guidanceVisible = raw.maneuverDesc != null || raw.distance != null || raw.nextStreet != null
        if (!guidanceVisible) return null
        val maneuverGaode = resolveManeuver(raw)
        return NavGuidance(
            maneuverGaode = maneuverGaode,
            distanceMeters = resolveDistance(raw.distance, raw.distanceUnit),
            road = raw.nextStreet ?: raw.statusPanel ?: "",
            etaSeconds = resolveEta(raw.etaTime),
            totalDistMeters = parseDistanceText(raw.etaDistance),
            speedLimit = raw.speedLimit?.trim()?.toIntOrNull() ?: 0,
            maneuverRaw = when {
                maneuverGaode == 0 -> ""
                exitNumberOf(raw) != null -> NavManeuverRaw.text("exit", raw.exitNumber)
                else -> NavManeuverRaw.text("desc", raw.maneuverDesc)
            },
            roadIsNextStreet = raw.nextStreet != null,
        )
    }

    /** Combined "300 м" / "1,2 км" string -> meters (notification distance, eta distance). */
    fun parseDistanceText(text: String?): Int {
        val s = text?.trim() ?: return 0
        DIST_KM.find(s)?.let {
            return ((it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0) * 1000).toInt()
        }
        DIST_M.find(s)?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
        return 0
    }

    private fun resolveManeuver(raw: RawFields): Int {
        // A numbered exit (1..10) is a sufficient roundabout signal even when the balloon
        // desc says "Поверните направо" (Yandex does that on small roundabouts).
        val exitNum = exitNumberOf(raw)
        // AutoNavi CCW_N_EXIT = 24+N (right-hand traffic); flat 24 only when the
        // exit number is missing or out of the 1..10 icon range.
        if (exitNum != null) return if (exitNum in 1..10) NavManeuverCodes.GAODE_ROUNDABOUT_EXIT + exitNum else NavManeuverCodes.GAODE_ROUNDABOUT_EXIT
        return NavManeuverCodes.fromA11yDescription(raw.maneuverDesc)
    }

    private fun exitNumberOf(raw: RawFields): Int? =
        raw.exitNumber?.let { Regex("""\d+""").find(it)?.value }?.toIntOrNull()

    private fun resolveDistance(distance: String?, unit: String?): Int {
        val rawText = distance?.trim() ?: return 0
        val u = unit?.trim()?.lowercase() ?: "м"
        val isKm = u.startsWith("км") || u.startsWith("km")
        rawText.toIntOrNull()?.let { return if (isKm) it * 1000 else it }
        rawText.replace(',', '.').toDoubleOrNull()?.let { return if (isKm) (it * 1000).toInt() else it.toInt() }
        return 0
    }

    private fun resolveEta(etaTime: String?): Int {
        val s = etaTime ?: return 0
        ETA_HR_MIN.find(s)?.let {
            return (it.groupValues[1].toIntOrNull() ?: 0) * 3600 + (it.groupValues[2].toIntOrNull() ?: 0) * 60
        }
        ETA_MIN.find(s)?.let { return (it.groupValues[1].toIntOrNull() ?: 0) * 60 }
        return 0
    }
}

/** The raw input behind a maneuver code, as the field journal records it (#294): "kind:value",
 *  short enough for one journal line and without the street a screen text may go on with. */
object NavManeuverRaw {
    const val MAX_CHARS = 40
    /** Words of an unmatched screen text kept: a street may follow the maneuver words. */
    private const val TEXT_WORDS = 2
    private const val HEAD_CHARS = 20
    private val SPACES = Regex("""[\s\p{Z}]+""")
    /** Words a street name follows: nothing from one of them on is kept. */
    private val SEPARATORS = setOf("на", "в", "по", "onto", "on", "to", "into")

    /** From a screen text: the table entry it matched (`key=`), else its first words up to a
     *  separator and its length; never the user text after them. */
    fun text(kind: String, text: String?): String {
        if (text == null) return name(kind, null)
        NavManeuverCodes.matchedPhrase(text)?.let { return name(kind, "key=$it") }
        val head = text.split(SPACES).filter { it.isNotEmpty() }
            .takeWhile { it.lowercase() !in SEPARATORS }.take(TEXT_WORDS).joinToString(" ")
        return name(kind, "${head.take(HEAD_CHARS)} len=${text.length}")
    }

    /** From a resource or icon name, capped at [MAX_CHARS]. */
    fun name(kind: String, name: String?): String = "$kind:$name".take(MAX_CHARS)
}
