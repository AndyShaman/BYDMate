package com.bydmate.app.data.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTriggerTest {

    // --- hhmm <-> minute helpers ---

    @Test
    fun `hhmm parses to minute of day`() {
        assertEquals(0, hhmmToMinute("00:00"))
        assertEquals(8 * 60 + 30, hhmmToMinute("08:30"))
        assertEquals(23 * 60 + 59, hhmmToMinute("23:59"))
    }

    @Test
    fun `hhmm rejects invalid`() {
        assertNull(hhmmToMinute("24:00"))
        assertNull(hhmmToMinute("8:60"))
        assertNull(hhmmToMinute("noon"))
        assertNull(hhmmToMinute("08-30"))
    }

    @Test
    fun `minute formats back to hhmm zero padded`() {
        assertEquals("08:05", minuteToHHmm(8 * 60 + 5))
        assertEquals("00:00", minuteToHHmm(0))
    }

    // --- spec JSON round-trip ---

    @Test
    fun `spec round trips through json`() {
        val spec = ScheduleSpec(fromMinute = 8 * 60, toMinute = 10 * 60, days = setOf(1, 2, 3, 4, 5))
        val restored = ScheduleSpec.fromJson(spec.toJson())
        assertEquals(spec, restored)
    }

    @Test
    fun `fromJson returns null on garbage`() {
        assertNull(ScheduleSpec.fromJson("not json"))
        assertNull(ScheduleSpec.fromJson("{}"))
    }

    @Test
    fun `fromJson tolerates missing days as every day`() {
        val spec = ScheduleSpec.fromJson("""{"from":"07:00","to":"07:00"}""")
        assertEquals(setOf<Int>(), spec!!.days)
    }

    // --- matching: range ---

    @Test
    fun `range matches inside window every day when days empty`() {
        val spec = ScheduleSpec(8 * 60, 10 * 60, emptySet())
        assertTrue(isWithinSchedule(spec, nowMinute = 9 * 60, nowDayOfWeek = 3))
        assertFalse(isWithinSchedule(spec, nowMinute = 7 * 60, nowDayOfWeek = 3))
        assertFalse(isWithinSchedule(spec, nowMinute = 11 * 60, nowDayOfWeek = 3))
    }

    @Test
    fun `range respects day filter`() {
        // Mon-Fri only
        val spec = ScheduleSpec(8 * 60, 10 * 60, setOf(1, 2, 3, 4, 5))
        assertTrue(isWithinSchedule(spec, nowMinute = 9 * 60, nowDayOfWeek = 5))  // Fri
        assertFalse(isWithinSchedule(spec, nowMinute = 9 * 60, nowDayOfWeek = 6)) // Sat
    }

    @Test
    fun `after a time is a range to end of day`() {
        // "после 8:30" -> 08:30..23:59
        val spec = ScheduleSpec(8 * 60 + 30, 23 * 60 + 59, setOf(1, 2, 3, 4, 5))
        assertTrue(isWithinSchedule(spec, nowMinute = 20 * 60, nowDayOfWeek = 1))
        assertFalse(isWithinSchedule(spec, nowMinute = 8 * 60, nowDayOfWeek = 1))
    }

    @Test
    fun `range wrapping past midnight matches both sides`() {
        // 22:00..06:00
        val spec = ScheduleSpec(22 * 60, 6 * 60, emptySet())
        assertTrue(isWithinSchedule(spec, nowMinute = 23 * 60, nowDayOfWeek = 2))
        assertTrue(isWithinSchedule(spec, nowMinute = 2 * 60, nowDayOfWeek = 2))
        assertFalse(isWithinSchedule(spec, nowMinute = 12 * 60, nowDayOfWeek = 2))
    }

    // --- matching: exact ---

    @Test
    fun `exact time matches only that minute`() {
        val spec = ScheduleSpec(11 * 60 + 34, 11 * 60 + 34, emptySet())
        assertTrue(isWithinSchedule(spec, nowMinute = 11 * 60 + 34, nowDayOfWeek = 4))
        assertFalse(isWithinSchedule(spec, nowMinute = 11 * 60 + 35, nowDayOfWeek = 4))
    }

    @Test
    fun `exact time respects day filter`() {
        val spec = ScheduleSpec(11 * 60 + 34, 11 * 60 + 34, setOf(6, 7))
        assertTrue(isWithinSchedule(spec, nowMinute = 11 * 60 + 34, nowDayOfWeek = 7))
        assertFalse(isWithinSchedule(spec, nowMinute = 11 * 60 + 34, nowDayOfWeek = 1))
    }

    // --- strict import checks ---

    @Test
    fun `days outside 1 to 7 or not numbers make the spec invalid`() {
        listOf("[0]", "[8]", "[\"x\"]", "[1,9]").forEach {
            assertNull(it, ScheduleSpec.fromJson("""{"from":"08:00","to":"10:00","days":$it}"""))
        }
    }

    @Test
    fun `days written as number strings still parse`() {
        assertEquals(setOf(1, 2), ScheduleSpec.fromJson("""{"from":"08:00","to":"10:00","days":["1","2"]}""")?.days)
    }

    @Test
    fun `days that are not an array make the spec invalid`() {
        assertNull(ScheduleSpec.fromJson("""{"from":"08:00","to":"10:00","days":8}"""))
        assertNull(ScheduleSpec.fromJson("""{"from":"08:00","to":"10:00","days":"[1,2,3,4,5]"}"""))
    }

    @Test
    fun `hhmm rejects a sign`() {
        assertNull(hhmmToMinute("+7:30"))
        assertNull(hhmmToMinute("-0:30"))
        assertNull(ScheduleSpec.fromJson("""{"from":"+7:30","to":"10:00","days":[1]}"""))
    }

    @Test
    fun `hhmm still accepts one and two digit hours`() {
        assertEquals(450, hhmmToMinute("7:30"))
        assertEquals(450, hhmmToMinute("07:30"))
    }
}
