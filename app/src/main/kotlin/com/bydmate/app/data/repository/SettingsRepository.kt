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

        const val DEFAULT_BATTERY_CAPACITY = "71.8"
        const val DEFAULT_HOME_TARIFF = "0.6"
        const val DEFAULT_DC_TARIFF = "2.0"
        const val DEFAULT_UNITS = "km"
    }

    suspend fun getString(key: String, default: String): String =
        settingsDao.get(key) ?: default

    fun observeString(key: String): Flow<String?> = settingsDao.observe(key)

    suspend fun setString(key: String, value: String) =
        settingsDao.set(SettingEntity(key, value))

    suspend fun getBatteryCapacity(): Double =
        getString(KEY_BATTERY_CAPACITY, DEFAULT_BATTERY_CAPACITY).toDoubleOrNull() ?: 71.8

    suspend fun getHomeTariff(): Double =
        getString(KEY_HOME_TARIFF, DEFAULT_HOME_TARIFF).toDoubleOrNull() ?: 0.6

    suspend fun getDcTariff(): Double =
        getString(KEY_DC_TARIFF, DEFAULT_DC_TARIFF).toDoubleOrNull() ?: 2.0
}
