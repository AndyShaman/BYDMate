package com.bydmate.app.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.TimeZone

/**
 * Calendar-aligned period boundaries (Monday-start week, 1st-of-month, Jan 1),
 * shared by the Trips / Dashboard / Charges period filters.
 */
object CalendarPeriods {

    /** Start of the current calendar week (Monday 00:00:00.000), independent of locale. */
    fun startOfWeek(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val zoneId = zone.toZoneId()
        val date = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        // DayOfWeek.MONDAY is a fixed constant, unlike Calendar.firstDayOfWeek, so this does
        // not depend on device locale.
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return startOfDate(monday, zoneId)
    }

    /** Start of the current calendar month (1st, 00:00:00.000). */
    fun startOfMonth(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val zoneId = zone.toZoneId()
        val date = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        return startOfDate(date.withDayOfMonth(1), zoneId)
    }

    /** Start of the current calendar year (Jan 1, 00:00:00.000). */
    fun startOfYear(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val zoneId = zone.toZoneId()
        val date = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        return startOfDate(date.withDayOfYear(1), zoneId)
    }

    // LocalDate.atStartOfDay(ZoneId) resolves the day's midnight through the zone's DST rules
    // instead of hand-zeroing Calendar fields: on a spring-forward gap at 00:00 it returns the
    // first valid instant after the gap, and on a fall-back overlap at 00:00 it returns the
    // earlier of the two midnights -- both are what "start of day" should mean, and neither is
    // reliably reproducible with lenient Calendar arithmetic.
    private fun startOfDate(date: LocalDate, zoneId: ZoneId): Long {
        return date.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }
}
