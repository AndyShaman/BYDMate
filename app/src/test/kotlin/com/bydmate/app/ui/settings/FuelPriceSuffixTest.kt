package com.bydmate.app.ui.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** DM-i (#184): the period summaries name the litre price on a hybrid only. */
class FuelPriceSuffixTest {

    private val saved = Locale.getDefault()

    @Before fun setup() = Locale.setDefault(Locale.US)

    @After fun teardown() = Locale.setDefault(saved)

    @Test fun `a hybrid with a price gets the litre suffix`() =
        assertEquals(" · 2.50 /л", fuelPriceSuffix(2.5, hybrid = true, litreUnit = "л"))

    @Test fun `a hybrid without a price gets nothing`() =
        assertEquals("", fuelPriceSuffix(null, hybrid = true, litreUnit = "л"))

    @Test fun `an EV gets nothing even with a price`() =
        assertEquals("", fuelPriceSuffix(2.5, hybrid = false, litreUnit = "л"))
}
