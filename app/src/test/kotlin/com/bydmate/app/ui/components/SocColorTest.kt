package com.bydmate.app.ui.components

import androidx.compose.ui.graphics.Color
import com.bydmate.app.ui.theme.SocGreen
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import org.junit.Assert.assertEquals
import org.junit.Test

class SocColorTest {

    @Test
    fun `green from 20, yellow from 10 to 19, red below 10`() {
        class Case(val label: String, val soc: Int, val expected: Color)
        val cases = listOf(
            Case("20% is green", 20, SocGreen),
            Case("19% is yellow", 19, SocYellow),
            Case("10% is yellow", 10, SocYellow),
            Case("9% is red", 9, SocRed),
        )
        for (c in cases) {
            assertEquals("case: ${c.label}", c.expected, socColor(c.soc))
        }
    }
}
