package com.bydmate.app.data.repository

import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Russian numeric keyboards emit "71,8" — bare toDoubleOrNull() returns null
// on the comma and we fall back to the default. That made ABRP / Charges /
// SoH read the default 72.9 instead of the user's setting (issue #19).
internal fun String.parseNumericSetting(): Double? =
    replace(',', '.').trim().toDoubleOrNull()

@Singleton
open class SettingsRepository @Inject constructor(
    private val settingsDao: SettingsDao,
    private val localePreferences: LocalePreferences,
) {
    companion object {
        const val KEY_BATTERY_CAPACITY = "battery_capacity_kwh"
        const val KEY_HOME_TARIFF = "home_tariff"
        const val KEY_DC_TARIFF = "dc_tariff"
        const val KEY_UNITS = "units" // "km" or "miles"
        const val KEY_CURRENCY = "currency" // "BYN", "RUB", "USD", "EUR", "CNY"
        const val KEY_TRIP_COST_TARIFF = "trip_cost_tariff" // "home", "dc", or numeric
        const val KEY_FUEL_PRICE_PER_LITER = "fuel_price_per_liter"
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
        /** "local" (default) = offline rules; "cloud" = OpenRouter LLM */
        const val KEY_INSIGHT_MODE = "insight_mode"
        const val INSIGHT_MODE_LOCAL = "local"
        const val INSIGHT_MODE_CLOUD = "cloud"
        const val KEY_ALICE_ENDPOINT = "alice_endpoint"
        const val KEY_ALICE_API_KEY = "alice_api_key"
        const val KEY_ALICE_ENABLED = "alice_enabled"
        /** Передавать живые данные DiPars в A Better Route Planner (Iternio Telemetry API). GPS не передаётся. */
        const val KEY_ABRP_ENABLED = "abrp_telemetry_enabled"
        /** API-ключ приложения Iternio ([abetterrouteplanner.com/resources/api](https://abetterrouteplanner.com/resources/api)). */
        const val KEY_ABRP_API_KEY = "abrp_api_key"
        /** Токен живых данных автомобиля из ABRP. */
        const val KEY_ABRP_USER_TOKEN = "abrp_user_token"
        /** Необязательный код модели автомобиля из библиотеки ABRP. */
        const val KEY_ABRP_CAR_MODEL = "abrp_car_model"
        const val KEY_PARKING_CAMERA_URL = "parking_camera_url"
        const val KEY_PARKING_CAMERAS = "parking_cameras"
        const val KEY_DATA_SOURCE = "data_source"
        const val KEY_VEHICLE_PROFILE = "vehicle_profile"
        const val KEY_MAP_TILE_SOURCE = "map_tile_source"
        const val KEY_AUTOSERVICE_ENABLED = "autoservice_enabled"
        /** "true" hides the native BYD voice assistant (pm disable-user); default "false". */
        const val KEY_DISABLE_NATIVE_ASSISTANT = "disable_native_assistant"
        const val KEY_LAST_MILEAGE_KM = "last_mileage_km"
        const val KEY_LAST_CAPACITY_KWH = "last_capacity_kwh"
        const val KEY_LAST_STATE_TS = "last_state_ts"
        // ChargingStateStore baseline. Kept separate from KEY_LAST_KNOWN_SOC
        // (which TrackingService overwrites on every DiPars poll) so the
        // cascade detector's pre-charging baseline survives polling and
        // runCatchUp can compute a real SOC delta on cold start.
        const val KEY_CHARGING_BASELINE_SOC = "charging_baseline_soc"
        // Set when runCatchUp saw the gun connected (a charge session is in
        // progress around the stored baseline); cleared once the session is
        // reconstructed or dismissed. Lets a later catch-up create the row
        // even if the odometer moved before the first successful run.
        const val KEY_CHARGE_PENDING = "charge_pending"
        // Persistent ring buffer of recent runCatchUp decisions (CatchUpJournal).
        // Included in the diagnostic dump — logcat rotates out the startup
        // window within minutes on DiLink, so field reports need this.
        const val KEY_CATCHUP_JOURNAL = "catchup_journal"
        const val KEY_MIGRATION_V2_4_17 = "migration_v2_4_17_done"
        const val KEY_INSIGHT_CACHE_V2_MIGRATION_DONE = "insight_cache_v2_migration_done"
        // One-shot migration flag kept for compatibility with upstream native-stack builds.
        const val KEY_MIGRATION_V281_DATA_SOURCE = "migration_v281_data_source_done"

        const val DEFAULT_BATTERY_CAPACITY = "18.3"
        const val DEFAULT_HOME_TARIFF = "0.20"
        const val DEFAULT_DC_TARIFF = "0.73"
        const val DEFAULT_FUEL_PRICE_PER_LITER = "0"
        const val DEFAULT_UNITS = "km"
        const val DEFAULT_CURRENCY = "BYN"
        const val DEFAULT_CONSUMPTION_GOOD = "20"
        const val DEFAULT_CONSUMPTION_BAD = "30"
        const val DEFAULT_VEHICLE_PROFILE = "SONG_L_DMI_112"
        const val DEFAULT_PARKING_CAMERA_URL = "https://parking.napaster.ru"
        const val DEFAULT_MAP_TILE_SOURCE = "osm" // "osm" or "amap"

        val CURRENCIES = listOf(
            Currency("BYN", "BYN"),
            Currency("RUB", "₽"),
            Currency("UAH", "₴"),
            Currency("KZT", "₸"),
            Currency("USD", "$"),
            Currency("EUR", "€"),
            Currency("CNY", "¥"),
        )

        val VEHICLE_PROFILES = listOf(
            VehicleProfile(
                id = "SONG_L_DMI_112",
                label = "Song L DM-i 112 km (2024)",
                shortLabel = "Song 112",
                batteryCapacityKwh = 18.3,
                dataSource = DataSource.DIPLUS,
                isHybrid = true,
                note = "DiPlus TripInfo, батарея 18.3 кВт·ч"
            ),
            VehicleProfile(
                id = "SONG_L_DMI_75",
                label = "Song L DM-i 75 km (2024)",
                shortLabel = "Song 75",
                batteryCapacityKwh = 12.9,
                dataSource = DataSource.DIPLUS,
                isHybrid = true,
                note = "DiPlus TripInfo, батарея 12.9 кВт·ч"
            ),
            VehicleProfile(
                id = "SONG_L_DMI_160",
                label = "Song L DM-i 160 km (2024)",
                shortLabel = "Song 160",
                batteryCapacityKwh = 26.6,
                dataSource = DataSource.DIPLUS,
                isHybrid = true,
                note = "DiPlus TripInfo, батарея 26.6 кВт·ч"
            ),
            VehicleProfile(
                id = "SONG_L_DMI_2026_130",
                label = "Song L DM-i 130 km (2026)",
                shortLabel = "Song 130",
                batteryCapacityKwh = 18.3,
                dataSource = DataSource.DIPLUS,
                isHybrid = true,
                note = "DiPlus TripInfo, батарея 18.3 кВт·ч"
            ),
            VehicleProfile(
                id = "SONG_L_DMI_2026_200",
                label = "Song L DM-i 200 km (2026)",
                shortLabel = "Song 200",
                batteryCapacityKwh = 26.6,
                dataSource = DataSource.DIPLUS,
                isHybrid = true,
                note = "DiPlus TripInfo, батарея 26.6 кВт·ч"
            ),
            VehicleProfile(
                id = "LEOPARD_3_BEV",
                label = "Leopard 3 BEV",
                shortLabel = "Leopard 3",
                batteryCapacityKwh = 72.9,
                dataSource = DataSource.ENERGYDATA,
                isHybrid = false,
                note = "BYD energydata, батарея 72.9 кВт·ч"
            ),
            VehicleProfile(
                id = "YUAN_PLUS_2023_430_LEADING",
                label = "Yuan Plus 2023 Champion 430 km Leading",
                shortLabel = "Yuan+ 430",
                batteryCapacityKwh = 49.92,
                dataSource = DataSource.ENERGYDATA,
                isHybrid = false,
                note = "BYD energydata, BEV, батарея 49.92 кВт·ч"
            ),
            VehicleProfile(
                id = "YUAN_PLUS_2023_430_BEYOND",
                label = "Yuan Plus 2023 Champion 430 km Beyond",
                shortLabel = "Yuan+ 430",
                batteryCapacityKwh = 49.92,
                dataSource = DataSource.ENERGYDATA,
                isHybrid = false,
                note = "BYD energydata, BEV, батарея 49.92 кВт·ч"
            ),
            VehicleProfile(
                id = "YUAN_PLUS_2023_510_LEADING",
                label = "Yuan Plus 2023 Champion 510 km Leading",
                shortLabel = "Yuan+ 510",
                batteryCapacityKwh = 60.48,
                dataSource = DataSource.ENERGYDATA,
                isHybrid = false,
                note = "BYD energydata, BEV, батарея 60.48 кВт·ч"
            ),
            VehicleProfile(
                id = "YUAN_PLUS_2023_510_BEYOND",
                label = "Yuan Plus 2023 Champion 510 km Beyond",
                shortLabel = "Yuan+ 510",
                batteryCapacityKwh = 60.48,
                dataSource = DataSource.ENERGYDATA,
                isHybrid = false,
                note = "BYD energydata, BEV, батарея 60.48 кВт·ч"
            ),
            VehicleProfile(
                id = "YUAN_PLUS_2023_510_EXCELLENCE",
                label = "Yuan Plus 2023 Champion 510 km Excellence",
                shortLabel = "Yuan+ 510",
                batteryCapacityKwh = 60.48,
                dataSource = DataSource.ENERGYDATA,
                isHybrid = false,
                note = "BYD energydata, BEV, батарея 60.48 кВт·ч"
            ),
            VehicleProfile(
                id = "YUAN_PLUS_2024_430_LEADING",
                label = "Yuan Plus 2024 Honor 430 km Leading",
                shortLabel = "Yuan+ 430",
                batteryCapacityKwh = 49.92,
                dataSource = DataSource.ENERGYDATA,
                isHybrid = false,
                note = "BYD energydata, BEV, батарея 49.92 кВт·ч"
            ),
            VehicleProfile(
                id = "YUAN_PLUS_2024_430_BEYOND",
                label = "Yuan Plus 2024 Honor 430 km Beyond",
                shortLabel = "Yuan+ 430",
                batteryCapacityKwh = 49.92,
                dataSource = DataSource.ENERGYDATA,
                isHybrid = false,
                note = "BYD energydata, BEV, батарея 49.92 кВт·ч"
            ),
            VehicleProfile(
                id = "YUAN_PLUS_2024_510_LEADING",
                label = "Yuan Plus 2024 Honor 510 km Leading",
                shortLabel = "Yuan+ 510",
                batteryCapacityKwh = 60.48,
                dataSource = DataSource.ENERGYDATA,
                isHybrid = false,
                note = "BYD energydata, BEV, батарея 60.48 кВт·ч"
            ),
            VehicleProfile(
                id = "YUAN_PLUS_2024_510_BEYOND",
                label = "Yuan Plus 2024 Honor 510 km Beyond",
                shortLabel = "Yuan+ 510",
                batteryCapacityKwh = 60.48,
                dataSource = DataSource.ENERGYDATA,
                isHybrid = false,
                note = "BYD energydata, BEV, батарея 60.48 кВт·ч"
            ),
            VehicleProfile(
                id = "YUAN_PLUS_2024_510_EXCELLENCE",
                label = "Yuan Plus 2024 Honor 510 km Excellence",
                shortLabel = "Yuan+ 510",
                batteryCapacityKwh = 60.48,
                dataSource = DataSource.ENERGYDATA,
                isHybrid = false,
                note = "BYD energydata, BEV, батарея 60.48 кВт·ч"
            ),
            VehicleProfile(
                id = "CUSTOM",
                label = "Другая BYD",
                shortLabel = "Custom",
                batteryCapacityKwh = null,
                dataSource = DataSource.DIPLUS,
                isHybrid = true,
                note = "ручная ёмкость, DiPlus TripInfo"
            ),
        )

        fun vehicleProfileById(id: String?): VehicleProfile =
            VEHICLE_PROFILES.firstOrNull { it.id == id } ?: vehicleProfileById(DEFAULT_VEHICLE_PROFILE)

        fun formatCapacity(capacityKwh: Double): String =
            if (capacityKwh % 1.0 == 0.0) "%.0f".format(capacityKwh) else "%.1f".format(capacityKwh)
    }

    data class Currency(val code: String, val symbol: String)

    enum class DataSource { ENERGYDATA, DIPLUS }

    data class VehicleProfile(
        val id: String,
        val label: String,
        val shortLabel: String,
        val batteryCapacityKwh: Double?,
        val dataSource: DataSource,
        val isHybrid: Boolean,
        val note: String
    )

    suspend fun getString(key: String, default: String): String =
        settingsDao.get(key) ?: default

    fun observeString(key: String): Flow<String?> = settingsDao.observe(key)

    suspend fun setString(key: String, value: String) =
        settingsDao.set(SettingEntity(key, value))

    /** Writes all key/value pairs in one Room transaction (all or nothing). */
    suspend fun setStrings(values: Map<String, String>) =
        settingsDao.setAll(values.map { (k, v) -> SettingEntity(k, v) })

    suspend fun getBatteryCapacity(): Double =
        getString(KEY_BATTERY_CAPACITY, DEFAULT_BATTERY_CAPACITY).parseNumericSetting() ?: 18.3

    suspend fun getHomeTariff(): Double =
        getString(KEY_HOME_TARIFF, DEFAULT_HOME_TARIFF).parseNumericSetting() ?: 0.20

    suspend fun getDcTariff(): Double =
        getString(KEY_DC_TARIFF, DEFAULT_DC_TARIFF).parseNumericSetting() ?: 0.73

    suspend fun getFuelPricePerLiter(): Double =
        getString(KEY_FUEL_PRICE_PER_LITER, DEFAULT_FUEL_PRICE_PER_LITER).parseNumericSetting() ?: 0.0

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
            else -> raw.parseNumericSetting() ?: getHomeTariff()
        }
    }

    suspend fun getTripCostTariffKey(): String =
        getString(KEY_TRIP_COST_TARIFF, "home")

    suspend fun getConsumptionGoodThreshold(): Double =
        getString(KEY_CONSUMPTION_GOOD, DEFAULT_CONSUMPTION_GOOD).parseNumericSetting() ?: 20.0

    suspend fun getConsumptionBadThreshold(): Double =
        getString(KEY_CONSUMPTION_BAD, DEFAULT_CONSUMPTION_BAD).parseNumericSetting() ?: 30.0

    /** Live (good, bad) pair for UI coloring. Emits on every Settings edit. */
    fun observeConsumptionThresholds(): Flow<Pair<Double, Double>> = combine(
        observeString(KEY_CONSUMPTION_GOOD).map {
            it?.parseNumericSetting() ?: DEFAULT_CONSUMPTION_GOOD.toDouble()
        },
        observeString(KEY_CONSUMPTION_BAD).map {
            it?.parseNumericSetting() ?: DEFAULT_CONSUMPTION_BAD.toDouble()
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

    suspend fun setSetupCompleted() {
        setString(KEY_SETUP_COMPLETED, "true")
        localePreferences.markSetupCompletedMirror()  // sync mirror
    }

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

    suspend fun getMapTileSource(): String =
        getString(KEY_MAP_TILE_SOURCE, DEFAULT_MAP_TILE_SOURCE)

    suspend fun setMapTileSource(source: String) =
        setString(KEY_MAP_TILE_SOURCE, source)

    suspend fun isIdleDrainV2CleanupDone(): Boolean =
        getString(KEY_IDLE_DRAIN_V2_CLEANUP, "false") == "true"

    suspend fun setIdleDrainV2CleanupDone() =
        setString(KEY_IDLE_DRAIN_V2_CLEANUP, "true")

    suspend fun getDataSource(): DataSource =
        when (getString(KEY_DATA_SOURCE, DataSource.DIPLUS.name)) {
            "DIPLUS" -> DataSource.DIPLUS
            else -> DataSource.ENERGYDATA
        }

    suspend fun setDataSource(source: DataSource) =
        setString(KEY_DATA_SOURCE, source.name)

    fun observeDataSource(): Flow<String?> = observeString(KEY_DATA_SOURCE)

    suspend fun getVehicleProfile(): VehicleProfile =
        vehicleProfileById(getString(KEY_VEHICLE_PROFILE, DEFAULT_VEHICLE_PROFILE))

    suspend fun setVehicleProfile(profile: VehicleProfile) {
        setString(KEY_VEHICLE_PROFILE, profile.id)
        setDataSource(profile.dataSource)
        profile.batteryCapacityKwh?.let {
            setString(KEY_BATTERY_CAPACITY, formatCapacity(it))
        }
    }

    suspend fun isAutoserviceEnabled(): Boolean =
        getString(KEY_AUTOSERVICE_ENABLED, "false") == "true"

    suspend fun setAutoserviceEnabled(enabled: Boolean) =
        setString(KEY_AUTOSERVICE_ENABLED, enabled.toString())

    suspend fun getChargingBaselineSoc(): Int? =
        getString(KEY_CHARGING_BASELINE_SOC, "").toIntOrNull()

    suspend fun setChargingBaselineSoc(soc: Int) =
        setString(KEY_CHARGING_BASELINE_SOC, soc.toString())

    suspend fun getChargePending(): Boolean =
        getString(KEY_CHARGE_PENDING, "") == "true"

    suspend fun setChargePending(pending: Boolean) =
        setString(KEY_CHARGE_PENDING, pending.toString())

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

    suspend fun isInsightCacheV2MigrationDone(): Boolean =
        getString(KEY_INSIGHT_CACHE_V2_MIGRATION_DONE, "false") == "true"

    suspend fun setInsightCacheV2MigrationDone() =
        setString(KEY_INSIGHT_CACHE_V2_MIGRATION_DONE, "true")

    suspend fun migrateDataSourceIfNeeded() {
        if (getString(KEY_MIGRATION_V281_DATA_SOURCE, "false") == "true") return
        setString(KEY_MIGRATION_V281_DATA_SOURCE, "true")
    }
}
