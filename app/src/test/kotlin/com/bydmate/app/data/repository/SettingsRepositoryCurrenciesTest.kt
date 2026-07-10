package com.bydmate.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SettingsRepositoryCurrenciesTest {

    @Test fun `UZS currency is available with code as symbol`() {
        val uzs = SettingsRepository.CURRENCIES.find { it.code == "UZS" }
        assertNotNull(uzs)
        assertEquals("UZS", uzs!!.symbol)
    }

    @Test fun `default currency stays BYN as first element`() {
        assertEquals(SettingsRepository.DEFAULT_CURRENCY, SettingsRepository.CURRENCIES.first().code)
    }
}
