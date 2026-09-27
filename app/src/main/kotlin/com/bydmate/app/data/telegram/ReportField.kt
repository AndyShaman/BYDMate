package com.bydmate.app.data.telegram

import com.bydmate.app.R
import org.json.JSONArray

/**
 * One item a Telegram report can carry. [id] is what the `telegram_report` action payload and the
 * `tg_report_off_fields` setting store; the enum order is the order of the lines in the message.
 */
enum class ReportField(val id: String, val labelRes: Int) {
    LOCATION("location", R.string.tg_report_field_location),
    SOC("soc", R.string.tg_report_field_soc),
    RANGE("range", R.string.tg_report_field_range),
    TRIP("trip", R.string.tg_report_field_trip),
    TEMPS("temps", R.string.tg_report_field_temps),
    OPENINGS("openings", R.string.tg_report_field_openings),
    TIRES("tires", R.string.tg_report_field_tires);

    companion object {
        /** What a new action and the power-off report start with. */
        val DEFAULT: Set<ReportField> = setOf(LOCATION, SOC, RANGE, TRIP)

        fun fromId(id: String): ReportField? = entries.firstOrNull { it.id == id }

        /** Unknown ids are dropped; an absent value falls back to [DEFAULT], an empty one stays empty. */
        fun parseCsv(csv: String?): Set<ReportField> =
            if (csv == null) DEFAULT
            else csv.split(',').mapNotNull { fromId(it.trim()) }.toSet()

        fun toCsv(fields: Set<ReportField>): String = entries.filter { it in fields }.joinToString(",") { it.id }

        fun fromJson(array: JSONArray?): Set<ReportField> =
            if (array == null) emptySet()
            else (0 until array.length()).mapNotNull { fromId(array.optString(it)) }.toSet()

        fun toJson(fields: Set<ReportField>): JSONArray =
            JSONArray().apply { entries.filter { it in fields }.forEach { put(it.id) } }
    }
}
