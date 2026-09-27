package com.bydmate.app.data.telegram

import androidx.annotation.StringRes
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.remote.DiParsData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Strings in the app language; `AppStrings.get` in the app, a localized context in tests. */
fun interface ReportStrings {
    fun get(@StringRes id: Int, args: Array<out Any>): String
}

/** A trip under way, from the service's session counters (not the widget's blended number). */
data class LiveTrip(val km: Double, val kwh: Double?, val startedAtMs: Long)

/** Everything a report can say, read at one moment. A null is «not readable on this car». */
data class ReportInputs(
    val data: DiParsData?,
    val rangeKm: Double?,
    val latitude: Double?,
    val longitude: Double?,
    val liveTrip: LiveTrip?,
    val lastTrip: TripEntity?,
)

/** The message in Telegram HTML plus what went into it, for the log (never the text itself). */
data class BuiltReport(val text: String, val taken: List<ReportField>, val skipped: List<ReportField>)

/**
 * Builds the Telegram report text (Telegram HTML, every value escaped). An item the car does not
 * report is left out and named in [BuiltReport.skipped]; nothing is guessed. Pure, so the automation
 * action and the power-off report (phase B) share one text.
 *
 * Layout (mock 2026-09-27, frame 3): bold header, the user's own text, «Заряд 64%, запас 312 км»,
 * the trip, «Снаружи +12°, в салоне +19°», what is open, tires, and the map link last.
 */
object TelegramReportBuilder {

    /** Stands for HH:MM in the power-off header; the helper daemon puts the real time in (phase B). */
    const val TIME_PLACEHOLDER = "{{time}}"

    /** A live trip counts from this distance; below it the last recorded trip is shown. */
    const val MIN_LIVE_TRIP_KM = 0.1

    /** Live consumption is shown from this distance, where the widget stops blending it too. */
    const val MIN_LIVE_CONSUMPTION_KM = 2.0

    /** Trips from this length are shown in whole kilometers. */
    private const val WHOLE_KM_FROM = 10.0

    const val APP_NAME = "BYDMate"

    private val TIME = SimpleDateFormat("HH:mm", Locale.US)
    private val DATE_TIME = SimpleDateFormat("dd.MM HH:mm", Locale.US)

    /** «BYDMate: <rule name>», or just «BYDMate» for a rule without a name. */
    fun ruleHeader(ruleName: String?): String =
        ruleName?.trim()?.takeIf { it.isNotEmpty() }?.let { "$APP_NAME: $it" } ?: APP_NAME

    /** «BYDMate: машина выключена в {{time}}»: the daemon fills the time when it sends. */
    fun powerOffHeader(strings: ReportStrings): String =
        strings.get(R.string.tg_report_header_off, arrayOf(TIME_PLACEHOLDER))

    /** Puts the send time into a power-off text built with [powerOffHeader]. */
    fun fillTime(text: String, timeMs: Long): String = text.replace(TIME_PLACEHOLDER, formatTime(timeMs))

    fun formatTime(timeMs: Long): String = synchronized(TIME) { TIME.format(Date(timeMs)) }

    fun formatDateTime(timeMs: Long): String = synchronized(DATE_TIME) { DATE_TIME.format(Date(timeMs)) }

    @Suppress("LongParameterList") // the report is exactly these inputs
    fun build(
        header: String,
        customText: String,
        fields: Set<ReportField>,
        inputs: ReportInputs,
        lang: String,
        strings: ReportStrings,
        nowMs: Long,
    ): BuiltReport {
        val locale = Locale.forLanguageTag(lang)
        val d = inputs.data
        val soc = d?.soc?.takeIf { ReportField.SOC in fields && it in 0..100 }
            ?.let { text(strings, R.string.tg_report_soc, it) }
        val range = inputs.rangeKm?.takeIf { ReportField.RANGE in fields && it > 0.0 }
            ?.let { text(strings, R.string.tg_report_range, it.roundToInt()) }
        val temps = if (ReportField.TEMPS in fields) {
            sentence(
                listOfNotNull(
                    d?.exteriorTemp?.let { text(strings, R.string.tg_report_temp_outside, it) },
                    d?.insideTemp?.let { text(strings, R.string.tg_report_temp_inside, it) },
                ),
                locale,
            )
        } else null
        val found = mapOf(
            ReportField.SOC to soc,
            ReportField.RANGE to range,
            ReportField.TRIP to (if (ReportField.TRIP in fields) tripLine(inputs, strings, nowMs) else null),
            ReportField.TEMPS to temps,
            ReportField.OPENINGS to (if (ReportField.OPENINGS in fields) d?.let { openingsLine(it, strings) } else null),
            ReportField.TIRES to (if (ReportField.TIRES in fields) d?.let { tiresLine(it, strings, locale) } else null),
            ReportField.LOCATION to (if (ReportField.LOCATION in fields) locationLine(inputs, lang, strings) else null),
        )

        val lines = mutableListOf("<b>${escape(header)}</b>")
        customText.trim().takeIf { it.isNotEmpty() }?.let { lines += escape(it) }
        sentence(listOfNotNull(soc, range), locale)?.let { lines += escape(it) }
        listOf(ReportField.TRIP, ReportField.TEMPS, ReportField.OPENINGS, ReportField.TIRES)
            .mapNotNull { found[it] }
            .forEach { lines += escape(it) }
        // The only line with markup: it escapes its own parts.
        found[ReportField.LOCATION]?.let { lines += it }

        val asked = ReportField.entries.filter { it in fields }
        return BuiltReport(
            text = lines.joinToString("\n"),
            taken = asked.filter { found[it] != null },
            skipped = asked.filter { found[it] == null },
        )
    }

    /** Telegram HTML needs only these three escaped, in text and in attribute values alike. */
    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** ru and be open Yandex Maps, every other interface language Google Maps. */
    fun mapUrl(lat: Double, lon: Double, lang: String): String {
        val la = String.format(Locale.US, "%.6f", lat)
        val lo = String.format(Locale.US, "%.6f", lon)
        return if (lang == "ru" || lang == "be") "https://yandex.ru/maps/?pt=$lo,$la&z=16&l=map"
        else "https://www.google.com/maps/search/?api=1&query=$la,$lo"
    }

    /** «заряд 64%» + «запас 312 км» -> «Заряд 64%, запас 312 км»; null when there is no part. */
    private fun sentence(parts: List<String>, locale: Locale): String? =
        parts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.replaceFirstChar { it.titlecase(locale) }

    private fun locationLine(inputs: ReportInputs, lang: String, strings: ReportStrings): String? {
        val lat = inputs.latitude ?: return null
        val lon = inputs.longitude ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        val label = escape(text(strings, R.string.tg_report_map_link))
        return "<a href=\"${escape(mapUrl(lat, lon, lang))}\">$label</a>"
    }

    /** The trip under way when it is at least [MIN_LIVE_TRIP_KM] long, else the last recorded one. */
    private fun tripLine(inputs: ReportInputs, strings: ReportStrings, nowMs: Long): String? {
        val live = inputs.liveTrip?.takeIf { it.km >= MIN_LIVE_TRIP_KM }
        if (live != null) {
            val consumption = live.kwh
                ?.takeIf { it > 0.0 && live.km >= MIN_LIVE_CONSUMPTION_KM }
                ?.let { it / live.km * 100.0 }
            val parts = tripParts(live.km, consumption, nowMs - live.startedAtMs, strings)
            return text(strings, R.string.tg_report_trip_now, parts)
        }
        val last = inputs.lastTrip ?: return null
        val km = last.distanceKm?.takeIf { it > 0.0 } ?: return null
        val durationMs = last.endTs?.let { it - last.startTs }?.takeIf { it > 0 }
        val parts = tripParts(km, last.kwhPer100km?.takeIf { it > 0.0 }, durationMs, strings)
        return text(strings, R.string.tg_report_trip_last, formatDateTime(last.startTs), parts)
    }

    private fun tripParts(km: Double, kwhPer100: Double?, durationMs: Long?, strings: ReportStrings): String =
        listOfNotNull(
            if (km >= WHOLE_KM_FROM) text(strings, R.string.tg_report_trip_km_whole, km.roundToInt())
            else text(strings, R.string.tg_report_trip_km, km),
            kwhPer100?.let { text(strings, R.string.tg_report_trip_consumption, it) },
            durationMs?.takeIf { it >= 0 }?.let { duration(it, strings) },
        ).joinToString(", ")

    private fun duration(ms: Long, strings: ReportStrings): String {
        val totalMin = (ms / 60_000L).toInt()
        val hours = totalMin / 60
        return if (hours > 0) text(strings, R.string.common_duration_hours_minutes, hours, totalMin % 60)
        else text(strings, R.string.common_duration_minutes, totalMin)
    }

    /**
     * «Открыто: …» with every panel reported open, «Всё закрыто» when none of the reported ones is;
     * null when the car reports none of them. Doors, trunks and hood: 1 = open; windows and the
     * sunroof: opening percent above 0.
     */
    private fun openingsLine(d: DiParsData, strings: ReportStrings): String? {
        val flags = listOf(
            R.string.tg_report_open_door_fl to d.doorFL?.let { it == 1 },
            R.string.tg_report_open_door_fr to d.doorFR?.let { it == 1 },
            R.string.tg_report_open_door_rl to d.doorRL?.let { it == 1 },
            R.string.tg_report_open_door_rr to d.doorRR?.let { it == 1 },
            R.string.window_pane_driver to d.windowFL?.let { it in 1..100 },
            R.string.window_pane_passenger to d.windowFR?.let { it in 1..100 },
            R.string.window_pane_rear_left to d.windowRL?.let { it in 1..100 },
            R.string.window_pane_rear_right to d.windowRR?.let { it in 1..100 },
            R.string.tg_report_open_sunroof to d.sunroof?.let { it in 1..100 },
            R.string.tg_report_open_trunk to d.trunk?.let { it == 1 },
            R.string.tg_report_open_front_trunk to d.frontTrunk?.let { it == 1 },
            R.string.tg_report_open_hood to d.hood?.let { it == 1 },
        ).filter { it.second != null }
        if (flags.isEmpty()) return null
        val open = flags.filter { it.second == true }.map { text(strings, it.first) }
        return if (open.isEmpty()) text(strings, R.string.tg_report_all_closed)
        else text(strings, R.string.tg_report_open_list, open.joinToString(", "))
    }

    /**
     * «Шины: 2,4 / 2,4 / 2,3 / 2,4 бар» in the order front left, front right, rear left, rear right,
     * as on the «Техника» screen; a wheel without a reading keeps its place as «-».
     */
    private fun tiresLine(d: DiParsData, strings: ReportStrings, locale: Locale): String? {
        val wheels = listOf(d.tirePressFL, d.tirePressFR, d.tirePressRL, d.tirePressRR).map { kpa -> kpa?.takeIf { it > 0 } }
        if (wheels.all { it == null }) return null
        val values = wheels.joinToString(" / ") { kpa -> kpa?.let { String.format(locale, "%.1f", it / 100.0) } ?: "-" }
        return text(strings, R.string.tg_report_tires, values)
    }

    /** A string with its arguments, not escaped yet: [build] escapes each finished line once. */
    private fun text(strings: ReportStrings, @StringRes id: Int, vararg args: Any): String = strings.get(id, args)
}
