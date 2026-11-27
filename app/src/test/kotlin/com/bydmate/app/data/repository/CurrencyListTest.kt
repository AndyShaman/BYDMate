package com.bydmate.app.data.repository

import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.FakeSettingsDao
import com.bydmate.app.data.repository.SettingsRepository.Companion.CURRENCIES
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrencyListTest {

    private val dao = FakeSettingsDao()
    private val repo = SettingsRepository(dao, mockk<LocalePreferences>(relaxed = true))

    @Test fun `AZN is offered with the manat sign`() {
        assertEquals("₼", CURRENCIES.single { it.code == "AZN" }.symbol)
    }

    @Test fun `a saved AZN is read back`() = runTest {
        dao.map[SettingsRepository.KEY_CURRENCY] = "AZN"
        assertEquals(SettingsRepository.Currency("AZN", "₼"), repo.getCurrency())
        assertEquals("₼", repo.getCurrencySymbol())
    }

    @Test fun `GEL is offered with the lari sign and read back`() = runTest {
        assertEquals("₾", CURRENCIES.single { it.code == "GEL" }.symbol)
        dao.map[SettingsRepository.KEY_CURRENCY] = "GEL"
        assertEquals("₾", repo.getCurrencySymbol())
    }

    @Test fun `codes are unique and BYN stays the first and default`() {
        val codes = CURRENCIES.map { it.code }
        assertEquals(codes.distinct(), codes)
        assertEquals("BYN", CURRENCIES.first().code)
        assertTrue(CURRENCIES.any { it.code == SettingsRepository.DEFAULT_CURRENCY })
    }

    @Test fun `an unknown saved code falls back to the first`() = runTest {
        dao.map[SettingsRepository.KEY_CURRENCY] = "XXX"
        assertEquals("BYN", repo.getCurrency().code)
    }

    @Test fun `BRL and IDR are present once and existing currencies keep their order`() {
        val expectedPrefix = listOf(
            SettingsRepository.Currency("BYN", "BYN"),
            SettingsRepository.Currency("RUB", "₽"),
            SettingsRepository.Currency("UAH", "₴"),
            SettingsRepository.Currency("KZT", "₸"),
            SettingsRepository.Currency("AMD", "֏"),
            SettingsRepository.Currency("AZN", "₼"),
            SettingsRepository.Currency("GEL", "₾"),
            SettingsRepository.Currency("USD", "$"),
            SettingsRepository.Currency("EUR", "€"),
            SettingsRepository.Currency("PLN", "zł"),
            SettingsRepository.Currency("CNY", "¥"),
            SettingsRepository.Currency("UZS", "UZS"),
            SettingsRepository.Currency("KGS", "сом"),
            SettingsRepository.Currency("MDL", "MDL"),
            SettingsRepository.Currency("RON", "lei"),
        )
        assertEquals(expectedPrefix, CURRENCIES.take(expectedPrefix.size))
        assertEquals(1, CURRENCIES.count { it == SettingsRepository.Currency("BRL", "R$") })
        assertEquals(1, CURRENCIES.count { it == SettingsRepository.Currency("IDR", "Rp") })
    }
}
