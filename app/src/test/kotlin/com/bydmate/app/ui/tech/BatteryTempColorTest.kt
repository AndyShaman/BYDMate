package com.bydmate.app.ui.tech

import androidx.compose.ui.graphics.Color
import com.bydmate.app.domain.BatteryThresholds
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextPrimary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One rule for the battery temperature on the home screen and on «Машина»: a cold pack is
 * winter, not a fault, so cold is yellow at most; red is for overheating only.
 */
class BatteryTempColorTest {

    @Test
    fun `cold is a warning and never critical, only heat above 50 is critical`() {
        class Case(val temp: Int, val severity: Int)
        val cases = listOf(
            Case(-25, 1), Case(4, 1), Case(5, 0), Case(20, 0), Case(45, 0),
            Case(46, 1), Case(50, 1), Case(51, 2),
        )
        for (c in cases) {
            assertEquals("temp ${c.temp}", c.severity, BatteryThresholds.batTempSeverity(c.temp))
        }
    }

    @Test
    fun `the Машина row takes its colour from the same rule`() {
        class Case(val temp: Int?, val expected: Color)
        val cases = listOf(
            Case(4, SocYellow), Case(-25, SocYellow), Case(20, AccentGreen),
            Case(48, SocYellow), Case(51, SocRed), Case(null, TextPrimary),
        )
        for (c in cases) {
            assertEquals("temp ${c.temp}", c.expected, batTempColor(c.temp))
        }
    }
}
