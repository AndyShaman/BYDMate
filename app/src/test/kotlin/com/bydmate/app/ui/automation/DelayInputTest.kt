package com.bydmate.app.ui.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DelayInputTest {

    private val splits = listOf(
        0L to (0.0 to DelayUnit.SECONDS),
        500L to (0.5 to DelayUnit.SECONDS),
        30_000L to (30.0 to DelayUnit.SECONDS),
        45_000L to (45.0 to DelayUnit.SECONDS),
        60_000L to (1.0 to DelayUnit.MINUTES),
        90_000L to (1.5 to DelayUnit.MINUTES),
        300_000L to (5.0 to DelayUnit.MINUTES),
        2_700_000L to (45.0 to DelayUnit.MINUTES),
        3_660_000L to (61.0 to DelayUnit.MINUTES),
        4_500_000L to (75.0 to DelayUnit.MINUTES),
        3_600_000L to (1.0 to DelayUnit.HOURS),
        5_400_000L to (1.5 to DelayUnit.HOURS),
        86_400_000L to (24.0 to DelayUnit.HOURS),
    )

    @Test fun `splitDelay picks the largest unit that keeps one decimal`() {
        splits.forEach { (ms, expected) -> assertEquals("$ms", expected, splitDelay(ms)) }
    }

    @Test fun `parseDelayMs accepts a number in the unit, comma or dot`() {
        assertEquals(300_000L, parseDelayMs("5", DelayUnit.MINUTES))
        assertEquals(5_400_000L, parseDelayMs("1,5", DelayUnit.HOURS))
        assertEquals(5_400_000L, parseDelayMs("1.5", DelayUnit.HOURS))
        assertEquals(500L, parseDelayMs("0,5", DelayUnit.SECONDS))
        assertEquals(10_000L, parseDelayMs(" 10 ", DelayUnit.SECONDS))
        assertEquals(0L, parseDelayMs("0", DelayUnit.SECONDS))
        assertEquals(86_400_000L, parseDelayMs("24", DelayUnit.HOURS))
        assertEquals(86_400_000L, parseDelayMs("1440", DelayUnit.MINUTES))
    }

    @Test fun `parseDelayMs rejects out of range and garbage`() {
        listOf(
            "24,1" to DelayUnit.HOURS, "1441" to DelayUnit.MINUTES, "86401" to DelayUnit.SECONDS,
            "" to DelayUnit.MINUTES, "  " to DelayUnit.MINUTES, "abc" to DelayUnit.MINUTES,
            "-1" to DelayUnit.SECONDS, "1,2,3" to DelayUnit.SECONDS,
        ).forEach { (text, unit) -> assertNull("$text $unit", parseDelayMs(text, unit)) }
    }

    @Test fun `split then parse round-trips`() {
        splits.forEach { (ms, _) ->
            val (value, unit) = splitDelay(ms)
            assertEquals("$ms", ms, parseDelayMs(value.toString(), unit))
        }
    }
}
