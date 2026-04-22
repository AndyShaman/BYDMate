package com.bydmate.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStatusTest {

    @Test
    fun `ok when soc above 30 and v12 above 12 5`() {
        assertEquals(Status.OK, widgetStatus(soc = 83, v12 = 13.8))
    }

    @Test
    fun `warn when soc between 15 and 30`() {
        assertEquals(Status.WARN, widgetStatus(soc = 25, v12 = 13.0))
    }

    @Test
    fun `warn when v12 between 12 0 and 12 5`() {
        assertEquals(Status.WARN, widgetStatus(soc = 70, v12 = 12.2))
    }

    @Test
    fun `critical when soc below 15`() {
        assertEquals(Status.CRIT, widgetStatus(soc = 9, v12 = 13.0))
    }

    @Test
    fun `critical when v12 below 12`() {
        assertEquals(Status.CRIT, widgetStatus(soc = 80, v12 = 11.7))
    }

    @Test
    fun `worst of soc and v12 wins`() {
        // WARN soc + CRIT v12 → CRIT
        assertEquals(Status.CRIT, widgetStatus(soc = 25, v12 = 11.7))
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
}
