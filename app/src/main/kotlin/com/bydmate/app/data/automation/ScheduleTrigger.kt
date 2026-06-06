package com.bydmate.app.data.automation

import org.json.JSONArray
import org.json.JSONObject

/**
 * Schedule spec for the "time_range" trigger kind, stored as JSON in
 * [com.bydmate.app.data.local.entity.TriggerDef.value]. No DB migration:
 * triggers are already persisted as a JSON string.
 *
 * - [fromMinute] == [toMinute] → exact-minute trigger ("11:34").
 * - [fromMinute] <  [toMinute] → window ("08:00-10:00"); "after 8:30" is just 08:30..23:59.
 * - [fromMinute] >  [toMinute] → window crossing midnight ("22:00-06:00").
 * - [days] uses ISO-8601 (1=Mon .. 7=Sun); empty = every day.
 */
data class ScheduleSpec(
    val fromMinute: Int,
    val toMinute: Int,
    val days: Set<Int>,
) {
    val isExact: Boolean get() = fromMinute == toMinute

    fun toJson(): String = JSONObject().apply {
        put("from", minuteToHHmm(fromMinute))
        put("to", minuteToHHmm(toMinute))
        put("days", JSONArray(days.sorted()))
    }.toString()

    companion object {
        fun fromJson(value: String): ScheduleSpec? = try {
            val obj = JSONObject(value)
            val from = hhmmToMinute(obj.optString("from", ""))
            val to = hhmmToMinute(obj.optString("to", ""))
            if (from == null || to == null) {
                null
            } else {
                val days = mutableSetOf<Int>()
                obj.optJSONArray("days")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val d = arr.optInt(i, -1)
                        if (d in 1..7) days.add(d)
                    }
                }
                ScheduleSpec(from, to, days)
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** "HH:mm" → minutes since midnight (0..1439), or null if malformed/out of range. */
internal fun hhmmToMinute(s: String): Int? {
    val parts = s.split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** Minutes since midnight → zero-padded "HH:mm". */
internal fun minuteToHHmm(minute: Int): String {
    val mm = ((minute % 1440) + 1440) % 1440
    return "%02d:%02d".format(mm / 60, mm % 60)
}

/**
 * True when [nowMinute] (0..1439) and [nowDayOfWeek] (ISO 1=Mon..7=Sun) fall
 * inside [spec]. Empty [ScheduleSpec.days] = every day. Edge-triggering in the
 * engine turns this state into a single fire on window/minute entry.
 */
internal fun isWithinSchedule(spec: ScheduleSpec, nowMinute: Int, nowDayOfWeek: Int): Boolean {
    if (spec.days.isNotEmpty() && nowDayOfWeek !in spec.days) return false
    return when {
        spec.fromMinute == spec.toMinute -> nowMinute == spec.fromMinute
        spec.fromMinute < spec.toMinute -> nowMinute in spec.fromMinute..spec.toMinute
        else -> nowMinute >= spec.fromMinute || nowMinute <= spec.toMinute
    }
}
