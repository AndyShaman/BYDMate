package com.bydmate.app.util

import java.util.Calendar
import java.util.TimeZone

/**
 * Calendar-aligned period boundaries (Monday-start week, 1st-of-month, Jan 1),
 * shared by the Trips / Dashboard / Charges period filters.
 */
object CalendarPeriods {

    /** Start of the current calendar week (Monday 00:00:00.000), independent of locale. */
    fun startOfWeek(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = nowMs
        // DAY_OF_WEEK is fixed (SUNDAY=1..SATURDAY=7) regardless of Calendar.firstDayOfWeek,
        // so this does not depend on device locale.
        val daysSinceMonday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        cal.add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
        return zeroTimeOfDay(cal)
    }

    /** Start of the current calendar month (1st, 00:00:00.000). */
    fun startOfMonth(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = nowMs
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return zeroTimeOfDay(cal)
    }

    /** Start of the current calendar year (Jan 1, 00:00:00.000). */
    fun startOfYear(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = nowMs
        cal.set(Calendar.DAY_OF_YEAR, 1)
        return zeroTimeOfDay(cal)
    }

    // Zeroes the time-of-day fields only after the date fields already sit on the period
    // start. Zeroing before moving the date (as this used to do) breaks when the current
    // day's own midnight does not exist -- e.g. a DST spring-forward at 00:00 -- because the
    // lenient Calendar then rounds "now" up to 01:00 and every later date shift keeps that
    // wrong hour. Zeroing last only risks the same rounding on the period-start date itself,
    // which is the one case where landing on the first valid instant of that day is correct.
    private fun zeroTimeOfDay(cal: Calendar): Long {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
