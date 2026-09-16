package com.bydmate.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** One slot on Главная carries both temperatures (#210); a missing outside reading must not
 *  change what a car without it showed before. */
class FormatCabinOutsideTest {

    @Test fun `both readings are shown as a pair, cabin first`() {
        assertEquals("22° / 8°", formatCabinOutside(22, 8))
        assertEquals("18° / -12°", formatCabinOutside(18, -12))
        assertEquals("0° / 0°", formatCabinOutside(0, 0))
    }

    @Test fun `without an outside reading the slot is the cabin value alone`() {
        assertEquals("22°", formatCabinOutside(22, null))
    }

    @Test fun `without a cabin reading the outside value still shows`() {
        assertEquals("— / 8°", formatCabinOutside(null, 8))
    }

    @Test fun `no readings at all keep the dash`() {
        assertEquals("—", formatCabinOutside(null, null))
    }
}
