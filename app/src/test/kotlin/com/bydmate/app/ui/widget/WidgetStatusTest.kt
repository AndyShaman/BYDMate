package com.bydmate.app.ui.widget

import com.bydmate.app.ui.components.socColor
import com.bydmate.app.ui.theme.SocGreen
import com.bydmate.app.ui.theme.SocYellow
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStatusTest {

    @Test
    fun `ok when soc at or above 20 and v12 at or above 12 5`() {
        assertEquals(Status.OK, widgetStatus(soc = 83, v12 = 13.8))
        assertEquals("soc 20 is ok", Status.OK, widgetStatus(soc = 20, v12 = null))
        assertEquals("v12 12.5 is ok", Status.OK, widgetStatus(soc = 80, v12 = 12.5))
    }

    @Test
    fun `warn when soc between 10 and 19`() {
        assertEquals("soc 19 is warn", Status.WARN, widgetStatus(soc = 19, v12 = 13.0))
        assertEquals("soc 10 is warn", Status.WARN, widgetStatus(soc = 10, v12 = 13.0))
    }

    @Test
    fun `warn when v12 between 12 0 and 12 5`() {
        assertEquals(Status.WARN, widgetStatus(soc = 70, v12 = 12.2))
        assertEquals("v12 12.49 is warn", Status.WARN, widgetStatus(soc = 70, v12 = 12.49))
        assertEquals("v12 12.0 is warn", Status.WARN, widgetStatus(soc = 70, v12 = 12.0))
    }

    @Test
    fun `critical when soc below 10`() {
        assertEquals(Status.CRIT, widgetStatus(soc = 9, v12 = 13.0))
    }

    @Test
    fun `critical when v12 below 12`() {
        assertEquals(Status.CRIT, widgetStatus(soc = 80, v12 = 11.7))
        assertEquals("v12 11.99 is crit", Status.CRIT, widgetStatus(soc = 80, v12 = 11.99))
    }

    @Test
    fun `worst of soc and v12 wins`() {
        // WARN soc + CRIT v12 → CRIT
        assertEquals(Status.CRIT, widgetStatus(soc = 15, v12 = 11.7))
    }

    @Test
    fun `no data when both missing`() {
        assertEquals(Status.NO_DATA, widgetStatus(soc = null, v12 = null))
    }

    @Test
    fun `uses available metric when one is null`() {
        // Only soc available, it's OK → status is OK
        assertEquals(Status.OK, widgetStatus(soc = 80, v12 = null))
    }

    @Test
    fun `soc digits and border agree`() {
        // The digits use socColor, the border uses widgetStatus: one set of thresholds for both.
        assertEquals("border at 40%", Status.OK, widgetStatus(soc = 40, v12 = null))
        assertEquals("digits at 40%", SocGreen, socColor(40))
        assertEquals("border at 15%", Status.WARN, widgetStatus(soc = 15, v12 = null))
        assertEquals("digits at 15%", SocYellow, socColor(15))
    }
}
