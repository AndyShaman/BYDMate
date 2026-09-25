package com.bydmate.app.util

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarPeriodsTest {

    private val zone = TimeZone.getTimeZone("Europe/Moscow")

    // Clocks jump 00:00 -> 01:00 at the DST spring-forward, so a handful of tests below need a
    // zone where that transition falls exactly at midnight (Europe/Moscow does not use DST).
    private val santiago = TimeZone.getTimeZone("America/Santiago")

    private fun ts(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, zone: TimeZone = this.zone): Long {
        val cal = Calendar.getInstance(zone)
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, 0)
        return cal.timeInMillis
    }

    @Test
    fun `startOfWeek - Sunday night belongs to the week that started the previous Monday`() {
        // 2024-01-01 is a Monday; 2024-01-07 is the Sunday closing that same week.
        val nowMs = ts(2024, 1, 7, 23, 59)
        assertEquals(ts(2024, 1, 1), CalendarPeriods.startOfWeek(nowMs, zone))
    }

    @Test
    fun `startOfWeek - Monday just after midnight starts a new week on itself`() {
        val nowMs = ts(2024, 1, 8, 0, 30)
        assertEquals(ts(2024, 1, 8), CalendarPeriods.startOfWeek(nowMs, zone))
    }

    @Test
    fun `startOfMonth - mid-month date rounds down to the 1st`() {
        val nowMs = ts(2024, 1, 15, 12, 0)
        assertEquals(ts(2024, 1, 1), CalendarPeriods.startOfMonth(nowMs, zone))
    }

    @Test
    fun `startOfMonth - the 1st itself still snaps to midnight`() {
        val nowMs = ts(2024, 3, 1, 15, 30)
        assertEquals(ts(2024, 3, 1), CalendarPeriods.startOfMonth(nowMs, zone))
    }

    @Test
    fun `startOfYear - Jan 1 snaps to midnight`() {
        val nowMs = ts(2024, 1, 1, 10, 0)
        assertEquals(ts(2024, 1, 1), CalendarPeriods.startOfYear(nowMs, zone))
    }

    @Test
    fun `leap year Feb 29 - month, year and week boundaries all resolve correctly`() {
        val nowMs = ts(2024, 2, 29, 12, 0)
        assertEquals(ts(2024, 2, 1), CalendarPeriods.startOfMonth(nowMs, zone))
        assertEquals(ts(2024, 1, 1), CalendarPeriods.startOfYear(nowMs, zone))
        // 2024-02-29 is a Thursday; the Monday of that week is 2024-02-26.
        assertEquals(ts(2024, 2, 26), CalendarPeriods.startOfWeek(nowMs, zone))
    }

    @Test
    fun `startOfWeek - Santiago DST spring-forward at midnight still resolves the week start to 00-00`() {
        // 2024-09-08 00:00 does not exist in America/Santiago (clocks jump straight to 01:00);
        // the week must still start on Monday 2024-09-02 at 00:00, not 01:00.
        val nowMs = ts(2024, 9, 8, 12, 0, santiago)
        assertEquals(ts(2024, 9, 2, zone = santiago), CalendarPeriods.startOfWeek(nowMs, santiago))
    }

    @Test
    fun `startOfWeek - now sitting right on the DST boundary itself still resolves to a normal week start`() {
        // 01:00 is the first local instant that exists on 2024-09-08 after the skip.
        val nowMs = ts(2024, 9, 8, 1, 0, santiago)
        assertEquals(ts(2024, 9, 2, zone = santiago), CalendarPeriods.startOfWeek(nowMs, santiago))
    }

    @Test
    fun `startOfMonth - Santiago DST spring-forward at midnight still resolves the month start to 00-00`() {
        val nowMs = ts(2024, 9, 8, 12, 0, santiago)
        assertEquals(ts(2024, 9, 1, zone = santiago), CalendarPeriods.startOfMonth(nowMs, santiago))
    }

    @Test
    fun `startOfYear - Santiago DST spring-forward at midnight still resolves the year start to 00-00`() {
        val nowMs = ts(2024, 9, 8, 12, 0, santiago)
        assertEquals(ts(2024, 1, 1, zone = santiago), CalendarPeriods.startOfYear(nowMs, santiago))
    }
}
