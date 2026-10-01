package com.bydmate.app.data.telegram

import androidx.annotation.StringRes
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.trips.TripCounterUi
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
    val trip1: TripCounterUi? = null,
    val trip2: TripCounterUi? = null,
)

/** The message in Telegram HTML plus what went into it, for the log (never the text itself). */
data class BuiltReport(val text: String, val taken: List<ReportField>, val skipped: List<ReportField>)

/** Where the car stands, for the native location under a report; toString never shows it. */
data class MapPoint(val latitude: Double, val longitude: Double) {
    override fun toString(): String = "MapPoint"
}

/**
 * Builds the Telegram report text (Telegram HTML, every value escaped). An item the car does not
 * report is left out and named in [BuiltReport.skipped]; nothing is guessed. Pure, so the automation
 * action and the power-off report (phase B) share one text.
 *
 * Layout (approved 2026-09-27, the odometer 2026-09-28, TRIP 1/2 2026-10-01): blocks split by one
 * empty line, each only when it has something: the bold header; the user's own text; the car
 * (🔋 charge and range, 🧭 odometer, 🌡 temperatures, 🛞 tires, 🔒 all closed); 🚗 the trip, its
 * numbers indented below the title; 🔁 the dashboard counter TRIP 1, then 🔁 TRIP 2, each laid out
 * like the trip; ⚠️ what is open; 📍 the map link, always last.
 */
@Suppress("TooManyFunctions") // one small function per report line
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

    private const val BLOCK_GAP = "\n\n"

    /** Two EM SPACEs: the trip's numbers line up under the title text after the emoji. */
    private const val INDENT = "\u2003\u2003"

    /** How the map block starts; user text is escaped, so only the builder's own link matches. */
    private const val MAP_PREFIX = "📍 <a href="

    /** `pt=<lon>,<lat>` in a Yandex link, `query=<lat>,<lon>` in a Google one ([mapUrl]). */
    private val YANDEX_POINT = Regex("""[?&]pt=(-?\d+\.\d+),(-?\d+\.\d+)""")
    private val GOOGLE_POINT = Regex("""[?&]query=(-?\d+\.\d+),(-?\d+\.\d+)""")

    /** An odometer below this is one the car has not reported yet (the fid reads 0 at startup). */
    private const val MIN_ODOMETER_KM = 1.0

    private val TIME = SimpleDateFormat("HH:mm", Locale.US)
    private val DATE = SimpleDateFormat("dd.MM", Locale.US)
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

    private fun formatDate(timeMs: Long): String = synchronized(DATE) { DATE.format(Date(timeMs)) }

    fun formatDateTime(timeMs: Long): String = synchronized(DATE_TIME) { DATE_TIME.format(Date(timeMs)) }

    /**
     * [text] with the late mark [mark] (Telegram HTML) as its own block right above the map link, so
     * the link stays last; at the end when there is no map block (a text from 3.19.0 too).
     */
    fun withLateMark(text: String, mark: String): String {
        val at = text.lastIndexOf(BLOCK_GAP + MAP_PREFIX)
        return if (at < 0) text + BLOCK_GAP + mark
        else text.substring(0, at) + BLOCK_GAP + mark + text.substring(at)
    }

    /**
     * The place of [text]'s own map link (found the way [withLateMark] finds it), for the native
     * location sent after the report; null when the text has no map link (a text from 3.19.0 too).
     */
    fun mapPoint(text: String): MapPoint? {
        val at = text.lastIndexOf(BLOCK_GAP + MAP_PREFIX)
        if (at < 0) return null
        val href = text.substring(at + BLOCK_GAP.length + MAP_PREFIX.length)
            .removePrefix("\"").substringBefore('"').replace("&amp;", "&")
        YANDEX_POINT.find(href)?.let { return MapPoint(it.groupValues[2].toDouble(), it.groupValues[1].toDouble()) }
        return GOOGLE_POINT.find(href)?.let { MapPoint(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
    }

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
        val found = ReportField.entries.filter { it in fields }
            .associateWith { itemLine(it, inputs, lang, strings, nowMs) }
        val openings = found[ReportField.OPENINGS]
        val car = listOfNotNull(
            chargeLine(found[ReportField.SOC], found[ReportField.RANGE], Locale.forLanguageTag(lang)),
            found[ReportField.ODOMETER]?.html,
            found[ReportField.TEMPS]?.html,
            found[ReportField.TIRES]?.html,
            openings?.takeIf { !it.alert }?.html,
        )

        val blocks = listOfNotNull(
            "<b>${escape(header)}</b>",
            customText.trim().takeIf { it.isNotEmpty() }?.let(::escape),
            car.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            found[ReportField.TRIP]?.html,
            found[ReportField.TRIP1]?.html,
            found[ReportField.TRIP2]?.html,
            openings?.takeIf { it.alert }?.html,
            found[ReportField.LOCATION]?.html,
        )

        val asked = ReportField.entries.filter { it in fields }
        return BuiltReport(
            text = blocks.joinToString(BLOCK_GAP),
            taken = asked.filter { found[it] != null },
            skipped = asked.filter { found[it] == null },
        )
    }

    /** Telegram HTML needs only these three escaped in text; an attribute value also needs the quote. */
    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttribute(text: String): String = escape(text).replace("\"", "&quot;")

    /** ru and be open Yandex Maps, every other interface language Google Maps. */
    fun mapUrl(lat: Double, lon: Double, lang: String): String {
        val la = String.format(Locale.US, "%.6f", lat)
        val lo = String.format(Locale.US, "%.6f", lon)
        return if (lang == "ru" || lang == "be") "https://yandex.ru/maps/?pt=$lo,$la&z=16&l=map"
        else "https://www.google.com/maps/search/?api=1&query=$la,$lo"
    }

    /** One item, ready Telegram HTML; [alert] = it goes to the warning block, not to the car block. */
    private class Line(val html: String, val alert: Boolean = false)

    /** One item's line, or null when the car does not report it. */
    private fun itemLine(field: ReportField, inputs: ReportInputs, lang: String, strings: ReportStrings, nowMs: Long): Line? =
        if (field == ReportField.OPENINGS) inputs.data?.let { openingsLine(it, strings) }
        else itemHtml(field, inputs, lang, strings, nowMs)?.let(::Line)

    /** Every item but the openings, as Telegram HTML. */
    private fun itemHtml(field: ReportField, inputs: ReportInputs, lang: String, strings: ReportStrings, nowMs: Long): String? {
        val d = inputs.data
        val locale = Locale.forLanguageTag(lang)
        return when (field) {
            ReportField.SOC -> socPart(d?.soc, strings)
            ReportField.RANGE -> rangePart(inputs.rangeKm, strings)
            ReportField.ODOMETER -> odometerLine(d?.mileage, strings)
            ReportField.TRIP -> tripBlock(inputs, strings, nowMs)
            ReportField.TRIP1, ReportField.TRIP2 -> counterBlock(field, inputs, strings)
            ReportField.TEMPS -> d?.let { tempsLine(it, strings, locale) }?.let { "🌡 ${escape(it)}" }
            ReportField.TIRES -> d?.let { tiresLine(it, strings, locale) }?.let { "🛞 ${escape(it)}" }
            ReportField.LOCATION -> locationLine(inputs, lang, strings)
            ReportField.OPENINGS -> null
        }
    }

    /** «заряд <b>64%</b>», null outside 0..100. */
    private fun socPart(soc: Int?, strings: ReportStrings): String? = soc?.takeIf { it in 0..100 }?.let {
        markup(strings, R.string.tg_report_soc, bold(escape(text(strings, R.string.tg_report_soc_value, it))))
    }

    /** «запас <b>312 км</b>», null for no range. */
    private fun rangePart(km: Double?, strings: ReportStrings): String? = km?.takeIf { it > 0.0 }?.let {
        markup(strings, R.string.tg_report_range, bold(escape(text(strings, R.string.tg_report_trip_km_whole, it.roundToInt()))))
    }

    /** «🧭 Пробег 23 456 км»: whole kilometers cut like the car's own odometer, grouped by the language. */
    private fun odometerLine(km: Double?, strings: ReportStrings): String? = km?.takeIf { it >= MIN_ODOMETER_KM }?.let {
        "🧭 ${escape(text(strings, R.string.tg_report_odometer, it.toInt()))}"
    }

    /** «🔋 Заряд <b>64%</b>, запас <b>312 км</b>», either half alone when only one is reported. */
    private fun chargeLine(soc: Line?, range: Line?, locale: Locale): String? =
        sentence(listOfNotNull(soc?.html, range?.html), locale)?.let { "🔋 $it" }

    /** «Снаружи +12°, в салоне +19°», either half alone when only one is reported. */
    private fun tempsLine(d: DiParsData, strings: ReportStrings, locale: Locale): String? = sentence(
        listOfNotNull(
            d.exteriorTemp?.let { text(strings, R.string.tg_report_temp_outside, it) },
            d.insideTemp?.let { text(strings, R.string.tg_report_temp_inside, it) },
        ),
        locale,
    )

    /** «заряд 64%» + «запас 312 км» -> «Заряд 64%, запас 312 км»; null when there is no part. */
    private fun sentence(parts: List<String>, locale: Locale): String? =
        parts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.replaceFirstChar { it.titlecase(locale) }

    private fun locationLine(inputs: ReportInputs, lang: String, strings: ReportStrings): String? {
        val lat = inputs.latitude ?: return null
        val lon = inputs.longitude ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        val label = escape(text(strings, R.string.tg_report_map_link))
        return "$MAP_PREFIX\"${escapeAttribute(mapUrl(lat, lon, lang))}\">$label</a>"
    }

    /**
     * The trip under way when it is at least [MIN_LIVE_TRIP_KM] long, else the last recorded one:
     * «🚗 <b>Последняя поездка</b> 25.09 в 16:21», then «14 км за 35 мин» and «Расход 22,1 кВт·ч/100 км»
     * indented; a line without its value is left out.
     */
    private fun tripBlock(inputs: ReportInputs, strings: ReportStrings, nowMs: Long): String? {
        val live = inputs.liveTrip?.takeIf { it.km >= MIN_LIVE_TRIP_KM }
        if (live != null) {
            val consumption = live.kwh
                ?.takeIf { it > 0.0 && live.km >= MIN_LIVE_CONSUMPTION_KM }
                ?.let { it / live.km * 100.0 }
            val title = "🚗 ${bold(escape(text(strings, R.string.tg_report_trip_now)))}"
            return tripLines(title, live.km, consumption, nowMs - live.startedAtMs, strings)
        }
        val last = inputs.lastTrip ?: return null
        val km = last.distanceKm?.takeIf { it > 0.0 } ?: return null
        val durationMs = last.endTs?.let { it - last.startTs }?.takeIf { it > 0 }
        val start = text(strings, R.string.tg_report_trip_when, formatDate(last.startTs), formatTime(last.startTs))
        val title = "🚗 ${bold(escape(text(strings, R.string.tg_report_trip_last)))} ${escape(start)}"
        return tripLines(title, km, last.kwhPer100km?.takeIf { it > 0.0 }, durationMs, strings)
    }

    /**
     * The dashboard counter [field] (TRIP1 or TRIP2): «🔁 <b>TRIP 1</b> с 25.09», then «412 км за 9 ч 20 мин»
     * and «Расход 21,4 кВт·ч/100 км» indented, consumption as the counter's popup counts it (all kWh over
     * the km). Null for a counter never reset or with no kilometers.
     */
    private fun counterBlock(field: ReportField, inputs: ReportInputs, strings: ReportStrings): String? {
        val counter = if (field == ReportField.TRIP1) inputs.trip1 else inputs.trip2
        val c = counter?.takeIf { it.resetTs > 0L && it.km > 0.0 } ?: return null
        val consumption = c.kwh.takeIf { it > 0.0 && c.km >= MIN_LIVE_CONSUMPTION_KM }?.let { it / c.km * 100.0 }
        val since = text(strings, R.string.tg_report_counter_since, formatDate(c.resetTs))
        val title = "🔁 ${bold(escape(text(strings, field.labelRes)))} ${escape(since)}"
        return tripLines(title, c.km, consumption, c.drivingMs.takeIf { it > 0L }, strings)
    }

    /** [title] (HTML) and the indented «14 км за 35 мин» / «Расход …» lines under it. */
    private fun tripLines(title: String, km: Double, kwhPer100: Double?, durationMs: Long?, strings: ReportStrings): String {
        val distance = if (km >= WHOLE_KM_FROM) text(strings, R.string.tg_report_trip_km_whole, km.roundToInt())
        else text(strings, R.string.tg_report_trip_km, km)
        val took = durationMs?.takeIf { it >= 0 }?.let { duration(it, strings) }
        val lines = listOfNotNull(
            took?.let { text(strings, R.string.tg_report_trip_distance_time, distance, it) } ?: distance,
            kwhPer100?.let { text(strings, R.string.tg_report_trip_consumption, it) },
        )
        return (listOf(title) + lines.map { INDENT + escape(it) }).joinToString("\n")
    }

    private fun duration(ms: Long, strings: ReportStrings): String {
        val totalMin = (ms / 60_000L).toInt()
        val hours = totalMin / 60
        return if (hours > 0) text(strings, R.string.common_duration_hours_minutes, hours, totalMin % 60)
        else text(strings, R.string.common_duration_minutes, totalMin)
    }

    /** Open, closed, or reported with a value that means neither (a sentinel, a stuck sensor). */
    private enum class PanelState { OPEN, CLOSED, UNKNOWN }

    /** Doors and the hood: 0 = closed, 1 = open, anything else the car sends is unreadable. */
    private fun hingeState(value: Int?): PanelState? = value?.let {
        when (it) {
            0 -> PanelState.CLOSED
            1 -> PanelState.OPEN
            else -> PanelState.UNKNOWN
        }
    }

    /** Trunk and front trunk position: 2 = closed, 1 = open, 3 = moving (not closed yet). */
    private fun hatchState(value: Int?): PanelState? = value?.let {
        when (it) {
            2 -> PanelState.CLOSED
            1, 3 -> PanelState.OPEN
            else -> PanelState.UNKNOWN
        }
    }

    /** Windows and the sunroof: an opening percent, 0 = closed. */
    private fun paneState(value: Int?): PanelState? = value?.let {
        when (it) {
            0 -> PanelState.CLOSED
            in 1..100 -> PanelState.OPEN
            else -> PanelState.UNKNOWN
        }
    }

    /**
     * «⚠️ Открыто: …» with every panel reported open, «🔒 Всё закрыто» in the car block. «Всё закрыто» only when all four doors and all
     * four windows are known closed (a sunroof, a trunk, a front trunk or a hood the car does not
     * report is fine: many cars lack them). Otherwise, with nothing open but some panel unreadable
     * or missing, the line is left out rather than guessed either way; null when the car reports
     * none of the twelve panels at all.
     */
    private fun openingsLine(d: DiParsData, strings: ReportStrings): Line? {
        val doors = listOf(
            R.string.tg_report_open_door_fl to hingeState(d.doorFL),
            R.string.tg_report_open_door_fr to hingeState(d.doorFR),
            R.string.tg_report_open_door_rl to hingeState(d.doorRL),
            R.string.tg_report_open_door_rr to hingeState(d.doorRR),
        )
        val windows = listOf(
            R.string.window_pane_driver to paneState(d.windowFL),
            R.string.window_pane_passenger to paneState(d.windowFR),
            R.string.window_pane_rear_left to paneState(d.windowRL),
            R.string.window_pane_rear_right to paneState(d.windowRR),
        )
        val optional = listOf(
            R.string.tg_report_open_sunroof to paneState(d.sunroof),
            R.string.tg_report_open_trunk to hatchState(d.trunk),
            R.string.tg_report_open_front_trunk to hatchState(d.frontTrunk),
            R.string.tg_report_open_hood to hingeState(d.hood),
        )
        val open = (doors + windows + optional).filter { it.second == PanelState.OPEN }.map { text(strings, it.first) }
        if (open.isNotEmpty()) {
            return Line("⚠️ ${bold(escape(text(strings, R.string.tg_report_open_list, open.joinToString(", "))))}", alert = true)
        }
        val allClosed = doors.all { it.second == PanelState.CLOSED } &&
            windows.all { it.second == PanelState.CLOSED } &&
            optional.all { it.second == null || it.second == PanelState.CLOSED }
        return if (allClosed) Line("🔒 ${escape(text(strings, R.string.tg_report_all_closed))}") else null
    }

    /**
     * «Шины: ПЛ 2,4 · ПП 2,4 · ЗЛ 2,3 · ЗП 2,4 бар» in the order front left, front right, rear left,
     * rear right, as on the «Техника» screen; a wheel without a reading keeps its place as «-».
     */
    private fun tiresLine(d: DiParsData, strings: ReportStrings, locale: Locale): String? {
        val wheels = listOf(
            R.string.tg_report_tire_fl to d.tirePressFL,
            R.string.tg_report_tire_fr to d.tirePressFR,
            R.string.tg_report_tire_rl to d.tirePressRL,
            R.string.tg_report_tire_rr to d.tirePressRR,
        ).map { (label, kpa) -> label to kpa?.takeIf { it > 0 } }
        if (wheels.all { it.second == null }) return null
        val values = wheels.joinToString(" · ") { (label, kpa) ->
            text(strings, label, kpa?.let { String.format(locale, "%.1f", it / 100.0) } ?: "-")
        }
        return text(strings, R.string.tg_report_tires, values)
    }

    /** A string with its arguments, not escaped yet: each line escapes its text once. */
    private fun text(strings: ReportStrings, @StringRes id: Int, vararg args: Any): String = strings.get(id, args)

    private fun bold(html: String): String = "<b>$html</b>"

    /**
     * The string [id] escaped, with [html] (ready Telegram HTML) as its `%s` arguments: markup inside
     * a translated sentence without escaping it away. The markers never occur in a translation.
     */
    private fun markup(strings: ReportStrings, @StringRes id: Int, vararg html: String): String {
        val marks = html.indices.map { "\u0000$it\u0000" }
        var out = escape(strings.get(id, marks.toTypedArray()))
        html.forEachIndexed { i, part -> out = out.replace(marks[i], part) }
        return out
    }
}
