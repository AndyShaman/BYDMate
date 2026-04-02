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
        const val KEY_TRIP_COST_TARIFF = "trip_cost_tariff" // "home", "dc", or numeric
        const val KEY_CONSUMPTION_GOOD = "consumption_good_threshold"
        const val KEY_CONSUMPTION_BAD = "consumption_bad_threshold"
        const val KEY_LAST_KNOWN_SOC = "last_known_soc"
        const val KEY_LAST_SOC_TIMESTAMP = "last_soc_timestamp"
        const val KEY_LAST_ENERGYDATA_IMPORT_TS = "last_energydata_import_ts"
        const val KEY_SETUP_COMPLETED = "setup_completed"
        const val KEY_DEDUP_CLEANUP_DONE = "dedup_cleanup_done"
        const val KEY_IDLE_DRAIN_CLEANUP_DONE = "idle_drain_cleanup_done"
        const val KEY_CONSUMPTION_RECALC_DONE = "consumption_recalc_done"
        const val KEY_IDLE_DRAIN_V2_CLEANUP = "idle_drain_v2_cleanup"
        const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        const val KEY_OPENROUTER_MODEL = "openrouter_model"

        const val DEFAULT_BATTERY_CAPACITY = "72.9"
        const val DEFAULT_HOME_TARIFF = "0.30"
        const val DEFAULT_DC_TARIFF = "0.50"
        const val DEFAULT_UNITS = "km"
        const val DEFAULT_CURRENCY = "BYN"
        const val DEFAULT_CONSUMPTION_GOOD = "20"
        const val DEFAULT_CONSUMPTION_BAD = "30"

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

    suspend fun getTripCostTariff(): Double {
        val raw = getString(KEY_TRIP_COST_TARIFF, "home")
        return when (raw) {
            "home" -> getHomeTariff()
            "dc" -> getDcTariff()
            else -> raw.toDoubleOrNull() ?: getHomeTariff()
        }
    }

    suspend fun getTripCostTariffKey(): String =
        getString(KEY_TRIP_COST_TARIFF, "home")

    suspend fun getConsumptionGoodThreshold(): Double =
        getString(KEY_CONSUMPTION_GOOD, DEFAULT_CONSUMPTION_GOOD).toDoubleOrNull() ?: 20.0

    suspend fun getConsumptionBadThreshold(): Double =
        getString(KEY_CONSUMPTION_BAD, DEFAULT_CONSUMPTION_BAD).toDoubleOrNull() ?: 30.0

    suspend fun saveLastKnownSoc(soc: Int) {
        setString(KEY_LAST_KNOWN_SOC, soc.toString())
        setString(KEY_LAST_SOC_TIMESTAMP, System.currentTimeMillis().toString())
    }

    suspend fun getLastKnownSoc(): Int? =
        getString(KEY_LAST_KNOWN_SOC, "").toIntOrNull()

    suspend fun getLastSocTimestamp(): Long =
        getString(KEY_LAST_SOC_TIMESTAMP, "0").toLongOrNull() ?: 0L

    suspend fun getLastEnergyImportTs(): Long =
        getString(KEY_LAST_ENERGYDATA_IMPORT_TS, "0").toLongOrNull() ?: 0L

    suspend fun setLastEnergyImportTs(ts: Long) =
        setString(KEY_LAST_ENERGYDATA_IMPORT_TS, ts.toString())

    suspend fun isSetupCompleted(): Boolean =
        getString(KEY_SETUP_COMPLETED, "false") == "true"

    suspend fun setSetupCompleted() =
        setString(KEY_SETUP_COMPLETED, "true")

    suspend fun isDedupCleanupDone(): Boolean =
        getString(KEY_DEDUP_CLEANUP_DONE, "false") == "true"

    suspend fun setDedupCleanupDone() =
        setString(KEY_DEDUP_CLEANUP_DONE, "true")

    suspend fun isIdleDrainCleanupDone(): Boolean =
        getString(KEY_IDLE_DRAIN_CLEANUP_DONE, "false") == "true"

    suspend fun setIdleDrainCleanupDone() =
        setString(KEY_IDLE_DRAIN_CLEANUP_DONE, "true")

    suspend fun isConsumptionRecalcDone(): Boolean =
        getString(KEY_CONSUMPTION_RECALC_DONE, "false") == "true"

    suspend fun setConsumptionRecalcDone() =
        setString(KEY_CONSUMPTION_RECALC_DONE, "true")

    suspend fun isIdleDrainV2CleanupDone(): Boolean =
        getString(KEY_IDLE_DRAIN_V2_CLEANUP, "false") == "true"

    suspend fun setIdleDrainV2CleanupDone() =
        setString(KEY_IDLE_DRAIN_V2_CLEANUP, "true")
}
