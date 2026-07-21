package com.bydmate.app.media

import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavManeuverCodes

/**
 * Donor YandexNaviNotificationListener post-processing (lines 65-166) split out as
 * pure logic: turns a parsed RichNaviInfo plus notification extras into a hub
 * RichUpdate, and implements the extras-only fallback for notifications without
 * RemoteViews. Keeping it pure makes the merge rules JVM-testable.
 */
object NaviRichPostProcessor {

    private val DIST_KM = Regex("""(\d+(?:[.,]\d+)?)\s*(км|km)(?!\S)""", RegexOption.IGNORE_CASE)
    private val DIST_M = Regex("""(\d+)\s*(м|m)(?!\S)""", RegexOption.IGNORE_CASE)
    private val TIME_HHMM = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val ETA_H = Regex("""(\d+)\s*ч""")
    private val ETA_MIN = Regex("""(\d+)\s*мин""")

    /** RICH outcome: donor listener merge collapsed into a RichUpdate (empty/zero
     *  fields mean "keep previous" via the hub's field-wise merge). */
    fun buildRichUpdate(
        rich: NaviRichNotificationParser.RichNaviInfo,
        extraTitle: String?,
        extraText: String?,
        extraSubText: String?,
    ): NavGuidanceHub.RichUpdate {
        val maneuverGaode = rich.maneuverGaode ?: NavManeuverCodes.richPhraseGaode(rich.instruction)
        val etaSeconds = if (rich.remainingTimeSec > 0) rich.remainingTimeSec
            else parseEtaSeconds(extraSubText ?: "")

        // Road from extras (android.text) preferred - the RV tree may carry "N мин"
        val extrasRoad = extraText?.trim() ?: ""
        val safeRoad = if (extrasRoad.isNotEmpty() && !isDurationText(extrasRoad) &&
            !NavManeuverCodes.isServicePhrase(extrasRoad) &&
            NavManeuverCodes.richPhraseGaode(extrasRoad) == 0
        ) extrasRoad else ""

        // Distance from extras (android.title) when the RV gave none
        val extrasDist = if (rich.distToManeuverM <= 0) parseDistance(extraTitle ?: "") ?: 0 else 0

        val road = when {
            safeRoad.isNotEmpty() -> safeRoad
            rich.road.isNotEmpty() && !isDurationText(rich.road) &&
                NavManeuverCodes.richPhraseGaode(rich.road) == 0 -> rich.road
            else -> "" // empty -> hub keeps prev.road
        }

        return NavGuidanceHub.RichUpdate(
            maneuverGaode = maneuverGaode,
            distanceMeters = if (rich.distToManeuverM > 0) rich.distToManeuverM else extrasDist,
            road = road,
            etaSeconds = etaSeconds,
            totalDistMeters = rich.totalDistM,
            maneuverPng = rich.maneuverPng,
            cameraAlert = rich.cameraAlert,
            cameraDistanceMeters = rich.cameraDistanceM,
            cameraIconPng = rich.cameraIconPng,
            applyCamera = true,
        )
    }

    /**
     * EXTRAS_FALLBACK outcome: no RemoteViews. Returns null for idle stubs
     * (app-logo icon, or zero distance with a "навигатор" road). Camera untouched
     * (applyCamera=false - extras carry no camera). Maps rule R2-1: while the hub
     * already holds a known maneuver, a Maps extras maneuver is dropped.
     */
    fun buildExtrasFallback(
        extraTitle: String?,
        extraText: String?,
        extraSubText: String?,
        smallIconName: String,
        isMaps: Boolean,
        hubHasKnownManeuver: Boolean,
    ): NavGuidanceHub.RichUpdate? {
        val title = extraTitle ?: ""
        val text = extraText ?: ""

        val maneuverFromText = NavManeuverCodes.richPhraseGaode("$title $text")
        val maneuver = if (maneuverFromText == 0) NavManeuverCodes.richIconNameGaode(smallIconName)
            else maneuverFromText
        val distanceMeters = parseDistance(text) ?: parseDistance(title) ?: 0
        val road = extractRoad(title, text)
        val etaSeconds = parseEtaSeconds(extraSubText ?: "")

        if (smallIconName == "notifications_app_logo" ||
            (distanceMeters == 0 && road.contains("навигатор", ignoreCase = true))
        ) return null

        val mergeManeuver = if (isMaps && hubHasKnownManeuver) 0 else maneuver
        val mergeRoad = if (road.isNotEmpty() && road != "Навигатор запущен") road else ""

        return NavGuidanceHub.RichUpdate(
            maneuverGaode = mergeManeuver,
            distanceMeters = distanceMeters,
            road = mergeRoad,
            etaSeconds = etaSeconds,
            totalDistMeters = 0,
            maneuverPng = null,
            applyCamera = false,
        )
    }

    internal fun parseEtaSeconds(s: String): Int {
        if (s.isBlank()) return 0
        val h = ETA_H.find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = ETA_MIN.find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return h * 3600 + m * 60
    }

    private fun extractRoad(title: String, text: String): String {
        val hasDist = DIST_KM.containsMatchIn(title) || DIST_M.containsMatchIn(title)
        return if (hasDist) text.trim() else title.trim()
    }

    /** Donor LISTENER variant (containsMatchIn for HH:MM); the parser's variant
     *  full-matches - keep both exactly as the donor has them. */
    private fun isDurationText(s: String): Boolean {
        val t = s.trim()
        return t.contains("мин") || t.contains("ч") || TIME_HHMM.containsMatchIn(t)
    }

    private fun parseDistance(s: String): Int? {
        val km = DIST_KM.find(s)
        if (km != null) {
            val v = km.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return (v * 1000).toInt()
        }
        val m = DIST_M.find(s)
        if (m != null) return m.groupValues[1].toIntOrNull()
        return null
    }
}
