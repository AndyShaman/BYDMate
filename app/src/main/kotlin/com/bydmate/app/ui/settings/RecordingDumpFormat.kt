package com.bydmate.app.ui.settings

import com.bydmate.app.data.local.entity.VehicleWriteLogEntity
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavPackages
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure formatting of the dump pieces added by the log audit (2026-10-06): the app versions line,
 * the vehicle writes section, and the parts of the end snapshot. Kept apart from the ViewModel
 * so each shape is testable without one.
 */
internal object RecordingDumpFormat {

    private const val TS_PATTERN = "yyyy-MM-dd HH:mm:ss"

    /** Label → package candidates, the first installed one wins. HUD and cluster bugs depend on
     *  the navigator's build (#198, #199), music and the card on the player's. */
    private val APPS: List<Pair<String, List<String>>> = listOf(
        "yandexnavi" to NavPackages.YANDEX_NAVI.toList(),
        "yandexmaps" to NavPackages.YANDEX_MAPS.toList(),
        "music" to listOf("ru.yandex.music"),
        "dublgis" to listOf("ru.dublgis.dgismobile"),
        "spotify" to listOf("com.spotify.music"),
    )

    /** `apps: yandexnavi=24.5.1/12345 ...`; [version] answers versionName and versionCode of an
     *  installed package, null when it is absent. A variant package names its suffix. */
    fun appVersionsLine(version: (String) -> Pair<String?, Long>?): String =
        "apps: " + APPS.joinToString(" ") { (label, packages) ->
            val found = packages.firstNotNullOfOrNull { pkg -> version(pkg)?.let { pkg to it } }
            if (found == null) {
                "$label=absent"
            } else {
                val (pkg, v) = found
                val variant = if (pkg == packages.first()) "" else "(${pkg.removePrefix(packages.first())})"
                "$label=${v.first ?: "?"}/${v.second}$variant"
            }
        }

    /** The `--- vehicle writes ---` section, oldest first, from rows newest first. */
    fun vehicleWriteLines(newestFirst: List<VehicleWriteLogEntity>): List<String> {
        if (newestFirst.isEmpty()) return listOf("(none)")
        val fmt = SimpleDateFormat(TS_PATTERN, Locale.US)
        return newestFirst.asReversed().map { row ->
            "${fmt.format(Date(row.ts))} ${row.actionName} dev=${row.dev} fid=${row.fid} " +
                "req=${row.requested} rb=${row.readback ?: "-"} ${if (row.status == 0) "ok" else "fail"}" +
                (row.error?.let { " err=$it" } ?: "") + if (row.validated) "" else " unvalidated"
        }
    }

    /**
     * The hub snapshot without its data: the maneuver and camera icons as their byte counts
     * instead of a few KB of numbers, the road as the script it is written in instead of the
     * street name (privacy, and the «иероглифы» reports need only the script).
     */
    fun hubSnapshot(s: NavGuidanceHub.Snapshot): String {
        var text = s.toString()
        s.maneuverPng?.let { text = text.replace("maneuverPng=${it.contentToString()}", "png=<${it.size} bytes>") }
        s.cameraIconPng?.let { text = text.replace("cameraIconPng=${it.contentToString()}", "cameraIconPng=<${it.size} bytes>") }
        return text.replace("road=${s.road},", "road_script=${script(s.road)} road_len=${s.road.length},")
    }

    /** `latin`, `cyrillic`, another script by its block, `mixed`, `digits` (no letters) or `empty`. */
    fun script(text: String): String {
        if (text.isBlank()) return "empty"
        val scripts = text.filter { it.isLetter() }.map {
            when (Character.UnicodeScript.of(it.code)) {
                Character.UnicodeScript.LATIN -> "latin"
                Character.UnicodeScript.CYRILLIC -> "cyrillic"
                else -> Character.UnicodeScript.of(it.code).name.lowercase()
            }
        }.toSet()
        return when (scripts.size) {
            0 -> "digits"
            1 -> scripts.first()
            else -> "mixed"
        }
    }

    /** Lines of a `yyyy-MM-dd HH:mm:ss ...` journal written at or after [sinceMs]. */
    fun since(lines: List<String>, sinceMs: Long): List<String> {
        val fmt = SimpleDateFormat(TS_PATTERN, Locale.US)
        val fromSecond = sinceMs - sinceMs % 1_000
        return lines.filter { line ->
            val ts = try {
                fmt.parse(line.take(TS_PATTERN.length))?.time
            } catch (_: ParseException) {
                null
            }
            ts != null && ts >= fromSecond
        }
    }

    /** A rule journal dump line by rule id alone: without the rule's name and the trigger
     *  snapshot (a place name), both user data. */
    fun ruleLineById(line: String): String {
        val head = line.substringBefore(" \"")
        val tail = line.substringAfter("\" ok=", missingDelimiterValue = "")
        val shown = if (tail.isEmpty()) line else "$head ok=$tail"
        return shown.substringBefore(" trig=")
    }

    /** The totals of the fid push section, without its row per fid. */
    fun fidPushTotals(lines: List<String>): List<String> = lines.filter { line ->
        FID_PUSH_TOTALS.any { line.startsWith(it) }
    }

    private val FID_PUSH_TOTALS = listOf("no subscription", "subscribed=", "callback:", "delivery:", "resubscribes=")
}
