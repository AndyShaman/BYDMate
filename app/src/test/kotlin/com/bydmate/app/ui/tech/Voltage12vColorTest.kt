package com.bydmate.app.ui.tech

import androidx.compose.ui.graphics.Color
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextPrimary
import org.junit.Assert.assertEquals
import org.junit.Test

class Voltage12vColorTest {

    @Test
    fun `green from 12 5, yellow from 12 0 to just under 12 5, red below 12 0`() {
        class Case(val label: String, val v: Double, val expected: Color)
        val cases = listOf(
            Case("12.5 V is green", 12.5, AccentGreen),
            Case("12.49 V is yellow", 12.49, SocYellow),
            Case("12.0 V is yellow", 12.0, SocYellow),
            Case("11.99 V is red", 11.99, SocRed),
        )
        for (c in cases) {
            assertEquals("case: ${c.label}", c.expected, voltage12vColor(c.v))
        }
    }

    @Test
    fun `unknown voltage is neutral`() {
        assertEquals(TextPrimary, voltage12vColor(null))
    }
}
