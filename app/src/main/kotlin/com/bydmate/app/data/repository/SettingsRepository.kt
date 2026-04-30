package com.bydmate.app.data.repository

import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class SettingsRepository @Inject constructor(
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
        const val KEY_ALICE_ENDPOINT = "alice_endpoint"
        const val KEY_ALICE_API_KEY = "alice_api_key"
        const val KEY_ALICE_ENABLED = "alice_enabled"
        const val KEY_DATA_SOURCE = "data_source"
        const val KEY_AUTOSERVICE_ENABLED = "autoservice_enabled"
        const val KEY_LAST_MILEAGE_KM = "last_mileage_km"
        const val KEY_LAST_CAPACITY_KWH = "last_capacity_kwh"
        const val KEY_LAST_STATE_TS = "last_state_ts"
        // ChargingStateStore baseline. Kept separate from KEY_LAST_KNOWN_SOC
        // (which TrackingService overwrites on every DiPars poll) so the
        // cascade detector's pre-charging baseline survives polling and
        // runCatchUp can compute a real SOC delta on cold start.
        const val KEY_CHARGING_BASELINE_SOC = "charging_baseline_soc"
        const val KEY_MIGRATION_V2_4_17 = "migration_v2_4_17_done"

        const val DEFAULT_BATTERY_CAPACITY = "72.9"
        const val DEFAULT_HOME_TARIFF = "0.20"
        const val DEFAULT_DC_TARIFF = "0.73"
        const val DEFAULT_UNITS = "km"
        const val DEFAULT_CURRENCY = "BYN"
        const val DEFAULT_CONSUMPTION_GOOD = "20"
        const val DEFAULT_CONSUMPTION_BAD = "30"

        val CURRENCIES = listOf(
            Currency("BYN", "BYN", "Бел. руб."),
            Currency("RUB", "₽", "Рос. руб."),
            Currency("UAH", "₴", "Гривна"),
            Currency("KZT", "₸", "Тенге"),
            Currency("USD", "$", "Доллар"),
            Currency("EUR", "€", "Евро"),
            Currency("CNY", "¥", "Юань"),
        )
    }

    data class Currency(val code: String, val symbol: String, val label: String)

    enum class DataSource { ENERGYDATA, DIPLUS }

    suspend fun getString(key: String, default: String): String =
        settingsDao.get(key) ?: default

    fun observeString(key: String): Flow<String?> = settingsDao.observe(key)

    suspend fun setString(key: String, value: String) =
        settingsDao.set(SettingEntity(key, value))

    suspend fun getBatteryCapacity(): Double =
        getString(KEY_BATTERY_CAPACITY, DEFAULT_BATTERY_CAPACITY).toDoubleOrNull() ?: 72.9

    suspend fun getHomeTariff(): Double =
        getString(KEY_HOME_TARIFF, DEFAULT_HOME_TARIFF).toDoubleOrNull() ?: 0.20

    suspend fun getDcTariff(): Double =
        getString(KEY_DC_TARIFF, DEFAULT_DC_TARIFF).toDoubleOrNull() ?: 0.73

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

    /** Live (good, bad) pair for UI coloring. Emits on every Settings edit. */
    fun observeConsumptionThresholds(): Flow<Pair<Double, Double>> = combine(
        observeString(KEY_CONSUMPTION_GOOD).map {
            it?.toDoubleOrNull() ?: DEFAULT_CONSUMPTION_GOOD.toDouble()
        },
        observeString(KEY_CONSUMPTION_BAD).map {
            it?.toDoubleOrNull() ?: DEFAULT_CONSUMPTION_BAD.toDouble()
        },
    ) { good, bad -> good to bad }

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

    suspend fun getDataSource(): DataSource =
        when (getString(KEY_DATA_SOURCE, "ENERGYDATA")) {
            "DIPLUS" -> DataSource.DIPLUS
            else -> DataSource.ENERGYDATA
        }

    suspend fun setDataSource(source: DataSource) =
        setString(KEY_DATA_SOURCE, source.name)

    fun observeDataSource(): Flow<String?> = observeString(KEY_DATA_SOURCE)

    suspend fun isAutoserviceEnabled(): Boolean =
        getString(KEY_AUTOSERVICE_ENABLED, "false") == "true"

    suspend fun setAutoserviceEnabled(enabled: Boolean) =
        setString(KEY_AUTOSERVICE_ENABLED, enabled.toString())

    suspend fun getChargingBaselineSoc(): Int? =
        getString(KEY_CHARGING_BASELINE_SOC, "").toIntOrNull()

    suspend fun setChargingBaselineSoc(soc: Int) =
        setString(KEY_CHARGING_BASELINE_SOC, soc.toString())

    suspend fun getLastMileageKm(): Float? =
        getString(KEY_LAST_MILEAGE_KM, "").toFloatOrNull()

    suspend fun setLastMileageKm(km: Float?) =
        setString(KEY_LAST_MILEAGE_KM, km?.toString() ?: "")

    suspend fun getLastCapacityKwh(): Float? =
        getString(KEY_LAST_CAPACITY_KWH, "").toFloatOrNull()

    suspend fun setLastCapacityKwh(kwh: Float?) =
        setString(KEY_LAST_CAPACITY_KWH, kwh?.toString() ?: "")

    suspend fun getLastStateTs(): Long =
        getString(KEY_LAST_STATE_TS, "0").toLongOrNull() ?: 0L

    suspend fun setLastStateTs(ts: Long) =
        setString(KEY_LAST_STATE_TS, ts.toString())

    suspend fun isMigrationV2_4_17Done(): Boolean =
        getString(KEY_MIGRATION_V2_4_17, "false") == "true"

    suspend fun setMigrationV2_4_17Done() =
        setString(KEY_MIGRATION_V2_4_17, "true")
}
