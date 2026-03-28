package com.bydmate.app.data.repository

import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val settingsDao: SettingsDao
) {
    companion object {
        const val KEY_BATTERY_CAPACITY = "battery_capacity_kwh"
        const val KEY_HOME_TARIFF = "home_tariff"
        const val KEY_DC_TARIFF = "dc_tariff"
        const val KEY_UNITS = "units" // "km" or "miles"
        const val KEY_CURRENCY = "currency" // "BYN", "RUB", "USD", "EUR", "CNY"

        const val DEFAULT_BATTERY_CAPACITY = "72.9"
        const val DEFAULT_HOME_TARIFF = "0.30"
        const val DEFAULT_DC_TARIFF = "0.50"
        const val DEFAULT_UNITS = "km"
        const val DEFAULT_CURRENCY = "BYN"

        val CURRENCIES = listOf(
            Currency("BYN", "Br", "Бел. руб."),
            Currency("RUB", "₽", "Рос. руб."),
            Currency("UAH", "₴", "Гривна"),
            Currency("KZT", "₸", "Тенге"),
            Currency("USD", "$", "Доллар"),
            Currency("EUR", "€", "Евро"),
            Currency("CNY", "¥", "Юань"),
        )
    }

    data class Currency(val code: String, val symbol: String, val label: String)

    suspend fun getString(key: String, default: String): String =
        settingsDao.get(key) ?: default

    fun observeString(key: String): Flow<String?> = settingsDao.observe(key)

    suspend fun setString(key: String, value: String) =
        settingsDao.set(SettingEntity(key, value))

    suspend fun getBatteryCapacity(): Double =
        getString(KEY_BATTERY_CAPACITY, DEFAULT_BATTERY_CAPACITY).toDoubleOrNull() ?: 72.9

    suspend fun getHomeTariff(): Double =
        getString(KEY_HOME_TARIFF, DEFAULT_HOME_TARIFF).toDoubleOrNull() ?: 0.30

    suspend fun getDcTariff(): Double =
        getString(KEY_DC_TARIFF, DEFAULT_DC_TARIFF).toDoubleOrNull() ?: 0.50

    suspend fun getCurrency(): Currency {
        val code = getString(KEY_CURRENCY, DEFAULT_CURRENCY)
        return CURRENCIES.find { it.code == code } ?: CURRENCIES.first()
    }

    suspend fun getCurrencySymbol(): String = getCurrency().symbol
}
